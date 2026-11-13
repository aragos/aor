"""Constructed protocol/count/clock proofs; no scientific streams or inputs are used."""
import copy
from pathlib import Path
import tempfile
import unittest

import coordinated_es_plan as p


def bank(offset, count):
    return dict(nativeOffset=offset, count=count,
        seeds=[-1000000000+offset+i*8191 for i in range(count)])


def protocol():
    return dict(kind=p.KIND, recipe=copy.deepcopy(p.RECIPE),
        calibration=copy.deepcopy(p.CALIBRATION), prefixGate=copy.deepcopy(p.PREFIX_GATE),
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
        values=retention();values['MAC']['generationDeltaBytes']=[2*1024**3,1]
        result=p.prefix_gate(10000,[1,2],values)
        self.assertFalse(result['storagePassed']);self.assertFalse(result['passed'])

    def test_missing_peer_storage_is_not_assumed_zero(self):
        with self.assertRaisesRegex(ValueError,'both original hosts'):
            p.prefix_gate(10000,[1,2],{'DESKTOP':retention()['DESKTOP']})

    def test_prefix_has_no_reward_or_action_change_field(self):
        values=retention();values['MAC']['reward']=.9
        with self.assertRaisesRegex(ValueError,'separate shared base'):p.prefix_gate(10000,[1,2],values)


if __name__=='__main__':unittest.main()
