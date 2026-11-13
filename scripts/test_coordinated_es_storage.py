"""Constructed byte/accounting and lossless-storage boundaries; no game/model inputs."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch

import coordinated_es_storage as s
import coordinated_es_plan as p
from test_coordinated_es_plan import protocol


class ResourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name)/'base';self.base.mkdir()
        self.root=Path(self.tmp.name)/'run';self.root.mkdir()
        (self.base/'original.json').write_bytes(b'abc')

    def put(self,name,raw=b'12'):
        path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
        return path

    def test_every_generation_copy_and_temporary_file_counts(self):
        for name in ('generations/generation-1/native/game.tmp',
            'publications/population-1/publication.tar.gz',
            'downloads/controls-1/center.json','incoming/controls-1-chunk-5/evidence.tar.gz'):
            self.put(name)
        self.put('worker-owner.json',b'abcd');self.put('.DS_Store',b'ignored')
        result=s.byte_census(self.root,[self.base])
        self.assertEqual(result['generationBytes']['1'],8)
        self.assertEqual(result['sharedBaseBytes'],7)
        self.assertEqual(result['totalBytes'],15)

    def test_receipt_counts_its_exact_own_bytes(self):
        self.put('generations/generation-1/a.json')
        value=s.retention_record(self.root,[self.base],'MAC',1,'1'*64)
        receipt=self.root/'generations/generation-1/retention.json'
        self.assertEqual(value['generationBytes']['1'],2+receipt.stat().st_size)
        self.assertEqual(value['totalBytes'],5+receipt.stat().st_size)
        self.assertEqual(value['regularFiles'],3)

    def test_resource_receipt_is_once_only(self):
        s.retention_record(self.root,[self.base],'MAC',1,'1'*64)
        with self.assertRaises(ValueError):s.retention_record(self.root,[self.base],'MAC',1,'1'*64)

    def test_package_run_overlap_rejected(self):
        with self.assertRaises(ValueError):s.byte_census(self.root,[self.root])

    def test_overlapping_input_directories_rejected(self):
        sub=self.base/'nested';sub.mkdir()
        with self.assertRaises(ValueError):s.byte_census(self.root,[self.base,sub])

    def test_symlink_cannot_hide_data(self):
        (self.root/'link').symlink_to(self.base,target_is_directory=True)
        with self.assertRaises(ValueError):s.byte_census(self.root,[self.base])

    def test_unknown_generation_directory_rejected(self):
        self.put('generations/best/a.json')
        with self.assertRaises(ValueError):s.byte_census(self.root,[self.base])

    def test_future_generation_bytes_rejected(self):
        self.put('generations/generation-2/a.json')
        row=s.retention_record(self.root,[self.base],'MAC',1,'1'*64)
        with self.assertRaises(ValueError):s.prefix_retention(row,'1'*64,'MAC',1)

    def test_both_actual_prefix_deltas_are_retained(self):
        self.put('generations/generation-1/a.json',b'a'*7)
        self.put('generations/generation-2/a.json',b'b'*11)
        row=s.retention_record(self.root,[self.base],'MAC',2,'1'*64)
        result=s.prefix_retention(row,'1'*64,'MAC',2)
        self.assertEqual(result['sharedBaseBytes'],3)
        self.assertEqual(result['generationDeltaBytes'][0],7)
        self.assertGreater(result['generationDeltaBytes'][1],11)


class PanelBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.plan=p.Plan.from_record(protocol())
        self.receipt=dict(signature='a'*64,scopeSha256='2'*64,scienceSha256='b'*64,
            sourceSha256='c'*64,files={'game-RAW_G3-0.json':{'sha256':'d'*64}})
        self.value=dict(kind=p.PREFIX+'-panel-1',**{key:self.receipt[key] for key in
            ('signature','scopeSha256','scienceSha256','sourceSha256')},
            originalBundleSha256='1'*64,generation=1,globalIndex=0,
            seed=self.plan.bank(1)['worldBank']['seeds'][0],seat=0,assignedEpoch=1,
            heads=dict(EXPANSION=[],TOKEN=[]),windows={head:dict(observed=3,branching=0)
                for head in ('EXPANSION','TOKEN')},game=dict(file='game-RAW_G3-0.json',sha256='d'*64))

    def state(self,number):
        return dict(number=number,batchStartNumber=number-1,epoch=1,referenceIndex=0,
            observation=dict(baselineIndex=0,actions=[{},{}]))

    def test_missing_windows_remain_public_worlds(self):
        row=s.panel_world(self.value,self.receipt,self.plan,0)
        self.assertEqual(row,dict(index=0,assignedEpoch=1,heads={'EXPANSION':[],'TOKEN':[]}))

    def test_first_last_are_distinct_chronological_branching_states(self):
        self.value['heads']['EXPANSION']=[self.state(10),self.state(20)]
        self.value['windows']['EXPANSION']['branching']=3
        row=s.panel_world(self.value,self.receipt,self.plan,0)
        self.assertEqual([x['number'] for x in row['heads']['EXPANSION']],[10,20])

    def test_duplicate_single_window_cannot_double_weight_a_world(self):
        self.value['heads']['EXPANSION']=[self.state(10),self.state(10)]
        self.value['windows']['EXPANSION']['branching']=2
        with self.assertRaisesRegex(ValueError,'distinct chronological'):
            s.panel_world(self.value,self.receipt,self.plan,0)

    def test_terminal_field_cannot_enter_calibration_panel(self):
        self.value['terminal']={'reward':1}
        with self.assertRaisesRegex(ValueError,'own/public'):
            s.panel_world(self.value,self.receipt,self.plan,0)

    def test_panel_cannot_swap_its_opaque_complete_game(self):
        self.value['game']['sha256']='e'*64
        with self.assertRaisesRegex(ValueError,'opaque complete game'):
            s.panel_world(self.value,self.receipt,self.plan,0)

    def test_unsealed_calibration_cannot_read_population_or_games(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'failed-calibration.json'
            s.publish_json(path,dict(kind=p.PREFIX+'-calibration-1',passed=False))
            with patch.object(s,'members') as semantic_read:
                with self.assertRaisesRegex(ValueError,'Fitness is closed'):
                    s.fitness(self.plan,'a'*64,{'path':'/absent','sha256':'b'*64},p.subject(path),[])
                semantic_read.assert_not_called()


class CalibrationVersionBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)

    def plan(self,version):
        value=protocol();value['calibration']=p.calibration_recipe(version)
        return p.Plan.from_record(value)

    def seal(self,version,**changes):
        value=dict(kind=p.PREFIX+'-calibration-'+str(version),passed=True,complete=True,
            protocolSha256='a'*64,semanticFitnessDecoded=False,calibrationAttempts=1)
        value.update(changes)
        path=self.root/'calibration.json';s.publish_json(path,value)
        return p.subject(path)

    def rejects_before_population(self,plan,subject,message='Fitness is closed'):
        with patch.object(p,'checked_subject',wraps=p.checked_subject) as subjects:
            with patch.object(s,'members') as members:
                with self.assertRaisesRegex(ValueError,message):
                    s.fitness(plan,'a'*64,{'path':'/absent-population','sha256':'b'*64},subject,[])
                self.assertEqual(subjects.call_args_list,[call(subject)])
                members.assert_not_called()

    def test_v1_plan_rejects_complete_v2_seal_before_population(self):
        self.rejects_before_population(self.plan(1),self.seal(2))

    def test_v2_plan_rejects_complete_v1_seal_before_population(self):
        self.rejects_before_population(self.plan(2),self.seal(1))

    def test_v2_failed_calibration_cannot_open_population(self):
        self.rejects_before_population(self.plan(2),self.seal(2,passed=False))

    def test_v2_incomplete_calibration_cannot_open_population(self):
        self.rejects_before_population(self.plan(2),self.seal(2,complete=False))

    def test_v2_wrong_protocol_cannot_open_population(self):
        self.rejects_before_population(self.plan(2),self.seal(2,protocolSha256='c'*64))

    def test_v2_recalibration_cannot_open_population(self):
        self.rejects_before_population(self.plan(2),self.seal(2,calibrationAttempts=2))

    def test_v2_prior_fitness_decode_cannot_open_population(self):
        self.rejects_before_population(self.plan(2),self.seal(2,semanticFitnessDecoded=True))

    def test_changed_seal_bytes_cannot_open_population(self):
        subject=self.seal(2)
        Path(subject['path']).write_bytes(b'{}\n')
        self.rejects_before_population(self.plan(2),subject,'Immutable subject bytes changed')


class ReproductionInventoryTests(unittest.TestCase):
    def setUp(self):
        value=protocol();dictionaries={head:{} for head in p.HEADS}
        for head in p.HEADS:value['original']['heads'][head]['dictionarySha256']=p.digest({})
        self.plan=p.Plan.from_record(value)
        subjects={key:dict(path='/constructed/'+key,sha256='a'*64) for key in
            ('protocol','arena','originalBundle','projection','center','directions','population','calibration','historical')}
        subjects['previousUpdate']=None;subjects['originalBundle']['sha256']='1'*64;subjects['projection']['sha256']='4'*64
        self.request=self.plan.generation_source(1,0,'POPULATION',subjects)
        self.prepared=dict(kind=p.PREFIX+'-prepared-1',stage='POPULATION',generation=1,globalStart=0,count=32,
            bank=self.plan.bank(1)['worldBank'],subjects={key:None if row is None else dict(row,originalPath=row['path'])
                for key,row in subjects.items()},source={'sha256':'a'*64},scopeSha256='2'*64,ecologySha256='3'*64,
            originalBundle={'sha256':'1'*64},arena={'sha256':'a'*64},dictionaries=dictionaries,
            inputPins={row['path']:row['sha256'] for row in subjects.values() if row is not None},
            signature='b'*64,scienceSha256='c'*64)
        self.files={s.game_name(index,member):dict(sha256='a'*64,size=1) for index in range(32) for member in range(32)}
        self.files.update({name:dict(sha256='a'*64,size=1) for name in ('source.json','arena.json','prepared.json')})
        self.finished=dict(kind=p.PREFIX+'-complete-1',**{key:self.prepared[key] for key in
            ('signature','scopeSha256','scienceSha256','stage','generation','globalStart','count')},
            attemptedGames=1024,completedGames=1024,expectedGames=1024,canonicalGames=1,
            files={name:row['sha256'] for name,row in self.files.items()})
        directory='replay-PLUS-00-0';receipt=directory+'/'+directory+'.json';actual=directory+'/game-PLUS-00-0.json'
        self.files.update({name:dict(sha256='a'*64,size=1) for name in ('finished.json',receipt,actual)})
        game=dict(canonical=dict(replayVerified=True),terminal=dict(elapsedMillis=0))
        self.values={'game-PLUS-00-0.json':game,actual:game,receipt:dict(kind=p.PREFIX+'-reproduction-1',
            signature='b'*64,scienceSha256='c'*64,sourceSha256='a'*64,role='PLUS-00',globalIndex=0,
            original={'sha256':'a'*64},actual={'file':'game-PLUS-00-0.json','sha256':'a'*64},
            terminalEqual=True,canonicalEqual=True,replayVerified=True,scientificTerminalExecutions=1,
            attemptedGames=1,completedGames=1)}

    def check(self):
        return s.generation_record(self.plan,self.request,'a'*64,self.prepared,self.finished,self.files,1,0,'population',self.values)

    def test_actual_game_and_receipt_are_the_complete_reproduction(self):
        self.assertEqual(self.check()['historicalReproductions'],1)

    def test_extra_replay_output_cannot_hide_under_directory_prefix(self):
        self.files['replay-PLUS-00-0/unexpected.json']=dict(sha256='a'*64,size=1)
        with self.assertRaisesRegex(ValueError,'exactly its actual game'):self.check()


if __name__=='__main__':unittest.main()
