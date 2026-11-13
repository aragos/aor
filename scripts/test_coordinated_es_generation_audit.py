"""Pure constructed receipts; actor files and native execution are deliberately outside this primitive."""
import copy
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest

import coordinated_es_math as es
import coordinated_es_calibration as cal
import coordinated_es_generation_audit as audit
import coordinated_es_plan as p
from test_coordinated_es_calibration import panel as gap_fixture, typed as typed_gap
from test_coordinated_es_plan import protocol as protocol_fixture


def put(path,value,lf=False):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(es.canonical(value)+(b'\n' if lf else b''))
    return p.subject(path)


class Fixture:
    def __init__(self,root,count=2):
        self.root=Path(root);self.run=self.root/'run';self.run.mkdir()
        self.source=put(self.root/'source.py',dict(constructedOwner=True))
        self.environment=put(self.root/'environment.json',dict(constructedEnvironment=True))
        self.original=put(self.root/'original.json',dict(constructedOriginal=True))
        self.projection=put(self.root/'projection.json',dict(constructedProjection=True))
        record=protocol_fixture();record['original']['bundleSha256']=self.original['sha256']
        record['original']['projectionSha256']=self.projection['sha256']
        self.plan=p.Plan.from_record(record);self.protocol=put(self.root/'protocol.json',record,True)
        self.scope=audit.scope(self.plan);self.start=time.time()-600
        self.clock=dict(kind='coordinated-es-clock-1',protocolSha256=self.protocol['sha256'],
            startedEpoch=self.start,deadlineEpoch=self.start+p.SCIENCE_SECONDS)
        put(self.run/'clock.json',self.clock)
        self.previous=put(self.root/'initial-center.json',es.Center(self.scope,0,(0.,)*256).record)
        self.rows=[];self.calibration=None
        for number in range(1,count+1):self.generation(number)
        terminal=dict(kind='coordinated-es-terminal-1',host='DESKTOP',status='completed' if count==16 else 'stopped',
            clock=self.clock,updatedEpoch=self.start+count*30+25,completedGenerations=count)
        if count!=16:terminal['reason']='prefix-resource-gate'
        put(self.run/'terminal.json',terminal)
        if count>=2:
            retention={host:dict(sharedBaseBytes=0,generationDeltaBytes=[100,100] if count==16 else [2*1024**3]*2)
                for host in p.HOSTS}
            censuses={host:put(self.root/f'{host}-retention.json',dict(kind='coordinated-es-retention-1',host=host,
                generation=2,protocolSha256=self.protocol['sha256'],sharedBaseBytes=0,
                generationBytes={str(i):values['generationDeltaBytes'][i-1] if i<=2 else 0 for i in range(1,17)},
                totalBytes=sum(values['generationDeltaBytes']),regularFiles=100)) for host,values in retention.items()}
            base=retention['DESKTOP']['generationDeltaBytes'][1];size=0
            for _ in range(8):
                retention['DESKTOP']['generationDeltaBytes'][1]=base+size
                row=p.prefix_gate(self.clock['deadlineEpoch']-(self.rows[1]['finishedEpoch']+1),[20.,20.],retention)
                row.update(censusAdjustmentBytes=size,censuses=censuses)
                new=len(es.canonical(row))+1
                if new==size:break
                size=new
            else:raise AssertionError('Constructed prefix framing must stabilize.')
            put(self.run/'generations/generation-2/operational-prefix.json',row,True)

    def numeric(self,number,verb,body,outputs):
        directory=self.run/'generations'/f'generation-{number}'/verb;directory.mkdir(exist_ok=True)
        request=dict(kind=f'coordinated-es-{verb}-request-1',protocol=self.protocol,
            sourceOwners={self.source['path']:self.source['sha256']},numerical=self.environment,
            deadlineEpoch=self.clock['deadlineEpoch'],**body)
        request_subject=put(directory.with_name(verb+'-request.json'),request,True)
        put(directory/'started.json',dict(kind='coordinated-es-numeric-started-1',verb=verb,
            request=request_subject,startedEpoch=self.start+number*30+1,deadlineEpoch=self.clock['deadlineEpoch']))
        inputs={item['path']:item['sha256'] for item in (request_subject,self.protocol,self.environment,self.source)}
        for item in body.values():
            if type(item) is dict and set(item)=={'path','sha256'}:inputs[item['path']]=item['sha256']
        receipt=dict(kind='coordinated-es-numeric-complete-1',passed=True,complete=True,verb=verb,
            request=request_subject,protocolSha256=self.protocol['sha256'],sourceOwners=request['sourceOwners'],
            inputPins=inputs,artifacts={path.relative_to(directory).as_posix():p.file_sha(path)
                for path in directory.rglob('*') if path.is_file()},outputs=outputs,
            completedEpoch=self.start+number*30+2,execution=audit.ZERO_EXECUTION)
        return put(directory/'finished.json',receipt)

    def generation(self,number):
        directory=self.run/'generations'/f'generation-{number}';directory.mkdir(parents=True)
        center=es.Center.from_record(p.read(self.previous['path']))
        directions=es.DirectionTable(self.scope,number,((1.,-1.)*128,)*16)
        direction_subject=put(self.root/f'directions-{number}.json',directions.record)
        bank=put(directory/'bank.json',self.plan.bank(number)['worldBank'])
        members=[dict(direction=row['direction'],sign=row['sign'],
            path=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/bundle.json",
            coordinatesSha256=es.digest(row['coordinates']),bundleSha256=es.digest([number,row['direction'],row['sign']]))
            for row in es.mutants(center,directions)]
        materialization=dict(kind='coordinated-es-materialization-1',scope=self.scope.record,generation=number,
            centerSha256=self.previous['sha256'],directionsSha256=direction_subject['sha256'],
            worldBankSha256=bank['sha256'],worldIds=list(range(256)),members=members)
        materialized=put(directory/'materialize/materialization.json',materialization)
        self.numeric(number,'materialize',dict(originalBundle=self.original,projection=self.projection,
            center=self.previous,directions=direction_subject,worldBank=bank,worldIds=list(range(256)),calibration=self.calibration),
            dict(materialization='materialization.json'))
        if number==1:
            panel=put(directory/'public-panel.json',dict(constructedPublicPanel=True))
            mean=es.prescribed_mean_probe(directions);receipts={}
            for head in ('EXPANSION','TOKEN'):
                gap=gap_fixture();gap.update(head=head,protocolSha256=self.protocol['sha256'],
                    originalModelSha256=self.plan.record['original']['heads'][head]['actorSha256'],
                    projectionSha256=self.projection['sha256'],directionsSha256=direction_subject['sha256'],
                    prescribedMeanSha256=es.digest(mean),publicPanelSha256=panel['sha256'])
                gap=typed_gap(gap);put(directory/f'calibrate/gap-{head}.json',gap.record)
                receipts[head]=cal.calibrate(gap)
            seal=dict(kind='coordinated-es-calibration-1',passed=True,complete=True,
                protocolSha256=self.protocol['sha256'],originalBundleSha256=self.original['sha256'],
                projectionSha256=self.projection['sha256'],panelSha256=panel['sha256'],
                probePopulationSha256=materialized['sha256'],directionsSha256=direction_subject['sha256'],
                prescribedMeanSha256=es.digest(mean),scales={head:row['scale'] for head,row in receipts.items()},
                heads=receipts,semanticFitnessDecoded=False,calibrationAttempts=1)
            self.calibration=put(directory/'calibrate/calibration.json',seal);self.panel=panel
            self.numeric(number,'calibrate',dict(originalBundle=self.original,projection=self.projection,
                directions=direction_subject,materialization=materialized,publicPanel=panel),dict(calibration='calibration.json'))
        population=dict(materialization,kind='coordinated-es-population-1',protocolSha256=self.protocol['sha256'],
            materializationSha256=materialized['sha256'],calibrationSha256=self.calibration['sha256'])
        population_subject=put(directory/'portable/population.json',population)
        fitness=dict(kind='coordinated-es-fitness-1',populationSha256=population_subject['sha256'],
            worldBankSha256=bank['sha256'],worldIds=list(range(256)),members=[dict(direction=row['direction'],sign=row['sign'],
                bundleSha256=row['bundleSha256'],rewards=[.6 if row['sign']==1 else .4]*256) for row in members])
        fitness_subject=put(directory/'fitness.json',fitness);result=es.update(center,directions,population,fitness)
        update=put(directory/'update/update.json',result);output=put(directory/'update/center.json',result['center'])
        self.numeric(number,'update',dict(center=self.previous,directions=direction_subject,population=population_subject,
            fitness=fitness_subject),dict(update='update.json',center='center.json'))
        put(directory/'diagnose/movement.json',dict(kind='coordinated-es-center-movement-1',generation=number,
            protocolSha256=self.protocol['sha256'],centerSha256=output['sha256'],calibrationSha256=self.calibration['sha256'],
            heads={head:dict(diagnosticOnly=True,eligibilityGate=False,rate=0.) for head in ('EXPANSION','TOKEN')},
            diagnosticOnly=True,eligibilityGate=False))
        diagnostic=self.numeric(number,'diagnose',dict(originalBundle=self.original,projection=self.projection,
            center=output,calibration=self.calibration,publicPanel=self.panel),dict(movement='movement.json'))
        row=dict(kind='coordinated-es-generation-receipt-1',complete=True,generation=number,
            protocolSha256=self.protocol['sha256'],scopeSha256=self.scope.scope_sha256,
            inputCenter=self.previous,outputCenter=output,directions=direction_subject,bank=bank,population=population_subject,
            calibration=self.calibration,fitness=fitness_subject,update=update,diagnostic=diagnostic,
            controls=[put(directory/f'control-{i}.json',dict(number=number,control=i)) for i in range(8)],
            populations=[put(directory/f'population-{i}.json',dict(number=number,population=i)) for i in range(8)],
            startedEpoch=self.start+number*30,finishedEpoch=self.start+number*30+20,elapsedSeconds=20.)
        put(directory/'receipt.json',row);self.rows.append(row);self.previous=output

    def stop_first(self,verb='update',zero=True):
        directory=self.run/'generations/generation-1';operation=directory/verb
        complete=p.read(operation/'finished.json');request=p.read(complete['request']['path'])
        if verb=='update':
            fitness=p.read(directory/'fitness.json')
            if zero:
                for row in fitness['members']:row['rewards']=[0.]*256
            request['fitness']=put(directory/'fitness.json',fitness)
            reason='zero-gradient';terminal_reason=reason
            shutil.rmtree(directory/'diagnose')
        else:
            public=dict(kind='coordinated-es-public-panel-1',protocolSha256=self.protocol['sha256'],
                originalBundleSha256=self.original['sha256'],worlds=[dict(index=i,assignedEpoch=1+(i//4)%3,
                    heads=dict(EXPANSION=[],TOKEN=[])) for i in range(256)])
            request['publicPanel']=put(directory/'public-panel.json',public)
            reason='calibration-insufficient-worlds';terminal_reason='calibration-failed'
            shutil.rmtree(directory/'update');shutil.rmtree(directory/'diagnose');shutil.rmtree(directory/'portable')
            (directory/'fitness.json').unlink()
        request_subject=put(Path(complete['request']['path']),request,True)
        started=p.read(operation/'started.json');started['request']=request_subject
        shutil.rmtree(operation);operation.mkdir()
        put(operation/'started.json',started)
        inputs={item['path']:item['sha256'] for item in (request_subject,self.protocol,self.environment,self.source)}
        for item in request.values():
            if type(item) is dict and set(item)=={'path','sha256'}:inputs[item['path']]=item['sha256']
        stopped=put(operation/'stopped.json',dict(kind='coordinated-es-numeric-stop-1',complete=False,passed=False,
            verb=verb,request=request_subject,reason=reason,diagnostics=None,inputPins=inputs,
            artifacts={'started.json':p.file_sha(operation/'started.json')},stoppedEpoch=self.start+32,
            deadlineEpoch=self.clock['deadlineEpoch'],execution=audit.ZERO_EXECUTION))
        (directory/'receipt.json').unlink()
        put(self.run/'terminal.json',dict(kind='coordinated-es-terminal-1',host='DESKTOP',status='stopped',
            clock=self.clock,updatedEpoch=self.start+55,completedGenerations=0,reason=terminal_reason,numericStop=stopped))


class GenerationAuditTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup);self.root=Path(self.temporary.name)

    def test_two_complete_generations_can_stop_only_at_exact_resource_prefix(self):
        fixture=Fixture(self.root);result=audit.verify(fixture.protocol,fixture.run)
        self.assertFalse(result['complete']);self.assertEqual(len(result['rows']),2)
        self.assertIsNone(result['eligibleFinalCenter']);self.assertEqual(result['execution'],audit.ZERO_EXECUTION)

    def test_complete16_chain_admits_only_exact_last_center(self):
        fixture=Fixture(self.root,16);result=audit.verify(fixture.protocol,fixture.run)
        self.assertTrue(result['complete']);self.assertEqual(result['eligibleFinalCenter'],fixture.previous)
        self.assertEqual(result['rows'][-1]['generation'],16)

    def test_complete_learning_proof_requires_no_terminal_publication(self):
        fixture=Fixture(self.root,16);(fixture.run/'terminal.json').unlink()
        result=audit.verify_learning(fixture.protocol,fixture.run)
        self.assertTrue(result['complete']);self.assertEqual(result['protocolSha256'],fixture.protocol['sha256'])
        self.assertEqual(result['artifactPins'][str(fixture.run/'generations/generation-16/receipt.json')],
            p.file_sha(fixture.run/'generations/generation-16/receipt.json'))
        self.assertFalse((fixture.run/'terminal.json').exists())

    def test_learning_proof_cannot_admit_a_partial_prefix(self):
        fixture=Fixture(self.root)
        with self.assertRaisesRegex(ValueError,'finite completed/stopped prefix'):
            audit.verify_learning(fixture.protocol,fixture.run)

    def test_honest_zero_gradient_stop_recomputes_without_any_candidate(self):
        fixture=Fixture(self.root,1);fixture.stop_first()
        result=audit.verify(fixture.protocol,fixture.run)
        self.assertFalse(result['complete']);self.assertEqual(result['rows'],[])
        self.assertIsNone(result['eligibleFinalCenter'])

    def test_successful_gradient_cannot_be_relabeled_a_stop(self):
        fixture=Fixture(self.root,1);fixture.stop_first(zero=False)
        with self.assertRaisesRegex(ValueError,'successful update cannot be relabeled'):
            audit.verify(fixture.protocol,fixture.run)

    def test_honest_missing_public_calibration_stops_before_fitness(self):
        fixture=Fixture(self.root,1);fixture.stop_first('calibrate')
        result=audit.verify(fixture.protocol,fixture.run)
        self.assertFalse(result['complete']);self.assertEqual(result['completedGenerations'],0)

    def test_mac_cannot_claim_full_fitness_recomputation(self):
        fixture=Fixture(self.root);terminal=p.read(fixture.run/'terminal.json');terminal['host']='MAC'
        put(fixture.run/'terminal.json',terminal)
        with self.assertRaisesRegex(ValueError,'sole Desktop'):audit.verify(fixture.protocol,fixture.run)

    def test_shortened_candidate_is_rejected(self):
        fixture=Fixture(self.root);terminal=p.read(fixture.run/'terminal.json');terminal['status']='completed'
        put(fixture.run/'terminal.json',terminal)
        with self.assertRaisesRegex(ValueError,'all16'):audit.verify(fixture.protocol,fixture.run)

    def test_changed_center_subject_fails_before_fitness_recomputation(self):
        fixture=Fixture(self.root);path=Path(fixture.rows[0]['outputCenter']['path']);path.write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'subject changed'):audit.verify(fixture.protocol,fixture.run)

    def test_first_center_cannot_start_from_a_previous_fit(self):
        fixture=Fixture(self.root);row=copy.deepcopy(fixture.rows[0]);row['inputCenter']=fixture.rows[0]['outputCenter']
        put(fixture.run/'generations/generation-1/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'Materialization must consume'):audit.verify(fixture.protocol,fixture.run)

    def test_later_mean_cannot_skip_its_immediate_predecessor(self):
        fixture=Fixture(self.root);row=copy.deepcopy(fixture.rows[1]);row['inputCenter']=fixture.rows[0]['inputCenter']
        put(fixture.run/'generations/generation-2/receipt.json',row)
        with self.assertRaisesRegex(ValueError,'Materialization must consume'):audit.verify(fixture.protocol,fixture.run)

    def test_false_resource_stop_is_rejected(self):
        fixture=Fixture(self.root);path=fixture.run/'generations/generation-2/operational-prefix.json';row=p.read(path)
        row['passed']=True;put(path,row)
        with self.assertRaisesRegex(ValueError,'resource gate must reproduce'):audit.verify(fixture.protocol,fixture.run)

    def test_retention_cannot_depart_from_the_original_host_censuses(self):
        fixture=Fixture(self.root);path=fixture.run/'generations/generation-2/operational-prefix.json';row=p.read(path)
        row['retention']['MAC']['sharedBaseBytes']=1
        changed=p.prefix_gate(row['remainingSeconds'],row['generationSeconds'],row['retention'])
        row.update(changed);put(path,row,True)
        with self.assertRaisesRegex(ValueError,'both sealed censuses'):audit.verify(fixture.protocol,fixture.run)

    def test_prefix_adjustment_must_equal_actual_retained_file_size(self):
        fixture=Fixture(self.root);path=fixture.run/'generations/generation-2/operational-prefix.json';row=p.read(path)
        row['censusAdjustmentBytes']+=1;put(path,row,True)
        with self.assertRaisesRegex(ValueError,'exact prefix receipt byte size'):audit.verify(fixture.protocol,fixture.run)

    def test_unregistered_future_generation_is_rejected(self):
        fixture=Fixture(self.root);(fixture.run/'generations/generation-3').mkdir()
        with self.assertRaisesRegex(ValueError,'finite completed/stopped prefix'):audit.verify(fixture.protocol,fixture.run)

    def test_incidental_finder_metadata_does_not_change_numeric_inventory(self):
        fixture=Fixture(self.root);(fixture.run/'generations/.DS_Store').write_bytes(b'finder')
        (fixture.run/'generations/generation-1/update/.DS_Store').write_bytes(b'finder')
        self.assertTrue(audit.verify(fixture.protocol,fixture.run)['passed'])

    def test_extra_real_numeric_artifact_is_rejected(self):
        fixture=Fixture(self.root);(fixture.run/'generations/generation-1/update/extra.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'inventory must be exact'):audit.verify(fixture.protocol,fixture.run)

    def test_failed_science_is_not_an_honest_operational_stop(self):
        fixture=Fixture(self.root);row=p.read(fixture.run/'terminal.json');row['status']='failed';put(fixture.run/'terminal.json',row)
        with self.assertRaisesRegex(ValueError,'Failed or expired'):audit.verify(fixture.protocol,fixture.run)

    def test_complete_numeric_source_imports_no_tensor_backend(self):
        import sys
        self.assertNotIn('torch',sys.modules);self.assertNotIn('numpy',sys.modules)


if __name__=='__main__':unittest.main()
