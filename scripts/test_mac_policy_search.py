"""Invented native records and tiny pinned files only; never launches a native process."""
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import learned_bid_contract as policy
import mac_policy_search as search
import mac_policy_prepare as prepare
import robust_token_audit as native_audit
import robust_token_campaign as c
import robust_token_worker as resources


def game(winner):
    ranks=[(3-(seat-winner)%4)/3 for seat in range(4)]
    wins=[float(seat==winner) for seat in range(4)]
    scores=[dict(player=f'p{seat+1}',advancePoints=0,cash=int(70+30*ranks[seat]),misery=0,
        total=int(70+30*ranks[seat]),winner=bool(wins[seat])) for seat in range(4)]
    return dict(scores=scores,wins=wins,ranks=ranks,rewards=[.8*w+.2*r for w,r in zip(wins,ranks)],
        decisions=20,elapsedMillis=0)


def producer(source, folder, concentrated=False):
    """The explicit fixture producer emits native-shaped JSON, not gameplay or model output."""
    folder.mkdir(parents=True)
    identity=dict(kind='robust-token-science-1',protocolSha256=source['protocolSha256'],
        seeds=source['seeds'],replayIndices=source['replayIndices'],opponentStyle=source['opponentStyle'],
        selector=source['selector'],candidateBundleSha256=source['candidateBundle']['sha256'],
        inferenceAssetSha256=source['inferenceAsset']['sha256'],nominationSha256=source['nomination']['sha256'],
        roles=['CANDIDATE','CONTROL'])
    science=hashlib.sha256(c.canonical(identity)[:-1]).hexdigest()
    source_sha=hashlib.sha256(c.canonical(source)).hexdigest()
    c.write(folder/'prepared.json',dict(passed=True,newGames=0,modelForwards=0,
        scienceIdentity=identity,scienceSha256=science,sourceSha256=source_sha))
    rows=[];replays=[]
    for index in range(source['globalStart'],source['globalStart']+source['count']):
        seat=index%4;roles=dict(CANDIDATE=game(seat),CONTROL=game((seat+1)%4))
        diagnostics=dict(CANDIDATE=dict(controlledBidCounts={'21':9,'22':1} if concentrated else {'21':5,'22':5}),
            CONTROL=dict(controlledBidCounts={'21':10}))
        hashes={role:hashlib.sha256(f'explicit-invented:{index}:{role}'.encode()).hexdigest()
            if index in source['replayIndices'] else None for role in roles}
        styles=('QUALIFIED','UNDERCUT','OVERBID','MIXED')
        rows.append(dict(index=index,seed=source['seeds'][index],seat=seat,roles=roles,
            opponentStyle=styles[(index//4+seat)%4] if source['opponentStyle']=='BALANCED' else 'QUALIFIED',
            diagnostics=diagnostics,canonicalSha256=hashes,scienceSha256=science))
        if index in source['replayIndices']:
            replays.extend(dict(index=index,role=role,matched=True,terminal=roles[role],
                diagnostics=diagnostics[role],canonicalSha256=hashes[role]) for role in roles)
    (folder/'worlds.jsonl').write_bytes(b''.join(c.canonical(row) for row in reversed(rows)))
    names=['prepared.json','worlds.jsonl']
    if replays:
        (folder/'reproductions.jsonl').write_bytes(b''.join(c.canonical(row) for row in reversed(replays)))
        names.append('reproductions.jsonl')
    total=2*len(rows)+len(replays)
    c.write(folder/'finished.json',dict(complete=True,sourceSha256=source_sha,scienceSha256=science,
        worlds=len(rows),games=2*len(rows),reproductions=len(replays),scientificTerminalExecutions=total,
        attemptedGames=total,completedGames=total,newGames=total,newFits=0,qualificationClaimed=False,
        files={name:c.sha(folder/name) for name in names}))
    return rows,total


def fixture(root):
    """A test-only injected contract keeps production identities unmodified and has no executor."""
    banks={};start=1
    for bank in policy.BANKS:
        banks[bank['name']]=dict(bank,seeds=list(range(start,start+bank['worlds'])))
        start+=bank['worlds']
    plan=dict(kind='constructed-policy-search-fixture-1',study=92,banks=banks,caseConfigs=policy.CASES,
        chunkSize=256,maximumTerminalExecutions=2648,maximumScienceSeconds=16200,auditSeconds=600,
        minimumRemainingSecondsAtPreparation=10800,globalDeadlineEpoch=policy.GLOBAL_DEADLINE,
        preparedEpoch=policy.GLOBAL_DEADLINE-20000,workers=10,heapMiB=6144,
        fullReservedWorlds=1664,activeReservedWorlds=832,unusedReservedWorlds=832,scheduleSha256='f'*64,
        maximumRssBytes=12*1024**3,minimumFreeBytes=16*1024**3,maximumDataBytes=768*1024**2)
    protocol=root/'protocol.json';profile_path=root/'profile.json';output=root/'run';output.mkdir()
    runtime=root/'runtime/lib';runtime.mkdir(parents=True)
    subjects={}
    for key in ('candidateBundle','inferenceAsset','nomination','arena','nativeJar'):
        path=runtime/'training.jar' if key=='nativeJar' else root/(key+'.json')
        path.write_text('explicit tiny constructed '+key+'\n')
        subjects[key]=dict(path=str(path),sha256=c.sha(path))
    plan['nativeJarSha256']=subjects['nativeJar']['sha256'];c.write(protocol,plan)
    profile=dict(kind='mac-policy-search-host-1',study=92,root=str(root),classpath=str(runtime/'*'),
        **{key:plan[key] for key in ('workers','heapMiB','maximumRssBytes','minimumFreeBytes','maximumDataBytes')},
        **subjects,pins={value['path']:value['sha256'] for value in subjects.values()})
    c.write(profile_path,profile)
    def source_value(value,actual,bank,case,chunk,protocol_sha):
        data=value['banks'][bank];start=chunk*256
        return dict(kind='robust-token-chunk-1',protocolSha256=protocol_sha,
            **{key:subjects[key] for key in ('candidateBundle','inferenceAsset','nomination','arena')},
            selector=case['selector'],seeds=data['seeds'],opponentStyle=data['opponentStyle'],
            globalStart=start,count=min(256,data['worlds']-start),replayIndices=data['replayIndices'])
    contract=SimpleNamespace(PREFIX='constructed-policy-search',BANK_ORDER=policy.BANK_ORDER,
        validate=lambda value:value,native_source=source_value,native_chunk=lambda source,path,bank:native_audit.native_chunk(source,path),
        aggregate=policy.aggregate,select=policy.select,passed=policy.passed)
    clock=c.science_clock(plan,policy.GLOBAL_DEADLINE-18000,c.sha(protocol),contract.PREFIX)
    c.write(output/'clock.json',clock);c.write(output/'launch.json',dict(constructedOnly=True))
    (output/'protocol-copy.json').write_bytes(protocol.read_bytes())
    selected=None;total=0
    for name in policy.BANK_ORDER:
        cases=policy.CASES if name=='development' else [selected];reports={}
        for case in cases:
            rows=[]
            for chunk in range((banks[name]['worlds']+255)//256):
                folder=output/'chunks'/name/case['id']/f'chunk-{chunk}';folder.mkdir(parents=True)
                source=source_value(plan,profile,name,case,chunk,c.sha(protocol));c.write(folder/'source.json',source)
                values,count=producer(source,folder/'native',concentrated=case['id']=='s00')
                rows.extend(values);total+=count
                c.write(folder/'receipt.json',dict(complete=True,sourceSha256=c.sha(folder/'source.json'),
                    finishedSha256=c.sha(folder/'native/finished.json'),
                    scienceSha256=c.read(folder/'native/finished.json')['scienceSha256'],terminalExecutions=count))
            reports[case['id']]=policy.aggregate(rows,banks[name])
        c.write(output/'reports'/f'{name}.json',dict(cases=reports))
        if name=='development':
            selected=policy.select(reports);c.write(output/'selection.json',dict(case=selected))
    terminal=dict(complete=True,status='completed',qualified=True,protocolSha256=c.sha(protocol),
        knownCompletedTerminalExecutions=total,reservedTerminalUpperBound=total)
    return protocol,profile_path,output,terminal,contract,clock


class SearchEvidenceTest(unittest.TestCase):
    def test_complete2648_record_search_reaggregates_selection_and_all_three_gates(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixture(Path(temporary))
            result=search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)
            self.assertTrue(result['qualified']);self.assertEqual(result['terminalExecutions'],2648)
            self.assertEqual(result['selectedCase']['id'],'s01')
            self.assertEqual(result['newGames'],0)
            self.assertEqual(result['protectedReservation'],dict(fullReservedWorlds=1664,
                activeReservedWorlds=832,unusedReservedWorlds=832,fullScheduleSha256='f'*64))

    def test_extra_unreported_attempted_bank_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixture(Path(temporary))
            (output/'chunks/unreported').mkdir()
            with self.assertRaisesRegex(ValueError,'unreported or ungated'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)

    def test_clock_renewal_is_rejected_before_original_effects_are_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixture(Path(temporary))
            clock['deadlineEpoch']+=1;(output/'clock.json').write_bytes(c.canonical(clock))
            with self.assertRaisesRegex(ValueError,'unchanged bounded audit'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)

    def test_expired_statistical_audit_cannot_restart_later(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixture(Path(temporary))
            with self.assertRaisesRegex(ValueError,'unchanged bounded audit'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['auditDeadlineEpoch']+1)

    def test_control_coherence_catches_a_changed_world_even_when_local_hashes_and_statistics_match(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,profile,output,terminal,contract,clock=fixture(Path(temporary))
            folder=output/'chunks/development/s00/chunk-0';path=folder/'native/worlds.jsonl'
            rows=[json.loads(line) for line in path.read_text().splitlines()]
            next(row for row in rows if row['index']==1)['roles']['CONTROL']=game(1)
            path.write_bytes(b''.join(c.canonical(row) for row in rows))
            finished=c.read(folder/'native/finished.json');finished['files']['worlds.jsonl']=c.sha(path)
            (folder/'native/finished.json').write_bytes(c.canonical(finished))
            receipt=c.read(folder/'receipt.json');receipt['finishedSha256']=c.sha(folder/'native/finished.json')
            (folder/'receipt.json').write_bytes(c.canonical(receipt))
            report_path=output/'reports/development.json';record=c.read(report_path)
            record['cases']['s00']=policy.aggregate(rows,c.read(protocol)['banks']['development'])
            report_path.write_bytes(c.canonical(record))
            with self.assertRaisesRegex(ValueError,'common CONTROL worlds'):
                search.audit(protocol,profile,output,terminal,contract,now=lambda:clock['deadlineEpoch']+1)


class PrerequisiteTest(unittest.TestCase):
    def records(self,root):
        terminal=root/'parent-terminal.json';closure=root/'parent-closure.json';data=root/'input.txt'
        data.write_text('explicit closed parent fixture\n')
        c.write(terminal,dict(kind=policy.PARENT_TERMINAL_KIND,status='failed',complete=False,
            qualified=False,protocolSha256='a'*64))
        c.write(closure,dict(kind=policy.PARENT_CLOSURE_KIND,passed=True,allOwnedProcessesExited=True,
            protocolSha256='a'*64,originalClocksRenewed=False,auditReruns=0,ownedPids=[111111],
            ownedGroups=[222222],artifactPins={str(data):c.sha(data)}))
        return dict(parentTerminal=dict(path=str(terminal),sha256=c.sha(terminal)),
            parentClosure=dict(path=str(closure),sha256=c.sha(closure)))

    def test_failed_closed_parent_can_open_independent_search_without_claiming_qualification(self):
        with tempfile.TemporaryDirectory() as temporary:
            search.prerequisite(self.records(Path(temporary)),policy,processes=lambda:[])

    def test_live_parent_process_cannot_overlap_next_study(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=self.records(Path(temporary))
            with self.assertRaisesRegex(ValueError,'preceding study owners'):
                search.prerequisite(value,policy,processes=lambda:[('111111','999999','R')])

    def test_changed_closed_parent_bytes_prevent_preparation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=self.records(root);(root/'input.txt').write_text('changed\n')
            with self.assertRaisesRegex(ValueError,'closed-study artifact changed'):
                search.prerequisite(value,policy,processes=lambda:[])

    def test_another_closed_trial_cannot_replace_the_declared94_prerequisite(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=self.records(Path(temporary));value['parentTrialProtocolSha256']=policy.FIXED['parentTrialProtocolSha256']
            with self.assertRaisesRegex(ValueError,'exact prospectively declared prior trial'):
                search.prerequisite(value,policy,processes=lambda:[])

    def test_source_closure_recurses_local_imports_without_copying_unrelated_pipeline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'a.py').write_text('import b\nimport json\n')
            (root/'b.py').write_text('from c import item\n')
            (root/'c.py').write_text('item=1\n');(root/'unused.py').write_text('raise RuntimeError\n')
            self.assertEqual([path.name for path in prepare.source_closure(root,['a.py'])],['a.py','b.py','c.py'])

    def test_frozen_jvm_performance_options_reject_unrelated_injection(self):
        self.assertEqual(resources.java_options(dict(jvmOptions=['-XX:ActiveProcessorCount=10'])),
            ['-XX:ActiveProcessorCount=10'])
        with self.assertRaisesRegex(ValueError,'performance options'):
            resources.java_options(dict(jvmOptions=['-javaagent:unreviewed.jar']))


if __name__=='__main__':unittest.main()
