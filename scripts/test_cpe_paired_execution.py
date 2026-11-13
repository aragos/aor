from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import cpe_paired_campaign as campaign
import cpe_paired_execution as execution
import cpe_paired_campaign_plan as p
import cpe_paired_storage as storage
import cpe_paired_process as process
from test_cpe_paired_campaign_plan import fixture


class ExecutionTests(unittest.TestCase):
    def test_cpe_child_uses_explicit_cpe_lifecycle_without_changing_legacy_defaults(self):
        with patch.object(process.shared,'live',side_effect=ValueError('closed')) as live,\
             patch.object(process.shared.subprocess,'Popen') as spawn:
            with self.assertRaisesRegex(ValueError,'closed'):
                process.run_child({},['constructed-native'],'unused')
            live.assert_called_once_with({},mailbox=process.handoff)
            spawn.assert_not_called()

    def package(self, root, host):
        root.mkdir();protocol=root/'protocol.json';profile=root/'profile.json';clock=root/'clock.json'
        record=fixture();storage.publish_json(protocol,record)
        storage.publish_json(profile,dict(kind='cpe-paired-host-1',host=host,
            protocolSha256=p.file_sha(protocol),files={},python={'executable':'/constructed/python'},
            **record['hosts'][host]))
        storage.publish_json(clock,dict(kind='cpe-paired-clock-1',protocolSha256=p.file_sha(protocol),
            startedEpoch=100.,deadlineEpoch=64900.))
        return protocol,profile,clock

    def test_both_hosts_use_existing_one_attempt_controller_with_same_original_clock(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve()
            for host in p.HOSTS:
                protocol,profile,clock=self.package(root/host,host)
                spec=execution.specification(protocol,profile,clock)
                execution.controller.validate_spec(spec,root/host/'run')
                self.assertEqual(spec['limits']['absoluteDeadlineEpoch'],64900.)
                self.assertEqual(spec['limits']['maximumSeconds'],64800)
                self.assertEqual(spec['limits']['maximumDataBytes'],24*1024**3)
                self.assertEqual(len(spec['stages']),1)
                self.assertEqual(spec['stages'][0]['maximumAttempts'],1)
                self.assertEqual(spec['stages'][0]['id'],'cpe-paired')
                self.assertEqual(Path(spec['stages'][0]['command'][4]).name,'cpe_paired_execution.py')

    def test_old_family_clock_cannot_enter_cpe_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            protocol,profile,clock=self.package(Path(directory)/'host','MAC')
            record=p.read(clock);record['kind']='token-paired-clock-1';clock.write_bytes(p.canonical(record))
            with self.assertRaisesRegex(ValueError,'shared protocol'):
                execution.specification(protocol,profile,clock)

    def test_one_cohort_exposure_failure_cannot_start_any_head_fit(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/'protocol.json';record=fixture()
            storage.publish_json(protocol,record);plan=p.Plan.from_record(record)
            actor=root/'bundle.json';storage.publish_json(actor,{'constructed':True});calls=[]
            def collect(_plan,_profile,_root,iteration,head,chunk,_current):
                calls.append((iteration,head,chunk));path=root/f'{head}-{chunk}.json'
                storage.publish_json(path,{});return path,path
            with patch.dict(sys.modules,{'cpe_paired_screen':SimpleNamespace()}),\
                 patch.object(campaign.time,'time',return_value=1.),patch.object(campaign,'collect',collect),\
                 patch.object(campaign,'wait_chunks',return_value=[]),\
                 patch.object(storage,'cohort_corpus',return_value={'constructed':True}),\
                 patch.object(storage,'iteration_corpus',return_value={'exposurePassed':False}),\
                 patch.object(campaign.execution,'run_child') as child:
                result=campaign.desktop(plan,{'originalBundle':p.subject(actor)},protocol,root/'profile',
                    p.Clock(0.,64800.),root)
            self.assertEqual(calls,[(1,head,chunk) for head in p.HEADS for chunk in p.COLLECTION_CHUNKS['DESKTOP']])
            self.assertEqual(result,dict(status='stopped',reason='public-exposure-gate',completedIterations=0))
            child.assert_not_called()

    def test_absent_phase_shard_does_not_become_recomputed_work(self):
        with tempfile.TemporaryDirectory() as directory:
            now=[64799.]
            with self.assertRaisesRegex(ValueError,'expired'):
                campaign.wait_chunks(directory,p.Clock(0.,64800.),1,head='PURCHASE',now=lambda:now[0],
                    sleep=lambda seconds:now.__setitem__(0,now[0]+seconds))
            self.assertEqual(list(Path(directory).iterdir()),[])

    def test_operational_stop_remains_rejection_without_effects(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            def stop(*_args):
                storage.publish_json(root/'study/terminal.json',{'status':'stopped'})
                return {'status':'stopped'}
            with patch.object(campaign,'run',side_effect=stop):
                result=execution.stage(root/'protocol',root/'profile',root/'clock',root)
            self.assertEqual(result['status'],'rejected')
            self.assertEqual(result['artifacts'],{'study/terminal.json':p.file_sha(root/'study/terminal.json')})

    def test_all_three_cohorts_share_one_current_bundle_until_atomic_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/'protocol.json';record=fixture()
            storage.publish_json(protocol,record);plan=p.Plan.from_record(record)
            initial=storage.publish_json(root/'initial.json',{'constructedIteration':0})
            events=[];inputs=[];outputs={0:initial['sha256']}
            def collect(_plan,_profile,_root,iteration,head,chunk,current):
                inputs.append((iteration,head,chunk,current['sha256']))
                path=root/f'{iteration}-{head}-{chunk}.json';storage.publish_json(path,{})
                return path,path
            def child(_profile,command,_log):
                mode=command[5];events.append(mode)
                target=Path(command[command.index('--output')+1])
                if mode=='effects':
                    storage.publish_json(target/'validation-effect.json',{'constructed':True});return
                iteration=int(target.parent.name.split('-')[1])
                outputs[iteration]=storage.publish_json(target/'bundle.json',{'constructedIteration':iteration})['sha256']
                seal=storage.publish_json(target.parent/'validation.json',{'constructed':True})
                storage.publish_json(target.parent/'numeric-complete.json',dict(iteration=iteration,
                    complete=True,protocolSha256=p.file_sha(protocol),operationalPassed=True,
                    validationProbabilitySeal=seal))
            def publish(*args):
                events.append('publish');Path(args[-1]).mkdir(exist_ok=True)
            def historical(*args):
                events.append('historical')
                return storage.publish_json(root/'historical.json',{'constructed':True})
            def screen_chunk(_plan,_profile,_root,chunk,*_):
                return storage.publish_json(root/f'screen-{chunk}.json',{'constructed':True})
            screen=SimpleNamespace(historical=historical,collect=screen_chunk,
                complete=lambda *_:storage.publish_json(root/'screen-complete.json',{'constructed':True}))
            with patch.dict(sys.modules,{'cpe_paired_screen':screen}),\
                 patch.object(campaign.time,'time',return_value=1.),patch.object(campaign,'collect',collect),\
                 patch.object(campaign,'wait_chunks',return_value=[]),\
                 patch.object(storage,'cohort_corpus',return_value={'constructed':True}),\
                 patch.object(storage,'iteration_corpus',return_value={'exposurePassed':True,'bundleScopeSha256':'b'*64}),\
                 patch.object(campaign.execution,'run_child',side_effect=child),\
                 patch.object(campaign.admission,'publish_iteration',side_effect=publish),\
                 patch.object(campaign.admission,'publish_final',return_value={'constructed':True}),\
                 patch.object(campaign,'publish_next'):
                result=campaign.desktop(plan,{'originalBundle':initial,'python':{'executable':'/constructed/python'}},
                    protocol,root/'profile',p.Clock(0.,64800.),root)
            self.assertEqual(events,['update','publish','update','publish','effects','historical'])
            self.assertEqual(inputs,[(iteration,head,chunk,outputs[iteration-1])
                for iteration in (1,2) for head in p.HEADS for chunk in p.COLLECTION_CHUNKS['DESKTOP']])
            self.assertEqual(result['status'],'completed')

    def test_any_atomic_actor_failure_prevents_publication_and_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protocol=root/'protocol.json';storage.publish_json(protocol,fixture())
            plan=p.Plan.from_record(fixture());initial=storage.publish_json(root/'initial.json',{})
            receipt=storage.publish_json(root/'receipt.json',{})
            def child(_profile,command,_log):
                target=Path(command[command.index('--output')+1])
                storage.publish_json(target.parent/'numeric-complete.json',dict(iteration=1,complete=True,
                    protocolSha256=p.file_sha(protocol),operationalPassed=False))
            with patch.dict(sys.modules,{'cpe_paired_screen':SimpleNamespace()}),\
                 patch.object(campaign.time,'time',return_value=1.),\
                 patch.object(campaign,'collect',return_value=(Path(receipt['path']),Path(receipt['path']))),\
                 patch.object(campaign,'wait_chunks',return_value=[]),\
                 patch.object(storage,'cohort_corpus',return_value={'constructed':True}),\
                 patch.object(storage,'iteration_corpus',return_value={'exposurePassed':True}),\
                 patch.object(campaign.execution,'run_child',side_effect=child) as numerical,\
                 patch.object(campaign.admission,'publish_iteration') as publication:
                result=campaign.desktop(plan,{'originalBundle':initial,'python':{'executable':'/constructed/python'}},
                    protocol,root/'profile',p.Clock(0.,64800.),root)
            self.assertEqual(result,dict(status='stopped',reason='no-accepted-actor-change',completedIterations=0))
            self.assertEqual(numerical.call_count,1);publication.assert_not_called()


if __name__=='__main__':unittest.main()
