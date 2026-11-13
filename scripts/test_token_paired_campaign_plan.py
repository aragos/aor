import copy
from pathlib import Path
import tempfile
import unittest

import token_paired_campaign_plan as p


def fixture():
    cursor = iter(range(100000, 120000))
    return dict(kind=p.KIND, recipe=p.RECIPE.copy(), maximumTerminalExecutions=27414,
        scienceSeconds=21600, auditSeconds=900, maximumDataBytes=16*1024**3,
        automaticRetry=False, automaticSuccessor=False,
        hosts={host:dict(machineCpus=cpus, workers=workers, heapMiB=6144,
            maximumRssBytes=12*1024**3, maximumDataBytes=8*1024**3,
            minimumFreeBytes=16*1024**3) for host,cpus,workers in
            (('DESKTOP',16,10),('MAC',10,6))},
        originalActorSha256='a'*64, ecologySha256='b'*64, dictionarySha256='c'*64,
        iterations=[dict(iteration=i,origins=[dict(gameSeed=next(cursor),
            proposalSeed=next(cursor),future=[next(cursor),next(cursor)]) for _ in range(640)],
            shuffleSeeds=[next(cursor) for _ in range(20)]) for i in (1,2)],
        screenSeeds=[next(cursor) for _ in range(1024)],
        selection=dict(candidate='ITERATION_2_ONLY',validationRewardLower95Positive=True,
            finalMinusInitialRewardLower95Positive=True,finalMinusRawWinAndRewardLower95Positive=True))


class PlanTests(unittest.TestCase):
    def test_exact_global_coverage_and_split(self):
        for screen,count in ((False,640),(True,1024)):
            chunks=p.SCREEN_CHUNKS if screen else p.COLLECTION_CHUNKS
            values=[i for host in p.HOSTS for chunk in chunks[host] for i in p.indices(chunk,screen)]
            self.assertEqual(sorted(values),list(range(count)))
        self.assertEqual(p.request(511)['split'],'TRAIN')
        self.assertEqual(p.request(512)['split'],'VALIDATION')
        self.assertEqual(p.request(639),dict(globalIndex=639,seat=3,epoch=1,ordinal=2,split='VALIDATION'))

    def test_replay_count_once_globally(self):
        self.assertEqual(sum(len(p.replays(i)) for i in range(10)),4)
        self.assertEqual(sum(len(p.replays(i,True))*3 for i in range(8)),12)
        self.assertEqual(sum(v for k,v in p.expected_terminal_budget().items() if k!='maximum'),27414)

    def test_stream_collision_across_iterations_rejected(self):
        value=fixture();value['iterations'][1]['origins'][0]['future'][0]=value['screenSeeds'][0]
        with self.assertRaisesRegex(ValueError,'disjoint'):p.Plan.from_record(value)

    def test_recipe_tuning_rejected(self):
        value=fixture();value['recipe']['learningRate']=.001
        with self.assertRaisesRegex(ValueError,'recipe'):p.Plan.from_record(value)

    def test_transfer_copies_cannot_exceed_combined_storage(self):
        value=fixture();value['hosts']['MAC']['maximumDataBytes']+=1
        with self.assertRaisesRegex(ValueError,'Physical retained'):p.Plan.from_record(value)

    def test_native_source_preserves_full_bank_and_host_range(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'actor.json';path.write_text('{}')
            item=p.subject(path);value=fixture();value['originalActorSha256']=item['sha256']
            plan=p.Plan.from_record(value)
            source=plan.collection_source(1,9,'MAC',item,item,item)
            self.assertEqual(source['globalStart'],576)
            self.assertEqual(source['origins'],value['iterations'][0]['origins'])
            with self.assertRaisesRegex(ValueError,'migrate'):
                plan.collection_source(1,9,'DESKTOP',item,item,item)

    def test_subject_tamper_rejected_before_native_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'actor.json';path.write_text('{}');item=p.subject(path)
            path.write_text('{"changed":true}')
            with self.assertRaisesRegex(ValueError,'changed'):p.checked_subject(item)

    def test_clock_cannot_be_renewed_and_early_audit_tightens(self):
        clock=p.Clock(100.,21700.)
        self.assertEqual(clock.audit_deadline(200.),1100.)
        self.assertEqual(clock.audit_deadline(22000.),22600.)
        with self.assertRaisesRegex(ValueError,'expired'):clock.remaining(21700.)
        with self.assertRaisesRegex(ValueError,'six-hour'):p.Clock(100.,22000.)

    def test_duplicate_json_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'x.json';path.write_text('{"a":1,"a":2}')
            with self.assertRaisesRegex(ValueError,'Duplicate'):p.read(path)


if __name__=='__main__':unittest.main()
