import copy
from pathlib import Path
import tempfile
import unittest

import coordinated_win_plan as p
import coordinated_es_continuation as c
import coordinated_es_math as es


def bank(offset,count):
    return dict(nativeOffset=offset,count=count,seeds=[-1000000000+offset+i*8191 for i in range(count)])


def fixture():
    scope=es.Scope('a'*64,'b'*64,'c'*64)
    origin=es.Center(scope,16,(.5,)*256)
    warm=c.WarmStart(scope,es.digest(origin.record),'d'*64,'e'*64,'f'*64,'1'*64,origin.coordinates)
    record=dict(kind=p.KIND,recipe=copy.deepcopy(p.RECIPE),
        parameterSpace=dict(scope.record,projectionRecipeSha256='2'*64,
            heads={head:dict(actorSha256=es.digest(head),dictionarySha256=es.digest([head])) for head in p.HEADS}),
        warmStart=dict(identitySha256=warm.sha256,protocolSha256=warm.origin_protocol_sha256,
            admissionSha256=warm.origin_admission_sha256,proofSha256={h:es.digest(h) for h in p.HOSTS},
            centerSha256=warm.origin_center_sha256,bundleSha256=warm.origin_bundle_sha256,materializationSha256='3'*64),
        calibration=dict(kind='coordinated-es-inherited-calibration-1',originProtocolSha256='4'*64,
            originCalibrationSha256=warm.calibration_sha256,recipe=p.base.calibration_recipe(2),
            scales=dict(EXPANSION=2.,TOKEN=3.),recalibration=False),
        evaluation=dict(recipe=copy.deepcopy(p.ECOLOGY),ecologySha256='5'*64,
            references={key:('FIXED_16_TO_15' if key=='tokenControlVersion' else es.digest(key))
                for key in p.REFERENCE_FIELDS}),
        prefixGate=copy.deepcopy(p.base.PREFIX_GATE),gameLimits=copy.deepcopy(p.base.GAME_LIMITS),
        budget=p.expected_budget(),scienceSeconds=p.SCIENCE_SECONDS,auditSeconds=p.AUDIT_SECONDS,
        hosts={h:p.host_limits(h) for h in p.HOSTS},
        generations=[dict(generation=i,worldBank=bank(100+i,256),directionSeed=101000+i) for i in range(1,17)],
        screenBank=bank(200,2048),screenGate=copy.deepcopy(p.SCREEN_GATE),
        historicalControls=[dict(globalIndex=g,index=n,side='candidate',purpose='RAW_REFERENCE_REGRESSION_ONLY',
            sourceSha256=es.digest([g,'source']),pairSha256=es.digest([g,'pair']),replaySha256=es.digest([g,'replay']))
            for g,n in ((0,0),(4095,511))],automaticRetry=False,automaticSuccessor=False)
    return warm,record


class ProtocolTests(unittest.TestCase):
    def test_exact_warm_origin_and_separate_calibration_protocol_roundtrip(self):
        warm,record=fixture();plan=p.Plan.from_record(record)
        plan.verify_warm_start(warm)
        self.assertNotEqual(record['warmStart']['protocolSha256'],record['calibration']['originProtocolSha256'])
        self.assertEqual(plan.scope,warm.scope)

    def test_framing_is_one_lf(self):
        _,record=fixture();plan=p.Plan.from_record(record)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'protocol.json';path.write_bytes(plan.wire+b'\n')
            self.assertEqual(p.Plan.from_file(path),plan)
            path.write_bytes(plan.wire)
            with self.assertRaisesRegex(ValueError,'framing LF'):p.Plan.from_file(path)

    def test_subjects_are_generic_not_hardcoded_to_experiment_heads(self):
        _,record=fixture();record['parameterSpace']['heads']['CARD']['actorSha256']='7'*64
        self.assertEqual(p.Plan.from_record(record).record['parameterSpace']['heads']['CARD']['actorSha256'],'7'*64)

    def test_zero_or_other_parent_identity_cannot_be_substituted(self):
        warm,record=fixture();record['warmStart']['centerSha256']='7'*64
        with self.assertRaisesRegex(ValueError,'complete admitted parent'):
            p.Plan.from_record(record).verify_warm_start(warm)

    def test_no_new_projection_stream(self):
        _,record=fixture();record['projectionSeed']=12345
        with self.assertRaisesRegex(ValueError,'unexpected'):p.Plan.from_record(record)

    def test_no_recalibration(self):
        _,record=fixture();record['calibration']['recalibration']=True
        with self.assertRaisesRegex(ValueError,'unchanged versioned'):p.Plan.from_record(record)

    def test_calibration_kind_cannot_be_relabelled_without_recipe(self):
        _,record=fixture();record['calibration']['recipe']['kind']=p.base.CALIBRATION['kind']
        with self.assertRaisesRegex(ValueError,'unchanged versioned'):p.Plan.from_record(record)

    def test_no_reward_shaping_or_movement_floor(self):
        for key,replacement in [('reward','AUTHORITATIVE_NORMALIZED_TERMINAL'),('liveHeadMovement','REQUIRED')]:
            with self.subTest(key=key):
                _,record=fixture();record['recipe'][key]=replacement
                with self.assertRaisesRegex(ValueError,'win-only'):p.Plan.from_record(record)

    def test_no_rival_support_or_mixed_schedule_change(self):
        for key,replacement in [('supportRoles','CONTROLLED_ONLY'),('rivals',['E_ON_T_ON'])]:
            with self.subTest(key=key):
                _,record=fixture();record['evaluation']['recipe'][key]=replacement
                with self.assertRaisesRegex(ValueError,'fixed mixed lineup'):p.Plan.from_record(record)

    def test_raw_histories_cannot_become_target_strength(self):
        _,record=fixture();record['historicalControls'][0]['purpose']='SUPPORTED_QUALIFICATION'
        with self.assertRaisesRegex(ValueError,'reference checks'):p.Plan.from_record(record)

    def test_old_global_local_history_guard_remains(self):
        _,record=fixture();record['historicalControls'][1]['index']=4095
        with self.assertRaisesRegex(ValueError,'reference checks'):p.Plan.from_record(record)

    def test_all_6160_values_are_unique(self):
        _,record=fixture();record['generations'][1]['directionSeed']=record['generations'][0]['directionSeed']
        with self.assertRaisesRegex(ValueError,'6160'):p.Plan.from_record(record)

    def test_final_must_beat_start_and_control_by_win_only(self):
        _,record=fixture();record['screenGate']['comparisons']=['FINAL_MINUS_CONTROL']
        with self.assertRaisesRegex(ValueError,'both fixed win95'):p.Plan.from_record(record)

    def test_no_fallback_nominee(self):
        _,record=fixture();record['screenGate']['fallback']=True
        with self.assertRaisesRegex(ValueError,'fallback'):p.Plan.from_record(record)

    def test_budget_counts_every_execution_once(self):
        counts=p.expected_budget()
        self.assertEqual(sum(v for k,v in counts.items() if k!='maximumTerminalExecutions'),141358)
        self.assertEqual(counts['screenReproductions'],len(p.SCREEN_REPLAYS)*3)


class SourceTests(unittest.TestCase):
    def setup_source(self,step=1,phase='CONTROLS',chunk=0):
        warm,record=fixture();plan=p.Plan.from_record(record)
        subjects={key:dict(path='/constructed/'+key,sha256='8'*64) for key in
            ('protocol','arena','originalBundle','projection','warmStart','originProof','center','directions',
             'population','inheritedCalibration','previousUpdate','historical')}
        subjects['originalBundle']['sha256']=warm.scope.original_bundle_sha256
        subjects['protocol']['sha256']=plan.file_sha256
        subjects['projection']['sha256']=warm.scope.projection_sha256
        subjects['warmStart']['sha256']=warm.sha256
        subjects['originProof']['sha256']=record['warmStart']['proofSha256'][p.owner(chunk)]
        if step==1:subjects['previousUpdate']=None
        if phase=='CONTROLS':subjects['population']=None
        return plan,subjects

    def test_first_controls_already_require_the_inherited_seal(self):
        plan,items=self.setup_source();items['inheritedCalibration']=None
        with self.assertRaisesRegex(ValueError,'required from step1'):plan.generation_source(1,0,'CONTROLS',items)

    def test_first_controls_bind_warm_start_and_fixed_host(self):
        plan,items=self.setup_source(chunk=5)
        source=plan.generation_source(1,5,'CONTROLS',items)
        self.assertEqual((source['host'],source['globalStart'],source['count']),('MAC',160,32))
        self.assertEqual(source['warmStart'],items['warmStart'])

    def test_later_controls_bind_predecessor(self):
        plan,items=self.setup_source(step=2);items['previousUpdate']=None
        with self.assertRaisesRegex(ValueError,'immediate predecessor'):plan.generation_source(2,0,'CONTROLS',items)

    def test_population_cannot_be_unsealed(self):
        plan,items=self.setup_source()
        with self.assertRaisesRegex(ValueError,'sealed'):plan.generation_source(1,0,'POPULATION',items)

    def test_shared_prefix_arithmetic_unchanged(self):
        retention={h:dict(sharedBaseBytes=17,generationDeltaBytes=[20,21]) for h in p.HOSTS}
        result=p.prefix_gate(10000,[10,11],retention)
        old=p.base.prefix_gate(10000,[10,11],retention)
        self.assertEqual({k:v for k,v in result.items() if k!='kind'},
            {k:v for k,v in old.items() if k!='kind'})


if __name__=='__main__':unittest.main()
