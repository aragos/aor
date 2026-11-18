import copy
from pathlib import Path
import tempfile
import unittest

import cpe_paired_campaign_plan as p
import token_paired_campaign_plan as legacy


def fixture():
    """Invented source-only integers; never an audited scientific reservation."""
    values = iter(range(100000, 130000))
    return dict(kind=p.KIND, recipe=copy.deepcopy(p.RECIPE),
        maximumTerminalExecutions=76070, scienceSeconds=64800, auditSeconds=900,
        maximumDataBytes=48*1024**3, automaticRetry=False, automaticSuccessor=False,
        hosts={host:dict(machineCpus=cpus, workers=workers, heapMiB=6144,
            maximumRssBytes=12*1024**3, maximumDataBytes=24*1024**3,
            minimumFreeBytes=16*1024**3) for host, cpus, workers in
            (('DESKTOP', 16, 10), ('MAC', 10, 6))},
        originalBundleSha256='a'*64, bundleScopeSha256='b'*64, ecologySha256='c'*64,
        heads={head:dict(originalActorSha256=p.ORIGINAL_EXPANSION_SHA if head=='EXPANSION' else 'd'*64,
            dictionarySha256='e'*64, semanticDictionarySha256='f'*64,
            scopeSha256=p.digest(dict(bundleScopeSha256='b'*64, head=head))) for head in p.HEADS},
        iterations=[dict(iteration=i, heads={head:dict(origins=[dict(
            gameSeed=next(values), reservoirSeed=next(values), proposalSeed=next(values),
            future=[next(values), next(values)]) for _ in range(640)],
            shuffleSeeds=[next(values) for _ in range(20)]) for head in p.HEADS}) for i in (1, 2)],
        screenBank=dict(nativeOffset=1000000, count=1024,
            seeds=[-999000000+i*8191 for i in range(1024)]),
        selection=dict(candidate='ITERATION_2_COMPLETE_BUNDLE_ONLY',
            validationEqualPhaseRewardLower95Positive=True,
            finalMinusInitialRewardLower95Positive=True,
            finalMinusRawWinAndRewardLower95Positive=True))


class PlanTests(unittest.TestCase):
    def test_protocol_digest_uses_exact_framed_json_bytes_without_reserialization(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'protocol.json';plan=p.Plan.from_record(fixture())
            path.write_bytes(plan.wire+b'\n')
            self.assertEqual(p.Plan.from_file(path).sha256,plan.sha256)
            path.write_bytes(plan.wire+b'\n\n')
            with self.assertRaisesRegex(ValueError,'framing LF'):p.Plan.from_file(path)

    def test_each_head_has_exact_split_and_no_fixed_window_ordinal(self):
        for head in p.HEADS:
            values=[i for host in p.HOSTS for chunk in p.COLLECTION_CHUNKS[host] for i in p.indices(chunk)]
            self.assertEqual(sorted(values), list(range(640)))
            self.assertEqual(p.request(639,head), dict(head=head,globalIndex=639,seat=3,
                epoch=1,split='VALIDATION'))
            self.assertEqual(p.request(511,head)['split'],'TRAIN')
            self.assertNotIn('ordinal',p.request(0,head))

    def test_budget_counts_six_independent_cohorts(self):
        budget=p.expected_terminal_budget()
        self.assertEqual(sum(v for k,v in budget.items() if k!='maximum'),76070)
        self.assertEqual(2*3*sum(len(p.replays(c)) for c in range(10)),24)

    def test_cross_head_reservoir_stream_collision_is_rejected(self):
        value=fixture()
        value['iterations'][1]['heads']['PURCHASE']['origins'][0]['reservoirSeed']=\
            value['iterations'][0]['heads']['CARD']['origins'][0]['proposalSeed']
        with self.assertRaisesRegex(ValueError,'globally disjoint'):p.Plan.from_record(value)

    def test_trained_expansion_actor_cannot_replace_original(self):
        value=fixture();value['heads']['EXPANSION']['originalActorSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'untrained75'):p.Plan.from_record(value)

    def test_head_scope_cannot_alias_another_head(self):
        value=fixture();value['heads']['CARD']['scopeSha256']=value['heads']['PURCHASE']['scopeSha256']
        with self.assertRaisesRegex(ValueError,'head scope'):p.Plan.from_record(value)

    def test_no_head_or_selection_fallback_is_accepted(self):
        value=fixture();value['selection']['candidate']='BEST_HEAD'
        with self.assertRaisesRegex(ValueError,'complete final bundle'):p.Plan.from_record(value)

    def test_old_token_plan_rejects_new_cpe_recipe(self):
        with self.assertRaisesRegex(ValueError,'TOKEN'):legacy.Plan.from_record(fixture())

    def test_host_storage_floor_cannot_be_changed(self):
        value=fixture();value['hosts']['MAC']['minimumFreeBytes']-=1
        with self.assertRaisesRegex(ValueError,'resource'):p.Plan.from_record(value)

    def test_source_uses_only_prescribed_head_bank_and_current_complete_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'bundle.json';path.write_text('{"constructed":true}')
            subject=p.subject(path);value=fixture();value['originalBundleSha256']=subject['sha256']
            plan=p.Plan.from_record(value)
            source=plan.collection_source(1,'PURCHASE',9,'MAC',subject,subject,subject)
            self.assertEqual(source['origins'],value['iterations'][0]['heads']['PURCHASE']['origins'])
            self.assertEqual(source['globalStart'],576)
            self.assertEqual(source['currentBundle'],source['originalBundle'])
            with self.assertRaisesRegex(ValueError,'migrate'):
                plan.collection_source(1,'PURCHASE',9,'DESKTOP',subject,subject,subject)

    def test_shared_clock_cannot_extend_and_early_terminal_tightens_audit(self):
        clock=p.Clock(100.,64900.)
        self.assertEqual(clock.audit_deadline(200.),1100.)
        self.assertEqual(clock.audit_deadline(65000.),65800.)
        with self.assertRaisesRegex(ValueError,'expired'):clock.remaining(64900.)
        with self.assertRaisesRegex(ValueError,'18-hour'):p.Clock(100.,65000.)


if __name__=='__main__':unittest.main()
