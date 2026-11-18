"""Fixed ordering and unchanged original-clock lifecycle; no child/game/model execution.

These tests deliberately substitute native/numerical work. The retained joined fixture separately
proves the real archive/fitness/numerical boundary and is not replaced by this ordering coverage.
"""
from contextlib import ExitStack
import os
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import coordinated_es_campaign as base
import coordinated_es_storage as storage
import coordinated_win_campaign as campaign
import coordinated_win_plan as p
from test_coordinated_win_plan import fixture


class SequenceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.base=Path(self.temp.name).resolve();self.root=self.base/'study';self.root.mkdir()
        _,record=fixture();self.plan=p.Plan.from_record(record)
        self.protocol=self.base/'protocol.json';self.protocol.write_bytes(self.plan.wire+b'\n')
        self.events=[];self.stop_step=None;self.prefix_pass=True
        original=self.put(self.base/'inputs/original.json',{})
        self.profile=dict(host='DESKTOP',originalBundle=original,projection=original,warmStart=original,
            inheritedCalibration=self.put(self.base/'inputs/inherited.json',dict(constructedInherited=True)),
            initialCenter=self.put(self.base/'inputs/center.json',dict(step=0)),
            directions=[self.put(self.base/f'inputs/directions-{g}.json',dict(step=g)) for g in range(1,17)],
            scientificInputDirectories=[str(self.base/'inputs')],diagnosticPanel=original)

    def put(self,path,value):return storage.publish_json(path,value)

    def numeric(self,profile,verb,directory,fields):
        directory=Path(directory);directory.mkdir(parents=True)
        number=int(directory.parent.name.split('-')[-1]);self.events.append(('numeric',number,verb,fields))
        if verb=='update' and number==self.stop_step:
            return dict(complete=False,reason='zero-gradient',receipt=self.put(directory/'stopped.json',{}))
        if verb=='materialize':self.put(directory/'population.json',dict(step=number))
        if verb=='update':
            self.put(directory/'center.json',dict(step=number));self.put(directory/'update.json',dict(step=number))
        return dict(complete=True,receipt=self.put(directory/'finished.json',dict(complete=True)))

    def create(self,directory,plan,protocol,profile,number,phase,center,previous,*rest):
        self.events.append(('context',number,phase,center,previous))
        Path(directory).mkdir(parents=True,exist_ok=True);return directory

    def collect(self,plan,profile,root,number,chunk,phase,subjects):
        self.events.append(('native',number,phase,chunk));return dict(constructedReceipt=chunk)

    def population(self,materialization,descriptor,calibration,output):
        self.assertEqual(calibration,self.profile['inheritedCalibration'])
        return self.put(Path(output)/'population.json',p.read(descriptor['path']))

    def fitness(self,*args):
        number=p.read(args[2]['path'])['step'];self.events.append(('fitness',number))
        return dict(constructedFitness=True,step=number)

    def execute(self):
        historical=self.put(self.root/'historical/gate.json',dict(constructedHistory=True))
        def finish(plan,profile,protocol,root,center,*args):
            self.events.append(('screen',p.read(center['path'])['step']))
            return dict(status='completed',completedGenerations=16)
        screen=SimpleNamespace(historical=lambda *a:historical,finish=finish)
        with ExitStack() as stack:
            stack.enter_context(patch.dict('sys.modules',{campaign.SCREEN_MODULE:screen}))
            for module,name,value in (
                (campaign.contexts,'create',self.create),(campaign.contexts,'publish',lambda *a:None),
                (campaign,'read_context',lambda *a:{}),(campaign,'collect',self.collect),
                (campaign,'wait_receipts',lambda *a:[]),(campaign,'prefix',lambda *a:{'passed':self.prefix_pass}),
                (campaign.execution,'live',lambda *a:None),(campaign.numeric,'invoke',self.numeric),
                (campaign.numeric,'population_publication',self.population),(campaign.wins,'fitness',self.fitness),
                (campaign.storage,'retention_record',lambda *a:{})):
                stack.enter_context(patch.object(module,name,value))
            return campaign.desktop(self.plan,self.profile,self.protocol,self.root)

    def test_inherited_calibration_is_fixed_for_all16_updates_without_calibration_verb(self):
        result=self.execute();calls=[row for row in self.events if row[0]=='numeric']
        self.assertEqual(result['completedGenerations'],16)
        self.assertNotIn('calibrate',[row[2] for row in calls])
        self.assertTrue(all(row[3]['inheritedCalibration']==self.profile['inheritedCalibration'] for row in calls))

    def test_all16_win_updates_precede_the_only_final16_screen(self):
        self.execute()
        self.assertEqual([row[1] for row in self.events if row[:1]==('numeric',) and row[2]=='update'],list(range(1,17)))
        self.assertEqual([row for row in self.events if row[0]=='screen'],[('screen',16)])

    def test_current_center_and_immediate_update_feed_both_next_phases(self):
        self.execute()
        for row in self.events:
            if row[0]!='context':continue
            number=row[1]
            self.assertEqual(p.read(row[3]['path'])['step'],number-1)
            self.assertEqual(None if row[4] is None else p.read(row[4]['path'])['step'],None if number==1 else number-1)

    def test_zero_gradient_keeps_no_shortened_nominee_or_next_generation(self):
        self.stop_step=3;result=self.execute()
        self.assertEqual((result['status'],result['completedGenerations']),('stopped',2))
        self.assertFalse(any(row[0]=='screen' or row[1]>3 for row in self.events))

    def test_two_complete_generation_prefix_stop_has_no_screen(self):
        self.prefix_pass=False;result=self.execute()
        self.assertEqual(result['reason'],'prefix-resource-gate')
        self.assertEqual(result['completedGenerations'],2)
        self.assertFalse(any(row[0]=='screen' or row[1]>2 for row in self.events))

    def test_optional_diagnostic_absence_does_not_change_learning_or_finality(self):
        self.profile['diagnosticPanel']=None;result=self.execute()
        self.assertEqual(result['status'],'completed')
        self.assertFalse(any(row[0]=='numeric' and row[2]=='diagnose' for row in self.events))
        self.assertIsNone(p.read(self.root/'generations/generation-16/receipt.json')['diagnostic'])


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();_,record=fixture();self.plan=p.Plan.from_record(record)
        self.protocol=self.root/'protocol.json';self.protocol.write_bytes(self.plan.wire+b'\n')
        self.launch=dict(kind=p.PREFIX+'-clock-1',protocolSha256=p.file_sha(self.protocol),
            startedEpoch=time.time()-1,deadlineEpoch=time.time()-1+p.SCIENCE_SECONDS)
        # Both values must differ by exactly the declared duration.
        self.launch['deadlineEpoch']=self.launch['startedEpoch']+p.SCIENCE_SECONDS
        self.clock=self.root/'clock.json';storage.publish_json(self.clock,self.launch)
        self.profile=self.root/'profile.json';storage.publish_json(self.profile,dict(constructedProfile=True))

    def execute(self,body,output=None):
        env={key:str(self.launch['deadlineEpoch']) for key in
            ('AOR_CAMPAIGN_DEADLINE_EPOCH','AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH')}
        with patch.object(campaign,'check_profile',return_value=(self.plan,dict(host='DESKTOP'))), \
                patch.object(campaign,'desktop',side_effect=body),patch.dict(os.environ,env):
            return campaign.run(self.protocol,self.profile,self.clock,output or self.root/'study')

    def test_owner_is_persisted_before_clock_visibility(self):
        observed=[];publish=storage.publish_json
        def record(path,value):
            result=publish(path,value)
            if Path(path).name=='clock.json':observed.append((Path(path).parent/'worker-owner.json').is_file())
            return result
        with patch.object(base.storage,'publish_json',side_effect=record):
            self.execute(lambda *a:dict(status='completed',completedGenerations=16))
        self.assertEqual(observed,[True])
        self.assertEqual(p.read(self.root/'study/worker-owner.json')['profile'],p.subject(self.profile))

    def test_second_start_never_reenters_scientific_body(self):
        self.execute(lambda *a:dict(status='completed',completedGenerations=16))
        with self.assertRaisesRegex(ValueError,'one original attempt'):
            self.execute(lambda *a:self.fail('A second attempt ran.'))

    def test_failure_is_terminal_without_replacement_or_clock_renewal(self):
        def fail(*args):raise ValueError('constructed scientific failure')
        with self.assertRaisesRegex(ValueError,'constructed scientific failure'):self.execute(fail)
        terminal=p.read(self.root/'study/terminal.json')
        self.assertEqual(terminal['status'],'failed');self.assertEqual(terminal['clock'],self.launch)


if __name__=='__main__':unittest.main()
