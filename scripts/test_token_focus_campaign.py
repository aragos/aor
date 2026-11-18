"""Invented complete1560-game evidence and lifecycle boundaries; no policy forwards or games."""
import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import mac_policy_prepare as prepare
import mac_policy_search as search
import robust_token_audit as native
import robust_token_campaign as c
import test_mac_policy_search as fixtures
import token_focus_campaign as learner
import token_focus_contract as policy


def subject(path):return dict(path=str(path),sha256=c.sha(path))


def bundle(owner,token):
    owner.mkdir(parents=True)
    heads={}
    for head in ('CARD','PURCHASE','EXPANSION','TOKEN'):
        path=owner/(head+'.json')
        if head=='TOKEN':
            c.write(path,dict(encoderVersion='invented',stateFeatureNames=['s'],actionFeatureNames=['a'],
                valueWeights=[7],valueBias=0,prior=[.3],stateWeights=[token],stateBias=[token],
                actionWeights=[token],actionBias=[token],actorWeights=[token]))
        else:path.write_text(head)
        heads[head]=dict(path=path.name,sha256=c.sha(path))
    path=owner/'bundle.json';c.write(path,dict(heads=heads));return subject(path)


def fixture(root,*,study=96,separate_control=False):
    profile_path=root/'profile.json';protocol=root/'protocol.json';output=root/'run';output.mkdir()
    origin=bundle(root/'origin','original TOKEN')
    control=bundle(root/'control','fixed94 TOKEN') if separate_control else origin
    assets={name:root/(name+'.json') for name in ('inferenceAsset','nomination','arena')}
    for path in assets.values():path.write_text('{}')
    jar=root/'runtime/lib/training.jar';jar.parent.mkdir(parents=True);jar.write_text('invented jar')
    plan=dict(kind='constructed-token-research-1',study=study,maximumScienceSeconds=7200,auditSeconds=600,
        globalDeadlineEpoch=1795020399.,minimumRemainingSecondsAtPreparation=7800,preparedEpoch=1795011399.,
        workers=10,heapMiB=6144,maximumRssBytes=12*1024**3,minimumFreeBytes=16*1024**3,
        maximumDataBytes=1024**3,nativeJarSha256=c.sha(jar),baseSelector=policy.mixture.CONTROL_SELECTOR,
        numericRecipe=dict(constructed=True),reservedSchedule=dict(directionSeed=1234),
        banks=dict(training=dict(nativeOffset=1,count=16,seeds=list(range(16))),
            holdout=dict(nativeOffset=2,count=128,seeds=list(range(100,228)))))
    schedule=root/'schedule.json';c.write(schedule,dict(kind='invented',banks=plan['banks']))
    plan['seedSchedule']=subject(schedule);c.write(protocol,plan)
    if study!=96:
        plan['numericStageSpec']=dict(study=study,constructed=True);protocol.write_bytes(c.canonical(plan))
    profile=dict(kind='mac-policy-search-host-1',study=study,root=str(root),classpath=str(jar.parent/'*'),
        **{key:plan[key] for key in ('workers','heapMiB','maximumRssBytes','minimumFreeBytes','maximumDataBytes')},
        candidateBundle=origin,nativeJar=subject(jar),**{name:subject(path) for name,path in assets.items()})
    if separate_control:profile['controlBundle']=control
    profile['pins']={item['path']:item['sha256'] for key,item in profile.items()
        if key in ('candidateBundle','nativeJar',*assets)};c.write(profile_path,profile)
    if separate_control:
        profile['pins'][control['path']]=control['sha256'];profile_path.write_bytes(c.canonical(profile))
    prefix=policy.PREFIX if study==96 else 'token-focus-second'
    clock=c.science_clock(plan,plan['globalDeadlineEpoch']-8000,c.sha(protocol),prefix)
    c.write(output/'clock.json',clock);c.write(output/'launch.json',dict(constructed=True))
    (output/'protocol-copy.json').write_bytes(protocol.read_bytes())
    def numeric(mode,body,files,**fields):
        folder=output/'numeric'/mode;artifact=folder/'artifacts';artifact.mkdir(parents=True)
        c.write(folder/'request.json',dict(kind='token-focus-step-numeric-'+mode+'-1',**body))
        for name,value in files.items():
            path=artifact/name;path.parent.mkdir(parents=True,exist_ok=True);c.write(path,value)
        raw_files={str(path.relative_to(artifact)):c.sha(path) for path in artifact.rglob('*') if path.is_file()}
        c.write(artifact/'receipt.json',dict(kind=learner.KINDS[mode],complete=True,
            recipe=plan['numericRecipe'],scopeSha256='a'*64,
            inputPins={str(folder/'request.json'):c.sha(folder/'request.json')},files=raw_files,
            **(dict(stageSpec=plan['numericStageSpec']) if study!=96 else {}),**fields))
        return subject(artifact/'receipt.json')
    vectors=[[0.]*192+[1.]*64 for _ in range(16)]
    prep=numeric('prepare',dict(protocol=subject(protocol),seedSchedule=subject(schedule),
        originBundle=origin,inferenceAsset=profile['inferenceAsset']),
        {'directions.json':dict(vectors=vectors),'center.json':dict(coordinates=[0.]*256)},
        directions={},center={})
    artifact=Path(prep['path']).parent;record=c.read(prep['path'])
    record.update(directions=dict(path='directions.json',sha256=c.sha(artifact/'directions.json')),
        center=dict(path='center.json',sha256=c.sha(artifact/'center.json')))
    c.write(artifact/'direction-draw.json',dict(seed=1234,drawShape=[16,64],expandedShape=[16,256],zeroPrefix=192,
        renormalized=False,directionsSha256=c.sha(artifact/'directions.json')))
    record['directionDraw']=dict(path='direction-draw.json',sha256=c.sha(artifact/'direction-draw.json'))
    record['files']['direction-draw.json']=c.sha(artifact/'direction-draw.json')
    Path(prep['path']).write_bytes(c.canonical(record));prep=subject(prep['path'])
    pop_root=output/'numeric/population/artifacts';pop_root.mkdir(parents=True)
    members=[]
    for index in range(32):
        item=bundle(pop_root/f'member-{index:02d}',str(index))
        members.append(dict(index=index,direction=index//2,sign=1 if index%2==0 else -1,
            bundle=dict(path=str(Path(item['path']).relative_to(pop_root)),sha256=item['sha256'])))
    population=dict(members=members,worldSeeds=plan['banks']['training']['seeds'],scopeSha256='a'*64,
        generationBankSha256=hashlib.sha256(c.canonical(plan['banks']['training'])[:-1]).hexdigest())
    # The generic receipt writer permits an existing constructed artifact directory here only.
    pop_request=output/'numeric/population/request.json'
    c.write(pop_request,dict(kind='token-focus-step-numeric-population-1',preparation=prep,
        center=subject(artifact/'center.json')))
    c.write(pop_root/'population.json',population)
    pop_files={str(path.relative_to(pop_root)):c.sha(path) for path in pop_root.rglob('*') if path.is_file()}
    c.write(pop_root/'receipt.json',dict(kind=learner.KINDS['population'],complete=True,
        recipe=plan['numericRecipe'],scopeSha256='a'*64,
        inputPins={str(pop_request):c.sha(pop_request)},files=pop_files,preparation=prep,
        population=dict(path='population.json',sha256=c.sha(pop_root/'population.json')),
        **(dict(stageSpec=plan['numericStageSpec']) if study!=96 else {})))
    pop=subject(pop_root/'receipt.json')
    def source_value(value,actual,bank,case,chunk,protocol_sha):
        return dict(kind='robust-token-chunk-1',protocolSha256=protocol_sha,
            **{name:actual[name] for name in assets},candidateBundle=case.get('bundle',origin),
            controlBundle=control,selector=plan['baseSelector'],controlSelector=plan['baseSelector'],
            seeds=plan['banks'][bank]['seeds'],globalStart=0,count=plan['banks'][bank]['count'],opponentStyle='BALANCED',
            replayIndices=([0,15] if case['id'] in ('PLUS_00','MINUS_00') else []) if bank=='training' else [0,42,85,127])
    def cohort(bank,cases):
        cohorts=[]
        for case in cases:
            folder=output/'chunks'/bank/case['id']/'chunk-0';folder.mkdir(parents=True)
            source=source_value(plan,profile,bank,case,0,c.sha(protocol));c.write(folder/'source.json',source)
            rows,count=fixtures.producer(source,folder/'native',candidate_wins=case['id'] not in ('MINUS_00','START'))
            c.write(folder/'receipt.json',dict(complete=True,sourceSha256=c.sha(folder/'source.json'),
                finishedSha256=c.sha(folder/'native/finished.json'),terminalExecutions=count))
            cohorts.append(rows)
        return cohorts
    _,cases=learner.members(output)
    trained=cohort('training',cases);outcome=learner.outcomes(population,cases,trained)
    outcome['populationSha256']=c.sha(pop_root/'population.json');c.write(output/'training-outcomes.json',outcome)
    differences,gradient,coordinates=learner.independent_step(dict(vectors=vectors),outcome)
    update=numeric('update',dict(preparation=prep,population=pop,outcomes=subject(output/'training-outcomes.json')),
        {'center.json':dict(coordinates=coordinates)},preparation=prep,population=pop,
        outcomes=subject(output/'training-outcomes.json'),antitheticWinDifferences=differences,step=dict(gradient=gradient),center={})
    record=c.read(update['path']);record['center']=dict(path='center.json',sha256=c.sha(Path(update['path']).parent/'center.json'))
    Path(update['path']).write_bytes(c.canonical(record));update=subject(update['path'])
    final_root=output/'numeric/final/artifacts';final_bundle=bundle(final_root/'model','final TOKEN')
    final_request=final_root.parent/'request.json';c.write(final_request,dict(kind='token-focus-step-numeric-final-1',preparation=prep,update=update))
    c.write(final_root/'receipt.json',dict(kind=learner.KINDS['final'],complete=True,
        recipe=plan['numericRecipe'],scopeSha256='a'*64,
        inputPins={str(final_request):c.sha(final_request)},
        files={str(path.relative_to(final_root)):c.sha(path) for path in final_root.rglob('*') if path.is_file()},
        preparation=prep,update=update,center=subject(Path(update['path']).parent/'center.json'),
        bundle=dict(path='model/bundle.json',sha256=final_bundle['sha256']),
        **(dict(stageSpec=plan['numericStageSpec']) if study!=96 else {})))
    hold=cohort('holdout',[dict(id='START',bundle=origin),dict(id='FINAL',bundle=final_bundle)])
    report=learner.holdout(*hold);c.write(output/'holdout.json',report)
    terminal=dict(complete=True,status='completed',qualified=False,researchSignal=True,
        candidateBundle=final_bundle,protocolSha256=c.sha(protocol),knownCompletedTerminalExecutions=1560,reservedTerminalUpperBound=1560)
    injected=SimpleNamespace(PREFIX=prefix,validate=lambda value:value,native_source=source_value,
        native_chunk=lambda source,path,bank,**kw:native.native_chunk(source,path,**kw))
    return protocol,profile_path,output,terminal,injected,clock


class ContractTests(unittest.TestCase):
    def test_exact_root_method_and_counts(self):
        method=policy.read_method(Path(__file__).parent/'testdata/token-focus-step-method.json')
        self.assertEqual(method['maximumTerminalExecutions'],32*16*2+2*2*2+2*(128+4)*2)

    def test_preparation_defers_below_original7800_seconds(self):
        with tempfile.TemporaryDirectory() as temporary:
            method=Path(__file__).parent/'testdata/token-focus-step-method.json'
            numeric=Path(temporary)/'numeric.json';c.write(numeric,dict(numericRecipe=dict(constructed=True)))
            value=dict(c.read(method),kind=policy.KIND,prospectiveMethod=subject(method),
                numericSourceReadiness=subject(numeric),nativeSourceReadiness=dict(sha256=policy.NATIVE_READINESS_SHA),
                preparedEpoch=1795020399.-7799,numericRecipe=dict(constructed=True))
            with patch.object(policy,'NUMERIC_READY_SHA',c.sha(numeric)):
                with self.assertRaisesRegex(ValueError,'fewer than7800 original seconds'):
                    policy.validate(value)

    def test_all_original_training_holdout_and_numeric_streams_stay_reserved(self):
        schedule=Path(__file__).parent/'testdata/token-focus-step-schedule.json'
        reserved=c.read(schedule);value=dict(reservedSchedule=reserved,scheduleSha256=c.sha(schedule),banks=copy.deepcopy(reserved['banks']))
        policy.validate_worlds(value)
        value['banks']['holdout']['seeds']=value['banks']['holdout']['seeds'][:-1]
        with self.assertRaisesRegex(ValueError,'world must remain complete and unchanged'):
            policy.validate_worlds(value)

    def test_active64_normalization_differs_from_full256(self):
        matrix=dict(members=[dict(winShares=[1.] *16) if index==0 else dict(winShares=[0.] *16) for index in range(32)])
        _,gradient,coordinates=learner.independent_step(dict(vectors=[[0.]*192+[1.]*64]*16),matrix)
        self.assertEqual(coordinates,[0.]*192+[1.]*64)
        self.assertEqual(sum(value*value for value in coordinates)/256,.25)

    def test_zero_gradient_cannot_trigger_another_direction_draw(self):
        matrix=dict(members=[dict(winShares=[0.]*16) for _ in range(32)])
        with self.assertRaisesRegex(ValueError,'zero/nonfinite TOKEN gradient'):
            learner.independent_step(dict(vectors=[[0.]*192+[1.]*64]*16),matrix)

    def test_changing_a_non_token_coordinate_is_rejected(self):
        matrix=dict(members=[dict(winShares=[0.]*16) for _ in range(32)])
        with self.assertRaisesRegex(ValueError,'Only final64 TOKEN'):
            learner.independent_step(dict(vectors=[[1.]*256]*16),matrix)

    def test_full_1560_original_evidence_is_research_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary));result=learner.audit(*args[:5],now=lambda:args[5]['deadlineEpoch']+1)
            self.assertTrue(result['researchSignal']);self.assertFalse(result['qualified'])
            self.assertEqual(result['terminalExecutions'],1560)

    def test_signal_does_not_allow_qualification(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=list(fixture(Path(temporary)));args[3]['qualified']=True
            with self.assertRaisesRegex(ValueError,'original complete research attempt'):
                learner.audit(*args[:5],now=lambda:args[5]['deadlineEpoch']+1)

    def test_clock_cannot_extend_past_original_global_end(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary));clock=c.read(args[2]/'clock.json');clock['auditDeadlineEpoch']+=601
            (args[2]/'clock.json').write_bytes(c.canonical(clock))
            with self.assertRaisesRegex(ValueError,'unchanged bounded audit'):
                learner.audit(*args[:5],now=lambda:clock['deadlineEpoch']+1)

    def test_controls_include_diagnostics_and_available_canonicals(self):
        row=dict(index=0,seed=3,roles=dict(CONTROL={}),diagnostics=dict(CONTROL={'value':1}),canonicalSha256=dict(CONTROL='a'))
        changed=copy.deepcopy(row);changed['diagnostics']['CONTROL']['value']=2
        with self.assertRaisesRegex(ValueError,'diagnostics and available decision histories'):
            learner.same_controls([[row],[changed]])

    def test_all_numeric_and_native_owners_use_shared_physical_closure(self):
        import mac_policy_close
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);protocol,profile,output,terminal,injected,clock=fixture(root)
            owner=root/'terminal-owner.json';c.write(owner,dict(controllerPid=100,caffeinatePid=103))
            (output/'launch.json').write_bytes(c.canonical(dict(pid=100,processGroup=100,
                protocolSha256=c.sha(protocol),profileSha256=c.sha(profile))))
            terminal.update(complete=False,status='interrupted');c.write(output/'terminal.json',terminal)
            c.write(output/'numeric/prepare/owner.json',dict(workerPid=100,nativePid=102,nativeProcessGroup=102))
            with patch.object(mac_policy_close.metadata,'processes',return_value=[('102','102')]):
                with self.assertRaisesRegex(ValueError,'Every original controller, native and caffeine'):
                    mac_policy_close.close(protocol,profile,output,owner,injected)

    def test_unqualified95_does_not_open_any_partial_effect_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal=root/'terminal.json';closed=root/'closed.json'
            c.write(terminal,dict(kind=policy.PARENT_TERMINAL_KIND,study=95,status='failed',qualified=False,
                protocolSha256=policy.PARENT_PROTOCOL_SHA))
            c.write(closed,dict(kind=policy.PARENT_CLOSURE_KIND,study=95,passed=True,qualified=False,
                allOwnedProcessesExited=True,originalClocksRenewed=False,auditReruns=0,
                protocolSha256=policy.PARENT_PROTOCOL_SHA))
            (root/'completion-audit.json').write_text('Invalid partial effect data must remain unopened.')
            self.assertEqual(policy.origin_policy(subject(terminal),subject(closed)),dict(study=94,tokenNeighborMixture=None))

    def test_qualified95_requires_its_original_audit_and_selected_mixture(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);terminal=root/'terminal.json';closed=root/'closed.json';audit=root/'completion-audit.json'
            case=policy.mixture.CASES[4]
            c.write(audit,dict(kind='local-bid-mixture-original-audit-1',complete=True,passed=True,qualified=True,
                terminalExecutions=2648,protocolSha256=policy.PARENT_PROTOCOL_SHA,selectedCase=case))
            c.write(terminal,dict(kind=policy.PARENT_TERMINAL_KIND,study=95,status='completed',complete=True,qualified=True,
                protocolSha256=policy.PARENT_PROTOCOL_SHA,knownCompletedTerminalExecutions=2648,
                selectedCase=case,completionAuditSha256=c.sha(audit)))
            c.write(closed,dict(kind=policy.PARENT_CLOSURE_KIND,study=95,passed=True,qualified=True,scienceComplete=True,
                allOwnedProcessesExited=True,originalClocksRenewed=False,auditReruns=0,
                protocolSha256=policy.PARENT_PROTOCOL_SHA,actualCompletedLowerBound=2648,artifactPins={str(audit):c.sha(audit)}))
            self.assertEqual(policy.origin_policy(subject(terminal),subject(closed))['tokenNeighborMixture'],case['tokenNeighborMixture'])

    def test_native_source_preserves_selected_mixture_only_for_candidate(self):
        plan=dict(arenaSha256='a'*64,baseSelector=policy.mixture.CONTROL_SELECTOR,
            originPolicy=dict(tokenNeighborMixture=policy.mixture.CASES[4]['tokenNeighborMixture']),
            banks=dict(training=dict(count=16,seeds=list(range(16))),holdout=dict(count=128,seeds=list(range(128)))))
        profile={name:dict(path='/invented/'+name,sha256=digest) for name,digest in
            [('candidateBundle',policy.CANDIDATE_SHA),('inferenceAsset',policy.ASSET_SHA),('nomination',policy.NOMINATION_SHA),('arena','a'*64)]}
        source=policy.native_source(plan,profile,'training',dict(id='PLUS_00',bundle=dict(path='/invented/mutant',sha256='b'*64)),0,'c'*64)
        self.assertEqual(source['tokenNeighborMixture'],plan['originPolicy']['tokenNeighborMixture'])
        self.assertEqual(source['controlBundle'],profile['candidateBundle'])
        self.assertEqual(source['replayIndices'],[0,15]);self.assertNotIn('controlTokenNeighborMixture',source)

    def test_holdout_cannot_accept_a_population_member(self):
        with self.assertRaisesRegex(ValueError,'holdout permits START and FINAL'):
            policy.native_source({}, {},'holdout',dict(id='PLUS_00'),0,'c'*64)

    def test_exact_zero_gradient_does_not_admit_partial_win_vectors(self):
        with self.assertRaisesRegex(ValueError,'complete16-world bounded'):
            learner.independent_step(dict(vectors=[[0.]*192+[1.]*64]*16),dict(members=[dict(winShares=[0.]*15)]*32))

    def test_immutable_other_heads_are_checked_from_actual_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);origin=bundle(root/'original','start');other=bundle(root/'other','mutant')
            record=c.read(other['path']);path=Path(other['path']).parent/record['heads']['CARD']['path']
            path.write_text('changedCARD');record['heads']['CARD']['sha256']=c.sha(path)
            Path(other['path']).write_bytes(c.canonical(record));other=subject(other['path'])
            with self.assertRaisesRegex(ValueError,'three immutable original head bytes'):
                learner.immutable_heads(dict(candidateBundle=origin),[dict(bundle=other)])

    def test_token_value_or_dictionary_changes_are_independently_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);origin=bundle(root/'original','start');other=bundle(root/'other','mutant')
            record=c.read(other['path']);path=Path(other['path']).parent/record['heads']['TOKEN']['path']
            model=c.read(path);model['valueBias']=1;path.write_bytes(c.canonical(model))
            record['heads']['TOKEN']['sha256']=c.sha(path);Path(other['path']).write_bytes(c.canonical(record))
            with self.assertRaisesRegex(ValueError,'all non-actor metadata must remain exactly original'):
                learner.immutable_heads(dict(candidateBundle=origin),[dict(bundle=subject(other['path']))])

    def test_extra_numeric_phase_cannot_be_hidden(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary));(args[2]/'numeric/retry').mkdir()
            with self.assertRaisesRegex(ValueError,'unreported numerical phase'):
                learner.audit(*args[:5],now=lambda:args[5]['deadlineEpoch']+1)

    def test_numerical_phase_cannot_change_the_frozen_recipe(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary));path=args[2]/'numeric/update/artifacts/receipt.json'
            value=c.read(path);value['recipe']={'changed':True};path.write_bytes(c.canonical(value))
            with self.assertRaisesRegex(ValueError,'original exact request'):
                learner.numerical_receipt(args[2],'update')

    def test_phase_scope_cannot_change_despite_matching_artifact_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            args=fixture(Path(temporary));path=args[2]/'numeric/update/artifacts/receipt.json'
            value=c.read(path);value['scopeSha256']='b'*64;path.write_bytes(c.canonical(value))
            with self.assertRaisesRegex(ValueError,'one prospectively bound scope'):
                learner.audit(*args[:5],now=lambda:args[5]['deadlineEpoch']+1)

    def test_controller_entrypoint_belongs_to_retained_source_closure(self):
        files=prepare.source_closure(Path(prepare.__file__).parent,
            ('token_focus_contract.py',*policy.EXECUTABLES))
        self.assertIn(Path(learner.__file__),[path.resolve() for path in files])


if __name__=='__main__':unittest.main()
