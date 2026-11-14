"""Typed supported-screen/archive boundary fixtures; no native/model execution."""
import copy
import unittest

import coordinated_win_plan as p
import coordinated_win_screen as screen
from test_coordinated_win_plan import fixture
from test_token_paired_screen import world as old_world


def world(plan,index):
    value=old_world(plan,index);value['kind']=screen.KIND
    game=value['roles']['FINAL'];value['roles']={role:copy.deepcopy(game) for role in screen.ROLES}
    value['opponentIndices']=[(index//4+seat)%4 for seat in range(4)]
    return value


class ShardTests(unittest.TestCase):
    def setUp(self):
        _,record=fixture();self.plan=p.Plan.from_record(record)
        self.worlds={index:world(self.plan,index) for index in range(128,256)}
        self.bindings=dict(protocolSha256=self.plan.file_sha256,finalBundleSha256='7'*64,
            startBundleSha256=record['warmStart']['bundleSha256'],originalBundleSha256=self.plan.scope.original_bundle_sha256,
            projectionSha256=self.plan.scope.projection_sha256,warmStartSha256=record['warmStart']['identitySha256'],
            inheritedCalibrationSha256='8'*64,calibrationSha256=record['calibration']['originCalibrationSha256'],centerSha256='9'*64)
        science=dict(seeds=record['screenBank']['seeds'],roles=list(screen.ROLES),replayIndices=list(p.SCREEN_REPLAYS),
            **screen.scope_identity(self.plan),**self.bindings)
        self.files={name:dict(sha256='f'*64,size=1) for name in
            ('finished.json','manifest.json','started.json',*screen.SNAPSHOTS.values(),
                *(f'world-{index}.json.gz' for index in self.worlds))}
        self.manifest=dict(kind=screen.KIND,globalStart=128,count=128,signature='a'*64,
            scienceSha256='b'*64,scienceIdentity=science,ownedReplayIndices=[],
            **{key:dict(sha256='f'*64,snapshot=name) for key,name in screen.SNAPSHOTS.items()})
        proof=record['warmStart']['proofSha256']['DESKTOP']
        self.manifest['originProof']['sha256']=proof;self.files['origin-proof.json']['sha256']=proof
        self.finished=dict(kind=screen.KIND,complete=True,stage='chunk',globalStart=128,count=128,
            signature='a'*64,scienceSha256='b'*64,scienceIdentity=science,finalAdmissionSha256='f'*64,
            historicalAdmissionSha256='f'*64,files={name:row['sha256'] for name,row in self.files.items() if name!='finished.json'},
            reproductions=0,scientificTerminalExecutions=384,attemptedGames=384,completedGames=384,
            worlds=[dict(index=index,file=f'world-{index}.json.gz',sha256='f'*64) for index in self.worlds])

    def verify(self):
        screen.chunk_evidence(self.plan,1,self.files,self.manifest,self.finished,self.worlds,{}, {},'f'*64,'f'*64,self.bindings)

    def test_complete_mixed_shard_has_three_roles_and384_actual_games(self):self.verify()

    def test_homogeneous_lineup_cannot_claim_mixed_continuation(self):
        self.worlds[128]['opponentIndices']=[0]*4
        with self.assertRaisesRegex(ValueError,'opponent bank'):self.verify()

    def test_two_role_count_cannot_omit_start_comparator(self):
        self.finished.update(scientificTerminalExecutions=256,attemptedGames=256,completedGames=256)
        with self.assertRaisesRegex(ValueError,'original completed'):self.verify()

    def test_screened_role_cannot_exceed_the_original_action_ceiling(self):
        self.worlds[128]['roles']['START']['decisions']=12001
        with self.assertRaisesRegex(ValueError,'action ceiling'):self.verify()

    def test_mac_origin_proof_cannot_replace_desktop_proof(self):
        self.manifest['originProof']['sha256']=self.plan.record['warmStart']['proofSha256']['MAC']
        with self.assertRaisesRegex(ValueError,'host-local'):self.verify()

    def test_origin_proof_snapshot_is_required_even_with_all_worlds(self):
        del self.files['origin-proof.json'];del self.finished['files']['origin-proof.json']
        with self.assertRaisesRegex(ValueError,'startup/manifest'):self.verify()

    def test_final_actor_and_start_actor_cannot_be_swapped(self):
        self.bindings['finalBundleSha256']=self.bindings['startBundleSha256']
        with self.assertRaisesRegex(ValueError,'fixed role'):self.verify()

    def test_inherited_selector_identity_is_common_to_all_worlds(self):
        self.bindings['inheritedCalibrationSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'fixed role'):self.verify()


class RoleTests(unittest.TestCase):
    def setUp(self):
        _,record=fixture();self.plan=p.Plan.from_record(record)
        self.bindings=screen.replay_bindings(self.plan,dict(originalBundleSha256=self.plan.scope.original_bundle_sha256,
            finalBundleSha256='8'*64,startBundleSha256=record['warmStart']['bundleSha256'],
            calibrationSha256=record['calibration']['originCalibrationSha256']))

    def role(self,name):
        control=name=='CONTROL';scales={head:1. for head in ('EXPANSION','TOKEN')} if control else self.bindings['scales']
        value=dict(version='coordinated-support-arena-1',reference='complete-joint-token-expansion-composition',
            supportProfile='known-own-chaos-support-1',supportRoles='ALL_SEATS_CP',
            originalBundle=dict(sha256=self.bindings['originalBundleSha256']),baseline=dict(sha256=self.bindings['purchaseF0Sha256']),
            selectors={head:dict(kind='incumbent-residual-margin-1',margin=1.,scale=scale) for head,scale in scales.items()},
            execution='ZERO_REFERENCE' if control else 'CALIBRATED',roles={} if control else
                dict(CANDIDATE=dict(sha256=self.bindings['finalBundleSha256' if name=='FINAL' else 'startBundleSha256'])))
        if not control:value['calibration']=dict(sha256=self.bindings['calibrationSha256'])
        return dict(coordinatedEs=value)

    def test_all_three_exact_supported_roles_pass(self):
        for role in screen.ROLES:
            with self.subTest(role=role):self.assertTrue(screen.replay_role(self.role(role),role,self.bindings))

    def test_control_cannot_acquire_a_learned_overlay(self):
        value=self.role('CONTROL');value['coordinatedEs']['roles']={'CANDIDATE':{'sha256':'8'*64}}
        self.assertFalse(screen.replay_role(value,'CONTROL',self.bindings))

    def test_raw_unsupported_role_cannot_claim_supported_composition(self):
        value=self.role('FINAL');del value['coordinatedEs']['supportProfile']
        self.assertFalse(screen.replay_role(value,'FINAL',self.bindings))

    def test_parent_is_a_distinct_full_frozen_start_not_a_candidate_alias(self):
        self.assertFalse(screen.replay_role(self.role('FINAL'),'START',self.bindings))

    def test_selector_scale_cannot_be_recalibrated_for_screen(self):
        value=self.role('FINAL');value['coordinatedEs']['selectors']['TOKEN']['scale']+=.1
        self.assertFalse(screen.replay_role(value,'FINAL',self.bindings))


if __name__=='__main__':unittest.main()
