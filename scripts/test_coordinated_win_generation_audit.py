"""Pure sixteen-lineage shape proof, with explicitly invented preparation/native attestations.

The scalar updates and inherited calibration arithmetic are real. No models, forwards, random
generation, subprocess or game is used; this is separate from the retained real numeric join.
"""
import copy
from pathlib import Path
import shutil
import tempfile
import time
import unittest

import coordinated_es_calibration_v2 as calibration
import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_win_context as contexts
import coordinated_win_generation_audit as audit
import coordinated_win_plan as p
from test_coordinated_es_calibration import panel,typed
from test_coordinated_es_generation_audit import put
from test_coordinated_win_plan import fixture as protocol_fixture


class Fixture:
    def __init__(self,root,count=16,diagnostic=False):
        self.root=Path(root).resolve();self.run=self.root/'run';self.run.mkdir()
        self.prepared=self.root/'prepared';self.prepared.mkdir()
        self.source=put(self.root/'source.json',dict(constructedOwner=True))
        self.environment=put(self.root/'environment.json',dict(constructedEnvironment=True))
        _,record=protocol_fixture()
        originals={head:put(self.root/f'original/heads/{head}/policy.json',dict(opaqueOriginal=head)) for head in p.HEADS}
        for head,item in originals.items():record['parameterSpace']['heads'][head]['actorSha256']=item['sha256']
        original=put(self.root/'original/bundle.json',dict(kind='coordinated-es-bundle-1',heads={head:dict(
            path=f'heads/{head}/policy.json',sha256=item['sha256'],
            dictionarySha256=record['parameterSpace']['heads'][head]['dictionarySha256']) for head,item in originals.items()}))
        projection=put(self.prepared/'projection.json',dict(constructedProjection=True))
        scope=es.Scope('a'*64,original['sha256'],projection['sha256'])
        old_protocol=put(self.prepared/'calibration/origin/protocol.json',dict(calibration=p.base.calibration_recipe(2)),True)
        public=put(self.root/'public-panel.json',dict(constructedPublicPanel=True));heads={}
        for head in ('EXPANSION','TOKEN'):
            gap=panel();gap.update(head=head,protocolSha256=old_protocol['sha256'],
                originalModelSha256=record['parameterSpace']['heads'][head]['actorSha256'],
                projectionSha256=projection['sha256'],directionsSha256='1'*64,
                prescribedMeanSha256='2'*64,publicPanelSha256=public['sha256'])
            gap=typed(gap);put(self.prepared/f'calibration/origin/gap-{head}.json',gap.record)
            heads[head]=calibration.calibrate(gap)
        seal=put(self.prepared/'calibration/origin/calibration.json',dict(kind='coordinated-es-calibration-2',
            passed=True,complete=True,protocolSha256=old_protocol['sha256'],originalBundleSha256=original['sha256'],
            projectionSha256=projection['sha256'],panelSha256=public['sha256'],probePopulationSha256='3'*64,
            directionsSha256='1'*64,prescribedMeanSha256='2'*64,heads=heads,
            scales={head:value['scale'] for head,value in heads.items()},semanticFitnessDecoded=False,calibrationAttempts=1))
        raw_heads={head:put(self.prepared/f'warm-bundle/heads/{head}/policy.json',dict(opaqueToyHead=head)) for head in p.HEADS}
        bundle=put(self.prepared/'warm-bundle/bundle.json',dict(kind='coordinated-es-bundle-1',heads={head:dict(
            path=f'heads/{head}/policy.json',sha256=item['sha256'],
            dictionarySha256=record['parameterSpace']['heads'][head]['dictionarySha256']) for head,item in raw_heads.items()}))
        parent=es.Center(scope,16,(.5,)*256)
        self.warm=c.WarmStart(scope,es.digest(parent.record),bundle['sha256'],'e'*64,'f'*64,seal['sha256'],parent.coordinates)
        warm=put(self.prepared/'warm-start.json',self.warm.record)
        initial=put(self.prepared/'initial-center.json',c.Center.initial(self.warm).record)
        record['parameterSpace'].update(scope.record)
        record['warmStart'].update(identitySha256=warm['sha256'],centerSha256=self.warm.origin_center_sha256,
            bundleSha256=bundle['sha256'],protocolSha256=self.warm.origin_protocol_sha256,
            admissionSha256=self.warm.origin_admission_sha256)
        record['calibration'].update(originProtocolSha256=old_protocol['sha256'],originCalibrationSha256=seal['sha256'],
            scales={head:value['scale'] for head,value in heads.items()})
        proof=put(self.root/'origin-proof.json',dict(constructedOriginProof=True))
        record['warmStart']['proofSha256']['DESKTOP']=proof['sha256']
        self.plan=p.Plan.from_record(record);self.protocol=put(self.root/'protocol.json',record,True)
        inherited=put(self.prepared/'calibration/inherited-calibration.json',dict(kind='coordinated-es-inherited-calibration-1',
            protocolSha256=self.protocol['sha256'],parameterSpace=scope.record,warmStartSha256=warm['sha256'],
            originProtocol=dict(path='origin/protocol.json',sha256=old_protocol['sha256']),
            originCalibration=dict(path='origin/calibration.json',sha256=seal['sha256']),
            recipe=record['calibration']['recipe'],scales=record['calibration']['scales'],recalibrated=False))
        directions=[]
        for number in range(1,17):
            direction=put(self.prepared/f'directions/generation-{number:02d}.json',
                es.DirectionTable(scope,number,((1.,-1.)*128,)*16).record)
            directions.append(direction)
            put(self.prepared/f'directions/generation-{number:02d}-generation.json',dict(
                kind='coordinated-es-direction-generation-1',generation=number,
                seed=self.plan.bank(number)['directionSeed'],generator='numpy-PCG64',
                distribution='independent-standard-normal',shape=[16,256],renormalized=False,
                directionsSha256=direction['sha256']))
        put(self.prepared/'started.json',dict(constructedPreparationStart=True))
        put(self.prepared/'warm-start-proof.json',dict(kind='coordinated-es-warm-start-proof-1',complete=True,
            protocolSha256=self.protocol['sha256'],warmStartSha256=warm['sha256'],originCenterSha256=self.warm.origin_center_sha256,
            originBundleSha256=bundle['sha256'],allFourHeadBytesExact=True,descriptorBytesExact=True,
            projectionRegenerated=False,calibrationRecomputedFromNewStates=False,initializationStreams=0,directionStreams=16,
            execution=audit.ZERO_EXECUTION))
        request=put(self.root/'prepare-request.json',dict(kind='coordinated-win-prepare-request-1',protocol=self.protocol,
            originalBundle=original,projection=projection,warmStart=warm,sourceOwners={self.source['path']:self.source['sha256']}))
        finished=put(self.prepared/'finished.json',dict(kind='coordinated-win-numeric-complete-1',verb='prepare',
            passed=True,complete=True,request=request,protocolSha256=self.protocol['sha256'],
            sourceOwners={self.source['path']:self.source['sha256']},execution=audit.ZERO_EXECUTION,
            artifacts={path.relative_to(self.prepared).as_posix():p.file_sha(path) for path in self.prepared.rglob('*') if path.is_file()},
            outputs=dict(projection='projection.json',warmStart='warm-start.json',center='initial-center.json',
                inheritedCalibration='calibration/inherited-calibration.json',proof='warm-start-proof.json')))
        self.profile=dict(kind='coordinated-win-host-1',host='DESKTOP',protocolSha256=self.protocol['sha256'],
            originalBundle=original,projection=projection,warmStart=warm,initialCenter=initial,
            inheritedCalibration=inherited,originProof=proof,arena=put(self.root/'arena.json',dict(constructedArena=True)),
            directions=directions,numericalPreparation=dict(request=request,finished=finished))
        if diagnostic:self.profile['diagnosticPanel']=public
        self.profile_subject=put(self.root/'profile.json',self.profile)
        self.historical=put(self.run/'historical/historical-admission.json',dict(constructedHistorical=True))
        self.start=time.time()-600;self.clock=dict(kind='coordinated-win-clock-1',protocolSha256=self.protocol['sha256'],
            startedEpoch=self.start,deadlineEpoch=self.start+p.SCIENCE_SECONDS)
        put(self.run/'clock.json',self.clock)
        put(self.run/'worker-owner.json',dict(kind='coordinated-win-worker-owner-1',pid=1,processGroup=1,profile=self.profile_subject))
        self.rows=[];self.previous=initial;self.previous_update=None
        for number in range(1,count+1):self.generation(number)
        terminal=dict(kind='coordinated-win-terminal-1',host='DESKTOP',status='completed' if count==16 else 'stopped',
            clock=self.clock,updatedEpoch=self.start+count*30+25,completedGenerations=count)
        if count!=16:terminal['reason']='prefix-resource-gate'
        put(self.run/'terminal.json',terminal)
        if count>=2:self.prefix(count==16)

    def numeric(self,number,verb,fields,outputs):
        root=self.run/'generations'/f'generation-{number}'/verb;root.mkdir(exist_ok=True)
        request=dict(kind=f'coordinated-win-{verb}-request-1',protocol=self.protocol,
            numerical=self.environment,sourceOwners={self.source['path']:self.source['sha256']},
            deadlineEpoch=self.clock['deadlineEpoch'],**fields)
        item=put(root.with_name(verb+'-request.json'),request,True)
        put(root/'started.json',dict(kind='coordinated-win-numeric-started-1',verb=verb,request=item,
            startedEpoch=self.start+number*30+1,deadlineEpoch=self.clock['deadlineEpoch']))
        values=[item,self.protocol,self.environment,self.source]+[row for row in fields.values()
            if type(row) is dict and set(row)=={'path','sha256'}]
        return put(root/'finished.json',dict(kind='coordinated-win-numeric-complete-1',passed=True,complete=True,
            verb=verb,request=item,protocolSha256=self.protocol['sha256'],sourceOwners=request['sourceOwners'],
            inputPins={row['path']:row['sha256'] for row in values},outputs=outputs,
            artifacts={path.relative_to(root).as_posix():p.file_sha(path) for path in root.rglob('*') if path.is_file()},
            completedEpoch=self.start+number*30+2,execution=audit.ZERO_EXECUTION))

    def common(self):
        return {key:self.profile[key] for key in ('originalBundle','projection','warmStart','inheritedCalibration')}

    def generation(self,number):
        root=self.run/'generations'/f'generation-{number}';root.mkdir(parents=True)
        center=c.Center.from_record(p.read(self.previous['path']),self.warm)
        direction=self.profile['directions'][number-1];directions=es.DirectionTable.from_record(p.read(direction['path']))
        bank=put(root/'bank.json',self.plan.bank(number)['worldBank'])
        members=[dict(direction=row['direction'],sign=row['sign'],
            path=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/bundle.json",
            coordinatesSha256=es.digest(row['coordinates']),bundleSha256=es.digest([number,row['direction'],row['sign']]))
            for row in c.mutants(center,directions)]
        materialization=dict(kind=c.MATERIALIZATION_KIND,parameterSpace=self.plan.scope.record,
            warmStartSha256=self.warm.sha256,step=number,centerSha256=self.previous['sha256'],directionsSha256=direction['sha256'],
            worldBankSha256=bank['sha256'],worldIds=list(range(256)),members=members)
        put(root/'materialize/materialization.json',materialization)
        population=c.seal_population(materialization,self.protocol['sha256'],self.profile['inheritedCalibration']['sha256'])
        put(root/'materialize/population.json',population)
        self.numeric(number,'materialize',dict(self.common(),center=self.previous,directions=direction,worldBank=bank),
            dict(materialization='materialization.json',population='population.json'))
        population_subject=put(root/'portable/population.json',population)
        consumed={}
        for phase,name in (('controls','control-context'),('population','portable')):
            contexts.create(root/name,self.plan,self.protocol['path'],self.profile,number,phase,self.previous,
                self.previous_update,self.historical,population_subject if phase=='population' else None)
            consumed[phase]=contexts.read(root/name,self.plan,self.protocol['path'],self.profile,number,phase)
        fitness=dict(kind=c.FITNESS_KIND,objective=c.OBJECTIVE,protocolSha256=self.protocol['sha256'],
            populationSha256=population_subject['sha256'],worldBankSha256=bank['sha256'],worldIds=list(range(256)),
            members=[dict(direction=row['direction'],sign=row['sign'],bundleSha256=row['bundleSha256'],
                winShares=[.6 if row['sign']==1 else .4]*256) for row in members])
        fit=put(root/'fitness.json',fitness);result=c.update(center,directions,population,fitness)
        update=put(root/'update/update.json',result);output=put(root/'update/center.json',result['center'])
        self.numeric(number,'update',dict(warmStart=self.profile['warmStart'],inheritedCalibration=self.profile['inheritedCalibration'],
            center=self.previous,directions=direction,population=population_subject,fitness=fit),dict(update='update.json',center='center.json'))
        diagnostic=None
        if self.profile.get('diagnosticPanel') is not None:
            put(root/'diagnose/movement.json',dict(kind='coordinated-es-continuation-movement-1',step=number,
                protocolSha256=self.protocol['sha256'],centerSha256=output['sha256'],
                inheritedCalibrationSha256=self.profile['inheritedCalibration']['sha256'],
                heads={head:dict(diagnosticOnly=True,eligibilityGate=False,rate=0.) for head in ('EXPANSION','TOKEN')},
                diagnosticOnly=True,eligibilityGate=False))
            diagnostic=self.numeric(number,'diagnose',dict(self.common(),center=output,
                publicPanel=self.profile['diagnosticPanel']),dict(movement='movement.json'))
        row=dict(kind='coordinated-win-generation-receipt-1',complete=True,generation=number,protocolSha256=self.protocol['sha256'],
            scopeSha256=self.plan.scope.scope_sha256,warmStartSha256=self.warm.sha256,
            ecologySha256=self.plan.record['evaluation']['ecologySha256'],inputCenter=self.previous,outputCenter=output,
            directions=direction,bank=bank,population=population_subject,inheritedCalibration=self.profile['inheritedCalibration'],
            controls=[put(root/f'control-{i}.json',dict(constructedControl=i,generation=number,subjects=consumed['controls'])) for i in range(8)],
            populations=[put(root/f'population-{i}.json',dict(constructedPopulation=i,generation=number,subjects=consumed['population'])) for i in range(8)],
            fitness=fit,update=update,diagnostic=diagnostic,startedEpoch=self.start+number*30,
            finishedEpoch=self.start+number*30+20,elapsedSeconds=20.)
        put(root/'receipt.json',row);self.rows.append(row);self.previous=output;self.previous_update=update

    def prefix(self,passed):
        delta=100 if passed else 8*1024**3
        retention={host:dict(sharedBaseBytes=0,generationDeltaBytes=[delta,delta]) for host in p.HOSTS}
        censuses={host:put(self.root/f'{host}-retention.json',dict(kind='coordinated-es-retention-1',host=host,
            generation=2,protocolSha256=self.protocol['sha256'],sharedBaseBytes=0,
            generationBytes={str(i):delta if i<=2 else 0 for i in range(1,17)},totalBytes=2*delta,regularFiles=100))
            for host in p.HOSTS}
        size=0
        for _ in range(8):
            retention['DESKTOP']['generationDeltaBytes'][1]=delta+size
            record=p.prefix_gate(self.clock['deadlineEpoch']-self.rows[1]['finishedEpoch']-1,[20.,20.],retention)
            record.update(censusAdjustmentBytes=size,censuses=censuses);new=len(es.canonical(record))+1
            if size==new:break
            size=new
        else:raise AssertionError('The constructed prefix byte framing must stabilize.')
        put(self.run/'generations/generation-2/operational-prefix.json',record,True)

    def stop_first(self,zero=True):
        root=self.run/'generations/generation-1';operation=root/'update'
        receipt=p.read(operation/'finished.json');request=p.read(receipt['request']['path'])
        if zero:
            value=p.read(root/'fitness.json')
            for row in value['members']:row['winShares']=[0.]*256
            request['fitness']=put(root/'fitness.json',value)
        request_subject=put(receipt['request']['path'],request,True)
        started=p.read(operation/'started.json');started['request']=request_subject
        shutil.rmtree(operation);operation.mkdir();put(operation/'started.json',started)
        inputs={item['path']:item['sha256'] for item in (request_subject,self.protocol,self.environment,self.source)}
        for item in request.values():
            if type(item) is dict and set(item)=={'path','sha256'}:inputs[item['path']]=item['sha256']
        stopped=put(operation/'stopped.json',dict(kind='coordinated-win-numeric-stop-1',passed=False,complete=False,
            verb='update',request=request_subject,reason='zero-gradient',diagnostics=None,inputPins=inputs,
            artifacts={'started.json':p.file_sha(operation/'started.json')},stoppedEpoch=self.start+32,
            deadlineEpoch=self.clock['deadlineEpoch'],execution=audit.ZERO_EXECUTION))
        (root/'receipt.json').unlink()
        put(self.run/'terminal.json',dict(kind='coordinated-win-terminal-1',host='DESKTOP',status='stopped',clock=self.clock,
            updatedEpoch=self.start+55,completedGenerations=0,reason='zero-gradient',numericStop=stopped))


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def verify(self,fixture):return audit.verify(fixture.protocol,fixture.run,profile_subject=fixture.profile_subject)

    def test_full16_scalar_chain_has_no_model_or_native_execution_claim(self):
        f=Fixture(self.root);result=self.verify(f)
        self.assertTrue(result['complete']);self.assertEqual(result['eligibleFinalCenter'],f.previous)
        self.assertFalse(result['nativeArchivesRecomputed']);self.assertEqual(result['execution'],audit.ZERO_EXECUTION)
        self.assertEqual(result['objective'],c.OBJECTIVE)

    def test_learning_admission_needs_no_terminal_and_pins_every_wrapper(self):
        f=Fixture(self.root);(f.run/'terminal.json').unlink()
        result=audit.verify_learning(f.protocol,f.run,profile_subject=f.profile_subject)
        self.assertEqual(result['completedGenerations'],16)
        for number in range(1,17):
            path=f.run/'generations'/f'generation-{number}'/'receipt.json'
            self.assertEqual(result['artifactPins'][str(path)],p.file_sha(path))

    def test_original_global_radius_can_bind_without_rebasing_warm_center(self):
        f=Fixture(self.root);result=self.verify(f)
        self.assertEqual(p.read(f.rows[0]['inputCenter']['path'])['coordinates'],[.5]*256)
        self.assertGreater(result['rows'][-1]['radiusDivisor'],1.)
        self.assertLessEqual(result['rows'][-1]['centerRms'],4.+2e-15)

    def test_optional_fixed_diagnostic_is_accepted_only_as_descriptive(self):
        f=Fixture(self.root,2,True);result=self.verify(f)
        self.assertFalse(result['complete']);self.assertIsNone(result['eligibleFinalCenter'])

    def test_profile_required_diagnostic_cannot_be_skipped(self):
        f=Fixture(self.root,2,True);row=copy.deepcopy(f.rows[0]);row['diagnostic']=None
        put(f.run/'generations/generation-1/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'Diagnostic presence'):self.verify(f)

    def test_absent_diagnostic_cannot_be_added_after_outcomes(self):
        f=Fixture(self.root,2);row=copy.deepcopy(f.rows[0]);row['diagnostic']=row['update']
        put(f.run/'generations/generation-1/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'Diagnostic presence'):self.verify(f)

    def test_warm_center_cannot_skip_immediate_predecessor(self):
        f=Fixture(self.root,2);row=copy.deepcopy(f.rows[1]);row['inputCenter']=f.rows[0]['inputCenter']
        put(f.run/'generations/generation-2/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'immediate predecessor'):self.verify(f)

    def test_actual_context_cannot_publish_an_unrelated_population(self):
        f=Fixture(self.root,2);path=f.run/'generations/generation-1/portable/stage.json';row=p.read(path)
        row['subjects']['population']=dict(path='center.json',sha256=row['subjects']['center']['sha256']);put(path,row)
        with self.assertRaisesRegex(ValueError,'exact sealed population'):self.verify(f)

    def test_new_ecology_cannot_replace_original_parameter_scope(self):
        f=Fixture(self.root,2);row=copy.deepcopy(f.rows[0]);row['scopeSha256']=row['ecologySha256']
        put(f.run/'generations/generation-1/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'parameter origin'):self.verify(f)

    def test_native_shard_cannot_bind_an_unrelated_preparation_or_center(self):
        f=Fixture(self.root,2);row=copy.deepcopy(f.rows[0]);item=row['populations'][0]
        native=p.read(item['path']);native['subjects']['numericalPreparation']=row['inputCenter']
        row['populations'][0]=put(item['path'],native);put(f.run/'generations/generation-1/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'same portable coordinator subject: numericalPreparation'):self.verify(f)

    def test_prepared_direction_stream_receipt_cannot_change(self):
        f=Fixture(self.root,2);path=f.prepared/'directions/generation-01-generation.json';value=p.read(path)
        value['seed']+=1;put(path,value)
        with self.assertRaisesRegex(ValueError,'subject changed'):self.verify(f)

    def test_inherited_original_protocol_cannot_be_relabelled(self):
        f=Fixture(self.root,2);path=f.prepared/'calibration/origin/calibration.json';value=p.read(path)
        value['protocolSha256']=f.protocol['sha256'];put(path,value)
        with self.assertRaisesRegex(ValueError,'subject changed'):self.verify(f)

    def test_worker_owner_requires_the_explicit_frozen_profile(self):
        f=Fixture(self.root,2);path=f.run/'worker-owner.json';value=p.read(path);value['profile']=f.protocol;put(path,value)
        with self.assertRaisesRegex(ValueError,'explicit audited profile'):self.verify(f)

    def test_honest_zero_gradient_has_no_eligible_shortened_center(self):
        f=Fixture(self.root,1);f.stop_first();result=self.verify(f)
        self.assertFalse(result['complete']);self.assertEqual(result['rows'],[]);self.assertIsNone(result['eligibleFinalCenter'])

    def test_nonzero_update_cannot_be_relabelled_a_stop(self):
        f=Fixture(self.root,1);f.stop_first(False)
        with self.assertRaisesRegex(ValueError,'successful win update'):self.verify(f)

    def test_prefix_failure_recomputes_retention_from_both_original_censuses(self):
        f=Fixture(self.root,2);result=self.verify(f)
        self.assertEqual(result['status'],'stopped');self.assertEqual(result['completedGenerations'],2)

    def test_shortened_completion_is_rejected(self):
        f=Fixture(self.root,2);path=f.run/'terminal.json';value=p.read(path);value['status']='completed';put(path,value)
        with self.assertRaisesRegex(ValueError,'all sixteen'):self.verify(f)

    def test_mac_cannot_claim_complete_fitness_recomputation(self):
        f=Fixture(self.root,2);path=f.run/'terminal.json';value=p.read(path);value['host']='MAC';put(path,value)
        with self.assertRaisesRegex(ValueError,'sole Desktop'):self.verify(f)

    def test_future_generation_residue_is_not_an_honest_prefix(self):
        f=Fixture(self.root,2);(f.run/'generations/generation-3').mkdir()
        with self.assertRaisesRegex(ValueError,'finite completed/stopped prefix'):self.verify(f)


if __name__=='__main__':unittest.main()
