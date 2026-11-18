"""Invented lifecycle/statistical fixtures only; these are not scientific game outcomes."""
import base64
import copy
import json
import math
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
import threading
from unittest.mock import patch

import robust_token_campaign as campaign
import robust_token_worker as worker


def plan():
    return dict(kind=campaign.KIND, maximumScienceSeconds=10800, auditSeconds=600,
        maximumTerminalExecutions=16464, chunkSize=64, minimumDevelopmentDelta=0.0,
        confidenceMultiplier=campaign.MULTIPLIER, globalDeadlineEpoch=time.time()+10000,
        caseConfigs=[dict(id=f'c{index:02d}', selector={}) for index in range(8)],
        banks={name:dict(seeds=list(range(start, start+count)), replayIndices=replays,
            opponentStyle='QUALIFIED' if name == 'homogeneous' else 'BALANCED')
            for name,start,count,replays in (
                ('development', 0, 512, [0,170,341,511]),
                ('mixed', 512, 2048, [0,682,1365,2047]),
                ('homogeneous', 2560, 2048, [0,682,1365,2047]))})


def worlds(count=16):
    styles = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED')
    def game(win):
        return dict(wins=[win]*4, rewards=[.5]*4, ranks=[.5]*4,
            scores=[dict(player=f'p{seat+1}', total=100) for seat in range(4)])
    return [dict(index=index, seed=index, seat=index%4,
        opponentStyle=styles[(index//4+index%4)%4],
        roles=dict(CANDIDATE=game(1), CONTROL=game(0))) for index in range(count)]


class StatisticsTest(unittest.TestCase):
    def test_concurrent_native_completion_order_does_not_change_world_membership(self):
        campaign.validate_chunk_indices([{'index':3},{'index':1},{'index':2},{'index':0}],0,4)

    def test_duplicate_world_cannot_replace_another_original_world(self):
        with self.assertRaisesRegex(ValueError,'exact original global index set'):
            campaign.validate_chunk_indices([{'index':0},{'index':1},{'index':1},{'index':3}],0,4)

    def test_native_failure_retains_failure_and_partial_execution_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            instance=object.__new__(campaign.Campaign)
            instance.output=Path(directory);instance.plan={'caseConfigs':[]}
            instance.protocol_sha='constructed-protocol'
            instance.cancel=threading.Event();instance.interrupted=False
            instance.lock=threading.Lock();instance.active={};instance.original_handlers={}
            instance.executions=128;instance.reserved_executions=258
            def fail(*_):
                instance.stop()
                raise RuntimeError('constructed native failure')
            instance.bank=fail
            with self.assertRaisesRegex(RuntimeError,'constructed native failure'):instance.run()
            terminal=campaign.read(instance.output/'terminal.json')
            self.assertEqual(terminal['status'],'failed')
            self.assertEqual(terminal['knownCompletedTerminalExecutions'],128)
            self.assertEqual(terminal['reservedTerminalUpperBound'],258)
            self.assertIn('constructed native failure',terminal['reason'])

    def test_internal_abort_does_not_become_a_user_interruption(self):
        instance=object.__new__(campaign.Campaign)
        instance.cancel=threading.Event();instance.interrupted=False
        instance.lock=threading.Lock();instance.active={}
        instance.stop()
        self.assertTrue(instance.cancel.is_set())
        self.assertFalse(instance.interrupted)

    def test_explicit_term_remains_user_interruption(self):
        instance=object.__new__(campaign.Campaign)
        instance.cancel=threading.Event();instance.interrupted=False
        instance.lock=threading.Lock();instance.active={}
        instance.stop(signal.SIGTERM,None)
        self.assertTrue(instance.interrupted)

    def test_independent_paired_difference_interval(self):
        result = campaign.interval([1, 0, 1, 0])
        self.assertEqual(result['mean'], .5)
        self.assertAlmostEqual(result['standardError'], math.sqrt(1/12))

    def test_selection_uses_win_mean_and_stable_case_order_only(self):
        cases = [dict(id='a'), dict(id='b')]
        reports = {'a':dict(wins=dict(mean=.1), chaos=dict(mean=1)),
                   'b':dict(wins=dict(mean=.1), chaos=dict(mean=-1))}
        self.assertEqual(campaign.chosen_case(reports, cases), cases[0])

    def test_nonpositive_development_does_not_open_confirmation(self):
        self.assertIsNone(campaign.chosen_case({'a':dict(wins=dict(mean=0))}, [dict(id='a')]))

    def test_missing_case_cannot_select(self):
        with self.assertRaisesRegex(ValueError, 'all eight'):
            campaign.chosen_case({'a':dict(wins=dict(mean=1))}, [dict(id='a'),dict(id='b')])

    def test_complete_crossed_world_table_aggregates(self):
        rows=worlds(); result=campaign.aggregate(rows, dict(seeds=list(range(16)),opponentStyle='BALANCED'))
        self.assertEqual(result['wins']['lower99'], 1)
        self.assertEqual(result['chaos']['mean'], 0)

    def test_missing_world_cannot_be_reported(self):
        with self.assertRaisesRegex(ValueError, 'every original world'):
            campaign.aggregate(worlds()[:-1], dict(seeds=list(range(16)),opponentStyle='BALANCED'))

    def test_same_style_as_seat_is_rejected(self):
        rows=worlds()
        for row in rows:row['opponentStyle']=('QUALIFIED','UNDERCUT','OVERBID','MIXED')[row['seat']]
        with self.assertRaisesRegex(ValueError, 'confounded'):
            campaign.aggregate(rows, dict(seeds=list(range(16)),opponentStyle='BALANCED'))

    def test_nonfinite_terminal_metric_is_rejected(self):
        rows=worlds();rows[0]['roles']['CANDIDATE']['wins'][0]=float('nan')
        with self.assertRaisesRegex(ValueError, 'finite normalized'):
            campaign.aggregate(rows, dict(seeds=list(range(16)),opponentStyle='BALANCED'))

    def test_valid_fixed_plan(self):
        self.assertEqual(campaign.validate(plan())['maximumTerminalExecutions'],16464)

    def test_confirmation_worlds_cannot_reuse_development(self):
        value=plan();value['banks']['mixed']['seeds'][0]=0
        with self.assertRaisesRegex(ValueError, 'mutually disjoint'):
            campaign.validate(value)

    def test_variable_execution_budget_is_rejected(self):
        value=plan();value['maximumTerminalExecutions']+=1
        with self.assertRaisesRegex(ValueError, 'fixed finite method'):
            campaign.validate(value)

    def test_native_tasks_partition_without_duplicates(self):
        owners=[campaign.owner(index) for index in range(32)]
        self.assertEqual(owners.count('DESKTOP'),20)
        self.assertEqual(owners.count('MAC'),12)


class NativeLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.root=Path(self.temporary.name)
        self.input=self.root/'input.json';self.input.write_text('{}\n')
        self.java=self.root/'fake-java'
        self.make_native("""output=pathlib.Path(sys.argv[sys.argv.index('--output')+1]);output.mkdir()
(output/'worlds.jsonl').write_text('{"fixture":true}\\n')
files={'worlds.jsonl':hashlib.sha256((output/'worlds.jsonl').read_bytes()).hexdigest()}
(output/'finished.json').write_text(json.dumps({'complete':True,'files':files}))
""")
        self.profile=self.root/'profile.json'
        self.record=dict(kind='robust-token-host-1',host='MAC',workers=1,
            pins={str(self.input):campaign.sha(self.input)},root=str(self.root),
            minimumFreeBytes=0,maximumDataBytes=10485760,maximumRssBytes=104857600,
            java=str(self.java),heapMiB=64,classpath='constructed-fixture')
        self.update_profile()
        self.request=dict(kind='robust-token-worker-task-1',taskId='development/c00/chunk-0',
            profileSha256=campaign.sha(self.profile),clock=dict(deadlineEpoch=time.time()+5),
            source=dict(inferenceAsset=dict(path=str(self.input),sha256=campaign.sha(self.input))))

    def tearDown(self):self.temporary.cleanup()

    def make_native(self,body):
        self.java.write_text('#!'+sys.executable+'\nimport hashlib,json,pathlib,sys,time\n'+body)
        self.java.chmod(0o700)

    def update_profile(self):self.profile.write_text(json.dumps(self.record))

    def test_native_outputs_are_losslessly_transported(self):
        with patch.object(worker,'group_rss',return_value=0):result=worker.execute(self.profile,self.request)
        raw=base64.b64decode(result['files']['worlds.jsonl']['base64'])
        self.assertEqual(raw,b'{"fixture":true}\n')
        self.assertEqual(result['files']['worlds.jsonl']['sha256'],campaign.sha(
            self.root/'tasks/development/c00/chunk-0/native/worlds.jsonl'))

    def test_second_attempt_cannot_start_native_work(self):
        with patch.object(worker,'group_rss',return_value=0):worker.execute(self.profile,self.request)
        with self.assertRaisesRegex(ValueError,'once-only'):
            worker.execute(self.profile,self.request)

    def test_expired_clock_cannot_start_native_work(self):
        self.request['clock']['deadlineEpoch']=time.time()-1
        with self.assertRaisesRegex(ValueError,'original science deadline'):
            worker.execute(self.profile,self.request)
        self.assertFalse((self.root/'tasks').exists())

    def test_changed_input_cannot_start_native_work(self):
        self.input.write_text('{"changed":true}')
        with self.assertRaisesRegex(ValueError,'pinned bytes'):
            worker.execute(self.profile,self.request)
        self.assertFalse((self.root/'tasks').exists())

    def test_deadline_reaps_hung_original_process(self):
        self.make_native('time.sleep(60)\n')
        self.request['clock']['deadlineEpoch']=time.time()+.2
        with patch.object(worker,'group_rss',return_value=0):
            with self.assertRaisesRegex(ValueError,'original-deadline'):
                worker.execute(self.profile,self.request)
        directory=self.root/'tasks/development/c00/chunk-0'
        self.assertTrue(campaign.read(directory/'failed.json')['nativeProcessExited'])
        pid=campaign.read(directory/'owner.json')['nativePid']
        with self.assertRaises(ProcessLookupError):os.kill(pid,0)

    def test_group_memory_guard_reaps_process(self):
        self.make_native('time.sleep(60)\n')
        with patch.object(worker,'group_rss',return_value=10**12):
            with self.assertRaisesRegex(ValueError,'group-memory-cap'):
                worker.execute(self.profile,self.request)
        self.assertTrue(campaign.read(self.root/'tasks/development/c00/chunk-0/failed.json')['nativeProcessExited'])

    def test_free_space_floor_prevents_native_start(self):
        self.record['minimumFreeBytes']=10**30;self.update_profile()
        self.request['profileSha256']=campaign.sha(self.profile)
        with self.assertRaisesRegex(ValueError,'free-space floor'):
            worker.execute(self.profile,self.request)
        self.assertFalse((self.root/'tasks').exists())


if __name__=='__main__':unittest.main()
