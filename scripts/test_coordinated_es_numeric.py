"""Temporary constructed artifacts only; no admitted actors, trajectories or environment scan."""
import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_es_math as es
import coordinated_es_numeric as n
import coordinated_es_projection as p
from test_coordinated_es_math import fixture
from test_coordinated_es_projection import original_actors, observation


def put(root,name,value,raw=False):
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
    content=value if raw else es.canonical(value);path.write_bytes(content)
    return dict(path=str(path),sha256=n.raw_sha(content))


def owners():
    return {str(Path(module.__file__).absolute()):n.raw_sha(Path(module.__file__).read_bytes())
        for module in (n,es,n.cal,p,n.environment,p.b,p.card_policy,p.expansion_mc,p.token_paired_policy)}


class RequestTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)

    def request(self,verb,**fields):
        value=dict(kind=f'coordinated-es-{verb}-request-1',sourceOwners=owners(),
            numerical=put(self.root,'environment.json',dict(constructed=True)),deadlineEpoch=time.time()+120,**fields)
        return put(self.root,'request.json',value)

    def update_request(self,zero=False):
        center,directions,population,fitness=fixture()
        protocol=put(self.root,'protocol.json',b'{"constructed":true}\n',raw=True)
        population['protocolSha256']=protocol['sha256'];fitness['populationSha256']=es.digest(population)
        if zero:
            for row in fitness['members']:row['rewards']=[0.]*256
        request=self.request('update',protocol=protocol,center=put(self.root,'center.json',center.record),
            directions=put(self.root,'directions.json',directions.record),
            population=put(self.root,'population.json',population),fitness=put(self.root,'fitness.json',fitness))
        return request

    def test_complete_update_seals_exact_raw_inputs_and_relative_artifact_inventory(self):
        request=self.update_request()
        with patch.object(n.environment,'verify') as verify:
            receipt=n.execute('update',request['path'],self.root/'output')
        verify.assert_called_once_with(dict(constructed=True))
        self.assertEqual(set(receipt['artifacts']),{'started.json','center.json','update.json'})
        self.assertEqual(receipt['execution'],dict(newGames=0,actualFits=0,optimizerSteps=0))
        self.assertEqual(json.loads((self.root/'output/center.json').read_bytes())['generation'],1)
        self.assertEqual(receipt['protocolSha256'],n.raw_sha((self.root/'protocol.json').read_bytes()))
        for name,digest in receipt['artifacts'].items():self.assertEqual(n.raw_sha((self.root/'output'/name).read_bytes()),digest)

    def test_zero_gradient_preserves_stop_without_next_center(self):
        request=self.update_request(zero=True)
        with patch.object(n.environment,'verify'),self.assertRaisesRegex(es.OperationalStop,'zero-gradient'):
            n.execute('update',request['path'],self.root/'output')
        receipt=json.loads((self.root/'output/stopped.json').read_bytes())
        self.assertEqual(receipt['reason'],'zero-gradient')
        self.assertEqual({path.name for path in (self.root/'output').iterdir()},{'started.json','stopped.json'})

    def test_cli_returns_normally_only_after_persisting_an_honest_stop(self):
        request=self.update_request(zero=True);output=io.StringIO()
        with patch.object(n.environment,'verify'),redirect_stdout(output):
            n.main(['update','--request',request['path'],'--output',str(self.root/'output')])
        result=json.loads(output.getvalue())
        self.assertEqual(result['status'],'stopped');self.assertEqual(result['reason'],'zero-gradient')
        self.assertEqual(result['stopped']['sha256'],n.raw_sha((self.root/'output/stopped.json').read_bytes()))

    def test_cli_does_not_convert_identity_failure_to_operational_stop(self):
        request=self.update_request();(self.root/'fitness.json').write_bytes(b'{}')
        with patch.object(n.environment,'verify'),self.assertRaisesRegex(ValueError,'input bytes'):
            n.main(['update','--request',request['path'],'--output',str(self.root/'output')])
        self.assertFalse((self.root/'output/stopped.json').exists())

    def test_existing_attempt_directory_cannot_be_reused(self):
        request=self.update_request();(self.root/'output').mkdir();marker=self.root/'output/original';marker.write_text('preserve')
        with patch.object(n.environment,'verify'),self.assertRaises(FileExistsError):
            n.execute('update',request['path'],self.root/'output')
        self.assertEqual(marker.read_text(),'preserve')

    def test_changed_fitness_bytes_fail_before_decode_or_update(self):
        request=self.update_request();(self.root/'fitness.json').write_text('{')
        with patch.object(n.environment,'verify'),patch.object(es,'update') as update,self.assertRaisesRegex(ValueError,'input bytes'):
            n.execute('update',request['path'],self.root/'output')
        update.assert_not_called()
        self.assertTrue((self.root/'output/failed.json').is_file())
        self.assertFalse((self.root/'output/center.json').exists())

    def test_extra_outcome_request_field_is_rejected(self):
        request=self.update_request();value=json.loads(Path(request['path']).read_bytes());value['winner']=0
        Path(request['path']).write_bytes(es.canonical(value))
        with patch.object(n.environment,'verify') as verify,self.assertRaisesRegex(ValueError,'unexpected'):
            n.execute('update',request['path'],self.root/'output')
        verify.assert_not_called()

    def test_later_input_rewrite_is_caught_before_finished(self):
        request=self.update_request();real=es.update
        def changed(*args):
            result=real(*args);(self.root/'fitness.json').write_bytes(b'{}');return result
        with patch.object(n.environment,'verify'),patch.object(es,'update',side_effect=changed),self.assertRaisesRegex(ValueError,'input bytes'):
            n.execute('update',request['path'],self.root/'output')
        self.assertFalse((self.root/'output/finished.json').exists())
        self.assertTrue((self.root/'output/failed.json').exists())

    def test_numerical_record_format_cannot_silently_change_raw_identity(self):
        subject=put(self.root,'record.json',b'{"n":1}\n',raw=True)
        with self.assertRaisesRegex(ValueError,'without LF'):n.Inputs().canonical(subject)

    def test_missing_imported_source_owner_rejects_before_environment_scan(self):
        request=self.update_request();value=json.loads(Path(request['path']).read_bytes());value['sourceOwners'].pop(str(Path(es.__file__).absolute()))
        Path(request['path']).write_bytes(es.canonical(value))
        with patch.object(n.environment,'verify') as verify,self.assertRaisesRegex(ValueError,'actual imported'):
            n.execute('update',request['path'],self.root/'output')
        verify.assert_not_called()


class PublicAndBundleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.original=original_actors()

    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup);self.root=Path(self.temporary.name)
        out=n.Output(self.root/'original');digest=out.bundle('',{head:source.raw for head,source in self.original.items()})
        self.subject=dict(path=str(out.path/'bundle.json'),sha256=digest)
        self.protocol=put(self.root,'protocol.json',dict(constructed=True))

    def panel(self):
        return dict(kind='coordinated-es-public-panel-1',protocolSha256=self.protocol['sha256'],
            originalBundleSha256=self.subject['sha256'],worlds=[dict(index=index,assignedEpoch=1+(index//4)%3,
            heads={head.value:[dict(number=8,batchStartNumber=7,epoch=1+(index//4)%3,referenceIndex=1,
                observation=observation(self.original[head].model))] for head in (es.Head.EXPANSION,es.Head.TOKEN)})
            for index in range(256)])

    def test_four_head_bundle_relocates_without_descriptor_rewrite(self):
        import shutil
        shutil.copytree(self.root/'original',self.root/'moved')
        subject=dict(path=str(self.root/'moved/bundle.json'),sha256=self.subject['sha256'])
        actual=n.bundle(n.Inputs(),subject)
        self.assertEqual(actual,{head:source.raw for head,source in self.original.items()})

    def test_bundle_path_escape_is_rejected(self):
        value=json.loads(Path(self.subject['path']).read_bytes());value['heads']['TOKEN']['path']='../outside.json'
        subject=put(self.root,'original/bad.json',value)
        with self.assertRaisesRegex(ValueError,'confined relative'):n.bundle(n.Inputs(),subject)

    def test_public_panel_rejects_private_or_terminal_payload(self):
        value=self.panel();value['worlds'][0]['terminal']={'reward':1}
        subject=put(self.root,'panel.json',value)
        with self.assertRaisesRegex(ValueError,'unexpected'):
            n.public_panel(n.Inputs(),subject,dict(protocol=self.protocol,originalBundle=self.subject),self.original)

    def test_public_panel_retains_missing_and_deduplicated_worlds(self):
        value=self.panel();value['worlds'][0]['heads']['TOKEN']=[];subject=put(self.root,'panel.json',value)
        _,rows=n.public_panel(n.Inputs(),subject,dict(protocol=self.protocol,originalBundle=self.subject),self.original)
        self.assertEqual(len(rows['TOKEN']),255);self.assertEqual(len(rows['EXPANSION']),256)
        self.assertEqual(rows['TOKEN'][0][2],2)

    def test_public_panel_preserves_reference_not_a_model_chosen_action(self):
        value=self.panel();value['worlds'][0]['heads']['TOKEN'][0]['referenceIndex']=2
        subject=put(self.root,'panel.json',value)
        with self.assertRaisesRegex(ValueError,'raw E/T reference'):
            n.public_panel(n.Inputs(),subject,dict(protocol=self.protocol,originalBundle=self.subject),self.original)

    def test_new_generation_source_uses_existing_fixed_predecessor_center(self):
        value=p.generate(self.original,'a'*64,self.subject['sha256'],17)
        directions,_=p.generate_directions(value.scope,2,19)
        request=dict(projection=put(self.root,'projection.json',value.wire,raw=True),originalBundle=self.subject,
            center=put(self.root,'center.json',es.Center(value.scope,0,(0.,)*256).record),
            directions=put(self.root,'directions.json',directions.record),
            worldBank=put(self.root,'bank.json',dict(nativeOffset=700,count=256,seeds=[-1000000000+700+i*8191 for i in range(256)])),
            worldIds=list(range(256)),calibration=None)
        with self.assertRaisesRegex(ValueError,'immediate same-scope'):
            n.materialize(n.Inputs(),request,n.Output(self.root/'output'),lambda:None)
        self.assertEqual(list((self.root/'output').iterdir()),[])

    def test_full_constructed_probe_pass_cannot_calibrate_zero_signed_gaps(self):
        value=p.generate(self.original,'a'*64,self.subject['sha256'],17)
        directions,_=p.generate_directions(value.scope,1,19)
        common=dict(projection=put(self.root,'projection.json',value.wire,raw=True),originalBundle=self.subject,
            directions=put(self.root,'directions.json',directions.record),protocol=self.protocol)
        materialize=dict(common,center=put(self.root,'center.json',es.Center(value.scope,0,(0.,)*256).record),
            worldBank=put(self.root,'bank.json',dict(nativeOffset=700,count=256,seeds=[-1000000000+700+i*8191 for i in range(256)])),
            worldIds=list(range(256)),calibration=None)
        n.materialize(n.Inputs(),materialize,n.Output(self.root/'materialized'),lambda:None)
        materialized=self.root/'materialized/materialization.json'
        panel=self.panel()
        for world in panel['worlds']:
            for states in world['heads'].values():
                for state in states:
                    for action in state['observation']['actions']:action['features']={}
        request=dict(common,materialization=dict(path=str(materialized),sha256=n.raw_sha(materialized.read_bytes())),
            publicPanel=put(self.root,'panel.json',panel))
        with self.assertRaisesRegex(es.OperationalStop,'positive-gap-mass'):
            n.calibrate(n.Inputs(),request,n.Output(self.root/'calibration'),lambda:None)
        self.assertTrue((self.root/'calibration/gap-EXPANSION.json').is_file())
        self.assertFalse((self.root/'calibration/calibration.json').exists())
        self.assertFalse((self.root/'calibration/population.json').exists())


if __name__=='__main__':unittest.main()
