"""Constructed scores, targets and fit-only CLI; no retained corpus, game or policy training."""
import contextlib
import io
from pathlib import Path
from types import SimpleNamespace
import tempfile
import time
import unittest
from unittest.mock import patch

import expansion_training_evidence as evidence
import purchase_policy as b
import token_paired_corpus as corpus
import token_paired_data as data
import token_paired_fit as fit
import token_paired_objective as objectives
import test_token_paired_corpus as native_fixture
import test_token_paired_fit as fixture


def win_training(original):
    legacy,dictionary=fixture.corpus(original)
    ctx=data.WinContext(**vars(legacy.context))
    roots=tuple(data.PublicRoot.from_record(root.record,ctx,original.model,split='TRAIN') for root in legacy.roots)
    targets=tuple(data.WinPairedTarget.from_record(root,dict(objectiveVersion=objectives.WIN,
        publicSha256=root.sha256,futureSeeds=root.record['futureSeeds'],actions=[dict(actionIndex=index,
            winShares=[0.,0.] if position==0 else [1.,1.])
            for position,index in enumerate(root.record['proposedIndices'])])) if root.ready else None for root in roots)
    return data.Training(ctx,roots,targets,legacy.anchors,legacy.validation_ready_count),dictionary


def game(totals,seat=0):
    best=max((value for value in totals if value is not None),default=None)
    scores=[dict(player=f'p{index+1}',cash=max(value or 0,0),advancePoints=0,
        misery=None if value is None else max(-(value or 0),0),total=value,
        winner=value is not None and value==best) for index,value in enumerate(totals)]
    count=sum(row['winner'] for row in scores)
    win=1/count if scores[seat]['winner'] else 0.
    return dict(controlledSeat=seat,terminal=dict(scores=scores,winShare=win,gameDecisions=50))


class WinObjectiveTests(unittest.TestCase):
    def setUp(self):b.torch.set_num_threads(1)

    def test_single_winner_is_derived_without_reward_or_rank_fields(self):
        self.assertEqual(evidence.terminal_win_share(game([4,3,2,1])),1.)

    def test_tied_winners_share_one_win(self):
        self.assertEqual(evidence.terminal_win_share(game([4,4,2,1])),.5)

    def test_three_way_tie_uses_three_equal_winner_shares(self):
        self.assertEqual(evidence.terminal_win_share(game([4,4,4,None])),1/3)

    def test_all_chaos_has_no_winner_share(self):
        self.assertEqual(evidence.terminal_win_share(game([None]*4)),0.)

    def test_false_recorded_win_share_is_rejected(self):
        value=game([4,4,2,1]);value['terminal']['winShare']=1.
        with self.assertRaisesRegex(ValueError,'authoritative winners'):evidence.terminal_win_share(value)

    def test_false_winner_flags_are_rejected(self):
        value=game([4,3,2,1]);value['terminal']['scores'][1]['winner']=True
        with self.assertRaisesRegex(ValueError,'winners differ'):evidence.terminal_win_share(value)

    def test_normalized_win_targets_ignore_reward_and_rank_payloads(self):
        public=native_fixture.public();record=corpus.normalized_public(public)
        typed=SimpleNamespace(record=record,sha256='1'*64);label=native_fixture.labels(public)
        for arm in label['arms']:
            for tail in arm['outcomes']:
                tail['terminal']['reward']='must not read';tail['terminal'].pop('rankReward')
        result=corpus.normalized_labels(label,public,typed,objective=objectives.WIN)
        self.assertEqual(result['actions'][1],dict(actionIndex=1,winShares=[1.,1.]))
        self.assertEqual(result['objectiveVersion'],objectives.WIN)
        with self.assertRaises((ValueError,KeyError)):
            corpus.normalized_labels(label,public,typed)

    def test_default_label_wire_remains_identical_to_explicit_legacy(self):
        public=native_fixture.public();record=corpus.normalized_public(public);typed=SimpleNamespace(record=record,sha256='1'*64)
        label=native_fixture.labels(public)
        implicit=corpus.normalized_labels(label,public,typed)
        explicit=corpus.normalized_labels(label,public,typed,objective=objectives.MIXED)
        self.assertEqual(b.canonical(implicit),b.canonical(explicit));self.assertNotIn('objectiveVersion',implicit)

    def test_training_and_target_objectives_cannot_cross(self):
        original=fixture.frozen(fixture.model());training,_=win_training(original);root=training.roots[0]
        with self.assertRaisesRegex(ValueError,'same explicit objective'):
            data.PairedTarget.from_record(root,fixture.label(root))
        with self.assertRaisesRegex(ValueError,'fitting objective must match'):
            fit.fit(training,original,original,b'not read',Path('/not-used'),fit.Config(tuple(range(1,21)),time.time()+60))

    def test_pure_fit_rejects_legacy_training_before_opening_output(self):
        original=fixture.frozen(fixture.model());training,_=fixture.corpus(original)
        with self.assertRaisesRegex(ValueError,'fitting objective must match'):
            fit.fit(training,original,original,b'not read',Path('/not-used'),fit.Config(tuple(range(1,21)),time.time()+60),
                objective=objectives.WIN)

    def test_pure_win_target_deltas_are_paired_not_reward_shaped(self):
        original=fixture.frozen(fixture.model());training,_=win_training(original)
        self.assertEqual(training.targets[0].deltas,(0.,1.,1.))
        self.assertEqual(training.context.objective,objectives.WIN)
        self.assertNotEqual(vars(training.context),vars(fixture.context(original)[0]))

    def test_win_wire_refuses_legacy_reward_field(self):
        original=fixture.frozen(fixture.model());training,_=win_training(original);root=training.roots[0]
        with self.assertRaisesRegex(ValueError,'Pure-win paired labels'):
            data.WinPairedTarget.from_record(root,fixture.label(root))

    def test_validation_split_is_rejected_before_decoding_win_labels(self):
        original=fixture.frozen(fixture.model());ctx,_=fixture.context(original)
        context=data.WinContext(**vars(ctx));record=fixture.root_record(512)
        root=data.PublicRoot.from_record(record,context,original.model,split='VALIDATION')
        class Explosive(dict):
            def __iter__(self):raise AssertionError('Validation labels decoded')
        with self.assertRaisesRegex(ValueError,'admitted split'):
            data.WinPairedTarget.from_record(root,Explosive())

    def test_rank_improvement_without_a_win_has_zero_win_target(self):
        self.assertEqual(evidence.terminal_win_share(game([1,4,3,2])),
            evidence.terminal_win_share(game([3,4,2,1])))

    def test_new_win_targets_supply_the_existing_gain_gradient(self):
        original=fixture.frozen(fixture.model());training,_=win_training(original);target=training.targets[0]
        logits=b.torch.tensor([0.,0.,0.],dtype=b.DTYPE,requires_grad=True)
        value=fit.gain(logits.softmax(0),[1/3]*3,[0,1,2],target.deltas,1.)
        value.backward()
        self.assertLess(float(logits.grad[0]),0.)
        self.assertGreater(float(logits.grad[1]),0.)

    def test_legacy_fit_receipt_is_not_a_win_fit_receipt(self):
        original=fixture.frozen(fixture.model());training,dictionary=fixture.corpus(original)
        with tempfile.TemporaryDirectory() as temporary:
            rejected=dict(accepted=False,anchor=dict(mean=.03,maximum=.11),attemptedAdamSteps=0)
            with patch.object(fit,'epoch',return_value=rejected):
                result=fit.fit(training,original,original,dictionary,Path(temporary)/'fit',
                    fit.Config(tuple(range(1,21)),time.time()+60))
            self.assertEqual(result['identity']['recipe'],fit.RECIPE)
            self.assertNotIn('objective_version',result['identity']['context'])
            with self.assertRaisesRegex(ValueError,'prior fit'):
                fit.completed(Path(temporary)/'fit',objective=objectives.WIN)

    def test_fit_cli_publishes_explicit_win_contract_and_no_activation(self):
        original=fixture.frozen(fixture.model());training,dictionary=win_training(original)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);actor=root/'actor.json';actor.write_bytes(original.raw)
            dictionary_path=root/'dictionary.json';dictionary_path.write_bytes(dictionary)
            protocol=root/'protocol.json';protocol.write_text('{}')
            raw_corpus=root/'corpus.json';raw_corpus.write_text('{}');output=root/'fit'
            class Corpus:
                record=dict(iteration=1)
                def __init__(self,*args):pass
                def context(self,*args,objective):
                    if objective!=objectives.WIN:raise AssertionError('Wrong CLI objective')
                    return training.context
                def training(self,*args):return training
            plan=SimpleNamespace(record=dict(iterations=[dict(shuffleSeeds=list(range(1,21)))]))
            argv=['token_paired_fit.py','--objective',objectives.WIN,'--protocol',str(protocol),
                '--corpus',str(raw_corpus),'--original-corpus',str(raw_corpus),'--original-actor',str(actor),
                '--current-actor',str(actor),'--dictionary',str(dictionary_path),'--output',str(output),
                '--deadline-epoch',str(time.time()+60)]
            rejected=dict(accepted=False,anchor=dict(mean=.03,maximum=.11),attemptedAdamSteps=0)
            with patch('sys.argv',argv),patch('token_paired_campaign_plan.Plan.from_record',return_value=plan),\
                patch('token_paired_corpus.Corpus',Corpus),patch.object(fit,'epoch',return_value=rejected),\
                contextlib.redirect_stdout(io.StringIO()) as printed:
                fit.main()
            done=fit.completed(output,objective=objectives.WIN)
            self.assertEqual(done['kind'],'token-paired-win-fit-complete-1')
            self.assertEqual(done['identity']['recipe']['objectiveVersion'],objectives.WIN)
            self.assertEqual(done['adamSteps'],0)
            self.assertIn('"activated": false',printed.getvalue())
            with self.assertRaisesRegex(ValueError,'prior fit'):
                fit.completed(output)

    def test_expired_cli_deadline_rejects_before_opening_corpus(self):
        argv=['token_paired_fit.py','--objective',objectives.WIN]
        for name in ('protocol','corpus','original-corpus','original-actor','current-actor','dictionary','output'):
            argv.extend(['--'+name,'/not-opened'])
        argv.extend(['--deadline-epoch',str(time.time()-1)])
        with patch('sys.argv',argv),patch('token_paired_campaign_plan.Plan.from_record',side_effect=AssertionError('Corpus opened')):
            with self.assertRaisesRegex(ValueError,'no corpus will be opened'):fit.main()


if __name__=='__main__':unittest.main()
