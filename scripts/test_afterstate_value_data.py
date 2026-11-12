import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import afterstate_value_data as s

def dictionary():
    needed=['card.decision.play-cards','purchase.decision.purchase-shipping','purchase.decision.purchase-advances','purchase.role.0.chaos']
    old=sorted(needed+[f'purchase.synthetic.{i:04}' for i in range(7241-len(needed))])
    return dict(version=s.VERSION,stateFeatureNames=old+s.APPENDED,legacyStateFeatureNames=old,appendedStateFeatureNames=s.APPENDED,
        contexts=[dict(name=n,index=i,decision=s.DECISIONS[i]) for i,n in enumerate(s.CONTEXTS)],units='unchanged-existing-phase-encoder-units',missingValue=0.)

def fixture(context='ADVANCES',exact=False):
    identity=dict(version=s.VERSION,signature='sig',index=0,gameSeed=-933000000,controlledSeat=0,sourceSha256='source',dictionarySha256='dictionary')
    c=s.CONTEXTS.index(context);prefix='card' if c==0 else 'purchase';features={prefix+'.decision.'+s.DECISIONS[c]:1.}
    if exact:features[prefix+'.role.0.chaos']=1.
    states=dict(identity,rows=[dict(number=2,ordinal=int(c==5),turn=1,context=context,stateFeatures=features,exact=exact,exactReason='OWN_CHAOS' if exact else None)],exclusions=[],originalStateParity=True)
    scores=[dict(player='p'+str(i+1),advancePoints=0,cash=0,misery=0,total=0,winner=False) for i in range(4)]
    if exact:scores[0]['total']=None
    label=dict(identity,statesSha256='state',terminal=dict(reward=0.,winShare=0.,rankReward=0.,scores=scores,gameDecisions=5))
    return states,label

def project(states,label):
    return s.project_world(states,label,dictionary=dictionary(),dictionary_sha='dictionary',signature='sig',index=0,seed=-933000000,source_sha='source',states_sha='state')

class DataTest(unittest.TestCase):
    def test_old_columns_exact(self):
        d=dictionary();self.assertEqual(s.dictionary_names(d)[:7241],d['legacyStateFeatureNames'])
    def test_old_reorder_rejected(self):
        d=dictionary();d['stateFeatureNames'][0],d['stateFeatureNames'][1]=d['stateFeatureNames'][1],d['stateFeatureNames'][0]
        with self.assertRaises(ValueError):s.dictionary_names(d)
    def test_public_rows_only(self):
        a,b=fixture();rows,_,counts=project(a,b);self.assertEqual(rows,a['rows']);self.assertEqual(counts['decisions'],1)
    def test_label_in_state_rejected(self):
        a,b=fixture();a['terminal']=b['terminal']
        with self.assertRaises(ValueError):project(a,b)
    def test_exact_chaos_counted_unfitted(self):
        a,b=fixture('OWNER_EXIT',True);rows,reward,c=project(a,b);self.assertEqual((rows,reward,c['exactCounts']['OWNER_EXIT']),([],0.,1))
    def test_chaos_nonzero_rejected(self):
        a,b=fixture('OWNER_EXIT',True);b['terminal']['reward']=.1;b['terminal']['rankReward']=.5
        with self.assertRaises(ValueError):project(a,b)
    def test_empty_world(self):
        a,b=fixture();a['rows']=[];self.assertEqual(project(a,b)[2]['decisions'],0)
    def test_unknown_feature(self):
        a,b=fixture();a['rows'][0]['stateFeatures']['private.rival.hand']=1.
        with self.assertRaises(ValueError):project(a,b)
    def test_duplicate_chronology(self):
        a,b=fixture();a['rows']*=2
        with self.assertRaises(ValueError):project(a,b)
    def test_wrong_context(self):
        a,b=fixture();a['rows'][0]['context']='RELIEF'
        with self.assertRaises(ValueError):project(a,b)
    def test_information_excluded(self):
        a,b=fixture();a['exclusions']=[dict(number=2,reason='INFORMATION')];self.assertEqual(project(a,b)[2]['exclusions'],1)
    def test_label_hash(self):
        a,b=fixture();b['statesSha256']='different'
        with self.assertRaises(ValueError):project(a,b)
    def test_absolute_path(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):s.relative_file(d,'/original/private/state.json')
    def test_test_feature_pass_never_decodes_opaque_labels(self):
        a,_=fixture();contents={'collection/states-0.json.gz':gzip.compress(json.dumps(a).encode()),'collection/label-0.json':b'NOT JSON: semantic TEST label must remain closed'}
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);path=root/'evidence.tar.gz'
            with tarfile.open(path,'w:gz') as target:
                for name,content in contents.items():
                    info=tarfile.TarInfo(name);info.size=len(content);target.addfile(info,io.BytesIO(content))
            hashes={n:hashlib.sha256(c).hexdigest() for n,c in contents.items()};batch=dict(archive=path.name,archiveSha256=s.sha(path),files=hashes);game=dict(index=0,files=hashes)
            self.assertEqual(list(s.iter_worlds(root,batch,[game],lambda:None,decode_labels=False))[0][1],[a,None])
            with self.assertRaises(json.JSONDecodeError):list(s.iter_worlds(root,batch,[game],lambda:None))
    def test_archive_all_members_hashed(self):
        a,b=fixture();contents={'collection/states-0.json.gz':gzip.compress(json.dumps(a).encode()),'collection/label-0.json':json.dumps(b).encode(),'collection/manifest.json':b'{}'}
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);path=root/'evidence.tar.gz'
            with tarfile.open(path,'w:gz') as target:
                for name,content in contents.items():
                    info=tarfile.TarInfo(name);info.size=len(content);target.addfile(info,io.BytesIO(content))
            hashes={n:hashlib.sha256(c).hexdigest() for n,c in contents.items()};batch=dict(archive=path.name,archiveSha256=s.sha(path),files=hashes);game=dict(index=0,files=hashes)
            self.assertEqual(list(s.iter_worlds(root,batch,[game],lambda:None))[0][1],[a,b])
            batch['files']=dict(hashes,**{'collection/manifest.json':'wrong'})
            with self.assertRaises(ValueError):list(s.iter_worlds(root,batch,[game],lambda:None))
if __name__=='__main__':unittest.main()
