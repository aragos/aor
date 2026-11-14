"""Invented parent admission/seal plus real toy projection/materialization/update operations.

This is numerical transition coverage. It is not the later native/archive fitness integration proof,
and its invented old calibration witnesses do not claim observed model sensitivity.
"""
import copy
from dataclasses import replace
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest
from unittest.mock import patch

import torch
import coordinated_es_calibration_v2 as v2
import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_es_numeric as base
import coordinated_es_projection as projection
import coordinated_win_numeric as n
import coordinated_win_plan as p
from test_coordinated_es_calibration import panel, typed
from test_coordinated_es_numeric import owners, put
from test_coordinated_es_projection import original_actors, observation
from test_coordinated_win_plan import fixture as plan_fixture


def source_owners():
    result=owners()
    result.update({str(Path(m.__file__).absolute()):base.raw_sha(Path(m.__file__).read_bytes())
        for m in (n,c,p,p.base,v2)})
    return result


def fixture(root):
    original=original_actors();output=base.Output(root/'original')
    digest=output.bundle('',{h:v.raw for h,v in original.items()})
    original_subject=dict(path=str(output.path/'bundle.json'),sha256=digest)
    factors=projection.generate(original,'a'*64,digest,17)
    projection_subject=put(root,'projection.json',factors.wire,raw=True)
    parent=es.Center(factors.scope,16,(.1,)*256)
    center=put(root,'origin-center.json',parent.record)
    old_protocol=put(root,'old-protocol.json',es.canonical(dict(calibration=p.base.calibration_recipe(2)))+b'\n',raw=True)
    parent_output=base.Output(root/'parent')
    parent_digest=parent_output.bundle('',projection.materialize(factors,original,parent.coordinates))
    parent_bundle=dict(path=str(parent_output.path/'bundle.json'),sha256=parent_digest)
    public=dict(kind='coordinated-es-public-panel-1',protocolSha256=old_protocol['sha256'],
        originalBundleSha256=digest,worlds=[dict(index=index,assignedEpoch=1+(index//4)%3,
            heads={h.value:([] if index>=128 else [dict(number=2*index,batchStartNumber=2*index,
                epoch=1+(index//4)%3,referenceIndex=1,observation=observation(original[h].model))])
                for h in (es.Head.EXPANSION,es.Head.TOKEN)}) for index in range(256)])
    public_subject=put(root,'public.json',public)
    receipts={}
    for h in (es.Head.EXPANSION,es.Head.TOKEN):
        record=panel();record.update(head=h.value,protocolSha256=old_protocol['sha256'],
            originalModelSha256=original[h].sha256,projectionSha256=factors.sha256,
            publicPanelSha256=public_subject['sha256'])
        for index,world in enumerate(record['worlds'][:128]):
            world['states'][0]['observationSha256']=es.digest(public['worlds'][index]['heads'][h.value][0]['observation'])
        gap=typed(record);put(root,f'old/gap-{h.value}.json',gap.wire,raw=True)
        receipts[h.value]=v2.calibrate(gap)
    calibration=put(root,'old/calibration.json',dict(kind=v2.OUTER_VERSION,passed=True,complete=True,
        protocolSha256=old_protocol['sha256'],originalBundleSha256=digest,projectionSha256=factors.sha256,
        panelSha256=public_subject['sha256'],probePopulationSha256='2'*64,directionsSha256='d'*64,
        prescribedMeanSha256='e'*64,scales={h:r['scale'] for h,r in receipts.items()},heads=receipts,
        semanticFitnessDecoded=False,calibrationAttempts=1))
    materialization=put(root,'old-materialization.json',dict(kind='coordinated-es-center-materialization-1',
        protocolSha256=old_protocol['sha256'],centerSha256=center['sha256'],generation=16,
        projectionSha256=factors.sha256,originalBundleSha256=digest,calibrationSha256=calibration['sha256'],
        bundleSha256=parent_digest,finalCandidate=True))
    admission=put(root,'invented-admission.json',dict(kind='coordinated-es-final-admission-1',complete=True,
        completedGenerations=16,scopeSha256=factors.scope.scope_sha256,protocol=old_protocol,center=center,finalBundle=parent_bundle,
        calibration=calibration,
        centerMaterialization=materialization,originalBundle=original_subject,projection=projection_subject))
    warm=c.WarmStart(factors.scope,center['sha256'],parent_digest,old_protocol['sha256'],admission['sha256'],
        calibration['sha256'],parent.coordinates)
    _,record=plan_fixture()
    record['parameterSpace']=dict(factors.scope.record,projectionRecipeSha256=es.digest(projection.RECIPE),
        heads={h.value:dict(actorSha256=v.sha256,dictionarySha256=projection.dictionary_sha(v.model)) for h,v in original.items()})
    record['warmStart'].update(identitySha256=warm.sha256,protocolSha256=old_protocol['sha256'],
        admissionSha256=admission['sha256'],centerSha256=center['sha256'],bundleSha256=parent_digest,
        materializationSha256=materialization['sha256'])
    record['calibration'].update(originProtocolSha256=old_protocol['sha256'],originCalibrationSha256=calibration['sha256'],
        scales={h:r['scale'] for h,r in receipts.items()})
    plan=p.Plan.from_record(record);protocol=put(root,'protocol.json',plan.wire+b'\n',raw=True)
    request=dict(protocol=protocol,originalBundle=original_subject,projection=projection_subject,
        warmStart=put(root,'warm-start.json',warm.record),originCenter=center,originWarmStart=None,
        originBundle=parent_bundle,originAdmission=admission,originMaterialization=materialization,
        calibrationProtocol=old_protocol,calibration=calibration)
    return request,public_subject,plan


class NumericTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):torch.set_num_threads(1)

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.request,self.public,self.plan=fixture(self.root)

    def execute(self,verb,fields,label):
        request=put(self.root,f'{label}-request.json',dict(kind=f'coordinated-win-{verb}-request-1',
            sourceOwners=source_owners(),numerical=put(self.root,'environment.json',dict(constructed=True)),
            deadlineEpoch=time.time()+120,**fields))
        with patch.object(base.environment,'verify'):
            return n.execute(verb,request['path'],self.root/label)

    def prepared(self):
        result=self.execute('prepare',self.request,'prepared')
        common={key:self.request[key] for key in ('protocol','originalBundle','projection','warmStart')}
        path=self.root/'prepared/inherited-calibration.json'
        common['inheritedCalibration']=dict(path=str(path),sha256=base.raw_sha(path.read_bytes()))
        center=put(self.root,'center0.json',json.loads((self.root/'prepared/initial-center.json').read_bytes()))
        return result,common,center

    def test_prepare_retains_nonzero_origin_seal_and_generates_only_fresh_directions(self):
        result,common,center=self.prepared()
        self.assertEqual(json.loads(Path(center['path']).read_bytes())['coordinates'],[.1]*256)
        self.assertEqual((self.root/'prepared/projection.json').read_bytes(),Path(self.request['projection']['path']).read_bytes())
        self.assertEqual((self.root/'prepared/origin/calibration.json').read_bytes(),Path(self.request['calibration']['path']).read_bytes())
        self.assertEqual(result['protocolSha256'],self.request['protocol']['sha256'])
        self.assertEqual(len(list((self.root/'prepared/directions').glob('*-generation.json'))),16)
        self.assertFalse((self.root/'prepared/prescribed-mean.json').exists())

    def test_real_materialize_win_update_diagnose_and_next_materialize(self):
        _,common,center=self.prepared()
        directions=dict(path=str(self.root/'prepared/directions/generation-01.json'),
            sha256=base.raw_sha((self.root/'prepared/directions/generation-01.json').read_bytes()))
        first=dict(common,center=center,directions=directions,worldBank=put(self.root,'bank1.json',self.plan.bank(1)['worldBank']))
        self.execute('materialize',first,'population1')
        population=put(self.root,'population.json',json.loads((self.root/'population1/population.json').read_bytes()))
        record=json.loads(Path(population['path']).read_bytes())
        fitness=put(self.root,'invented-win-fitness.json',dict(kind=c.FITNESS_KIND,objective=c.OBJECTIVE,
            protocolSha256=record['protocolSha256'],populationSha256=population['sha256'],worldBankSha256=record['worldBankSha256'],
            worldIds=list(range(256)),members=[dict(direction=r['direction'],sign=r['sign'],bundleSha256=r['bundleSha256'],
                winShares=[1.]*(128 if r['sign']==1 else 120)+[0.]*(128 if r['sign']==1 else 136)) for r in record['members']]))
        self.execute('update',{k:v for k,v in dict(common,center=center,directions=directions,population=population,fitness=fitness).items()
            if k not in ('originalBundle','projection')},'update1')
        next_center=put(self.root,'center1.json',json.loads((self.root/'update1/center.json').read_bytes()))
        self.assertEqual(json.loads(Path(next_center['path']).read_bytes())['step'],1)
        self.assertNotEqual(Path(next_center['path']).read_bytes(),Path(center['path']).read_bytes())
        self.execute('diagnose',dict(common,center=next_center,publicPanel=self.public),'diagnosis1')
        movement=json.loads((self.root/'diagnosis1/movement.json').read_bytes())
        self.assertTrue(movement['diagnosticOnly']);self.assertFalse(movement['eligibilityGate'])
        self.execute('center',dict(common,center=next_center),'center-bundle1')
        directions2=put(self.root,'direction2.json',json.loads((self.root/'prepared/directions/generation-02.json').read_bytes()))
        self.execute('materialize',dict(common,center=next_center,directions=directions2,
            worldBank=put(self.root,'bank2.json',self.plan.bank(2)['worldBank'])),'population2')
        second=json.loads((self.root/'population2/population.json').read_bytes())
        self.assertEqual(second['centerSha256'],next_center['sha256'])
        self.assertEqual(second['inheritedCalibrationSha256'],record['inheritedCalibrationSha256'])
        self.assertEqual(len(second['members']),32)

    def test_parent_materialization_tamper_stops_before_new_materialization(self):
        Path(self.request['originMaterialization']['path']).write_bytes(b'{}')
        with patch.object(projection,'materialize') as materialize,self.assertRaisesRegex(ValueError,'input bytes'):
            self.execute('prepare',self.request,'rejected')
        materialize.assert_not_called()
        self.assertFalse((self.root/'rejected/finished.json').exists())

    def test_rebased_parent_coordinates_cannot_match_original_head_bytes(self):
        wrong=dict(self.request,originBundle=put(self.root,'wrong-bundle.json',dict(kind='coordinated-es-bundle-1',heads={})))
        with self.assertRaisesRegex(ValueError,'exact complete parent'):
            self.execute('prepare',wrong,'rejected')

    def test_parent_admission_cannot_swap_the_original_scalar_seal(self):
        value=json.loads(Path(self.request['originAdmission']['path']).read_bytes())
        value['calibration']=dict(value['calibration'],sha256='7'*64)
        changed=put(self.root,'changed-admission.json',value)
        warm=c.WarmStart.from_record(json.loads(Path(self.request['warmStart']['path']).read_bytes()))
        replacement=replace(warm,origin_admission_sha256=changed['sha256'])
        record=self.plan.record;record['warmStart'].update(admissionSha256=changed['sha256'],identitySha256=replacement.sha256)
        protocol=put(self.root,'changed-protocol.json',p.Plan.from_record(record).wire+b'\n',raw=True)
        request=dict(self.request,originAdmission=changed,protocol=protocol,
            warmStart=put(self.root,'changed-warm.json',replacement.record))
        with patch.object(projection,'materialize') as materialize,self.assertRaisesRegex(ValueError,'admission must bind'):
            self.execute('prepare',request,'rejected')
        materialize.assert_not_called()

    def test_inherited_seal_cannot_be_relabelled_as_new_protocol(self):
        _,common,_=self.prepared();subject=common['inheritedCalibration']
        path=self.root/'prepared/origin/calibration.json';record=json.loads(path.read_bytes())
        record['protocolSha256']=self.request['protocol']['sha256'];path.write_bytes(es.canonical(record))
        warm=c.WarmStart.from_record(json.loads(Path(self.request['warmStart']['path']).read_bytes()))
        with self.assertRaisesRegex(ValueError,'input bytes'):
            n.inherited_metadata(base.Inputs(),subject,self.request,self.plan,warm)

    def test_envelope_cannot_change_original_scales(self):
        _,common,_=self.prepared();record=json.loads(Path(common['inheritedCalibration']['path']).read_bytes())
        record['scales']['TOKEN']+=.01;subject=put(self.root,'changed-envelope.json',record)
        warm=c.WarmStart.from_record(json.loads(Path(self.request['warmStart']['path']).read_bytes()))
        with self.assertRaisesRegex(ValueError,'unchanged seal'):
            n.inherited_metadata(base.Inputs(),subject,self.request,self.plan,warm)

    def test_missing_continuation_owner_rejects_before_environment(self):
        values=source_owners();values.pop(str(Path(n.__file__).absolute()))
        with self.assertRaisesRegex(ValueError,'continuation owner'):n.source_owners(base.Inputs(),values)

    def test_old_or_new_request_has_no_calibration_operation(self):
        with self.assertRaisesRegex(ValueError,'Unknown continuation'):
            n.execute('calibrate','unopened','unopened')


if __name__=='__main__':unittest.main()
