"""Invented metadata/world fixtures; no actual scientific games or fitting."""
import json
from pathlib import Path
import signal
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import robust_token_campaign as c
import mac_qualification_campaign as m
import mac_qualification_close as close


def plan():
    return dict(kind=m.KIND,candidateBundleSha256=m.CANDIDATE,baseInferenceAssetSha256=m.ASSET,
        nativeJarSha256=m.NATIVE,selector=m.SELECTOR,chunkSize=256,maximumTerminalExecutions=m.MAXIMUM,
        maximumScienceSeconds=25200,auditSeconds=600,globalDeadlineEpoch=m.USER_END,
        confidenceMultiplier=c.MULTIPLIER,banks={name:dict(count=2048,
            seeds=list(range(index*2048,(index+1)*2048)),replayIndices=m.REPLAYS,
            opponentStyle='BALANCED' if name=='mixed' else 'QUALIFIED') for index,name in enumerate(m.BANKS)})


def worlds(count=2048,win=1):
    def game(value):
        return dict(wins=[value]*4,rewards=[.5]*4,ranks=[.5]*4,
            scores=[dict(player=f'p{index+1}',total=100) for index in range(4)])
    return [dict(index=index,seed=index,seat=index%4,
        opponentStyle=('QUALIFIED','UNDERCUT','OVERBID','MIXED')[(index//4+index%4)%4],
        roles=dict(CANDIDATE=game(win),CONTROL=game(0))) for index in range(count)]


def native_fixture(root):
    item=root/'constructed-input';item.write_text('invented bytes, never a model')
    subject=dict(path=str(item),sha256=c.sha(item))
    source=dict(kind='robust-token-chunk-1',protocolSha256='a'*64,candidateBundle=subject,
        inferenceAsset=subject,nomination=subject,arena=subject,selector=m.SELECTOR,seeds=[10,11],
        globalStart=0,count=2,replayIndices=[0],opponentStyle='BALANCED',candidateOnly=False)
    native=root/'native';native.mkdir()
    source_sha=__import__('hashlib').sha256(c.canonical(source)).hexdigest()
    c.write(native/'prepared.json',dict(passed=True,newGames=0,modelForwards=0,maximumTerminalExecutions=6,
        sourceSha256=source_sha,scienceSha256='science',roles=['CANDIDATE','CONTROL'],
        inputPins={str(item):c.sha(item)},scienceIdentity=dict(candidateBundleSha256=subject['sha256'],
            controlAndRivalsBundleSha256=m.CURRENT,selector=m.SELECTOR)))
    rows=worlds(2)
    for row in rows:
        row.update(seed=source['seeds'][row['index']],scienceSha256='science',
            diagnostics={role:{} for role in row['roles']},
            canonicalSha256={role:'f'*64 if row['index']==0 else None for role in row['roles']})
    (native/'worlds.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in reversed(rows)))
    replays=[dict(index=0,role=role,matched=True,terminal=rows[0]['roles'][role],
        diagnostics={},canonicalSha256='f'*64) for role in rows[0]['roles']]
    (native/'reproductions.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in replays))
    c.write(native/'finished.json',dict(complete=True,sourceSha256=source_sha,scienceSha256='science',
        roles=['CANDIDATE','CONTROL'],candidateOnly=False,worlds=2,games=4,reproductions=2,
        scientificTerminalExecutions=6,attemptedGames=6,completedGames=6,newGames=6,newFits=0,
        qualificationClaimed=False,files={name:c.sha(native/name) for name in
            ('prepared.json','worlds.jsonl','reproductions.jsonl')}))
    return source,native


def alter(native,name,change):
    path=native/name;rows=[json.loads(line) for line in path.read_text().splitlines()];change(rows)
    path.write_text(''.join(json.dumps(row)+'\n' for row in rows))
    record=c.read(native/'finished.json');record['files'][name]=c.sha(path)
    (native/'finished.json').write_text(json.dumps(record))


class QualificationTests(unittest.TestCase):
    def test_exact_frozen_two_bank_plan(self):
        self.assertEqual(m.validate(plan())['maximumTerminalExecutions'],8208)

    def test_banks_must_be_disjoint(self):
        value=plan();value['banks']['homogeneous']['seeds'][0]=0
        with self.assertRaisesRegex(ValueError,'disjoint'):m.validate(value)

    def test_candidate_cannot_be_changed(self):
        value=plan();value['candidateBundleSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'exactly final'):m.validate(value)

    def test_original_clock_allowance_cannot_change(self):
        value=plan();value['maximumScienceSeconds']+=1
        with self.assertRaisesRegex(ValueError,'allowances'):m.validate(value)

    def test_all_chunks_and_reproductions_total8208(self):
        p=plan();profile={name:{} for name in ('inferenceAsset','nomination','arena','candidateBundle')}
        sources=[m.source_value(p,'a'*64,profile,bank,start) for bank in m.BANKS for start in range(0,2048,256)]
        self.assertEqual(sum(m.expected_executions(source) for source in sources),8208)
        self.assertEqual(len(sources),16)

    def test_only_positive_strict_win99_passes(self):
        bank=dict(seeds=list(range(2048)),opponentStyle='BALANCED')
        self.assertTrue(m.complete_bank(worlds(),bank)['win99Passed'])
        self.assertFalse(m.complete_bank(worlds(win=0),bank)['win99Passed'])

    def test_partial_bank_cannot_produce_a_win_interval(self):
        with self.assertRaisesRegex(ValueError,'every original world'):
            m.complete_bank(worlds()[:-1],dict(seeds=list(range(2048)),opponentStyle='BALANCED'))

    def test_unordered_native_completion_is_valid(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary))
            self.assertEqual([row['index'] for row in m.validate_chunk(source,native)],[0,1])

    def test_duplicate_world_cannot_replace_an_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary))
            alter(native,'worlds.jsonl',lambda rows:rows[0].update(index=rows[1]['index']))
            with self.assertRaisesRegex(ValueError,'exact original global index set'):m.validate_chunk(source,native)

    def test_prescribed_reproduction_must_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary))
            alter(native,'reproductions.jsonl',lambda rows:rows[0].update(matched=False))
            with self.assertRaisesRegex(ValueError,'canonical decision'):m.validate_chunk(source,native)

    def test_dynamic_candidate_bytes_are_rehashed(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary));Path(source['candidateBundle']['path']).write_text('changed')
            with self.assertRaisesRegex(ValueError,'input changed'):m.validate_chunk(source,native)

    def test_native_count_cannot_exclude_reproductions(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary));record=c.read(native/'finished.json');record['attemptedGames']=4
            (native/'finished.json').write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError,'counted exactly'):m.validate_chunk(source,native)

    def test_partial_native_counts_remain_lower_bounds(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,native=native_fixture(Path(temporary));(native/'finished.json').unlink()
            c.write(native/'failed.json',dict(attemptedGames=5,completedGames=3))
            result=close.accounting(source,native)
            self.assertEqual(result['completedLowerBound'],3)
            self.assertEqual(result['reservedUpperBound'],6)
            self.assertFalse(result['finished'])

    def test_internal_failure_is_not_an_explicit_interruption(self):
        instance=object.__new__(m.Campaign);instance.cancelled=threading.Event();instance.process=None;instance.interrupted=False
        instance.stop();self.assertFalse(instance.interrupted)
        instance.stop(signal.SIGTERM);self.assertTrue(instance.interrupted)

    def test_original_clock_is_clamped_and_attempt_cannot_resume(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);protocol=root/'protocol.json';profile=root/'profile.json';approval=root/'approval.json'
            c.write(protocol,plan());c.write(profile,{})
            c.write(approval,dict(launchAuthorized=True,protocolSha256=c.sha(protocol),profileSha256=c.sha(profile)))
            with patch.object(m,'verify_profile',return_value=dict(root=str(root))), \
                    patch.object(m.time,'time',return_value=m.USER_END-100):
                instance=m.Campaign(protocol,profile,approval,c.sha(approval),root/'execution')
                self.assertEqual(instance.clock['deadlineEpoch'],m.USER_END)
                self.assertEqual(instance.clock['auditDeadlineEpoch'],m.USER_END+600)
                with self.assertRaisesRegex(ValueError,'once-only'):
                    m.Campaign(protocol,profile,approval,c.sha(approval),root/'execution')

    def test_partial_audit_is_refused_before_reading_outcomes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);protocol=root/'protocol.json';c.write(protocol,plan())
            c.write(root/'clock.json',dict(auditDeadlineEpoch=time.time()+100,protocolSha256=c.sha(protocol)))
            with self.assertRaisesRegex(ValueError,'complete attempt'):
                m.audit(protocol,root/'missing-profile',root,dict(complete=False))


class LifecycleTests(unittest.TestCase):
    def test_expired_task_cannot_start_a_subprocess(self):
        with tempfile.TemporaryDirectory() as temporary,patch.object(m.subprocess,'Popen') as start:
            with self.assertRaisesRegex(ValueError,'expiry'):
                m.bounded_process([sys.executable],Path(temporary),{},dict(deadlineEpoch=time.time()-1),threading.Event())
            start.assert_not_called()

    def test_hung_process_is_reaped_at_original_deadline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);profile=dict(root=str(root),minimumFreeBytes=0,maximumDataBytes=1024**2,maximumRssBytes=1024**3)
            started=time.time()
            with patch.object(m.resource,'group_rss',return_value=0):
                with self.assertRaisesRegex(ValueError,'deadline'):
                    m.bounded_process([sys.executable,'-c','import time;time.sleep(30)'],root,profile,
                        dict(deadlineEpoch=started+.15),threading.Event())
            pid=c.read(root/'owner.json')['nativePid']
            self.assertLess(time.time()-started,3)
            with self.assertRaises(ProcessLookupError):__import__('os').kill(pid,0)

    def test_low_free_space_blocks_start(self):
        with tempfile.TemporaryDirectory() as temporary,patch.object(m.subprocess,'Popen') as start:
            root=Path(temporary);profile=dict(root=str(root),minimumFreeBytes=10**30,maximumDataBytes=10**30)
            with self.assertRaisesRegex(ValueError,'disk allowance'):
                m.bounded_process([sys.executable],root,profile,dict(deadlineEpoch=time.time()+10),threading.Event())
            start.assert_not_called()


if __name__=='__main__':unittest.main()
