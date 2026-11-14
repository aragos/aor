"""Portable metadata boundaries; all model/history/proof contents are explicitly invented."""
from pathlib import Path
import shutil
import tempfile
import unittest

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_win_context as context
import coordinated_win_plan as p
from test_coordinated_win_plan import fixture


def put(path,value,lf=False):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(p.canonical(value)+(b'\n' if lf else b''))
    return p.subject(path)


class ContextTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();inputs=self.root/'inputs'
        _,record=fixture()
        original=put(inputs/'original.json',dict(constructedOriginal=True))
        projection=put(inputs/'projection.json',dict(constructedProjection=True))
        scope=es.Scope('a'*64,original['sha256'],projection['sha256'])
        old_protocol=put(inputs/'calibration/origin/protocol.json',dict(calibration=p.base.calibration_recipe(2)),True)
        seal=put(inputs/'calibration/origin/calibration.json',dict(kind='coordinated-es-calibration-2',
            passed=True,complete=True,protocolSha256=old_protocol['sha256'],
            originalBundleSha256=original['sha256'],projectionSha256=projection['sha256'],
            scales=record['calibration']['scales'],semanticFitnessDecoded=False,calibrationAttempts=1))
        for head in ('EXPANSION','TOKEN'):
            put(inputs/f'calibration/origin/gap-{head}.json',dict(constructedWitness=True,head=head))
        parent=es.Center(scope,16,(.5,)*256)
        self.warm=c.WarmStart(scope,es.digest(parent.record),'d'*64,'e'*64,'f'*64,seal['sha256'],parent.coordinates)
        warm=put(inputs/'warm-start.json',self.warm.record)
        initial=put(inputs/'initial.json',c.Center.initial(self.warm).record)
        record['parameterSpace'].update(scope.record)
        record['warmStart'].update(identitySha256=warm['sha256'],centerSha256=self.warm.origin_center_sha256,
            bundleSha256=self.warm.origin_bundle_sha256,protocolSha256=self.warm.origin_protocol_sha256,
            admissionSha256=self.warm.origin_admission_sha256)
        record['calibration'].update(originProtocolSha256=old_protocol['sha256'],originCalibrationSha256=seal['sha256'])
        proofs={host:put(inputs/f'proof-{host}.json',dict(host=host,constructedOriginProof=True)) for host in p.HOSTS}
        record['warmStart']['proofSha256']={host:proof['sha256'] for host,proof in proofs.items()}
        self.plan=p.Plan.from_record(record);self.protocol=self.root/'protocol.json'
        self.protocol.write_bytes(self.plan.wire+b'\n')
        inherited=put(inputs/'calibration/inherited-calibration.json',dict(kind='coordinated-es-inherited-calibration-1',
            protocolSha256=self.plan.file_sha256,parameterSpace=scope.record,warmStartSha256=warm['sha256'],
            originProtocol=dict(path='origin/protocol.json',sha256=old_protocol['sha256']),
            originCalibration=dict(path='origin/calibration.json',sha256=seal['sha256']),
            recipe=record['calibration']['recipe'],scales=record['calibration']['scales'],recalibrated=False))
        self.profile=dict(host='DESKTOP',arena=put(inputs/'arena.json',dict(constructedArena=True)),
            originalBundle=original,projection=projection,warmStart=warm,originProof=proofs['DESKTOP'],
            numericalPreparation=dict(finished=put(inputs/'prepared/finished.json',dict(constructedPreparation=True))),
            initialCenter=initial,inheritedCalibration=inherited,directions=[
                put(inputs/f'direction-{g}.json',es.DirectionTable(scope,g,((1.,)*256,)*16).record) for g in range(1,17)])
        self.mac=dict(self.profile,host='MAC',originProof=proofs['MAC'])
        self.historical=put(self.root/'historical/gate.json',dict(constructedHistorical=True))

    def create(self,number=1,center=None,previous=None,phase='controls',population=None):
        return context.create(self.root/f'{phase}-{number}',self.plan,self.protocol,self.profile,number,phase,
            center or self.profile['initialCenter'],previous,self.historical,population)

    def successor(self):
        center=c.Center(self.warm,1,(.75,)*256)
        subject=put(self.root/'successor.json',center.record)
        update=put(self.root/'update.json',dict(kind=c.UPDATE_KIND,complete=True,objective=c.OBJECTIVE,
            protocolSha256=self.plan.file_sha256,warmStartSha256=self.warm.sha256,center=center.record,
            directionsSha256=self.profile['directions'][0]['sha256']))
        return subject,update

    def rewrite(self,directory,edit):
        path=directory/'stage.json';row=p.read(path);edit(row);put(path,row)

    def test_same_portable_control_context_uses_each_hosts_own_origin_proof(self):
        directory=self.create()
        desktop=context.read(directory,self.plan,self.protocol,self.profile,1,'controls')
        mac=context.read(directory,self.plan,self.protocol,self.mac,1,'controls')
        self.assertNotEqual(desktop['originProof'],mac['originProof'])
        self.assertEqual(desktop['center'],mac['center'])

    def test_portable_copy_does_not_reopen_the_original_stage_paths(self):
        directory=self.create();copy_root=self.root/'received';shutil.copytree(directory,copy_root);shutil.rmtree(directory)
        value=context.read(copy_root,self.plan,self.protocol,self.mac,1,'controls')
        self.assertTrue(Path(value['center']['path']).is_relative_to(copy_root))

    def test_immediate_previous_update_binds_next_center_and_original_direction(self):
        center,previous=self.successor();directory=self.create(2,center,previous)
        value=context.read(directory,self.plan,self.protocol,self.mac,2,'controls')
        self.assertEqual(value['previousUpdate']['sha256'],previous['sha256'])

    def test_changed_direction_even_with_rehashed_subject_is_rejected(self):
        directory=self.create();row=p.read(directory/'directions.json');row['vectors'][0][0]=2.
        changed=put(directory/'directions.json',row)
        self.rewrite(directory,lambda v:v['subjects']['directions'].update(sha256=changed['sha256']))
        with self.assertRaisesRegex(ValueError,'prospective direction'):
            context.read(directory,self.plan,self.protocol,self.mac,1,'controls')

    def test_foreign_host_origin_proof_is_not_portable(self):
        directory=self.create();profile=dict(self.mac,originProof=self.profile['originProof'])
        with self.assertRaisesRegex(ValueError,'original/warm-start subjects'):
            context.read(directory,self.plan,self.protocol,profile,1,'controls')

    def test_stage_cannot_skip_to_a_later_center(self):
        center,_=self.successor()
        with self.assertRaisesRegex(ValueError,'immediate center'):
            self.create(center=center)

    def test_next_stage_rejects_reward_objective_predecessor(self):
        center,previous=self.successor();value=p.read(previous['path']);value['objective']='AUTHORITATIVE_NORMALIZED_TERMINAL'
        previous=put(previous['path'],value)
        with self.assertRaisesRegex(ValueError,'previous win-only update'):
            self.create(2,center,previous)

    def test_changed_original_seal_cannot_be_rehashed_into_inherited_closure(self):
        directory=self.create();put(directory/'calibration/origin/calibration.json',dict(passed=True))
        with self.assertRaisesRegex(ValueError,'Immutable subject bytes'):
            context.read(directory,self.plan,self.protocol,self.mac,1,'controls')

    def test_private_or_extra_calibration_files_cannot_enter_publication(self):
        put(Path(self.profile['inheritedCalibration']['path']).parent/'unexpected.json',{})
        with self.assertRaisesRegex(ValueError,'five unchanged'):
            self.create()

    def test_population_phase_cannot_omit_the_sealed_population(self):
        with self.assertRaisesRegex(ValueError,'population must be sealed'):
            self.create(phase='population')

    def test_stage_subject_cannot_escape_portable_directory(self):
        directory=self.create()
        self.rewrite(directory,lambda v:v['subjects']['center'].update(path='../inputs/initial.json'))
        with self.assertRaises(ValueError):context.read(directory,self.plan,self.protocol,self.mac,1,'controls')

    def test_identity_cannot_replace_supported_ecology(self):
        directory=self.create()
        self.rewrite(directory,lambda v:v.update(ecologySha256='9'*64))
        with self.assertRaisesRegex(ValueError,'supported ecology'):
            context.read(directory,self.plan,self.protocol,self.mac,1,'controls')


if __name__=='__main__':unittest.main()
