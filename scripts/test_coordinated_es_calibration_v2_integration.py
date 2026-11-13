"""Constructed public receipts only; no actors, forward, native process or scientific input."""
import copy
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest

import coordinated_es_calibration as v1
import coordinated_es_calibration_v2 as v2
import coordinated_es_campaign as campaign
import coordinated_es_generation_audit as audit
import coordinated_es_math as es
import coordinated_es_numeric_stage as stage
import coordinated_es_plan as p
from test_coordinated_es_calibration import panel,typed
from test_coordinated_es_plan import protocol
from token_paired_campaign_audit import Pins


def put(path,value,lf=False):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(es.canonical(value)+(b'\n' if lf else b''))
    return p.subject(path)


class Fixture:
    def __init__(self,root,version=2):
        self.root=Path(root);record=protocol();record['calibration']=p.calibration_recipe(version)
        self.plan=p.Plan.from_record(record);self.protocol=put(self.root/'protocol.json',record,True)
        self.owner=v2 if version==2 else v1;self.heads={}
        for head in ('EXPANSION','TOKEN'):
            value=typed(panel()).record
            value.update(head=head,protocolSha256=self.protocol['sha256'],
                originalModelSha256=record['original']['heads'][head]['actorSha256'],
                projectionSha256=record['original']['projectionSha256'])
            gap=v1.GapPanel.from_record(value)
            put(self.root/'calibration'/f'gap-{head}.json',value)
            self.heads[head]=self.owner.calibrate(gap)
        one=self.heads['EXPANSION']['subjects']
        self.receipt=dict(kind=getattr(self.owner,'OUTER_VERSION','coordinated-es-calibration-1'),passed=True,complete=True,
            protocolSha256=self.protocol['sha256'],originalBundleSha256=record['original']['bundleSha256'],
            projectionSha256=record['original']['projectionSha256'],panelSha256=one['publicPanelSha256'],
            probePopulationSha256='9'*64,directionsSha256=one['directionsSha256'],
            prescribedMeanSha256=one['prescribedMeanSha256'],scales={head:row['scale'] for head,row in self.heads.items()},
            heads=self.heads,semanticFitnessDecoded=False,calibrationAttempts=1)
        self.seal=put(self.root/'calibration/calibration.json',self.receipt)
        self.request=dict(protocol=self.protocol,originalBundle=dict(path='unopened-constructed-bundle',
            sha256=record['original']['bundleSha256']),sourceOwners={str(Path(v2.__file__).absolute()):p.file_sha(v2.__file__)})
        self.projection=SimpleNamespace(sha256=record['original']['projectionSha256'],
            record=dict(heads={head:dict(modelSha256=row['actorSha256']) for head,row in record['original']['heads'].items()}))


class PureAdmissionTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup);self.root=Path(temporary.name)

    def test_pure_audit_recomputes_complete_v2_head_receipts(self):
        fixture=Fixture(self.root)
        value=audit.calibration(Pins(),fixture.seal,fixture.protocol,fixture.plan)
        self.assertEqual(value,fixture.receipt)

    def test_pure_audit_rejects_resealed_interval_tamper(self):
        fixture=Fixture(self.root);fixture.receipt['heads']['EXPANSION']['intervals']['mutants']['lowerAtWeight']+=1
        subject=put(self.root/'calibration/calibration.json',fixture.receipt)
        with self.assertRaisesRegex(ValueError,'arithmetic'):
            audit.calibration(Pins(),subject,fixture.protocol,fixture.plan)

    def test_pure_audit_rejects_mixed_outer_version(self):
        fixture=Fixture(self.root);fixture.receipt['kind']='coordinated-es-calibration-1'
        subject=put(self.root/'calibration/calibration.json',fixture.receipt)
        with self.assertRaisesRegex(ValueError,'outcome-free'):
            audit.calibration(Pins(),subject,fixture.protocol,fixture.plan)

    def test_pure_legacy_audit_keeps_original_receipt(self):
        fixture=Fixture(self.root,1)
        value=audit.calibration(Pins(),fixture.seal,fixture.protocol,fixture.plan)
        self.assertEqual(value['heads']['EXPANSION']['scale'],1+2**-20)

    def stop_receipt(self,version,reason):
        root=self.root/str(version)/reason;root.mkdir(parents=True)
        record=protocol();record['calibration']=p.calibration_recipe(version)
        protocol_subject=put(root/'protocol.json',record,True)
        request=put(root/'request.json',dict(protocol=protocol_subject,deadlineEpoch=time.time()+60))
        directory=root/'calibrate';directory.mkdir()
        row=dict(kind='coordinated-es-numeric-stop-1',complete=False,passed=False,verb='calibrate',
            request=request,reason=reason,artifacts={},deadlineEpoch=p.read(request['path'])['deadlineEpoch'],
            stoppedEpoch=time.time(),inputPins={request['path']:request['sha256'],
                protocol_subject['path']:protocol_subject['sha256']})
        subject=put(directory/'stopped.json',row)
        return directory,request,subject

    def test_new_admitted_stop_reasons_reach_existing_campaign_boundary(self):
        for reason in ('calibration-empty-feasible-interval','calibration-no-interior-scale','calibration-nonfinite-movement'):
            with self.subTest(reason=reason):
                directory,request,subject=self.stop_receipt(2,reason)
                validated=stage.verify_stop(directory,request['path'],'calibrate')
                result=campaign.stop(dict(reason=validated['reason'],receipt=subject),0)
                self.assertEqual(result,dict(status='stopped',reason='calibration-failed',completedGenerations=0,numericStop=subject))

    def test_new_stop_reason_cannot_weaken_legacy_admission(self):
        directory,request,_=self.stop_receipt(1,'calibration-empty-feasible-interval')
        with self.assertRaisesRegex(ValueError,'original calibration'):
            stage.verify_stop(directory,request['path'],'calibrate')

    def test_legacy_scale_rule_stop_cannot_enter_v2(self):
        directory,request,_=self.stop_receipt(2,'calibration-scale-bounds')
        with self.assertRaisesRegex(ValueError,'original calibration'):
            stage.verify_stop(directory,request['path'],'calibrate')


class NumericBoundaryTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup);self.root=Path(temporary.name)
        import coordinated_es_numeric as numeric
        self.numeric=numeric

    def test_numeric_owner_requires_explicit_complete_v2_and_source_pin(self):
        fixture=Fixture(self.root)
        value=self.numeric.checked_calibration(self.numeric.Inputs(),fixture.seal,fixture.request,fixture.projection)
        self.assertEqual(value[0],fixture.receipt)

    def test_v2_owner_cannot_be_unpinned(self):
        fixture=Fixture(self.root);fixture.request['sourceOwners']={}
        with self.assertRaisesRegex(ValueError,'V2 numerical owner must be pinned'):
            self.numeric.checked_calibration(self.numeric.Inputs(),fixture.seal,fixture.request,fixture.projection)

    def test_numeric_owner_rejects_resealed_mixed_kind(self):
        fixture=Fixture(self.root);fixture.receipt['kind']='coordinated-es-calibration-1'
        subject=put(self.root/'calibration/calibration.json',fixture.receipt)
        with self.assertRaisesRegex(ValueError,'one-attempt'):
            self.numeric.checked_calibration(self.numeric.Inputs(),subject,fixture.request,fixture.projection)

    def test_v1_does_not_require_new_owner(self):
        fixture=Fixture(self.root,1);fixture.request['sourceOwners']={}
        value=self.numeric.checked_calibration(self.numeric.Inputs(),fixture.seal,fixture.request,fixture.projection)
        self.assertEqual(value[0]['kind'],'coordinated-es-calibration-1')

    def test_partial_v2_recipe_cannot_select_owner(self):
        request=dict(protocol=put(self.root/'protocol.json',dict(calibration=dict(kind=v2.VERSION))))
        with self.assertRaisesRegex(ValueError,'entire fixed V2'):
            self.numeric.calibration_owner(self.numeric.Inputs(),request)

    def test_unknown_kind_cannot_fall_back_to_v1(self):
        request=dict(protocol=put(self.root/'protocol.json',dict(calibration=dict(kind='invented'))))
        with self.assertRaisesRegex(ValueError,'unknown calibration'):
            self.numeric.calibration_owner(self.numeric.Inputs(),request)


if __name__=='__main__':unittest.main()
