"""Pure invented metadata/terminal boundaries; no numerical model imports or execution."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import coordinated_es_continuation as c
import coordinated_es_math as es
import coordinated_win_plan as p
import coordinated_win_storage as s
from test_coordinated_win_plan import fixture


def put(root,name,value,lf=False):
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(p.canonical(value)+(b'\n' if lf else b''))
    return p.base.subject(path)


def calibration(root,version=2,**changes):
    _,record=fixture();recipe=p.base.calibration_recipe(version)
    old=put(root,'old/protocol.json',dict(calibration=recipe),True)
    seal=dict(kind='coordinated-es-calibration-'+str(version),passed=True,complete=True,
        protocolSha256=old['sha256'],originalBundleSha256=record['parameterSpace']['originalBundleSha256'],
        projectionSha256=record['parameterSpace']['projectionSha256'],scales=record['calibration']['scales'],
        semanticFitnessDecoded=False,calibrationAttempts=1)
    seal.update(changes);subject=put(root,'old/calibration.json',seal)
    record['calibration'].update(recipe=recipe,originProtocolSha256=old['sha256'],originCalibrationSha256=subject['sha256'])
    plan=p.Plan.from_record(record)
    envelope=put(root,'inherited.json',dict(kind='coordinated-es-inherited-calibration-1',
        protocolSha256=plan.file_sha256,parameterSpace=plan.scope.record,
        warmStartSha256=record['warmStart']['identitySha256'],recipe=recipe,scales=seal['scales'],recalibrated=False,
        originProtocol=dict(path='old/protocol.json',sha256=old['sha256']),
        originCalibration=dict(path='old/calibration.json',sha256=subject['sha256'])))
    return plan,envelope


def terminal(totals):
    """Score order is deliberately reversed; authoritative player IDs own seat mapping."""
    maximum=max((x for x in totals if x is not None),default=None)
    winners=[x is not None and x==maximum for x in totals]
    wins=[1/sum(winners) if win else 0. for win in winners]
    ranks=[0. if total is None else sum(1. if other is None or total>other else .5 if total==other else 0.
        for j,other in enumerate(totals) if j!=i)/3 for i,total in enumerate(totals)]
    scores=[dict(player='p'+str(i+1),cash=0 if total is None else total,advancePoints=0,
        misery=None if total is None else 0,total=total,winner=winners[i]) for i,total in enumerate(totals)]
    return dict(scores=list(reversed(scores)),wins=wins,ranks=ranks,
        rewards=[.8*w+.2*r for w,r in zip(wins,ranks)],decisions=9,elapsedMillis=0)


class CalibrationBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()

    def rejects(self,plan,envelope,expected):
        with patch.object(s.storage,'members') as members,patch.object(s,'canonical_subject',wraps=s.canonical_subject) as opened:
            with self.assertRaisesRegex(ValueError,expected):
                s.fitness(plan,plan.file_sha256,dict(path='/unopened-population',sha256='1'*64),envelope,[])
            members.assert_not_called()
            self.assertNotIn('/unopened-population',[call.args[0]['path'] for call in opened.call_args_list])

    def test_inherited_v1_has_its_exact_old_recipe_and_protocol(self):
        plan,envelope=calibration(self.root,1)
        self.assertEqual(s.inherited_calibration(plan,plan.file_sha256,envelope)['recipe'],p.base.calibration_recipe(1))

    def test_inherited_v2_has_its_exact_old_recipe_and_protocol(self):
        plan,envelope=calibration(self.root,2)
        self.assertEqual(s.inherited_calibration(plan,plan.file_sha256,envelope)['recipe'],p.base.calibration_recipe(2))

    def test_v1_envelope_cannot_open_v2_original(self):
        plan,envelope=calibration(self.root,1,kind='coordinated-es-calibration-2')
        self.rejects(plan,envelope,'original versioned')

    def test_v2_envelope_cannot_open_v1_original(self):
        plan,envelope=calibration(self.root,2,kind='coordinated-es-calibration-1')
        self.rejects(plan,envelope,'original versioned')

    def test_changed_original_bytes_reject_before_population(self):
        plan,envelope=calibration(self.root);(self.root/'old/calibration.json').write_text('{}')
        self.rejects(plan,envelope,'Immutable subject bytes')

    def test_old_seal_cannot_claim_new_protocol(self):
        plan,envelope=calibration(self.root,protocolSha256='9'*64)
        self.rejects(plan,envelope,'original versioned')

    def test_failed_original_cannot_open_population(self):
        plan,envelope=calibration(self.root,passed=False)
        self.rejects(plan,envelope,'original versioned')

    def test_reward_protocol_cannot_open_continuation_envelope(self):
        plan,envelope=calibration(self.root)
        with patch.object(s,'canonical_subject') as decode,self.assertRaisesRegex(ValueError,'raw continuation protocol'):
            s.inherited_calibration(plan,plan.sha256,envelope)
        decode.assert_not_called()

    def population(self,plan,envelope,**changes):
        materialization=dict(kind=c.MATERIALIZATION_KIND,parameterSpace=plan.scope.record,
            warmStartSha256=plan.record['warmStart']['identitySha256'],step=1,centerSha256='6'*64,
            directionsSha256='7'*64,worldBankSha256=p.digest(plan.bank(1)['worldBank']),worldIds=list(range(256)),
            members=[dict(direction=i//2,sign=1 if i%2==0 else -1,path=f'members/{s.storage.role(i)}/bundle.json',
                coordinatesSha256='8'*64,bundleSha256=p.digest(i)) for i in range(32)])
        value=c.seal_population(materialization,plan.file_sha256,envelope['sha256']);value.update(changes)
        return put(self.root,'population.json',value)

    def test_legacy_population_cannot_decode_continuation_games(self):
        plan,envelope=calibration(self.root)
        subject=self.population(plan,envelope,kind='coordinated-es-population-1')
        with patch.object(s.storage,'members') as members,self.assertRaisesRegex(ValueError,'continuation population'):
            s.fitness(plan,plan.file_sha256,subject,envelope,[])
        members.assert_not_called()

    def test_incomplete_population_cannot_decode_games(self):
        plan,envelope=calibration(self.root);subject=self.population(plan,envelope,members=[])
        with patch.object(s.storage,'members') as members,self.assertRaisesRegex(ValueError,'continuation population'):
            s.fitness(plan,plan.file_sha256,subject,envelope,[])
        members.assert_not_called()

    def test_all_eight_shards_required_before_decode(self):
        plan,envelope=calibration(self.root);subject=self.population(plan,envelope)
        with patch.object(s.storage,'members') as members,self.assertRaisesRegex(ValueError,'all eight'):
            s.fitness(plan,plan.file_sha256,subject,envelope,[])
        members.assert_not_called()


class TerminalTests(unittest.TestCase):
    def setUp(self):
        _,record=fixture();self.plan=p.Plan.from_record(record)
        self.receipt=dict(generation=1,signature='1'*64,scopeSha256=self.plan.scope.scope_sha256,
            scienceSha256='2'*64,sourceSha256='3'*64,warmStartSha256=record['warmStart']['identitySha256'],
            inheritedCalibrationSha256='4'*64,ecologySha256=record['evaluation']['ecologySha256'])

    def game(self,totals,index=0):
        return dict(kind=p.PREFIX+'-game-1',globalIndex=index,seed=self.plan.bank(1)['worldBank']['seeds'][index],
            seat=index%4,role='PLUS-00',bundleSha256='5'*64,
            originalBundleSha256=self.plan.scope.original_bundle_sha256,terminal=terminal(totals),**self.receipt)

    def test_split_win_uses_player_slot_despite_score_order(self):
        game=self.game([4,4,1,0],1)
        self.assertEqual(s.game_win(game,self.receipt,self.plan,1,'PLUS-00','5'*64),.5)

    def test_chaos_supplies_exact_recorded_zero_win(self):
        game=self.game([None,4,1,0])
        self.assertEqual(s.game_win(game,self.receipt,self.plan,0,'PLUS-00','5'*64),0.)

    def test_win_is_not_nonzero_rank_reward(self):
        game=self.game([3,4,1,0])
        self.assertGreater(game['terminal']['rewards'][0],0.)
        self.assertEqual(s.game_win(game,self.receipt,self.plan,0,'PLUS-00','5'*64),0.)

    def test_wrong_controlled_seat_rejected_before_terminal(self):
        game=self.game([4,3,2,1]);game['seat']=1
        with patch.object(s.storage,'terminal_metrics') as terminal_check,self.assertRaisesRegex(ValueError,'controlled world'):
            s.game_win(game,self.receipt,self.plan,0,'PLUS-00','5'*64)
        terminal_check.assert_not_called()

    def test_wrong_recorded_win_cannot_be_replaced_by_derived_winner(self):
        game=self.game([4,3,2,1]);game['terminal']['wins'][0]=0.
        with self.assertRaisesRegex(ValueError,'terminal label differs: winShare'):
            s.game_win(game,self.receipt,self.plan,0,'PLUS-00','5'*64)

    def test_unchanged_rewards_must_still_validate(self):
        game=self.game([4,3,2,1]);game['terminal']['rewards'][0]=0.
        with self.assertRaisesRegex(ValueError,'terminal label differs: reward'):
            s.game_win(game,self.receipt,self.plan,0,'PLUS-00','5'*64)


class NativeGenerationTests(unittest.TestCase):
    """Invented native DTOs exercise shared strict replay/inventory mechanics, not simulation."""
    def build(self,phase='population'):
        _,record=fixture()
        for head in p.HEADS:record['parameterSpace']['heads'][head]['dictionarySha256']=p.digest({})
        self.plan=p.Plan.from_record(record);self.phase=phase
        subjects={key:dict(path='/constructed/'+key,sha256=p.digest(key)) for key in s.GENERATION_SUBJECTS}
        subjects['previousUpdate']=None
        if phase=='controls':subjects['population']=None
        for key,digest in (('protocol',self.plan.file_sha256),('originalBundle',self.plan.scope.original_bundle_sha256),
                ('projection',self.plan.scope.projection_sha256),('warmStart',record['warmStart']['identitySha256']),
                ('originProof',record['warmStart']['proofSha256']['DESKTOP'])):subjects[key]['sha256']=digest
        self.request=self.plan.generation_source(1,0,phase.upper(),subjects)
        self.source_sha=p.digest(self.request)
        self.prepared=dict(kind='coordinated-win-prepared-1',stage=phase.upper(),generation=1,globalStart=0,count=32,
            bank=self.plan.bank(1)['worldBank'],subjects={key:None if item is None else dict(item,originalPath=item['path'])
                for key,item in subjects.items()},source={'sha256':self.source_sha},scopeSha256=self.plan.scope.scope_sha256,
            ecologySha256=record['evaluation']['ecologySha256'],warmStartSha256=record['warmStart']['identitySha256'],
            inheritedCalibrationSha256=subjects['inheritedCalibration']['sha256'],originalBundle=subjects['originalBundle'],
            arena=subjects['arena'],dictionaries={head:{} for head in p.HEADS},
            inputPins={item['path']:item['sha256'] for item in subjects.values() if item is not None},
            signature='b'*64,scienceSha256='c'*64)
        self.files={s.game_name(index,member):dict(sha256='a'*64,size=1) for index in range(32)
            for member in ([None] if phase=='controls' else range(32))}
        self.files.update({name:dict(sha256=digest,size=1) for name,digest in
            (('source.json',self.source_sha),('arena.json',subjects['arena']['sha256']),('prepared.json','a'*64))})
        expected=32 if phase=='controls' else 1024
        self.finished=dict(kind='coordinated-win-complete-1',**{key:self.prepared[key] for key in
            ('signature','scopeSha256','scienceSha256','stage','generation','globalStart','count')+s.EXTRA_IDENTITIES},
            attemptedGames=expected,completedGames=expected,expectedGames=expected,canonicalGames=0 if phase=='controls' else 1,
            files={name:row['sha256'] for name,row in self.files.items()})
        self.files['finished.json']=dict(sha256='a'*64,size=1);self.values={}
        if phase=='population':
            directory='replay-PLUS-00-0';receipt=directory+'/'+directory+'.json';actual=directory+'/game-PLUS-00-0.json'
            self.files.update({name:dict(sha256='a'*64,size=1) for name in (receipt,actual)})
            game=dict(canonical=dict(replayVerified=True),terminal=dict(elapsedMillis=0))
            self.values={'game-PLUS-00-0.json':game,actual:game,receipt:dict(kind='coordinated-win-reproduction-1',
                signature='b'*64,scienceSha256='c'*64,sourceSha256=self.source_sha,role='PLUS-00',globalIndex=0,
                original={'sha256':'a'*64},actual={'file':'game-PLUS-00-0.json','sha256':'a'*64},
                terminalEqual=True,canonicalEqual=True,replayVerified=True,scientificTerminalExecutions=1,
                attemptedGames=1,completedGames=1,**{key:self.prepared[key] for key in s.EXTRA_IDENTITIES})}

    def check(self):
        return s.generation_record(self.plan,self.request,self.source_sha,self.prepared,self.finished,self.files,
            1,0,self.phase,self.values)

    def test_supported_controls_have_exact_control_filenames_and_no_panels(self):
        self.build('controls');row=self.check()
        self.assertEqual(row['games'],[f'game-CONTROL-{i}.json' for i in range(32)])
        self.assertEqual(row['panels'],[]);self.assertEqual(row['scientificTerminalExecutions'],32)

    def test_complete_mutant_shard_reuses_actual_reproduction_inventory(self):
        self.build();row=self.check()
        self.assertEqual(row['scientificTerminalExecutions'],1025)
        self.assertEqual(row['inheritedCalibrationSha256'],self.request['inheritedCalibration']['sha256'])
        self.assertEqual(row['subjects']['numericalPreparation'],self.request['numericalPreparation'])

    def test_new_control_cannot_emit_a_recalibration_panel(self):
        self.build('controls');self.files['panel-0.json']=dict(sha256='a'*64,size=1)
        self.finished['files']['panel-0.json']='a'*64
        with self.assertRaisesRegex(ValueError,'extra or substituted'):self.check()

    def test_old_raw_control_filename_cannot_replace_supported_control(self):
        self.build('controls');self.files['game-RAW_G3-0.json']=self.files.pop('game-CONTROL-0.json')
        with self.assertRaisesRegex(ValueError,'completion file must match'):self.check()

    def test_completed_shard_cannot_replace_supported_ecology(self):
        self.build();self.finished['ecologySha256']='d'*64
        with self.assertRaisesRegex(ValueError,'complete once-attempted'):self.check()

    def test_prepared_identity_cannot_replace_original_parameter_scope(self):
        self.build();self.prepared['scopeSha256']=self.prepared['ecologySha256']
        with self.assertRaisesRegex(ValueError,'unchanged parameter origin'):self.check()

    def test_preparation_subject_cannot_be_dropped_from_native_source(self):
        self.build();self.request.pop('numericalPreparation')
        with self.assertRaises(KeyError):self.check()

    def test_extra_actual_replay_file_is_rejected_by_shared_owner(self):
        self.build();self.files['replay-PLUS-00-0/unexpected.json']=dict(sha256='a'*64,size=1)
        with self.assertRaisesRegex(ValueError,'exactly its actual game'):self.check()

    def test_forged_canonical_equality_flag_is_rejected(self):
        self.build();self.values['replay-PLUS-00-0/replay-PLUS-00-0.json']['canonicalEqual']=False
        with self.assertRaisesRegex(ValueError,'full canonical equality'):self.check()

    def test_reproduction_cannot_relabel_its_inherited_selector(self):
        self.build();self.values['replay-PLUS-00-0/replay-PLUS-00-0.json']['inheritedCalibrationSha256']='9'*64
        with self.assertRaisesRegex(ValueError,'full canonical equality'):self.check()


if __name__=='__main__':unittest.main()
