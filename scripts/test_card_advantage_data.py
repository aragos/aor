import copy,gzip,hashlib,json
from pathlib import Path
import tempfile,unittest
import card_advantage as a
import card_advantage_data as data
import purchase_policy as common
from test_card_advantage import SCHEMA,observation

def fixture(directory):
    directory=Path(directory);signature='fixture-signature'
    common.atomic_json(directory/'source.json',{'fixture':True})
    for name in ('baseline.json','frozen-purchase-policy.json'):common.atomic_json(directory/name,{'fixture':name})
    requests=[{'index':i,'split':split,'seed':100+i,'seat':i,'epoch':1,'cohort':'generic','ordinal':1,'opponentUsesCandidate':[False]*4} for i,split in enumerate(('train','development','validation'))]
    manifest={'kind':'card-advantage-collect','schemaVersion':1,'signature':signature,**SCHEMA,
      'selectorVersion':a.SELECTOR,'proposalVersion':a.PROPOSAL,'origins':requests,
      'baseline':{'path':'unused','sha256':data.digest(directory/'baseline.json')},
      'frozenPurchasePolicy':{'path':'unused','sha256':data.digest(directory/'frozen-purchase-policy.json')},'continuation':None}
    common.atomic_json(directory/'collection.json',manifest);common.atomic_json(directory/'state.json',{'phase':'complete','signature':signature})
    for request in requests:
      outcomes={f'{action}-{rep}':{'reward':float(action),'winShare':float(action),'rankReward':float(action),
        'chanceSeed':500+rep,'scores':[],'gameDecisions':100,'canonicalSha256':'unused'} for action in (0,1) for rep in range(4)}
      oid='a'*64;group={'id':'root','kind':'root','observationSha256':oid,'number':3,'botSeed':0,'proposalIndices':[0,1],'referenceIndex':0,
          'returns':[{'actionIndex':action,'outcomeIds':[f'{action}-{rep}' for rep in range(4)]} for action in (0,1)]}
      origin={'schemaVersion':1,'signature':signature,**{k:request[k] for k in ('index','split','seed','seat')},'request':request,'status':'collected',
              'observations':{oid:observation()},'groups':[group],'outcomes':outcomes}
      write_origin(directory,request['index'],origin)
    finish(directory)

def write_origin(directory,index,origin):
    (directory/f'origin-{index}.json.gz').write_bytes(gzip.compress(json.dumps(origin).encode(),mtime=0))
def read_origin(directory,index):return json.loads(gzip.decompress((directory/f'origin-{index}.json.gz').read_bytes()))
def finish(directory):
    common.atomic_json(directory/'finished.json',{'kind':'fixture-completion','signature':'fixture-signature','origins':3,
      'originSha256':[data.digest(directory/f'origin-{i}.json.gz') for i in range(3)]})
class Checks(unittest.TestCase):
 def test_reader_preserves_splits_and_paired_targets(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);dataset,provenance=data.read_collection(p)
      self.assertEqual(provenance['counts'],{'train':1,'development':1,'validation':1})
      self.assertEqual(dataset['train'][0][0].targets.tolist(),[0.,1.])
 def test_postcompletion_outcome_mutation_rejected(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);origin['outcomes']['1-0']['reward']=0;write_origin(p,0,origin)
      with self.assertRaisesRegex(ValueError,'bytes differ'):data.read_collection(p)
 def test_all_hashes_checked_before_any_decode(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);(p/'origin-0.json.gz').write_bytes(b'not gzip');finish(p)
      (p/'origin-2.json.gz').write_bytes(b'changed after completion')
      with self.assertRaisesRegex(ValueError,'bytes differ'):data.read_collection(p)
 def test_validation_not_decoded_during_fitting(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);(p/'origin-2.json.gz').write_bytes(gzip.compress(b'not json'));finish(p)
      dataset,provenance=data.read_collection(p,splits=('train','development'))
      self.assertEqual(dataset['validation'],[]);self.assertEqual(provenance['counts']['validation'],0)
      with self.assertRaises(json.JSONDecodeError):data.read_collection(p,splits=('validation',))
 def test_reference_must_be_actual_F_index(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);origin['groups'][0]['referenceIndex']=1;write_origin(p,0,origin);finish(p)
      with self.assertRaisesRegex(ValueError,'reference differ'):data.read_collection(p)
 def test_proposal_membership_matches_versioned_sampler(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);origin['groups'][0]['proposalIndices']=[0];write_origin(p,0,origin);finish(p)
      with self.assertRaisesRegex(ValueError,'proposals/reference'):data.read_collection(p)
 def test_unchanged_child_groups_merge_before_weighting(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);oid='b'*64;origin['observations'][oid]=observation(-1)
      for rep in range(4):
        child=copy.deepcopy(origin['groups'][0]);child.update(id=f'child-{rep}',kind='child',observationSha256=oid,replicate=rep)
        for outcome in child['returns']:outcome['outcomeIds']=[outcome['outcomeIds'][rep]]
        origin['groups'].append(child)
      write_origin(p,0,origin);finish(p);dataset,provenance=data.read_collection(p)
      self.assertEqual(len(dataset['train'][0]),2);self.assertEqual(dataset['train'][0][1].replicates,4)
      self.assertEqual(provenance['rawDecisionGroups'],7);self.assertEqual(provenance['mergedDecisionGroups'],4)
 def test_frozen_purchase_snapshot_is_bound(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);common.atomic_json(p/'frozen-purchase-policy.json',{'different':True})
      with self.assertRaisesRegex(ValueError,'model snapshot'):data.read_collection(p)
 def test_stream_mismatch_rejected(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);origin['outcomes']['1-0']['chanceSeed']=501;write_origin(p,0,origin);finish(p)
      with self.assertRaisesRegex(ValueError,'future stream'):data.read_collection(p)
 def test_unknown_safe_input_key_rejected_even_when_zero(self):
    with tempfile.TemporaryDirectory() as tmp:
      p=Path(tmp);fixture(p);origin=read_origin(p,0);origin['observations']['a'*64]['stateFeatures']['hidden']=0;write_origin(p,0,origin);finish(p)
      with self.assertRaisesRegex(ValueError,'unknown feature'):data.read_collection(p)
if __name__=='__main__':unittest.main()
