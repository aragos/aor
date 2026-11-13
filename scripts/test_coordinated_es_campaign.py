"""Finite original-worker transitions with constructed artifacts and no native/numeric execution."""
from contextlib import ExitStack
import copy
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import coordinated_es_campaign as c
import coordinated_es_plan as p
import coordinated_es_storage as s
from test_coordinated_es_plan import protocol


class SequenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name);self.root=self.base/'run';self.root.mkdir()
        self.plan=p.Plan.from_record(protocol());self.protocol=self.base/'protocol.json'
        self.protocol.write_bytes(self.plan.wire+b'\n');self.events=[];self.stop_at=None
        self.prefix_pass=True
        original=self.write(self.base/'inputs/bundle.json',{})
        initial=self.write(self.base/'inputs/initial.json',dict(generation=0))
        self.profile=dict(host='DESKTOP',originalBundle=original,projection=original,initialCenter=initial,
            directions=[self.write(self.base/f'inputs/directions-{g}.json',dict(generation=g)) for g in range(1,17)],
            scientificInputDirectories=[str(self.base/'inputs')])

    def write(self,path,row):return s.publish_json(path,row)

    def fake_context(self,directory,plan,protocol,profile,number,phase,*args):
        self.events.append(('publish',number,phase));Path(directory).mkdir(parents=True,exist_ok=True)
        return directory

    def fake_collect(self,plan,profile,root,number,chunk,phase,subjects):
        self.events.append(('native',number,phase,chunk))
        return {'constructedReceipt':number,'phase':phase,'chunk':chunk}

    def fake_numeric(self,profile,verb,directory,fields):
        directory=Path(directory);directory.mkdir(parents=True)
        number=int(directory.parent.name.split('-')[-1]);self.events.append(('numeric',number,verb))
        if self.stop_at==(number,verb):
            reason='calibration-movement-bounds' if verb=='calibrate' else 'zero-gradient'
            receipt=self.write(directory/'stopped.json',dict(reason=reason))
            return dict(complete=False,directory=directory,receipt=receipt,reason=reason)
        if verb=='materialize':
            self.write(directory/'materialization.json',dict(generation=number))
            if number>1:self.write(directory/'population.json',dict(generation=number))
        elif verb=='calibrate':
            self.write(directory/'calibration.json',dict(complete=True))
            self.write(directory/'population.json',dict(generation=1))
        elif verb=='update':
            self.write(directory/'center.json',dict(generation=number))
            self.write(directory/'update.json',dict(generation=number))
        elif verb=='diagnose':self.write(directory/'movement.json',dict(changed=0,eligibilityGate=False))
        receipt=self.write(directory/'finished.json',dict(complete=True))
        return dict(complete=True,directory=directory,receipt=receipt)

    def fake_population(self,materialization,descriptor,calibration,output):
        return self.write(Path(output)/'population.json',p.read(descriptor['path']))

    def fake_fitness(self,*args):
        number=p.read(args[2]['path'])['generation'];self.events.append(('fitness',number))
        return dict(generation=number,constructed=True)

    def execute(self):
        historical=self.write(self.root/'historical/historical-admission.json',dict(constructed=True))
        def finish(plan,profile,protocol,root,center,*args):
            self.events.append(('screen',p.read(center['path'])['generation']))
            return dict(status='completed',completedGenerations=16)
        screen=SimpleNamespace(historical=lambda *args:historical,finish=finish)
        with ExitStack() as stack:
            stack.enter_context(patch.dict('sys.modules',{'coordinated_es_screen':screen}))
            for name,value in (('context',self.fake_context),('read_context',lambda *a:{}),
                ('publish_context',lambda *a:None),('collect',self.fake_collect),
                ('wait_receipts',lambda *a:[]),('prefix',lambda *a:{'passed':self.prefix_pass})):
                stack.enter_context(patch.object(c,name,value))
            stack.enter_context(patch.object(c.execution,'live',lambda *a:None))
            stack.enter_context(patch.object(c.numeric,'invoke',self.fake_numeric))
            stack.enter_context(patch.object(c.numeric,'population_publication',self.fake_population))
            stack.enter_context(patch.object(c.storage,'public_panel',lambda *a:({'worlds':[]},[])))
            stack.enter_context(patch.object(c.storage,'fitness',self.fake_fitness))
            stack.enter_context(patch.object(c.storage,'retention_record',lambda *a:{}))
            return c.desktop(self.plan,self.profile,self.protocol,self.root)

    def test_all16_updates_precede_the_only_final_mean_screen(self):
        result=self.execute();self.assertEqual(result['completedGenerations'],16)
        self.assertEqual([event[1] for event in self.events if event[0]=='numeric' and event[2]=='update'],list(range(1,17)))
        self.assertEqual([event for event in self.events if event[0]=='screen'],[('screen',16)])

    def test_one_calibration_precedes_all_semantic_fitness_and_population_games(self):
        self.execute();seal=self.events.index(('numeric',1,'calibrate'))
        self.assertEqual(sum(event[0]=='numeric' and event[2]=='calibrate' for event in self.events),1)
        self.assertTrue(all(index>seal for index,event in enumerate(self.events) if
            event[0]=='fitness' or (event[0]=='native' and event[2]=='population')))

    def test_calibration_failure_never_plays_mutants_or_opens_fitness(self):
        self.stop_at=(1,'calibrate');result=self.execute()
        self.assertEqual(result['reason'],'calibration-failed')
        self.assertFalse(any(event[0] in ('fitness','screen') or
            (event[0]=='native' and event[2]=='population') for event in self.events))

    def test_zero_gradient_has_no_next_generation_or_shortened_nominee(self):
        self.stop_at=(3,'update');result=self.execute()
        self.assertEqual((result['reason'],result['completedGenerations']),('zero-gradient',2))
        self.assertFalse(any(event[0]=='screen' or event[1]>3 for event in self.events))

    def test_two_generation_resource_stop_cannot_become_a_candidate(self):
        self.prefix_pass=False;result=self.execute()
        self.assertEqual((result['reason'],result['completedGenerations']),('prefix-resource-gate',2))
        self.assertFalse(any(event[0]=='screen' or event[1]>2 for event in self.events))

    def test_zero_live_movement_never_stops_the_fixed_sequence(self):
        result=self.execute();self.assertEqual(result['status'],'completed')
        self.assertEqual(sum(event[0]=='numeric' and event[2]=='diagnose' for event in self.events),16)


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name).resolve();self.plan=p.Plan.from_record(protocol())
        self.root=self.base/'original/output'
        request=s.publish_json(self.base/'original/request.json',dict(
            projectionSeed=self.plan.record['projectionSeed'],
            directionSeeds=[row['directionSeed'] for row in self.plan.record['generations']],
            scopeSha256=self.plan.record['original']['scopeSha256'],
            originalBundle=dict(sha256=self.plan.record['original']['bundleSha256'])))
        projection=s.publish_json(self.root/'projection.json',dict(constructed='projection'))
        center=s.publish_json(self.root/'initial-center.json',dict(constructed='center'))
        directions=[]
        for index in range(1,17):
            directions.append(s.publish_json(self.root/f'directions/generation-{index:02d}.json',dict(generation=index)))
            s.publish_json(self.root/f'directions/generation-{index:02d}-generation.json',dict(seed=101000+index))
        s.publish_json(self.root/'started.json',dict(request=request))
        finished=s.publish_json(self.root/'finished.json',dict(kind=p.PREFIX+'-numeric-complete-1',
            complete=True,passed=True,verb='prepare',request=request,
            artifacts={name:row['sha256'] for name,row in s.inventory(self.root).items()},
            outputs=dict(projection='projection.json',center='initial-center.json')))
        self.profile=dict(originalBundle=dict(sha256=self.plan.record['original']['bundleSha256']),
            projection=projection,initialCenter=center,directions=directions,
            numericalPreparation=dict(request=request,finished=finished))

    def relocated(self):
        source=self.base/'original';target=self.base/'other-host'
        shutil.copytree(source,target)
        def move(value):
            if isinstance(value,list):return [move(item) for item in value]
            if isinstance(value,dict):
                if 'path' in value:
                    return dict(value,path=str(target/Path(value['path']).relative_to(source)),originalPath=value['path'])
                return {key:move(item) for key,item in value.items()}
            return value
        return move(self.profile)

    def test_original_completed_preparation_passes_without_relocation(self):
        c.check_preparation(self.plan,self.profile)

    def test_exact_copied_preparation_passes_without_original_host_files(self):
        profile=self.relocated();shutil.rmtree(self.base/'original')
        c.check_preparation(self.plan,profile)

    def test_relocated_request_requires_its_actual_original_path(self):
        profile=self.relocated();profile['numericalPreparation']['request']['originalPath']='/wrong/request.json'
        with self.assertRaisesRegex(ValueError,'reserved streams'):c.check_preparation(self.plan,profile)

    def test_changed_request_bytes_cannot_use_original_completion(self):
        profile=self.relocated();item=profile['numericalPreparation']['request'];path=Path(item['path'])
        path.write_bytes(path.read_bytes()+b' ');item['sha256']=p.file_sha(path)
        with self.assertRaisesRegex(ValueError,'reserved streams'):c.check_preparation(self.plan,profile)

    def test_updated_profile_hash_cannot_replace_a_prepared_output(self):
        profile=self.relocated();path=Path(profile['projection']['path']);path.write_bytes(b'{}')
        profile['projection']['sha256']=p.file_sha(path)
        with self.assertRaisesRegex(ValueError,'completed preparation output'):c.check_preparation(self.plan,profile)

    def test_reordered_directions_are_rejected(self):
        profile=copy.deepcopy(self.profile);profile['directions'][0:2]=reversed(profile['directions'][0:2])
        with self.assertRaisesRegex(ValueError,'completed preparation output'):c.check_preparation(self.plan,profile)

    def test_missing_preparation_generation_receipt_is_rejected(self):
        (self.root/'directions/generation-16-generation.json').unlink()
        with self.assertRaisesRegex(ValueError,'completed preparation output'):c.check_preparation(self.plan,self.profile)


if __name__=='__main__':unittest.main()
