"""Constructed transport/publication boundaries; no host, network or numerical child is started."""
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_es_continuation as c
import coordinated_es_numeric_stage as old
import coordinated_win_handoff as h
import coordinated_win_numeric_stage as n
import coordinated_win_plan as p


def put(root,name,value):return old.publish_numeric(Path(root)/name,value)


class HandoffTests(unittest.TestCase):
    def test_exact_original_fixed_slots_with_new_receipt_kind(self):
        tags=[h.tag(g,chunk,phase=phase) for g in range(1,17) for phase in ('controls','population')
            for chunk in p.GENERATION_CHUNKS['MAC']]
        tags += [h.tag(None,chunk,True) for chunk in p.SCREEN_CHUNKS['MAC']]
        self.assertEqual(len(tags),102);self.assertEqual(len(set(tags)),102)
        for tag in tags:self.assertEqual(h.allowed_tag(tag),tag)
        self.assertEqual(h.receipt_tag(dict(kind=p.PREFIX+'-chunk-receipt-1',generation=1,chunk=5,phase='population')),
            'population-1-chunk-5')

    def test_inherited_calibration_cannot_open_a_new_calibration_stop(self):
        value=dict(kind=p.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',reason='calibration-failed',completedGenerations=0)
        with self.assertRaisesRegex(ValueError,'arithmetic/resource'):h.PeerStopped(dict(terminal=value))

    def test_resource_stop_only_after_two_complete_generations(self):
        value=dict(kind=p.PREFIX+'-terminal-1',host='DESKTOP',status='stopped',reason='prefix-resource-gate',completedGenerations=3)
        with self.assertRaisesRegex(ValueError,'two-complete'):h.PeerStopped(dict(terminal=value))

    def test_typed_stop_propagates_through_existing_retention_method(self):
        profile=dict(address=h.ADDRESS,root='/constructed',script='/constructed/remote.py',python='/constructed/python')
        with tempfile.TemporaryDirectory() as folder:
            now=time.time();outbound=h.Outbound(profile,p.Clock(now,now+86400),folder)
            response=dict(status='terminal',terminal=dict(kind=p.PREFIX+'-terminal-1',host='DESKTOP',
                status='stopped',reason='zero-gradient',completedGenerations=1))
            with patch.object(outbound,'remote',return_value=response),self.assertRaises(h.PeerStopped):
                outbound.push_retention({})


class StageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def profile(self):
        owner=put(self.root,'owner.py',dict(constructed=True))
        return dict(numericalSourceOwners={owner['path']:owner['sha256']},
            numerical=put(self.root,'environment.json',dict(constructed=True)),
            python=dict(executable='/constructed/python'),_clock=p.Clock(100.,86500.))

    def test_fixed_numeric_request_uses_new_kind_without_new_deadline(self):
        value=n.request(self.profile(),'update',dict(protocol=dict(path='/protocol',sha256='a'*64)))
        self.assertEqual(value['kind'],'coordinated-win-update-request-1')
        self.assertEqual(value['deadlineEpoch'],86500.)

    def test_calibration_verb_rejected_before_child_execution(self):
        with patch.object(n,'run_child') as run,self.assertRaisesRegex(ValueError,'fixed scientific'):
            n.invoke(self.profile(),'calibrate',self.root/'attempt',{})
        run.assert_not_called()

    def test_shared_invocation_selects_exact_new_module_and_receipt(self):
        profile=self.profile();protocol=put(self.root,'protocol.json',dict(constructed=True));observed=[]
        def child(_profile,command,_log):
            observed.append(command)
            request_path=Path(command[command.index('--request')+1]);value=p.read(request_path)
            output=Path(command[command.index('--output')+1]);output.mkdir()
            result=put(output,'update.json',dict(constructed=True))
            put(output,'finished.json',dict(kind='coordinated-win-numeric-complete-1',passed=True,complete=True,
                verb='update',request=p.subject(request_path),protocolSha256=protocol['sha256'],sourceOwners=value['sourceOwners'],
                artifacts={'update.json':result['sha256']},inputPins={protocol['path']:protocol['sha256']},outputs={'update':'update.json'}))
        with patch.object(n,'run_child',side_effect=child):
            result=n.invoke(profile,'update',self.root/'attempt',dict(protocol=protocol))
        self.assertTrue(result['complete'])
        self.assertEqual(observed[0][1:4],['-E','-s','-B'])
        self.assertEqual(Path(observed[0][4]).name,'coordinated_win_numeric.py')

    def publication(self):
        members=[];material=self.root/'materialization'
        for index in range(32):
            role=f"{'PLUS' if index%2==0 else 'MINUS'}-{index//2:02d}"
            name=f'members/{role}/bundle.json';subject=put(material,name,dict(constructedMember=index))
            members.append(dict(path=name,bundleSha256=subject['sha256']))
        calibration=self.root/'preparation/calibration'
        for name in ('origin/calibration.json','origin/protocol.json','origin/gap-EXPANSION.json','origin/gap-TOKEN.json'):
            put(calibration,name,dict(constructed=name))
        seal=put(calibration,'inherited-calibration.json',dict(constructed=True))
        descriptor=put(material,'population.json',dict(kind=c.POPULATION_KIND,members=members,
            inheritedCalibrationSha256=seal['sha256']))
        put(self.root/'preparation','large-shared-projection.json',dict(constructed=True))
        return material,descriptor,seal

    def test_only_small_exact_calibration_tree_is_copied(self):
        material,descriptor,seal=self.publication()
        output=self.root/'portable';published=n.population_publication(material,descriptor,seal,output)
        self.assertEqual(published['sha256'],descriptor['sha256'])
        self.assertEqual(len(list((output/'calibration').rglob('*.json'))),5)
        self.assertFalse((output/'large-shared-projection.json').exists())
        self.assertEqual(len(list((output/'members').rglob('bundle.json'))),32)

    def test_extra_calibration_member_rejected_before_copy(self):
        material,descriptor,seal=self.publication()
        put(Path(seal['path']).parent,'extra.json',dict(constructed=True))
        with self.assertRaisesRegex(ValueError,'five original sealed'):
            n.population_publication(material,descriptor,seal,self.root/'portable')
        self.assertFalse((self.root/'portable').exists())


if __name__=='__main__':unittest.main()
