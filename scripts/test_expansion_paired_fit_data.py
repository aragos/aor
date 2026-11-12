"""Synthetic boundary fixtures; no experiment corpus or TEST subject is opened."""
import copy
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import expansion_paired_fit_data as data


NAMES=dict(stateFeatureNames=['raw.cash'],actionFeatureNames=['action.kind'])
SIGNATURE='a'*64


def encoded():
    return dict(encoderVersion=data.ENCODER,stateFeatures={'raw.cash':.2},baselineIndex=1,
                actions=[dict(command='buy-expansion-card',features={'action.kind':1.}),
                         dict(command='pass',features={}),
                         dict(command='place market 1',features={'action.kind':2.})])


def original(index):
    streams=[index*2+100,index*2+101]
    arms=[]
    for action,command in enumerate(['buy-expansion-card','pass','place market 1']):
        outcomes=[]
        for replicate,seed in enumerate(streams):
            win=float(action!=1);rank=.5
            outcomes.append(dict(replicate=replicate,replayVerified=True,terminal=dict(
                continuationSeed=seed,reward=.8*win+.2*rank,winShare=win,rankReward=rank,
                scores=[dict(player=f'p{index%4+1}',total=100 if action!=1 else None)])))
        arms.append(dict(proposal=dict(actionIndex=action,command=command),outcomes=outcomes))
    return dict(kind='expansion-paired-origin-1',signature=SIGNATURE,status='COLLECTED',
                request=dict(index=index,seat=index%4,split=data.split(index)),streams=dict(future=streams),
                exactIncumbentVerified=True,incumbentCommand='pass',actorInput=dict(encoded=encoded()),arms=arms)


def public(metadata):
    captured=metadata['status']=='COLLECTED'
    return dict(kind='expansion-paired-public-1',index=metadata['index'],signature=SIGNATURE,
                sourceSha256=metadata['sourceSha256'],status=metadata['status'],
                encoded=encoded() if captured else None,actionIndices=[0,1,2] if captured else [])


def fixture(root):
    origins=[];receipts=[];requests=[];streams=[]
    for index in range(1024):
        captured=index in (0,768) or 896<=index<960
        original_path=root/f'origin-{index}.json.gz'
        # Missing roots are never interpreted as labeled states; retain a small synthetic subject.
        with gzip.open(original_path,'wt') as stream:json.dump(original(index) if captured else {'missing':index},stream)
        digest=data.sha(original_path)
        meta=dict(index=index,split=data.split(index),status='COLLECTED' if captured else 'MISSING',
                  seat=index%4,signature=SIGNATURE,sourceSha256=digest,futureSeeds=[index*2+100,index*2+101],
                  original=dict(path=original_path.name,sha256=digest))
        if index<896:
            meta.update(commands=['buy-expansion-card','pass','place market 1'] if captured else [],
                        actionIndices=[0,1,2] if captured else [],baselineIndex=1 if captured else None)
            path=root/f'public-{index}.json';data.document(path,public(meta))
            meta['public']=dict(path=path.name,sha256=data.sha(path))
        origins.append(meta)
        receipts.append(dict(index=index,file=original_path.name,sha256=digest,captured=captured))
        requests.append(dict(index=index,seat=index%4,split=data.split(index)))
        streams.append(dict(future=meta['futureSeeds']))
    path=root/'finished.json';data.document(path,dict(complete=True,signature=SIGNATURE,origins=receipts))
    dictionary_path=root/'dictionary.json'
    data.document(dictionary_path,dict(encoderVersion=data.ENCODER,dictionary=NAMES))
    prepared_path=root/'prepared.json'
    data.document(prepared_path,dict(kind='expansion-paired-data-1',signature=SIGNATURE,
        requests=requests,origins=streams,dictionarySha256=data.sha(dictionary_path)))
    manifest=dict(kind=data.KIND,encoderVersion=data.ENCODER,
                  dictionary=dict(encoderVersion=data.ENCODER,dictionary=NAMES),origins=origins,
                  completion=dict(path=path.name,sha256=data.sha(path)),
                  prepared=dict(path=prepared_path.name,sha256=data.sha(prepared_path)),
                  dictionarySource=dict(path=dictionary_path.name,sha256=data.sha(dictionary_path)))
    path=root/'corpus.json';data.document(path,manifest)
    return path


class BoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.root=Path(cls.temp.name)
        cls.path=fixture(cls.root)
        cls.public_directory=cls.root/'public-export';cls.public_directory.mkdir()
        corpus=data.read(cls.path);prepared=data.read(cls.root/'prepared.json')
        for meta in corpus['origins'][:896]:
            index=meta['index'];path=cls.public_directory/f'origin-{index}.json'
            data.document(path,public(meta))
            data.document(path.with_name(path.name+'.metadata.json'),dict(meta,
                kind='expansion-paired-public-metadata-1',request=prepared['requests'][index],
                streams=prepared['origins'][index]))

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def setUp(self):self.corpus=data.Corpus(self.path)

    def seal(self,part='VALIDATION',epochs=None,selection=None):
        rows=self.corpus.public(part,selection)
        epochs=epochs or list(range(1,21))
        predictions={str(e):[dict(index=r['index'],values=[.6,.5,.4],choice=0)
                             for r in rows if r['status']=='COLLECTED'] for e in epochs}
        value=dict(kind='expansion-paired-predictions-1',corpusSha256=self.corpus.sha256,
                   partition=part,epochs=epochs,predictions=predictions,
                   checkpoints={str(e):dict(path=f'epoch-{e}.json',sha256='b'*64) for e in epochs},
                   publicSha256=self.corpus.public_hashes(part))
        if part=='TEST':value['selectionSha256']=selection.sha256
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        path=Path(directory.name)/'predictions.json';data.document(path,value)
        return data.PredictionSeal(path,data.sha(path))

    def selection(self):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        path=Path(directory.name)/'selection.json'
        data.document(path,dict(kind='expansion-paired-selection-1',corpusSha256=self.corpus.sha256,
            epoch=3,validationGain=.1,checkpoint=dict(path='epoch-3.json',sha256='b'*64),
            validationPredictionsSha256='c'*64,metrics={str(e):dict(
                gain={'reward':{'mean':.1 if e==3 else 0.}},pairedMse=.1) for e in range(1,21)}))
        return data.SelectionSeal(path,data.sha(path))

    def projector(self,inputs):
        directory=tempfile.TemporaryDirectory();self.addCleanup(directory.cleanup)
        paths={}
        for index,_ in inputs:
            path=Path(directory.name)/f'{index}.json';data.document(path,public(self.corpus.origins[index]))
            paths[index]=path
        return paths

    def test_missing_requests_and_test_coverage_remain_metadata_only(self):
        self.assertEqual(767,self.corpus.counts['TRAIN']['missing'])
        self.assertEqual(64,self.corpus.counts['TEST']['ready'])
        self.assertIsNone(self.corpus.counts['TEST']['card'])
        self.assertFalse(data.training_coverage(self.corpus.counts))
        self.assertEqual({},self.corpus._public)

    def test_coverage_requires_both_card_and_destination_floors(self):
        counts={p:dict(ready=n,card=c,destination=c) for p,n,c in [('TRAIN',256,64),('VALIDATION',64,16)]}
        self.assertTrue(data.training_coverage(counts))
        counts['TRAIN']['destination']=63
        self.assertFalse(data.training_coverage(counts))

    def test_training_labels_keep_paired_reward_and_chaos_units(self):
        value=self.corpus.labels('TRAIN')
        self.assertEqual({0},set(value))
        self.assertEqual([.9,.1,.9],[r['reward'] for r in value[0]])
        self.assertEqual([0.,1.,0.],[r['chaos'] for r in value[0]])

    def test_validation_without_prediction_seal_never_opens_gzip(self):
        with patch.object(data.gzip,'open',side_effect=AssertionError('labels must stay closed')):
            with self.assertRaisesRegex(ValueError,'prediction seal'):self.corpus.labels('VALIDATION')

    def test_incomplete_validation_epoch_set_is_rejected_before_labels(self):
        seal=self.seal(epochs=[1])
        with patch.object(data.gzip,'open',side_effect=AssertionError('labels must stay closed')):
            with self.assertRaisesRegex(ValueError,'All20'):self.corpus.labels('VALIDATION',seal)

    def test_complete_validation_seal_admits_exact_labels(self):
        self.assertEqual({768},set(self.corpus.labels('VALIDATION',self.seal())))

    def test_prediction_tamper_is_rejected_before_label_semantics(self):
        seal=self.seal();seal.path.write_text('{}')
        with patch.object(data.gzip,'open',side_effect=AssertionError('labels must stay closed')):
            with self.assertRaisesRegex(ValueError,'predictions changed'):self.corpus.labels('VALIDATION',seal)

    def test_nonpublic_fields_cannot_be_smuggled_into_model_input(self):
        meta=self.corpus.origins[0];value=public(meta);value['encoded']['outcome']=.9
        with self.assertRaisesRegex(ValueError,'Research encoder'):data.public_origin(value,meta,NAMES)

    def test_unknown_state_feature_is_rejected(self):
        meta=self.corpus.origins[0];value=public(meta);value['encoded']['stateFeatures']['future.card']=1.
        with self.assertRaisesRegex(ValueError,'finite original'):data.public_origin(value,meta,NAMES)

    def test_test_is_lazy_and_requires_persisted_selection(self):
        self.corpus.test_projector=lambda _:self.fail('TEST exporter called before admission')
        with self.assertRaisesRegex(ValueError,'positive VAL'):self.corpus.public('TEST')
        self.assertNotIn('TEST',self.corpus._public)

    def test_selected_test_only_is_sealed_before_labels(self):
        self.corpus.test_projector=self.projector;selection=self.selection()
        seal=self.seal('TEST',[3],selection)
        self.assertEqual(64,len(self.corpus.labels('TEST',seal,selection)))

    def test_test_prediction_cannot_substitute_another_checkpoint(self):
        self.corpus.test_projector=self.projector;selection=self.selection()
        seal=self.seal('TEST',[3],selection)
        value=data.read(seal.path);value['checkpoints']['3']['sha256']='d'*64
        seal.path.write_text(json.dumps(value));seal=data.PredictionSeal(seal.path,data.sha(seal.path))
        with patch.object(data.gzip,'open',side_effect=AssertionError('labels must stay closed')):
            with self.assertRaisesRegex(ValueError,'already selected'):self.corpus.labels('TEST',seal,selection)

    def test_test_floor_stops_before_export(self):
        self.corpus.counts['TEST']['ready']=63
        self.corpus.test_projector=lambda _:self.fail('TEST exporter called below floor')
        with self.assertRaisesRegex(ValueError,'at least64'):self.corpus.public('TEST',self.selection())

    def test_label_join_checks_public_encoding_against_original(self):
        rows=self.corpus.public('TRAIN');rows[0]['encoded']['stateFeatures']['raw.cash']=.4
        with self.assertRaisesRegex(ValueError,'public subset'):self.corpus.labels('TRAIN')

    def test_label_join_checks_original_reference(self):
        rows=self.corpus.public('TRAIN');rows[0]['encoded']['baselineIndex']=0
        with self.assertRaisesRegex(ValueError,'public subset'):self.corpus.labels('TRAIN')

    def test_old_corpus_and_dictionary_tags_are_rejected(self):
        value=dict(encoderVersion='aor-expansion-observation-2',dictionary=NAMES)
        with self.assertRaisesRegex(ValueError,'separately tagged'):data.dictionary(value)

    def test_manifest_assembly_reads_only_metadata_and_public_exports(self):
        output=self.root/'assembled.json'
        with patch.object(data.gzip,'open',side_effect=AssertionError('no outcome decode in preparation')):
            data.prepare_manifest(self.root,self.public_directory,output)
            actual=data.Corpus(output)
        self.assertEqual(self.corpus.counts,actual.counts)
        self.assertNotIn('public',actual.origins[896])
        self.assertNotIn('commands',actual.origins[896])

    def test_manifest_rejects_any_preexported_test_actor_input(self):
        path=self.public_directory/'origin-896.json'
        data.document(path,{'forbidden':'TEST features'})
        try:
            with self.assertRaisesRegex(ValueError,'must not be pre-exported'):
                data.prepare_manifest(self.root,self.public_directory,self.root/'forbidden.json')
        finally:path.unlink()

    def test_selection_artifact_cannot_name_a_runner_up(self):
        selection=self.selection();value=data.read(selection.path);value['epoch']=4
        selection.path.write_text(json.dumps(value))
        selection=data.SelectionSeal(selection.path,data.sha(selection.path))
        with self.assertRaisesRegex(ValueError,'prescribed complete'):
            selection.read(self.corpus.sha256)


if __name__=='__main__':unittest.main()
