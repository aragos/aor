"""Synthetic bounded-source and portable archive checks; no native games or fitting."""
import gzip,json,tarfile,tempfile,unittest
from pathlib import Path
from unittest import mock
import automata_experiments as exp
import joint_state_corpus as corpus
import joint_state_data as data

class SourceTest(unittest.TestCase):
    def test_plan_preserves_fixed_splits_and_replay_seats(self):
        names=[*[f'training-{i}' for i in range(1,13)],'validation-1','validation-2','test-1','test-2']
        banks={name:dict(nativeOffset=52000007+i,count=256,seeds=exp.arena_seeds(52000007+i,256),role=name.split('-')[0]) for i,name in enumerate(names)}
        chunks=data.chunk_plan(dict(seedBanks=banks))
        self.assertEqual([12,2,2],[sum(x['split']==s for x in chunks) for s in ('training','validation','test')])
        self.assertEqual([0,85,170,255]*4,[x['replayIndex'] for x in chunks])
        self.assertEqual(4096,len({s for x in chunks for s in x['seeds']}))
    def test_source_retains_explicit_card_purchase_and_overlay_roles(self):
        p=dict(ordinary='m8',prior='f0',bundle='zero-g2',rivalBundle='raw-g2',expansion='e-m8',experimentalRulesTransfer={})
        s=data.source_document(p,dict(nativeOffset=52,replayIndex=85))
        self.assertEqual(('m8','f0',['raw-g2'],[85]),(s['baseline'],s['sharedPurchaseBaseline'],s['opponents'],s['replayIndices']))
        self.assertEqual(dict(candidateSelector='FIXED_16_TO_15',opponentSelectors=['FIXED_16_TO_15']),s['tokenOrder'])
    def test_supervisor_has_one_data_attempt_and_original_resource_caps(self):
        s=data.campaign_spec(Path('/frozen'),dict(python=dict(executable='/python')),[])
        self.assertEqual(dict(maximumSeconds=7200,maximumDataBytes=8*exp.GIB,maximumRssBytes=6*exp.GIB,minimumFreeBytes=8*exp.GIB),s['limits'])
        self.assertEqual([('collect',1)],[(x['id'],x['maximumAttempts']) for x in s['stages']])
    def test_replay_seam_leaves_native_output_absent(self):
        with tempfile.TemporaryDirectory() as t:
            target=Path(t)/'replay';p=dict(java=dict(executable='/java'),heapMiB=64,workers=6,runtime='/runtime')
            def run(*a,**k):self.assertFalse(target.exists());return mock.Mock(returncode=0)
            with mock.patch.object(exp,'remaining_seconds',return_value=50),mock.patch.object(exp.subprocess,'run',side_effect=run):
                exp.native(p,'joint-replay',Path(t)/'source',target,create_output=False)
    def test_native_legacy_default_still_precreates_output(self):
        with tempfile.TemporaryDirectory() as t:
            target=Path(t)/'collect';p=dict(java=dict(executable='/java'),heapMiB=64,workers=6,runtime='/runtime')
            def run(*a,**k):self.assertTrue(target.is_dir());return mock.Mock(returncode=0)
            with mock.patch.object(exp,'remaining_seconds',return_value=50),mock.patch.object(exp.subprocess,'run',side_effect=run):
                exp.native(p,'joint-collect',Path(t)/'source',target)
    def test_partial_attempt_cannot_launch_more_games(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);(root/'chunks').mkdir()
            with mock.patch.object(exp,'native') as native,self.assertRaisesRegex(ValueError,'One fresh'):
                data.collect({},root/'protocol',root)
            native.assert_not_called()

class CorpusTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.collection=self.root/'collection';self.collection.mkdir()
        self.dictionary=dict(sha256='dictionary',metadata=dict(stateFeatureNames=['card.cash','purchase.epoch']))
        self.bindings=dict(bundleSha256='bundle',cardModelSha256='card',purchaseModelSha256='purchase')
        self.chunk=dict(index=1,split='training',seeds=[-999])
        self.game=dict(index=0,gameSeed=-999,controlledSeat=0,configuration=exp.CONFIGURATION,signature='signature',**self.bindings,
            cardDecisions=[dict(number=3,turn=1,observation=dict(stateFeatures={'cash':.25},actions=['private-action'],baselineIndex=0))],
            purchaseDecisions=[dict(number=8,turn=2,observation=dict(stateFeatures={'epoch':2/3}))],terminal=dict(reward=.8))
        self.write_game()
    def write_game(self):
        with gzip.open(self.collection/'game-0.json.gz','wt') as f:json.dump(self.game,f)
        common=dict(version='joint-state-chronology-1',signature='signature',originIndex=0,sourceGameSha256=exp.sha(self.collection/'game-0.json.gz'),dictionarySha256='dictionary')
        rows=[]
        for phase,key in (('CARD','cardDecisions'),('PURCHASE','purchaseDecisions')):
            rows.extend(dict(number=r['number'],turn=r['turn'],phase=phase,sourceRow=i,stateFeatures={phase.lower()+'.'+k:v for k,v in r['observation']['stateFeatures'].items()}) for i,r in enumerate(self.game[key]))
        rows.sort(key=lambda r:r['number'])
        for name,value in [('collection',dict(signature='signature')),('joint-state-0',dict(common,rows=rows)),('joint-label-0',dict(common,terminal=self.game['terminal'])),('joint-coverage-0',dict(common,independentWorlds=1,cardDecisions=len(self.game['cardDecisions']),purchaseDecisions=len(self.game['purchaseDecisions'])))]:exp.document(self.collection/(name+'.json'),value,immutable=False)
    def origin(self):return corpus.origin(self.collection,0,self.chunk,self.dictionary,self.bindings)
    def test_portable_identity_and_units_exclude_action_and_label_inputs(self):
        row=self.origin();self.assertEqual(0,row['id']);self.assertEqual('chunk-01/origin-000',row['originKey'])
        self.assertEqual(dict(CARD=1,PURCHASE=1),row['phaseCounts']);self.assertEqual(2,row['nonzeros'])
        self.assertNotIn('reward',row);self.assertNotIn('private-action',str(row))
    def test_sparse_entry_count_preserves_explicit_zero_values(self):
        self.game['cardDecisions'][0]['observation']['stateFeatures']['cash']=0
        self.write_game()
        self.assertEqual(2,self.origin()['nonzeros'])
    def test_empty_phases_retain_the_world_without_rows(self):
        self.game.update(cardDecisions=[],purchaseDecisions=[]);self.write_game()
        self.assertEqual(0,self.origin()['decisions']);self.assertEqual(dict(CARD=0,PURCHASE=0),self.origin()['phaseCounts'])
    def test_changed_terminal_label_is_rejected(self):
        p=self.collection/'joint-label-0.json';x=exp.read(p);x['terminal']['reward']=0;exp.document(p,x,immutable=False)
        with self.assertRaisesRegex(ValueError,'separate'):self.origin()
    def test_chosen_action_cannot_become_a_shared_state_feature(self):
        p=self.collection/'joint-state-0.json';x=exp.read(p);x['rows'][0]['stateFeatures']['chosenAction']=1;exp.document(p,x,immutable=False)
        with self.assertRaisesRegex(ValueError,'exact existing'):self.origin()
    def archive_fixture(self):
        (self.root/'reproduction').mkdir();(self.root/'reproduction/receipt.json').write_text('{}')
        order=corpus.world_members(0)+['collection/collection.json','reproduction/receipt.json']
        files={n:dict(sha256=exp.sha(self.root/n),size=(self.root/n).stat().st_size) for n in order}
        return files,order
    def test_lossless_archive_order_is_verified_before_removal(self):
        files,order=self.archive_fixture();original={n:(self.root/n).read_bytes() for n in order}
        result=corpus.archive(self.root,files,order)
        with tarfile.open(self.root/corpus.ARCHIVE,'r:gz') as a:
            self.assertEqual(order,a.getnames());self.assertEqual(original,{n:a.extractfile(n).read() for n in order})
        self.assertFalse(self.collection.exists());self.assertEqual(files,result['files'])
    def test_failed_archive_verification_preserves_originals(self):
        files,order=self.archive_fixture()
        with mock.patch.object(corpus.storage,'verify_members',side_effect=ValueError('corrupt')),self.assertRaisesRegex(ValueError,'corrupt'):corpus.archive(self.root,files,order)
        self.assertTrue(all((self.root/n).exists() for n in order));self.assertFalse((self.root/corpus.MANIFEST).exists())
    def test_external_deletion_paths_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'relative paths'):corpus.archive(self.root,{'../outside':dict(sha256='bad',size=0)},['../outside'])

if __name__=='__main__':unittest.main()
