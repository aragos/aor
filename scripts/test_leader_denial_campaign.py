"""Constructed protocols and terminal records only; no native process, inference or game."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest

import leader_denial_contract as policy
import leader_denial_prepare as prepare
import robust_token_audit as audit
import robust_token_campaign as c


FIXTURES = Path(__file__).parent/'testdata'


def freeze():
    return dict(kind='leader-denial-comparator-freeze-1', passed=True, complete=True, qualified=True,
        origin='87', bundleSha256='2'*64, selector=None, qualificationSha256='3'*64,
        methodSha256=policy.METHOD_SHA)


def plan(mode='SMALL'):
    remaining = 10000 if mode == 'FULL' else 6000
    return prepare.protocol(FIXTURES/'leader-denial-method.json',
        FIXTURES/'leader-denial-reserved-schedule.json', FIXTURES/'leader-denial-seed-review.json',
        freeze(), '4'*64, '5'*64, now=lambda:policy.GLOBAL_DEADLINE-remaining)


def profile(value, host='DESKTOP'):
    subject = lambda name, digest:dict(path='/constructed/'+name, sha256=digest)
    return dict(kind=policy.LeaderDenialExperiment.profile_kind, host=host,
        workers=policy.LeaderDenialExperiment.workers[host],
        inferenceAsset=subject('inference.json', value['inferenceAssetSha256']),
        nomination=subject('nomination.json', value['nominationSha256']),
        arena=subject('arena.json', value['arenaSha256']),
        controlBundle=subject('bundle.json', value['comparator']['bundleSha256']),
        controlSelector=value['comparator']['selector'])


def game(winner):
    scores = []; wins = []; ranks = []; rewards = []
    for seat in range(4):
        rank = (seat-winner)%4; value = (3-rank)/3; win = float(seat == winner)
        cash = 100-10*rank
        scores.append(dict(player=f'p{seat+1}', advancePoints=0, cash=cash, misery=0,
            total=cash, winner=bool(win)))
        wins.append(win); ranks.append(value); rewards.append(.8*win+.2*value)
    return dict(scores=scores, wins=wins, ranks=ranks, rewards=rewards, decisions=1, elapsedMillis=0)


def native(source, directory, positive=True, reverse=True):
    """Invent an explicit producer-shaped chunk, including genuine-shaped matching replay rows."""
    directory.mkdir(parents=True)
    identity = dict(kind='robust-token-science-1', protocolSha256=source['protocolSha256'],
        seeds=source['seeds'], replayIndices=source['replayIndices'],
        opponentStyle=source['opponentStyle'], selector=source['selector'],
        inferenceAssetSha256=source['inferenceAsset']['sha256'],
        nominationSha256=source['nomination']['sha256'],
        candidateBundleSha256=source['candidateBundle']['sha256'],
        controlAndRivalsBundleSha256=source['controlBundle']['sha256'],
        expansionDenial=source['expansionDenial'], roles=['CANDIDATE', 'CONTROL'])
    science = hashlib.sha256(c.canonical(identity)[:-1]).hexdigest()
    source_sha = hashlib.sha256(c.canonical(source)).hexdigest()
    c.write(directory/'prepared.json', dict(passed=True, newGames=0, modelForwards=0,
        scienceIdentity=identity, scienceSha256=science, sourceSha256=source_sha))
    rows = []; replays = []
    for index in range(source['globalStart'], source['globalStart']+source['count']):
        seat = index%4; roles = dict(CANDIDATE=game(seat if positive else (seat+1)%4),
            CONTROL=game((seat+1)%4))
        diagnostics = {role:dict(constructedOnly=True, controlledBidCounts={'15':1}) for role in roles}
        canonical = {role:hashlib.sha256(f'invented:{index}:{role}'.encode()).hexdigest()
            if index in source['replayIndices'] else None for role in roles}
        style = ('QUALIFIED', 'UNDERCUT', 'OVERBID', 'MIXED')[(index//4+seat)%4]
        rows.append(dict(index=index, seed=source['seeds'][index], seat=seat,
            scienceSha256=science, opponentStyle=style if source['opponentStyle']=='BALANCED' else 'QUALIFIED',
            roles=roles, diagnostics=diagnostics, canonicalSha256=canonical))
        if index in source['replayIndices']:
            replays.extend(dict(index=index, role=role, matched=True, terminal=roles[role],
                diagnostics=diagnostics[role], canonicalSha256=canonical[role]) for role in roles)
    if reverse: rows.reverse()
    (directory/'worlds.jsonl').write_bytes(b''.join(c.canonical(row) for row in rows))
    names = ['prepared.json', 'worlds.jsonl']
    if replays:
        (directory/'reproductions.jsonl').write_bytes(b''.join(c.canonical(row) for row in reversed(replays)))
        names.append('reproductions.jsonl')
    total = 2*len(rows)+len(replays)
    c.write(directory/'finished.json', dict(complete=True, sourceSha256=source_sha, scienceSha256=science,
        worlds=len(rows), games=2*len(rows), reproductions=len(replays),
        scientificTerminalExecutions=total, attemptedGames=total, completedGames=total,
        newGames=total, newFits=0, qualificationClaimed=False,
        files={name:c.sha(directory/name) for name in names}))
    return rows, total


def campaign_fixture(root, mode='SMALL', failed_bank=None):
    value = plan(mode); protocol = root/'protocol.json'; c.write(protocol,value)
    output = root/'run'; output.mkdir(); sha = c.sha(protocol); contract=policy.LeaderDenialExperiment()
    c.write(output/'clock.json', c.science_clock(value, policy.GLOBAL_DEADLINE-1000, sha, contract.prefix))
    c.write(output/'launch.json',dict(constructedOnly=True))
    totals = 0; reports = {}; selected = None
    banks = ['development'] if failed_bank=='development' else ['development','mixed']
    if failed_bank not in ('development','mixed'):banks.append('homogeneous')
    for name in banks:
        reports[name] = {}
        cases = value['caseConfigs'] if name == 'development' else [selected]
        for case in cases:
            rows = []
            for chunk in range(len(value['banks'][name]['seeds'])//64):
                host = contract.task_owner(value,name,case,chunk)
                source = contract.native_source(value,profile(value,host),name,case,chunk,sha)
                folder = output/'chunks'/name/case['id']/f'chunk-{chunk}'
                folder.mkdir(parents=True)
                c.write(folder/'request.json',dict(source=source))
                c.write(folder/'receipt.json',dict(host=host))
                records, count = native(source,folder/'native',positive=name != failed_bank)
                rows.extend(records);totals+=count
            reports[name][case['id']] = c.aggregate(rows,value['banks'][name])
        c.write(output/'reports'/f'{name}.json',dict(cases=reports[name]))
        if name=='development':
            selected=c.chosen_case(reports[name],value['caseConfigs'])
            c.write(output/'selection.json',dict(case=selected))
    terminal=dict(status='completed',complete=True,qualified=failed_bank is None,
        protocolSha256=sha,terminalExecutions=totals)
    return protocol,output,terminal


def verify_campaign(protocol, output, terminal):
    return audit.verify(protocol,output,terminal,contract=policy.LeaderDenialExperiment(),
        now=lambda:policy.GLOBAL_DEADLINE)


class FixedMethodTest(unittest.TestCase):
    def test_full_and_small_exact_counts_and_held_suffixes(self):
        for name,total,unused in [('FULL',16464,0),('SMALL',8272,2304)]:
            value=policy.validate(plan(name));self.assertEqual(value['maximumTerminalExecutions'],total)
            self.assertEqual(value['unusedReservedWorlds'],unused)

    def test_size_is_chosen_only_by_original_remaining_time(self):
        self.assertEqual(policy.data_mode(policy.GLOBAL_DEADLINE-9000),'FULL')
        self.assertEqual(policy.data_mode(policy.GLOBAL_DEADLINE-8999),'SMALL')
        self.assertEqual(policy.data_mode(policy.GLOBAL_DEADLINE-4500),'SMALL')
        self.assertIsNone(policy.data_mode(policy.GLOBAL_DEADLINE-4499))

    def test_protocol_defers_before_any_preparation_if_original_time_is_insufficient(self):
        with self.assertRaisesRegex(ValueError,'defer without preparing'):
            prepare.protocol(FIXTURES/'leader-denial-method.json',
                FIXTURES/'leader-denial-reserved-schedule.json', FIXTURES/'leader-denial-seed-review.json',
                freeze(), '4'*64, '5'*64, now=lambda:policy.GLOBAL_DEADLINE-4499)

    def test_frozen_small_cannot_be_topped_up_to_full(self):
        value=plan();value['banks']['mixed']['seeds']=value['reservedSchedule']['banks']['mixed']['seeds']
        with self.assertRaisesRegex(ValueError,'fixed original prefix'):policy.validate(value)

    def test_unused_suffix_cannot_replace_an_active_world(self):
        value=plan();value['banks']['development']['seeds'][-1]=value['reservedSchedule']['banks']['development']['seeds'][256]
        with self.assertRaisesRegex(ValueError,'fixed original prefix'):policy.validate(value)

    def test_reserved_suffix_itself_remains_protected(self):
        value=plan();value['reservedSchedule']['banks']['mixed']['seeds'][-1]+=1
        with self.assertRaisesRegex(ValueError,'byte-canonically unchanged'):policy.validate(value)

    def test_selection_order_cannot_change(self):
        value=plan();value['caseConfigs'].reverse()
        with self.assertRaisesRegex(ValueError,'prospectively ordered'):policy.validate(value)

    def test_terminal_restored_worker_allocation_is_exact(self):
        value=plan();value['resources']['workers']['MAC']=8
        with self.assertRaisesRegex(ValueError,'10 Mac workers'):policy.validate(value)

    def test_each_case_uses_the_same_control_architecture(self):
        value=plan('FULL');contract=policy.LeaderDenialExperiment()
        for case in value['caseConfigs']:
            owners=[contract.task_owner(value,'development',case,chunk) for chunk in range(8)]
            self.assertEqual(owners,['DESKTOP']*3+['MAC']+['DESKTOP']*3+['MAC'])
        self.assertEqual(sum(contract.task_owner(value,'mixed',value['caseConfigs'][0],chunk)=='MAC'
            for chunk in range(32)),8)

    def test_execution_accounting_matches_every_prescribed_reproduction(self):
        for mode in policy.DATA_MODES:
            value=plan(mode);total=0
            for name,bank in value['banks'].items():
                total+=(8 if name=='development' else 1)*(2*len(bank['seeds'])+2*len(bank['replayIndices']))
            self.assertEqual(total,value['maximumTerminalExecutions'])

    def test_qualified_comparator_required_even_for_passing_resource_closure(self):
        value=plan();record=freeze();record['qualified']=False;record['origin']='88b-selector'
        value['comparator']['origin']='88b-selector'
        with self.assertRaisesRegex(ValueError,'unqualified development'):policy.validate_freeze(record,value)

    def test_missing_qualification_flag_cannot_admit_comparator(self):
        record=freeze();record.pop('qualified')
        with self.assertRaisesRegex(ValueError,'unqualified development'):policy.validate_freeze(record,plan())

    def test_exact_models_and_token_sampler_are_forwarded_without_injection(self):
        value=plan();case=value['caseConfigs'][0];p=profile(value)
        source=policy.LeaderDenialExperiment().native_source(value,p,'development',case,0,'a'*64)
        self.assertEqual(source['candidateBundle'],source['controlBundle'])
        self.assertIsNone(source['selector']);self.assertIsNone(source['controlSelector'])
        self.assertEqual(source['expansionDenial'],case['expansionDenial'])
        policy.LeaderDenialExperiment().check_source(value,source,'development',case,0,'a'*64)

    def test_mismatched_token_policy_is_rejected_before_native_work(self):
        value=plan();case=value['caseConfigs'][0]
        source=policy.LeaderDenialExperiment().native_source(value,profile(value),'development',case,0,'a'*64)
        source['controlSelector']=dict(kind='near-optimal-token-softmax-1',temperature=.7,maxScoreGap=1.25,neighborRadius=2)
        with self.assertRaisesRegex(ValueError,'matched-model denial'):
            policy.LeaderDenialExperiment().check_source(value,source,'development',case,0,'a'*64)

    def test_missing_root_comparator_freeze_refuses_actual_host_admission(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError,'original frozen comparator bytes'):
                policy.LeaderDenialExperiment().extra_profile(Path(temporary),plan())

    def test_worker_admits_only_the_exact_source_and_assigned_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=plan();c.write(root/'protocol.json',value)
            actual=profile(value,'MAC');actual['root']=str(root)
            source=policy.LeaderDenialExperiment().native_source(value,actual,'development',
                value['caseConfigs'][0],3,c.sha(root/'protocol.json'))
            request=dict(taskId='development/d00/chunk-3',source=source)
            policy.worker_source(actual,request)
            wrong_host={**actual,'host':'DESKTOP','workers':16}
            with self.assertRaisesRegex(ValueError,'assigned host'):
                policy.worker_source(wrong_host,request)

    def test_worker_rejects_an_alias_of_an_original_task(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=plan();c.write(root/'protocol.json',value)
            actual=profile(value);actual['root']=str(root)
            with self.assertRaisesRegex(ValueError,'canonical stage'):
                policy.worker_source(actual,dict(taskId='development/d00/chunk-00',source={}))

    def test_clock_cannot_run_past_absolute_user_deadline(self):
        value=plan();clock=c.science_clock(value,policy.GLOBAL_DEADLINE-1000,'a'*64,'leader-denial')
        self.assertEqual(clock['deadlineEpoch'],policy.GLOBAL_DEADLINE)
        self.assertEqual(clock['auditDeadlineEpoch'],policy.GLOBAL_DEADLINE+600)

    def test_expired_global_window_cannot_create_a_new_clock(self):
        with self.assertRaisesRegex(ValueError,'window has elapsed'):
            c.science_clock(plan(),policy.GLOBAL_DEADLINE,'a'*64,'leader-denial')

    def test_java_options_are_removed_without_mutating_parent_environment(self):
        original=os.environ.get('JAVA_TOOL_OPTIONS')
        os.environ['JAVA_TOOL_OPTIONS']='-XX:ActiveProcessorCount=2'
        try:
            result=policy.LeaderDenialExperiment().native_environment()
            self.assertNotIn('JAVA_TOOL_OPTIONS',result)
            self.assertEqual(os.environ['JAVA_TOOL_OPTIONS'],'-XX:ActiveProcessorCount=2')
        finally:
            if original is None:os.environ.pop('JAVA_TOOL_OPTIONS')
            else:os.environ['JAVA_TOOL_OPTIONS']=original


class NativeEvidenceTest(unittest.TestCase):
    def test_unordered_native_rows_and_replays_are_admitted_by_membership(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=plan();source=policy.LeaderDenialExperiment().native_source(value,profile(value),'development',value['caseConfigs'][0],0,'a'*64)
            root=Path(temporary)/'native';native(source,root)
            rows,counts,_=audit.native_chunk(source,root)
            self.assertEqual(rows[0]['index'],63);self.assertEqual(counts['terminalExecutions'],130)

    def test_changed_original_reproduction_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            value=plan();source=policy.LeaderDenialExperiment().native_source(value,profile(value),'development',value['caseConfigs'][0],0,'a'*64)
            root=Path(temporary)/'native';native(source,root)
            path=root/'reproductions.jsonl';rows=[json.loads(line) for line in path.read_text().splitlines()]
            rows[0]['canonicalSha256']='f'*64;path.write_bytes(b''.join(c.canonical(row) for row in rows))
            completed=c.read(root/'finished.json');completed['files']['reproductions.jsonl']=c.sha(path)
            (root/'finished.json').write_bytes(c.canonical(completed))
            with self.assertRaisesRegex(ValueError,'Original reproductions'):audit.native_chunk(source,root)

    def test_complete_small_run_reaggregates_all8272_native_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary))
            result=verify_campaign(protocol,output,terminal)
            self.assertTrue(result['qualified']);self.assertEqual(result['counts']['terminalExecutions'],8272)

    def test_complete_full_run_reaggregates_all16464_native_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary),mode='FULL')
            result=verify_campaign(protocol,output,terminal)
            self.assertTrue(result['qualified']);self.assertEqual(result['counts']['terminalExecutions'],16464)

    def test_original_pretty_source_bytes_are_admitted_without_rewriting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=plan()
            source=policy.LeaderDenialExperiment().native_source(value,profile(value),'development',
                value['caseConfigs'][0],0,'a'*64)
            native(source,root/'native');path=root/'source.json'
            path.write_text(json.dumps(source,indent=2)+'\n')
            for name in ('prepared.json','finished.json'):
                record=c.read(root/'native'/name);record['sourceSha256']=c.sha(path)
                if name=='finished.json':record['files']['prepared.json']=c.sha(root/'native/prepared.json')
                (root/'native'/name).write_bytes(c.canonical(record))
            rows,_,_=audit.native_chunk(source,root/'native',source_path=path)
            self.assertEqual(len(rows),64)
            with self.assertRaisesRegex(ValueError,'exact source'):
                audit.native_chunk(source,root/'native')

    def test_duplicate_original_index_cannot_pass_native_admission(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);value=plan()
            source=policy.LeaderDenialExperiment().native_source(value,profile(value),'development',
                value['caseConfigs'][0],0,'a'*64)
            native(source,root/'native');path=root/'native/worlds.jsonl'
            rows=[json.loads(line) for line in path.read_text().splitlines()];rows[-1]=rows[0]
            path.write_bytes(b''.join(c.canonical(row) for row in rows))
            finished=c.read(root/'native/finished.json');finished['files']['worlds.jsonl']=c.sha(path)
            (root/'native/finished.json').write_bytes(c.canonical(finished))
            with self.assertRaisesRegex(ValueError,'original global index set'):
                audit.native_chunk(source,root/'native')

    def test_unreported_attempted_bank_cannot_disappear_from_execution_accounting(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary),failed_bank='development')
            (output/'chunks/mixed').mkdir()
            with self.assertRaisesRegex(ValueError,'original attempted bank'):
                verify_campaign(protocol,output,terminal)

    def test_nonpositive_development_never_opens_confirmation(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary),failed_bank='development')
            result=verify_campaign(protocol,output,terminal)
            self.assertFalse(result['qualified']);self.assertEqual(result['counts']['terminalExecutions'],4160)
            self.assertFalse((output/'chunks/mixed').exists())

    def test_failed_mixed_gate_keeps_homogeneous_unopened(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary),failed_bank='mixed')
            result=verify_campaign(protocol,output,terminal)
            self.assertFalse(result['qualified']);self.assertFalse((output/'chunks/homogeneous').exists())

    def test_expired_original_audit_cannot_be_rerun(self):
        with tempfile.TemporaryDirectory() as temporary:
            protocol,output,terminal=campaign_fixture(Path(temporary),failed_bank='development')
            deadline=c.read(output/'clock.json')['auditDeadlineEpoch']
            with self.assertRaisesRegex(ValueError,'cannot restart'):
                audit.verify(protocol,output,terminal,contract=policy.LeaderDenialExperiment(),now=lambda:deadline+1)


if __name__=='__main__':unittest.main()
