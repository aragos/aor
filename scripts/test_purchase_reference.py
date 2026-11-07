"""Explicit purchase exploration objective and unchanged typed/default update boundaries."""

from contextlib import redirect_stdout
from dataclasses import asdict, replace
import io
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import torch

import card_policy as card
import purchase_policy as backend
from purchase_policy_toy import SCHEMA, observation
from test_purchase_policy import behavior_row, collection


def lab_loss(policy, rows, config):
    """Frozen LAB operation order, independently extended here to variable offered counts."""
    ordinary = backend.ordinary_loss_for_rows(policy, rows, config)
    logps, _ = policy([row.get('_prepared', row['observation']) for row in rows])
    penalty = torch.stack([-logp.mean() - math.log(logp.numel()) for logp in logps]).mean()
    return ordinary + .10 * penalty


class PurchaseReferenceTest(unittest.TestCase):
    def setUp(self):
        torch.set_num_threads(1)
        self.model = backend.initialize(SCHEMA, 17)
        self.objective = backend.PurchaseReferenceObjective()
        self.config = backend.UpdateConfig(epochs=2, batchSize=2)

    def rows(self, counts=(2,)):
        result = []
        for index, count in enumerate(counts):
            state = observation()
            state['actions'] = ([{'command': 'pass', 'features': {}}] if count == 1 else
                [{'command': f'buy advance-{i:03}',
                  'features': {'left': float(i % 2), 'right': float((i+1) % 2)}}
                 for i in range(count-1)] + [{'command': 'pass', 'features': {}}])
            result.append(behavior_row(self.model, state, 0, reward=float(index % 2)))
        return result

    def test_production_loss_and_gradients_equal_lab_operation_order(self):
        rows = self.rows((2, 2, 2))
        actual, expected = backend.PurchasePolicy(self.model), backend.PurchasePolicy(self.model)
        loss = backend.loss_for_rows(actual, rows, self.config, purchase_reference=self.objective)
        oracle = lab_loss(expected, rows, self.config)
        self.assertEqual(float(loss.detach()), float(oracle.detach()))
        loss.backward(); oracle.backward()
        for left, right in zip(actual.parameters(), expected.parameters()):
            torch.testing.assert_close(left.grad, right.grad, rtol=0, atol=0)

    def test_pass_only_reference_is_exactly_zero_and_changes_no_gradient(self):
        rows = self.rows((1,))
        actual, ordinary = backend.PurchasePolicy(self.model), backend.PurchasePolicy(self.model)
        loss = backend.loss_for_rows(actual, rows, self.config, purchase_reference=self.objective)
        old = backend.loss_for_rows(ordinary, rows, self.config)
        self.assertEqual(float(loss.detach()), float(old.detach()))
        loss.backward(); old.backward()
        for left, right in zip(actual.parameters(), ordinary.parameters()):
            torch.testing.assert_close(left.grad, right.grad, rtol=0, atol=0)

    def test_all_offered_actions_including_pass_have_equal_reference_mass(self):
        rows = self.rows((2, 7, 128))
        policy = backend.PurchasePolicy(self.model)
        ordinary = backend.loss_for_rows(policy, rows, self.config)
        actual = backend.loss_for_rows(policy, rows, self.config, purchase_reference=self.objective)
        logps, _ = policy([row['observation'] for row in rows])
        expected = sum(sum((1/len(logp)) * (math.log(1/len(logp))-float(value))
                           for value in logp.detach()) for logp in logps)/len(rows)
        self.assertAlmostEqual(float((actual-ordinary).detach()), .1*expected, places=14)
        actual.backward()
        self.assertTrue(all(bool(torch.isfinite(p.grad).all()) for p in policy.parameters()))

    def test_raw_return_minus_recorded_value_is_not_centered_or_normalized(self):
        rows = self.rows((2, 2))
        rows[0]['return'], rows[0]['behaviorValue'] = 1., .15
        rows[1]['return'], rows[1]['behaviorValue'] = 0., .90
        captured = []
        original = backend.clipped_policy_loss
        def record(logp, old, advantage, clip):
            captured.append(advantage.tolist())
            return original(logp, old, advantage, clip)
        with patch.object(backend, 'clipped_policy_loss', side_effect=record):
            backend.loss_for_rows(backend.PurchasePolicy(self.model), rows, self.config,
                                  purchase_reference=self.objective)
        self.assertEqual(captured, [[.85, -.90]])

    def test_purchase_still_rejects_the_card_physical_reference_configuration(self):
        config = replace(self.config, cardReferenceKlCoefficient=.1)
        with self.assertRaisesRegex(ValueError, 'only for card updates'):
            backend.loss_for_rows(backend.PurchasePolicy(self.model), self.rows(), config,
                                  purchase_reference=self.objective)

    def test_purchase_objective_rejects_card_actor_even_with_zero_card_reference(self):
        from card_policy_toy import SCHEMA as CARD_SCHEMA
        actor = card.CardPolicy(card.initialize(CARD_SCHEMA, 17))
        with self.assertRaisesRegex(ValueError, 'typed purchase contract'):
            backend.loss_for_rows(actor, [], self.config, purchase_reference=self.objective)

    def test_objective_rejects_unknown_versions_and_invalid_or_disabled_coefficients(self):
        for values in [dict(version='future',coefficient=.1),dict(version=backend.PURCHASE_REFERENCE_VERSION,coefficient=0),
                       dict(version=backend.PURCHASE_REFERENCE_VERSION,coefficient=True),
                       dict(version=backend.PURCHASE_REFERENCE_VERSION,coefficient=float('nan')),
                       dict(version=backend.PURCHASE_REFERENCE_VERSION,coefficient=.1,extra=1)]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                backend.PurchaseReferenceObjective.from_json(values)


class PurchaseReferenceUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = backend.initialize(SCHEMA, 17)
        self.model_path = self.root/'model.json'
        backend.atomic_json(self.model_path,self.model)
        self.data = self.root/'data'
        collection(self.data,self.model_path,games=19)
        self.config = backend.UpdateConfig(epochs=2,batchSize=2)
        self.objective = backend.PurchaseReferenceObjective()

    def update(self, directory, **kwargs):
        return backend.update(self.model_path,self.data,self.config,directory,
            update_mode=backend.CHECKED_PREFIX_MODE,purchase_reference=self.objective,**kwargs)

    def test_two_updates_match_lab_models_adam_histories_and_exact_portable_bytes(self):
        model = self.model_path
        data = self.data
        for stage in (1,2):
            outputs=[]
            for name in ('lab','production'):
                output=self.root/f'{name}-{stage}'
                carry=self.root/f'{name}-1/checkpoint.json' if stage==2 else None
                if name=='lab':
                    with patch.object(backend,'loss_for_rows',side_effect=lab_loss):
                        backend.update(model,data,self.config,output,optimizer_from=carry,
                                       update_mode=backend.CHECKED_PREFIX_MODE)
                else:
                    backend.update(model,data,self.config,output,optimizer_from=carry,
                        update_mode=backend.CHECKED_PREFIX_MODE,purchase_reference=self.objective)
                outputs.append(output)
            left,right=[backend.read_checkpoint(p/'checkpoint.json') for p in outputs]
            for key in ('model','optimizer','history','cursor','acceptedSteps','acceptedBlocks'):
                self.assertEqual(left[key],right[key],(stage,key))
            self.assertEqual((outputs[0]/'policy.json').read_bytes(),(outputs[1]/'policy.json').read_bytes())
            model=outputs[0]/'policy.json'
            if stage==1:
                data=self.root/'second-data';collection(data,model,games=19)

    def test_interrupted_objective_update_resumes_exact_model_and_adam(self):
        complete,resumed=self.root/'complete',self.root/'resumed'
        self.update(complete)
        fit=backend.fit_checked_block
        def interrupted(*args,**kwargs):
            if args[-2]['minibatchOffset']==8:
                raise RuntimeError('interrupted')
            return fit(*args,**kwargs)
        with patch.object(backend,'fit_checked_block',side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError,'interrupted'):
                self.update(resumed)
        self.update(resumed,resume=True)
        left,right=[backend.read_checkpoint(p/'checkpoint.json') for p in (complete,resumed)]
        for key in ('model','optimizer','history','cursor','acceptedSteps','acceptedBlocks'):
            self.assertEqual(left[key],right[key],key)

    def test_purchase_objective_requires_prefix_before_reading_any_collection(self):
        with patch.object(backend,'read_collection',side_effect=AssertionError('must not read')):
            with self.assertRaisesRegex(ValueError,'requires checked-prefix'):
                backend.update(self.model_path,self.data,self.config,self.root/'bad',
                               purchase_reference=self.objective)
        self.assertFalse((self.root/'bad').exists())

    def test_resume_cannot_change_enabled_objective(self):
        output=self.root/'update';self.update(output)
        original=(output/'checkpoint.json').read_bytes()
        with self.assertRaisesRegex(ValueError,'Resume inputs'):
            backend.update(self.model_path,self.data,self.config,output,resume=True,
                update_mode=backend.CHECKED_PREFIX_MODE,
                purchase_reference=backend.PurchaseReferenceObjective(coefficient=.2))
        self.assertEqual((output/'checkpoint.json').read_bytes(),original)

    def test_resume_cannot_remove_enabled_objective(self):
        output=self.root/'update';self.update(output)
        with self.assertRaisesRegex(ValueError,'Resume inputs'):
            backend.update(self.model_path,self.data,self.config,output,resume=True,
                           update_mode=backend.CHECKED_PREFIX_MODE)

    def test_cli_records_enabled_objective_only_in_training_artifacts(self):
        source=self.root/'objective.json';backend.atomic_json(source,asdict(self.objective))
        output=self.root/'cli'
        with redirect_stdout(io.StringIO()):
            backend.main(['update','--model',str(self.model_path),'--source',str(self.data),
                '--seed','17','--output',str(output),'--update-mode',backend.CHECKED_PREFIX_MODE,
                '--purchase-reference-objective',str(source)])
        self.assertEqual(backend.read_json(output/'identity.json')['purchaseReferenceObjective'],asdict(self.objective))
        self.assertEqual(backend.read_json(output/'finished.json')['purchaseReferenceObjective'],asdict(self.objective))
        self.assertNotIn('purchaseReferenceObjective',backend.read_json(output/'policy.json'))

    def test_default_mode_keeps_objective_and_reader_fields_absent(self):
        output=self.root/'old';backend.update(self.model_path,self.data,self.config,output)
        identity=backend.read_json(output/'identity.json')
        self.assertNotIn('purchaseReferenceObjective',identity)
        self.assertNotIn('collectionReaderSource',identity)
        self.assertNotIn('updateMode',identity)
        self.assertEqual(backend.read_checkpoint(output/'checkpoint.json')['schemaVersion'],1)

    def test_custom_reader_binds_its_source_separately_from_entrypoint(self):
        def reader(directory,model_path,model,*,contract):
            rows,provenance=backend.read_collection(directory,model_path,model,contract=contract)
            return rows,{**provenance,'readerSha256':backend.file_digest(__file__)}
        output=self.root/'custom'
        self.update(output,collection_reader=reader,entrypoint=backend.__file__)
        identity=backend.read_json(output/'identity.json')
        self.assertEqual(identity['collectionReaderSource'],
            {'path':str(Path(__file__).resolve()),'sha256':backend.file_digest(__file__)})
        self.assertEqual(identity['entrypointSha256'],backend.file_digest(backend.__file__))
        self.assertNotEqual(identity['entrypointSha256'],identity['collectionReaderSource']['sha256'])

    def test_custom_reader_without_owner_rejects_before_invocation(self):
        with patch.object(backend,'read_collection',side_effect=AssertionError('must not read')):
            with self.assertRaisesRegex(ValueError,'explicit owning entrypoint'):
                self.update(self.root/'bad',collection_reader=lambda *args:None)

    def test_custom_reader_must_bind_the_same_source_hash_in_provenance(self):
        def reader(directory,model_path,model,*,contract):
            rows,provenance=backend.read_collection(directory,model_path,model,contract=contract)
            return rows,{**provenance,'readerSha256':'wrong'}
        with self.assertRaisesRegex(ValueError,'match its provenance'):
            self.update(self.root/'bad',collection_reader=reader,entrypoint=__file__)
        self.assertFalse((self.root/'bad').exists())


if __name__=='__main__':unittest.main()
