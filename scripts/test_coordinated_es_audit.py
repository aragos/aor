"""Constructed native/numerical joins; no game, model, inference or numerical update."""
import copy
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

import coordinated_es_audit as audit
import coordinated_es_plan as p
import coordinated_es_screen as screen
from token_paired_campaign_audit import Pins
from test_coordinated_es_plan import protocol


def put(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(p.canonical(value))
    return p.subject(path)


def world(index):
    return dict(index=index,assignedEpoch=1+(index//4)%3,heads=dict(EXPANSION=[],TOKEN=[]))


class Fixture:
    """The lower native archive validator is independently tested by its own wire owner."""
    def __init__(self,root,*,calibration_stop=False):
        self.root=Path(root);self.run=self.root/'run';self.run.mkdir()
        self.protocol=put(self.root/'protocol.json',protocol())
        Path(self.protocol['path']).write_bytes(p.canonical(protocol())+b'\n')
        self.protocol=p.subject(self.protocol['path']);self.plan=p.Plan.from_file(self.protocol['path'])
        self.count=1 if calibration_stop else 2;self.calls=[];self.rows=[]
        self.terminal=dict(status='stopped',completedGenerations=0 if calibration_stop else 2,
            reason='calibration-failed' if calibration_stop else 'prefix-resource-gate')
        for number in range(1,self.count+1):
            directory=self.run/'generations'/f'generation-{number}'
            subjects={phase:[] for phase in ('controls','population')}
            for phase in ('controls',) if calibration_stop else ('controls','population'):
                for chunk in range(8):
                    receipt=dict(kind=p.PREFIX+'-chunk-receipt-1',generation=number,chunk=chunk,
                        host=p.owner(chunk),phase=phase,protocolSha256=self.protocol['sha256'],
                        scienceSha256=p.digest(dict(number=number,phase=phase)),
                        completedGames=32 if phase=='controls' else 1024,
                        historicalReproductions=0 if phase=='controls' else len(p.replays(number,chunk)))
                    subjects[phase].append(put(audit.receipt_path(self.run,number,phase,chunk,'DESKTOP'),receipt))
            if number==1:
                self.panel_record=dict(kind=p.PREFIX+'-public-panel-1',protocolSha256=self.protocol['sha256'],
                    originalBundleSha256=self.plan.record['original']['bundleSha256'],
                    worlds=[world(index) for index in range(256)])
                self.panel=put(directory/'public-panel.json',self.panel_record)
                self.binding=put(directory/'public-panel-binding.json',dict(panel=self.panel,controls=subjects['controls']))
                request=put(directory/'calibrate-request.json',dict(publicPanel=self.panel))
                put(directory/'calibrate/started.json',dict(request=request))
            if not calibration_stop:
                population=put(directory/'portable/population.json',dict(kind=p.PREFIX+'-population-1',generation=number))
                fitness=put(directory/'fitness.json',dict(kind=p.PREFIX+'-fitness-1',populationSha256=population['sha256'],
                    members=[dict(rewards=[.25]*256) for _ in range(32)]))
                row=dict(generation=number,controls=subjects['controls'],populations=subjects['population'],
                    population=population,fitness=fitness)
                self.rows.append(put(directory/'receipt.json',row))

    def chunk(self,plan,item,pins,*,population=None,decode_games=True):
        receipt=pins.read(pins.subject(item));self.calls.append((receipt['generation'],receipt['phase'],receipt['chunk'],decode_games))
        panels=[world(index) for index in p.indices(receipt['chunk'])] if receipt['phase']=='controls' and receipt['generation']==1 else []
        rewards={(index,member):.25 for index in p.indices(receipt['chunk'])
            for member in ([None] if receipt['phase']=='controls' else range(32))} if decode_games else {}
        return receipt,rewards,panels

    def verify(self):
        with patch.object(audit,'chunk',side_effect=self.chunk):
            return audit.training(self.protocol,self.run,terminal=self.terminal)


class JoinTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.fixture=Fixture(self.temp.name)

    def test_complete_prefix_joins_every_original_shard_once(self):
        result=self.fixture.verify()
        self.assertEqual((result['rawControls'],result['mutants'],result['reproductions']),(512,16384,4))
        self.assertEqual(len(self.fixture.calls),32)
        self.assertEqual(len(set(self.fixture.calls)),32)
        self.assertEqual(result['artifactPins'][self.fixture.panel['path']],self.fixture.panel['sha256'])
        wrapper=str((self.fixture.run/'generations/generation-1/receipt.json').absolute())
        self.assertEqual(result['artifactPins'][wrapper],self.fixture.rows[0]['sha256'])

    def test_reordered_native_receipts_cannot_satisfy_numeric_wrapper(self):
        path=self.fixture.rows[0]['path'];row=p.read(path)
        row['controls'][0],row['controls'][1]=row['controls'][1],row['controls'][0];put(path,row)
        with self.assertRaisesRegex(ValueError,'exact native shards'):self.fixture.verify()

    def test_same_bytes_at_another_fitness_path_cannot_replace_consumed_subject(self):
        path=self.fixture.rows[0]['path'];row=p.read(path)
        row['fitness']=put(self.fixture.root/'unrelated-fitness.json',p.read(row['fitness']['path']));put(path,row)
        with self.assertRaisesRegex(ValueError,'exact native shards'):self.fixture.verify()

    def test_same_bytes_at_another_population_path_cannot_replace_native_actor(self):
        path=self.fixture.rows[0]['path'];row=p.read(path)
        row['population']=put(self.fixture.root/'unrelated-population.json',p.read(row['population']['path']));put(path,row)
        with self.assertRaisesRegex(ValueError,'exact native shards'):self.fixture.verify()

    def test_self_consistent_panel_rewrite_still_fails_native_reconstruction(self):
        record=copy.deepcopy(self.fixture.panel_record);record['worlds'][0]['assignedEpoch']=2
        panel=put(self.fixture.panel['path'],record)
        binding=p.read(self.fixture.binding['path']);binding['panel']=panel;put(self.fixture.binding['path'],binding)
        request=put(Path(panel['path']).parent/'calibrate-request.json',dict(publicPanel=panel))
        put(Path(panel['path']).parent/'calibrate/started.json',dict(request=request))
        with self.assertRaisesRegex(ValueError,'evidence subject changed'):self.fixture.verify()

    def test_panel_lf_is_not_the_numeric_canonical_subject(self):
        path=Path(self.fixture.panel['path']);path.write_bytes(path.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'evidence subject changed'):self.fixture.verify()

    def test_calibration_cannot_read_an_equal_but_unrelated_panel(self):
        panel=put(self.fixture.root/'other-panel.json',self.fixture.panel_record)
        request=put(self.fixture.root/'other-request.json',dict(publicPanel=panel))
        put(Path(self.fixture.panel['path']).parent/'calibrate/started.json',dict(request=request))
        with self.assertRaisesRegex(ValueError,'exact reconstructed native'):self.fixture.verify()

    def test_binding_cannot_substitute_a_native_control_receipt(self):
        binding=p.read(self.fixture.binding['path']);binding['controls']=list(reversed(binding['controls']))
        put(self.fixture.binding['path'],binding)
        with self.assertRaisesRegex(ValueError,'exact ordered native'):self.fixture.verify()


class PanelAndStopTests(unittest.TestCase):
    def test_original_receipt_symlink_is_rejected_before_path_normalization(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root);put(path/'actual.json',{})
            (path/'alias.json').symlink_to(path/'actual.json')
            with self.assertRaisesRegex(ValueError,'original regular file'):
                audit.file_subject(Pins(),path/'alias.json')

    def test_native_public_worlds_are_normalized_during_one_archive_pass(self):
        with tempfile.TemporaryDirectory() as root:
            plan=p.Plan.from_record(protocol());files={audit.storage.game_name(i):{'sha256':'d'*64} for i in range(32)}
            receipt=dict(kind=p.PREFIX+'-chunk-receipt-1',host='DESKTOP',generation=1,chunk=0,phase='controls',
                panels=[f'panel-{i}.json' for i in range(32)],games=list(files),replays=[],files=files,
                signature='a'*64,scopeSha256='2'*64,scienceSha256='b'*64,sourceSha256='c'*64,
                archive='evidence.tar.gz',archiveSha256='e'*64)
            values={name:{} for name in ('source.json','prepared.json','finished.json')}
            for index in range(32):
                values[f'panel-{index}.json']=dict(kind=p.PREFIX+'-panel-1',
                    **{key:receipt[key] for key in ('signature','scopeSha256','scienceSha256','sourceSha256')},
                    originalBundleSha256='1'*64,generation=1,globalIndex=index,
                    seed=plan.bank(1)['worldBank']['seeds'][index],seat=index%4,assignedEpoch=1+(index//4)%3,
                    heads=dict(EXPANSION=[],TOKEN=[]),windows={head:dict(observed=0,branching=0)
                        for head in ('EXPANSION','TOKEN')},game=dict(file=audit.storage.game_name(index),sha256='d'*64))
            expected={key:value for key,value in receipt.items() if key not in ('archive','archiveSha256')}
            item=put(Path(root)/'receipt.json',receipt)
            with patch.object(audit,'stream_archive',return_value=values) as stream:
                with patch.object(audit.storage,'generation_record',return_value=expected):
                    _,rewards,panels=audit.chunk(plan,item,Pins(),decode_games=False)
            self.assertEqual(panels,[world(index) for index in range(32)])
            self.assertEqual(rewards,{})
            stream.assert_called_once()
            self.assertFalse(set(receipt['games']) & stream.call_args.args[3])

    def test_incremental_hash_matches_full_canonical_with_nonempty_unicode_state(self):
        plan=p.Plan.from_record(protocol());worlds=[world(index) for index in range(256)]
        worlds[8]['heads']['TOKEN']=[dict(number=17,observation={'state':{'name-é':.25},'actions':[{},{}]})]
        digest=audit.PublicPanelDigest(plan,'a'*64)
        for value in worlds:digest.append(value)
        expected=dict(kind=p.PREFIX+'-public-panel-1',protocolSha256='a'*64,
            originalBundleSha256=plan.record['original']['bundleSha256'],worlds=worlds)
        self.assertEqual(digest.digest(),p.digest(expected))

    def test_duplicate_world_is_rejected(self):
        digest=audit.PublicPanelDigest(p.Plan.from_record(protocol()),'a'*64);digest.append(world(0))
        with self.assertRaisesRegex(ValueError,'once in global order'):digest.append(world(0))

    def test_missing_world_cannot_finish_panel(self):
        digest=audit.PublicPanelDigest(p.Plan.from_record(protocol()),'a'*64)
        with self.assertRaisesRegex(ValueError,'all256'):digest.digest()

    def test_calibration_stop_checks_public_join_without_semantic_games(self):
        with tempfile.TemporaryDirectory() as root:
            fixture=Fixture(root,calibration_stop=True);result=fixture.verify()
            self.assertEqual((result['rawControls'],result['mutants'],result['reproductions']),(256,0,0))
            self.assertFalse(result['semanticGameRewardsChecked'])
            self.assertEqual(len(fixture.calls),8)
            self.assertTrue(all(not row[3] for row in fixture.calls))

    def test_stopped_update_binds_original_fitness_before_no_candidate_receipt(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);population=put(directory/'population.json',{});fitness=put(directory/'fitness.json',{})
            request=put(directory/'update-request.json',dict(population=population,fitness=fitness))
            put(directory/'update/stopped.json',dict(request=request))
            audit.bind_generation(Pins(),directory,1,0,[],[],population,fitness)

    def test_stopped_update_rejects_same_bytes_from_different_native_population(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);population=put(directory/'population.json',{});fitness=put(directory/'fitness.json',{})
            request=put(directory/'update-request.json',dict(population=put(directory/'other.json',{}),fitness=fitness))
            put(directory/'update/stopped.json',dict(request=request))
            with self.assertRaisesRegex(ValueError,'exact retained native'):
                audit.bind_generation(Pins(),directory,1,0,[],[],population,fitness)

    def test_bad_numeric_calibration_precedes_native_semantic_reads(self):
        with tempfile.TemporaryDirectory() as root:
            directory=Path(root);protocol_subject=put(directory/'protocol.json',{})
            start=time.time()-1;clock=dict(kind=p.PREFIX+'-clock-1',protocolSha256=protocol_subject['sha256'],
                startedEpoch=start,deadlineEpoch=start+p.SCIENCE_SECONDS)
            put(directory/'clock.json',clock);put(directory/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',
                host='DESKTOP',status='stopped',clock=clock,updatedEpoch=start+.5))
            with patch.object(audit.numerical,'verify',side_effect=ValueError('bad calibration')):
                with patch.object(audit,'training') as native:
                    with self.assertRaisesRegex(ValueError,'bad calibration'):audit.verify(protocol_subject,directory)
                    native.assert_not_called()


class FinalCenterTests(unittest.TestCase):
    def verify(self,root,center_sha):
        directory=Path(root);protocol_subject=put(directory/'protocol.json',protocol())
        Path(protocol_subject['path']).write_bytes(p.canonical(protocol())+b'\n');protocol_subject=p.subject(protocol_subject['path'])
        start=time.time()-1;clock=dict(kind=p.PREFIX+'-clock-1',protocolSha256=protocol_subject['sha256'],
            startedEpoch=start,deadlineEpoch=start+p.SCIENCE_SECONDS)
        put(directory/'clock.json',clock);put(directory/'terminal.json',dict(kind=p.PREFIX+'-terminal-1',
            host='DESKTOP',status='completed',clock=clock,updatedEpoch=start+.5,completedGenerations=16,
            finalAdmission={'path':'/constructed/admission.json','sha256':'f'*64},
            finalBundle={'path':'/constructed/bundle.json','sha256':'e'*64}))
        with patch.object(audit.numerical,'verify',return_value={'eligibleFinalCenter':{'sha256':'a'*64}}):
            with patch.object(audit,'training',return_value=dict(rawControls=4096,mutants=131072,reproductions=32)):
                with patch.object(screen,'audit_historical'):
                    with patch.object(screen,'audit_completed',return_value=dict(games=4096,reproductions=8,historicalControls=2)):
                        with patch.object(screen,'final_bindings',return_value=({'center':{'sha256':center_sha}},{})):
                            return audit.verify(protocol_subject,directory)

    def test_screened_center_must_match_recomputed_final_mean(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaisesRegex(ValueError,'independently recomputed final mean16'):self.verify(root,'b'*64)

    def test_exact_screened_center_preserves_complete_global_budget(self):
        with tempfile.TemporaryDirectory() as root:
            result=self.verify(root,'a'*64)
            self.assertTrue(result['passed'])
            self.assertEqual(result['scientificTerminalExecutions'],p.MAXIMUM_TERMINALS)


if __name__=='__main__':unittest.main()
