"""Constructed statistical and compact native-wire cases; no games, fits or numeric RNG."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest

import robust_token_campaign as c
import robust_league_campaign as league
import robust_token_worker as worker
from test_robust_token_campaign import plan as token_plan


def game(seat,win):
    winner=seat if win else (seat+1)%4
    return dict(wins=[float(index==winner) for index in range(4)],
        rewards=[float(index==winner) for index in range(4)],
        ranks=[float(index==winner) for index in range(4)],
        scores=[dict(player=f'p{index+1}',total=200 if index==winner else 100) for index in range(4)],
        elapsedMillis=0)


def rows(count,candidate_wins,control_wins=False,only=False):
    styles=('QUALIFIED','UNDERCUT','OVERBID','MIXED');result=[]
    for index in range(count):
        seat=index%4
        candidate=candidate_wins(index) if callable(candidate_wins) else candidate_wins
        control=control_wins(index) if callable(control_wins) else control_wins
        roles={'CANDIDATE':game(seat,candidate)}
        if not only:roles['CONTROL']=game(seat,control)
        result.append(dict(index=index,seed=index,seat=seat,opponentStyle=styles[(index//4+seat)%4],
            scienceSha256='c'*64,roles=roles,diagnostics={role:{} for role in roles},
            canonicalSha256={role:None for role in roles}))
    return result


class ExperimentalRulesTest(unittest.TestCase):
    def test_incomplete_parent_uses_apriori_selector_without_opening_partial_quality(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);parent=root/'protocol.json';c.write(parent,token_plan())
            (root/'reports').mkdir()
            (root/'reports/mixed.json').write_text('This forbidden confirmation record is deliberately not JSON.')
            closed=root/'closed.json';c.write(closed,dict(passed=True,complete=True,
                allNativeGroupsAndControllerIndependentlyGone=True,parentProtocolSha256=c.sha(parent)))
            adopted=league.adopt_selector(parent,root,closed)
            self.assertEqual(adopted['selectedCase'],'c06')
            self.assertFalse(adopted['completeDesktop320Initializer'])
            self.assertEqual(adopted['confirmationReportsOpened'],0)

    def test_explicit_profile_can_select_a_parallel_collector(self):
        flags=['-XX:ActiveProcessorCount=10','-XX:+UseG1GC','-XX:ParallelGCThreads=2','-Xms1024m']
        self.assertEqual(worker.java_options({'jvmOptions':flags}),flags)

    def test_performance_settings_cannot_inject_an_external_java_agent(self):
        with self.assertRaisesRegex(ValueError,'performance options'):
            worker.java_options({'jvmOptions':['-javaagent:/private/fixture.jar']})

    def test_training_assigns_work_to_both_hosts_for_every_population(self):
        for generation in range(1,7):
            assignments=[(member,chunk) for member in range(32) for chunk in range(2)
                if league.owner(member,chunk,generation)=='MAC']
            self.assertEqual(len(assignments),16)
            self.assertEqual(sum(member%2==0 for member,_ in assignments),8)
            self.assertEqual({chunk for _,chunk in assignments},{(generation-1)%2})

    def test_test_banks_have_fixed_small_mac_share(self):
        self.assertEqual([chunk for chunk in range(32) if league.test_owner(chunk)=='MAC'],[3,7,11,15,19,23,27,31])

    def test_six_generation_execution_budget_counts_actual_reproductions(self):
        training=6*32*128+sum(len(league.replay_indices('generation-1',member)) for member in range(32))*6
        development=2*1024*2+2*2*len(league.replay_indices('development'))
        qualification=2*2048*2+2*2*len(league.replay_indices('mixed'))
        self.assertEqual(training+development+qualification,36920)

    def test_only_prescribed_antithetic_members_are_reproduced(self):
        self.assertEqual(league.replay_indices('generation-4',0),[0,127])
        self.assertEqual(league.replay_indices('generation-4',1),[0,127])
        self.assertEqual(league.replay_indices('generation-4',2),[])

    def test_development_requires_improvement_over_start_and_control(self):
        final=rows(1024,True);start=rows(1024,True)
        report=league.development(final,start,list(range(1024)))
        self.assertTrue(report['controlWin95Passed'])
        self.assertFalse(report['learningWin95Passed'])
        self.assertFalse(report['passed'])

    def test_development_win_gates_ignore_other_metric_improvements(self):
        final=rows(1024,lambda index:index%2==0)
        start=rows(1024,lambda index:index%2!=0)
        for row in final:row['roles']['CANDIDATE']['rewards']=[1.]*4
        report=league.development(final,start,list(range(1024)))
        self.assertFalse(report['passed'])

    def test_repeated_controls_must_match_before_development_statistics_open(self):
        final=rows(1024,True);start=rows(1024,False)
        start[0]['roles']['CONTROL']=game(0,True)
        with self.assertRaisesRegex(ValueError,'CONTROL worlds must match'):
            league.development(final,start,list(range(1024)))

    def test_native_candidate_only_case_admits_completion_order(self):
        original=rows(128,True,only=True)
        ordered=league.check_worlds(list(reversed(original)),list(range(128)),True)
        self.assertEqual([row['index'] for row in ordered],list(range(128)))

    def test_candidate_only_case_rejects_a_hidden_second_game_role(self):
        with self.assertRaisesRegex(ValueError,'role accounting'):
            league.check_worlds(rows(128,True),list(range(128)),True)

    def test_whole_win_matrix_preserves_signed_member_identity(self):
        members=[dict(index=index,direction=index//2,sign=1 if index%2==0 else -1,
            bundle=dict(sha256=str(index).zfill(64))) for index in range(32)]
        population=dict(members=members,worldSeeds=list(range(128)),generationBankSha256='b'*64)
        result=league.outcome_matrix(1,population,{index:rows(128,index%2==0,only=True) for index in range(32)})
        self.assertEqual(len(result['members']),32)
        self.assertEqual(result['members'][1]['sign'],-1)
        self.assertEqual(result['members'][1]['winShares'],[0.]*128)

    def test_partial_population_cannot_supply_numeric_fitness(self):
        with self.assertRaisesRegex(ValueError,'all32'):
            league.outcome_matrix(1,{}, {0:[]})


class NativeWireTest(unittest.TestCase):
    def test_candidate_only_native_chunk_counts_one_game_per_world(self):
        with tempfile.TemporaryDirectory() as directory:
            instance=object.__new__(league.Campaign)
            instance.output=Path(directory);profile=instance.output/'profile.json';profile.write_text('{}')
            instance.profile_paths={'DESKTOP':profile};instance.profiles={'DESKTOP':{
                'inferenceAsset':dict(path='invented-asset',sha256='a'*64),
                'nomination':dict(path='invented-nomination',sha256='b'*64),
                'arena':dict(path='invented-arena',sha256='c'*64)}}
            instance.selector=None;instance.protocol_sha='f'*64
            instance.plan={'banks':{'generation-1':{'seeds':list(range(128))}}}
            instance.lock=threading.Lock();instance.reserved=0;instance.known=0
            instance.status=lambda **_:None
            def fake_request(host,body,folder,mode=None):
                folder.mkdir(parents=True);source=body['source'];original=rows(64,True,only=True)
                original=list(reversed(original));source_sha=hashlib.sha256(c.canonical(source)).hexdigest()
                prepared=dict(sourceSha256=source_sha,scienceSha256='c'*64,roles=['CANDIDATE'],
                    passed=True,newGames=0,modelForwards=0)
                raw_worlds=b''.join(c.canonical(row) for row in original)
                raw_prepared=c.canonical(prepared)
                files={'worlds.jsonl':raw_worlds,'prepared.json':raw_prepared}
                finished=dict(complete=True,sourceSha256=source_sha,scienceSha256='c'*64,
                    candidateOnly=True,roles=['CANDIDATE'],worlds=64,games=64,reproductions=0,
                    scientificTerminalExecutions=64,attemptedGames=64,completedGames=64,newGames=64,
                    newFits=0,qualificationClaimed=False,
                    files={name:hashlib.sha256(raw).hexdigest() for name,raw in files.items()})
                files['finished.json']=c.canonical(finished)
                return dict(complete=True,taskId=body['taskId'],profileSha256=c.sha(profile),
                    files={name:dict(sha256=hashlib.sha256(raw).hexdigest(),
                        base64=base64.b64encode(raw).decode()) for name,raw in files.items()})
            instance.request=fake_request
            result=instance.native_task('DESKTOP','generation-1',dict(id='member-02',member=2),0,True)
            self.assertEqual(len(result),64)
            self.assertEqual(instance.known,64)
            self.assertEqual(instance.reserved,64)


if __name__=='__main__':unittest.main()
