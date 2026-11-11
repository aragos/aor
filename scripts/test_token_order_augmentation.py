"""Synthetic inherited-data identity and shared ridge execution; no experimental evidence."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import token_order_augmentation as a
import token_order_eight_tail as fit
from test_token_order_reference_neighbors import view
from test_token_order_policy import outcome, schema


def fixture(index=0, baseline=16):
    observation = view(baseline=baseline)
    old = a.proposals.ordinary_indices(observation)
    expected = a.proposals.reference_neighbor_indices(observation)
    parent = dict(schemaVersion=1, signature='synthetic-parent', index=index,
        split='TRAINING' if index < 2 else 'VALIDATION', status='COLLECTED',
        gameSeed=-100000+index, originDecisions=12, observation=observation,
        baselineCommand=f'buy-tokens {baseline}', proposedIndices=old,
        referenceIndex=a.fixed.expected_reference_index(observation))
    def branch(i):
        amount = int(observation['actions'][i]['command'].split()[1])
        return dict(index=i, command=observation['actions'][i]['command'],
            outcomes=[outcome(.8 if amount == 14 else .4, seed) for seed in a.streams(parent)])
    parent['alternatives'] = [branch(i) for i in old]
    added = [i for i in expected if i not in old]
    delta = dict(schemaVersion=1,kind=a.KIND,index=index,split=parent['split'],status='COLLECTED',
        parentOriginSha256='a'*64,parentSignature=parent['signature'],originPolicySeed=parent['gameSeed'],
        parentMetadataValidated=True,parentProposedIndices=old,proposedIndices=expected,
        reusedIndices=[i for i in expected if i in old],newIndices=added,
        omittedIndices=[i for i in old if i not in expected],referenceIndex=parent['referenceIndex'],
        continuationSeeds=a.streams(parent),prefixReconstructed=bool(added),prefixMetadataExact=bool(added),
        prefixPlayerActions=12 if added else None,newAlternatives=[branch(i) for i in added])
    return parent,delta


class DerivedViewTest(unittest.TestCase):
    def test_inherited_outcomes_retain_the_original_objects(self):
        parent,delta=fixture()
        before=copy.deepcopy(parent)
        result=a.effective_origin(parent,delta,'a'*64)
        inherited={row['index']:row for row in parent['alternatives']}
        self.assertEqual(before,parent)
        for row in result['alternatives']:
            if row['index'] in inherited:
                self.assertIs(inherited[row['index']],row)
        self.assertEqual(1,len(delta['newAlternatives']))

    def test_only_declared_new_command_is_allowed(self):
        parent,delta=fixture()
        delta['newAlternatives'][0]['command']='buy-tokens 13'
        with self.assertRaisesRegex(ValueError,'exact offered'):
            a.effective_origin(parent,delta,'a'*64)

    def test_eighth_stream_cannot_be_replaced(self):
        parent,delta=fixture()
        delta['newAlternatives'][0]['outcomes'][7]['continuationSeed']+=1
        with self.assertRaisesRegex(ValueError,'original paired'):
            a.effective_origin(parent,delta,'a'*64)

    def test_reused_branch_cannot_be_resimulated_in_sidecar(self):
        parent,delta=fixture()
        delta['newAlternatives'].append(parent['alternatives'][0])
        with self.assertRaisesRegex(ValueError,'no inherited'):
            a.effective_origin(parent,delta,'a'*64)

    def test_parent_hash_is_mandatory(self):
        parent,delta=fixture()
        with self.assertRaisesRegex(ValueError,'historical root'):
            a.effective_origin(parent,delta,'b'*64)

    def test_unchanged_roots_do_not_reconstruct_prefixes(self):
        parent,delta=fixture(baseline=18)
        result=a.effective_origin(parent,delta,'a'*64)
        self.assertFalse(delta['prefixReconstructed'])
        self.assertEqual(parent,result)

    def test_skips_remain_skips_with_no_outcomes(self):
        parent=dict(index=5,split='TRAINING',status='SKIPPED',gameSeed=-10,signature='s')
        delta=dict(schemaVersion=1,kind=a.KIND,index=5,split='TRAINING',status='SKIPPED',
            parentOriginSha256='a'*64,parentSignature='s',originPolicySeed=-10,parentMetadataValidated=True,
            parentProposedIndices=[],proposedIndices=[],reusedIndices=[],newIndices=[],omittedIndices=[],
            referenceIndex=None,continuationSeeds=[],prefixReconstructed=False,prefixMetadataExact=False,
            prefixPlayerActions=None,newAlternatives=[])
        self.assertEqual(parent,a.effective_origin(parent,delta,'a'*64))

    def test_reordered_inherited_queries_are_rejected(self):
        parent,delta=fixture()
        delta['proposedIndices']=list(reversed(delta['proposedIndices']))
        with self.assertRaisesRegex(ValueError,'exact fixed version'):
            a.effective_origin(parent,delta,'a'*64)

    def test_shared_fit_exports_all_five_new_envelopes_and_marks_development(self):
        model=fit.q.initialize(schema(),137,'token')
        originals={i:a.effective_origin(*fixture(i),'a'*64) for i in range(4)}
        rows=[fit.token.validate_root(row,model,8,fixed_reference=True,
              proposal_profile=a.proposals.ProposalProfile.REFERENCE_NEIGHBORS) for row in originals.values()]
        with tempfile.TemporaryDirectory() as name:
            root=Path(name)
            fit.b.atomic_json(root/'view.json',{'synthetic':True})
            bindings=a.AugmentedBindings(str(root/'view.json'),'a'*64,str(root/'initializer.json'),
                'b'*64,'c'*64,str(root/'identity.json'),'d'*64)
            with mock.patch.object(a,'load_collection',return_value=(model,rows,{'synthetic':True})), \
                 mock.patch.object(a,'read_effective_origin',side_effect=lambda view,index:originals[index]):
                report=fit.fit(bindings,root/'fit',fit.ridge.FitConfig(),fixed_reference=True)
            identity=fit.b.read_json(root/'fit/identity.json')
            self.assertEqual(a.FIT_KIND,report['kind'])
            self.assertTrue(report['validationPreviouslyInspected'])
            self.assertEqual('REUSED_DEVELOPMENT_SELECTION_ONLY',identity['validationPurpose'])
            self.assertEqual(a.codec.SELECTOR,identity['selector'])
            self.assertFalse(report['qualificationPassed'])
            for i in range(5):
                artifact=fit.b.read_json(root/f'fit/model-{i}.json')
                self.assertEqual(a.codec.KIND,artifact['kind'])
                raw=a.codec.unwrap(artifact)
                self.assertEqual(model['stateWeights'],raw['stateWeights'])
            self.assertFalse(fit.b.read_json(root/'fit/selection.json')['prefixDiagnosticsUsed'])

    def test_derived_fit_cannot_use_ordinary_reference_mode(self):
        with tempfile.TemporaryDirectory() as name:
            bindings=a.AugmentedBindings('/view','a'*64,'/initializer','b'*64,'c'*64,'/identity','d'*64)
            with self.assertRaisesRegex(ValueError,'explicit fixed-T'):
                fit.fit(bindings,Path(name)/'fit',fit.ridge.FitConfig())


if __name__=='__main__':
    unittest.main()
