"""Constructed protocol/count/clock proofs; no scientific streams or inputs are used."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import coordinated_es_plan as p
import expansion_on_policy_loop as native


def bank(offset, count):
    return dict(nativeOffset=offset, count=count,
        seeds=[-1000000000+offset+i*8191 for i in range(count)])


def protocol():
    return dict(kind=p.KIND, recipe=copy.deepcopy(p.RECIPE),
        calibration=copy.deepcopy(p.CALIBRATION), prefixGate=copy.deepcopy(p.PREFIX_GATE),
        gameLimits=copy.deepcopy(p.GAME_LIMITS),
        historicalControls=copy.deepcopy(p.HISTORICAL_CONTROLS),
        budget=p.expected_budget(), scienceSeconds=p.SCIENCE_SECONDS, auditSeconds=p.AUDIT_SECONDS,
        hosts={h:p.host_limits(h) for h in p.HOSTS}, original=dict(
            bundleSha256='1'*64, scopeSha256='2'*64, ecologySha256='3'*64,
            projectionSha256='4'*64, projectionRecipeSha256='5'*64,
            heads={h:dict(actorSha256=v,dictionarySha256='6'*64) for h,v in p.ORIGINAL_ACTORS.items()}),
        projectionSeed=101000,
        generations=[dict(generation=i,worldBank=bank(100+i,256),directionSeed=101000+i)
            for i in range(1,17)], screenBank=bank(200,2048),
        screenGate=dict(worlds=2048,roles=['FINAL','RAW_G3'],candidate='FINAL_MEAN_16_ONLY',
            intervalMultiplier=1.96,rewardLower95Positive=True,winLower95Positive=True,
            qualification=False), automaticRetry=False, automaticSuccessor=False)


def retention(base=100, delta=1000):
    return {host:dict(sharedBaseBytes=base,generationDeltaBytes=[delta,delta]) for host in p.HOSTS}


class ProtocolTest(unittest.TestCase):
    def test_legacy_protocol_keeps_exact_original_semantic_identity(self):
        self.assertEqual(p.Plan.from_record(protocol()).sha256,
            '2845a84477ea990651da13448588b6bbd6a41bc4d652b1b64c2aa1e43e621f3f')

    def test_v2_recipe_is_explicit_and_changes_only_calibration(self):
        original=protocol();value=copy.deepcopy(original)
        value['calibration']=p.calibration_recipe(2)
        accepted=p.Plan.from_record(value).record
        self.assertEqual(accepted['calibration']['kind'],'coordinated-es-feasible-gap-calibration-2')
        self.assertEqual({k:v for k,v in accepted.items() if k!='calibration'},
            {k:v for k,v in original.items() if k!='calibration'})

    def test_v2_cannot_reinterpret_a_legacy_kind(self):
        value=protocol();value['calibration']=p.calibration_recipe(2)
        value['calibration']['kind']=p.CALIBRATION['kind']
        with self.assertRaisesRegex(ValueError,'prescribed optimizer'):p.Plan.from_record(value)

    def test_v2_cannot_relax_existing_movement_or_eligibility_bounds(self):
        for key,replacement in [('maximumMovement',.41),('minimumPositiveMass',.05),
                ('minimumQuantile',0.),('requireStrictFloat64Interior',False)]:
            with self.subTest(key=key):
                value=protocol();value['calibration']=p.calibration_recipe(2)
                value['calibration'][key]=replacement
                with self.assertRaisesRegex(ValueError,'prescribed optimizer'):p.Plan.from_record(value)

    def test_preparation_rejects_unknown_or_implicit_recipe_versions(self):
        for version in (None,True,0,3,'2'):
            with self.subTest(version=version):
                with self.assertRaisesRegex(ValueError,'explicit supported version'):
                    p.calibration_recipe(version)

    def test_preparation_returns_an_independent_recipe(self):
        chosen=p.calibration_recipe(2);chosen['heads'].append('CARD')
        self.assertEqual(p.calibration_recipe(2)['heads'],['EXPANSION','TOKEN'])

    def test_exact_final_protocol_roundtrips_with_one_lf(self):
        plan=p.Plan.from_record(protocol())
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'protocol.json';path.write_bytes(plan.wire+b'\n')
            self.assertEqual(p.Plan.from_file(path),plan)

    def test_second_framing_lf_is_rejected(self):
        plan=p.Plan.from_record(protocol())
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'protocol.json';path.write_bytes(plan.wire+b'\n\n')
            with self.assertRaisesRegex(ValueError,'framing LF'):p.Plan.from_file(path)

    def test_live_head_movement_cannot_become_a_qualification_gate(self):
        value=protocol();value['recipe']['liveHeadMovement']='MINIMUM_ONE_PERCENT'
        with self.assertRaisesRegex(ValueError,'prescribed optimizer'):p.Plan.from_record(value)

    def test_alternate_final_generation_is_rejected(self):
        value=protocol();value['screenGate']['candidate']='BEST_MEAN'
        with self.assertRaisesRegex(ValueError,'complete final mean'):p.Plan.from_record(value)

    def test_unfinished_game_cannot_be_imputed_or_replaced(self):
        for key in ('rewardImputation','refill'):
            with self.subTest(key=key):
                value=protocol();value['gameLimits'][key]=True
                with self.assertRaisesRegex(ValueError,'prescribed optimizer'):p.Plan.from_record(value)

    def test_changed_original_parameter_container_is_rejected(self):
        value=protocol();value['original']['heads']['EXPANSION']['actorSha256']='f'*64
        with self.assertRaisesRegex(ValueError,'original qualified or untrained'):p.Plan.from_record(value)

    def test_overlapping_generation_banks_are_rejected(self):
        value=protocol();value['generations'][1]['worldBank']=value['generations'][0]['worldBank']
        with self.assertRaisesRegex(ValueError,'all be distinct'):p.Plan.from_record(value)

    def test_duplicate_numerical_stream_is_rejected(self):
        value=protocol();value['generations'][0]['directionSeed']=value['projectionSeed']
        with self.assertRaisesRegex(ValueError,'all be distinct'):p.Plan.from_record(value)

    def test_changed_native_global_index_is_rejected(self):
        value=protocol();value['screenBank']['seeds'][17]+=1
        with self.assertRaisesRegex(ValueError,'global-index formula'):p.Plan.from_record(value)

    def test_no_automatic_retry_or_successor(self):
        for key in ('automaticRetry','automaticSuccessor'):
            with self.subTest(key=key):
                value=protocol();value[key]=True
                with self.assertRaisesRegex(ValueError,'no-retry'):p.Plan.from_record(value)

    def test_unfrozen_resource_profile_is_rejected(self):
        value=protocol();value['hosts']['MAC']['workers']=8
        with self.assertRaisesRegex(ValueError,'resource profiles'):p.Plan.from_record(value)


class OwnershipTest(unittest.TestCase):
    def source_subjects(self,number,phase):
        result={key:dict(path='/constructed/'+key,sha256='a'*64) for key in
            ('protocol','arena','originalBundle','projection','center','directions',
             'population','calibration','previousUpdate','historical')}
        result['originalBundle']['sha256']='1'*64;result['projection']['sha256']='4'*64
        if phase=='CONTROLS':result['population']=None
        if phase=='CONTROLS' and number==1:result['calibration']=None
        if number==1:result['previousUpdate']=None
        return result

    def test_controls_cannot_execute_an_unsealed_population(self):
        plan=p.Plan.from_record(protocol());items=self.source_subjects(1,'CONTROLS')
        source=plan.generation_source(1,5,'CONTROLS',items)
        self.assertIsNone(source['population']);self.assertIsNone(source['calibration'])
        self.assertEqual(source['globalStart'],160)
        self.assertEqual(source['bank'],protocol()['generations'][0]['worldBank'])

    def test_population_requires_calibration_even_in_generation_one(self):
        plan=p.Plan.from_record(protocol());items=self.source_subjects(1,'POPULATION')
        items['calibration']=None
        with self.assertRaisesRegex(ValueError,'single calibration'):
            plan.generation_source(1,0,'POPULATION',items)

    def test_later_control_must_bind_immediate_update(self):
        plan=p.Plan.from_record(protocol());items=self.source_subjects(2,'CONTROLS')
        items['previousUpdate']=None
        with self.assertRaisesRegex(ValueError,'preceding update'):
            plan.generation_source(2,0,'CONTROLS',items)

    def test_all_generation_worlds_have_one_fixed_host(self):
        worlds=[i for host in p.HOSTS for chunk in p.GENERATION_CHUNKS[host] for i in p.indices(chunk)]
        self.assertEqual(worlds,list(range(256)))
        self.assertEqual(sum(len(p.indices(c)) for c in p.GENERATION_CHUNKS['DESKTOP']),160)

    def test_all_final_worlds_have_one_fixed_host(self):
        worlds=[i for host in p.HOSTS for chunk in p.SCREEN_CHUNKS[host] for i in p.indices(chunk,True)]
        self.assertEqual(worlds,list(range(2048)))

    def test_exactly_two_prescribed_mutant_reproductions_per_generation(self):
        for number in range(1,17):
            rows=[r for chunk in range(8) for r in p.replays(number,chunk)]
            self.assertEqual(rows,[dict(globalIndex=0,mutant=p.mutant(2*(number-1))),
                dict(globalIndex=255,mutant=p.mutant(2*(number-1)+1))])

    def test_screen_replays_are_owned_once(self):
        self.assertEqual([i for c in range(16) for i in p.screen_replays(c)],list(p.SCREEN_REPLAYS))

    def test_complete_execution_budget_includes_all_controls(self):
        values=p.expected_budget()
        self.assertEqual(sum(v for k,v in values.items() if k!='maximumTerminalExecutions'),139306)


class ClockAndPrefixTest(unittest.TestCase):
    def native_seconds(self,maximum=None):
        profile=dict(java={'executable':'/constructed/java'},heapMiB=6144,machineCpus=16,runtime='/runtime')
        options={} if maximum is None else dict(maximum_seconds=maximum)
        command=native.native_command(profile,'constructed','source','output',**options)
        return int(command[command.index('--seconds')+1])

    def test_legacy_native_default_ceiling_remains_two_hours(self):
        with patch.object(native.exp,'remaining_seconds',return_value=50000):
            self.assertEqual(self.native_seconds(),7200)

    def test_es_native_stage_still_uses_original_remaining_clock(self):
        with patch.object(native.exp,'remaining_seconds',return_value=50000):
            self.assertEqual(self.native_seconds(86400),50000)

    def test_native_ceiling_cannot_exceed_finite_day(self):
        with self.assertRaisesRegex(ValueError,'at most one day'):self.native_seconds(86401)

    def test_clock_cannot_renew_scientific_allowance(self):
        with self.assertRaisesRegex(ValueError,'unchanged original'):p.Clock(100.,100.+86401.)

    def test_early_terminal_tightens_audit(self):
        self.assertEqual(p.Clock(100.,86500.).audit_deadline(200.),1100.)

    def test_late_terminal_cannot_extend_original_audit_ceiling(self):
        self.assertEqual(p.Clock(100.,86500.).audit_deadline(86520.),87400.)

    def test_exact_time_threshold_passes(self):
        result=p.prefix_gate(14*100+7200,[90,100],retention())
        self.assertTrue(result['passed'])

    def test_transfer_time_is_not_averaged_away(self):
        result=p.prefix_gate(14*95+7200,[90,100],retention())
        self.assertFalse(result['timePassed'])

    def test_shared_base_is_counted_once_and_screen_reserved(self):
        result=p.prefix_gate(10000,[1,2],retention(base=123,delta=17))
        self.assertEqual(result['projectedRetainedBytes']['MAC'],123+16*17+2*1024**3)

    def test_one_host_over_budget_stops_both(self):
        values=retention();values['MAC']['generationDeltaBytes']=[3*1024**3,1]
        result=p.prefix_gate(10000,[1,2],values)
        self.assertFalse(result['storagePassed']);self.assertFalse(result['passed'])

    def test_fixed_host_caps_preserve_minimum_free_space(self):
        self.assertEqual(p.host_limits('DESKTOP')['maximumDataBytes'],64*1024**3)
        self.assertEqual(p.host_limits('MAC')['maximumDataBytes'],40*1024**3)
        self.assertTrue(all(p.host_limits(host)['minimumFreeBytes']==16*1024**3 for host in p.HOSTS))

    def test_same_projected_bytes_can_pass_desktop_but_stop_mac(self):
        values=retention();values['DESKTOP']['generationDeltaBytes']=[3*1024**3,1]
        self.assertTrue(p.prefix_gate(10000,[1,2],values)['storagePassed'])
        values['MAC']['generationDeltaBytes']=[3*1024**3,1]
        self.assertFalse(p.prefix_gate(10000,[1,2],values)['storagePassed'])

    def test_missing_peer_storage_is_not_assumed_zero(self):
        with self.assertRaisesRegex(ValueError,'both original hosts'):
            p.prefix_gate(10000,[1,2],{'DESKTOP':retention()['DESKTOP']})

    def test_prefix_has_no_reward_or_action_change_field(self):
        values=retention();values['MAC']['reward']=.9
        with self.assertRaisesRegex(ValueError,'separate shared base'):p.prefix_gate(10000,[1,2],values)


if __name__=='__main__':unittest.main()
