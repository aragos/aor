"""Constructed conservation/terminal joins; native archives and numeric math are separate proofs."""
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import coordinated_es_continuation as c
import coordinated_win_audit as audit
import coordinated_win_plan as p
from test_coordinated_win_plan import fixture


def put(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(p.canonical(value));return p.subject(path)


class ConservationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve();_,record=fixture();self.plan=p.Plan.from_record(record)
        path=self.root/'protocol.json';path.write_bytes(self.plan.wire+b'\n');self.protocol=p.subject(path)
        self.run=self.root/'run';self.run.mkdir();self.calls=[]
        self.terminal=dict(status='stopped',reason='prefix-resource-gate',completedGenerations=2)
        for number in (1,2):
            directory=self.run/'generations'/f'generation-{number}'
            subjects={phase:[] for phase in ('controls','population')}
            for phase in subjects:
                for ordinal in range(8):
                    row=dict(kind=p.PREFIX+'-chunk-receipt-1',generation=number,chunk=ordinal,
                        host=p.owner(ordinal),phase=phase,protocolSha256=self.protocol['sha256'],
                        scienceSha256=p.digest([number,phase]),completedGames=32 if phase=='controls' else 1024,
                        historicalReproductions=0 if phase=='controls' else len(p.replays(number,ordinal)))
                    subjects[phase].append(put(audit.receipt_path(self.run,number,phase,ordinal,'DESKTOP'),row))
            population=put(directory/'portable/population.json',dict(kind=c.POPULATION_KIND,
                step=number,protocolSha256=self.protocol['sha256']))
            fitness=put(directory/'fitness.json',dict(kind=c.FITNESS_KIND,objective=c.OBJECTIVE,
                protocolSha256=self.protocol['sha256'],populationSha256=population['sha256'],
                members=[dict(winShares=[.25]*256) for _ in range(32)]))
            put(directory/'receipt.json',dict(generation=number,controls=subjects['controls'],
                populations=subjects['population'],population=population,fitness=fitness))

    def chunk(self,plan,item,pins,*,population=None,decode_games=True):
        row=pins.read(pins.subject(item));self.calls.append((row['generation'],row['phase'],row['chunk']))
        values={(index,member):.25 for index in p.indices(row['chunk']) for member in
            ([None] if row['phase']=='controls' else range(32))}
        return row,values,[]

    def verify(self):
        with patch.object(audit,'chunk',side_effect=self.chunk):
            return audit.training(self.protocol,self.run,terminal=self.terminal)

    def test_two_completed_steps_count_global_support_controls_without_double_counting_mac(self):
        result=self.verify()
        self.assertEqual((result['supportedControls'],result['mutants'],result['reproductions']),(512,16384,4))
        self.assertEqual(len(self.calls),32)
        self.assertNotIn('rawControls',result)

    def test_native_win_rows_must_match_every_consumed_fitness_entry(self):
        path=self.run/'generations/generation-1/fitness.json';value=p.read(path)
        value['members'][31]['winShares'][255]=.5;put(path,value)
        with self.assertRaisesRegex(ValueError,'controlled-seat win share'):self.verify()

    def test_old_reward_units_cannot_enter_continuation_audit(self):
        path=self.run/'generations/generation-1/fitness.json';value=p.read(path)
        value['objective']='AUTHORITATIVE_NORMALIZED_TERMINAL';put(path,value)
        with self.assertRaisesRegex(ValueError,'controlled-seat win share'):self.verify()

    def test_native_receipt_order_cannot_change_consumed_fitness_population(self):
        path=self.run/'generations/generation-2/receipt.json';value=p.read(path)
        value['populations']=list(reversed(value['populations']));put(path,value)
        with self.assertRaisesRegex(ValueError,'these exact native shards'):self.verify()

    def test_calibration_stop_is_not_a_continuation_branch(self):
        self.terminal.update(reason='calibration-failed',completedGenerations=0)
        with self.assertRaisesRegex(ValueError,'cannot introduce recalibration'):self.verify()
        self.assertEqual(self.calls,[])


class TerminalTests(unittest.TestCase):
    def verify(self,root,*,host='DESKTOP',center_sha='a'*64,screen_games=None):
        root=Path(root);_,record=fixture();plan=p.Plan.from_record(record)
        protocol=root/'protocol.json';protocol.write_bytes(plan.wire+b'\n');subject=p.subject(protocol)
        start=time.time()-1;clock=dict(kind=p.PREFIX+'-clock-1',protocolSha256=subject['sha256'],
            startedEpoch=start,deadlineEpoch=start+p.SCIENCE_SECONDS)
        put(root/'clock.json',clock);put(root/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',host=host,
            status='completed',clock=clock,updatedEpoch=start+.5,completedGenerations=16,
            finalAdmission=dict(path='/constructed/admission.json',sha256='f'*64),
            finalBundle=dict(path='/constructed/bundle.json',sha256='e'*64)))
        desktop=host=='DESKTOP'
        counts=dict(supportedControls=4096 if desktop else 1536,mutants=131072 if desktop else 49152,
            reproductions=32 if desktop else 16)
        screen=SimpleNamespace(audit_historical=lambda *a:None,
            audit_completed=lambda *a:dict(games=screen_games if screen_games is not None else 6144 if desktop else 2304,
                reproductions=12 if desktop else 6,historicalControls=2 if desktop else 0),
            final_bindings=lambda *a:({'center':{'sha256':center_sha}},{}))
        with patch.dict('sys.modules',{audit.SCREEN_MODULE:screen}), \
                patch.object(audit,'verify_numerical',return_value=dict(eligibleFinalCenter=dict(sha256='a'*64))), \
                patch.object(audit,'training',return_value=counts):
            return audit.verify(subject,root)

    def test_global_completed_budget_is_exactly141358(self):
        with tempfile.TemporaryDirectory() as root:
            result=self.verify(root)
            self.assertEqual(result['scientificTerminalExecutions'],141358)
            self.assertFalse(result['interpretationAllowed'])

    def test_mac_retains_only_its_subset_without_claiming_global_executions(self):
        with tempfile.TemporaryDirectory() as root:
            result=self.verify(root,host='MAC')
            self.assertEqual(result['scientificTerminalExecutions'],53014)
            self.assertFalse(result['numericalRecomputed'])

    def test_shortened_final_screen_cannot_claim_complete_campaign(self):
        with tempfile.TemporaryDirectory() as root,self.assertRaisesRegex(ValueError,'exact global count'):
            self.verify(root,screen_games=4096)

    def test_screened_center_must_equal_the_independently_recomputed_final_mean(self):
        with tempfile.TemporaryDirectory() as root,self.assertRaisesRegex(ValueError,'independently recomputed'):
            self.verify(root,center_sha='b'*64)


if __name__=='__main__':unittest.main()
