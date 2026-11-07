"""Completed, compressed paired-root/child collection reader. Hidden authority is never input."""
import gzip
import hashlib
import json
from pathlib import Path
import re

import card_advantage as a
import purchase_policy as common

MAX_BYTES = 64*1024*1024

def load_json(path): return common.read_json(Path(path))
def digest(path): return common.file_digest(Path(path))

def read_collection(directory, ablation=False, splits=('train','development','validation')):
    directory=Path(directory)
    state=load_json(directory/'state.json');finished=load_json(directory/'finished.json')
    a.require(state.get('phase')=='complete','The entire declared card collection must finish before reading outcomes.')
    manifest=load_json(directory/'collection.json');schema=a.validate_schema(manifest)
    a.require(manifest.get('kind')=='card-advantage-collect' and manifest.get('schemaVersion')==1,
              'Unsupported advantage collection kind/version.')
    a.require(manifest.get('selectorVersion')==a.SELECTOR and manifest.get('proposalVersion')==a.PROPOSAL,
              'Collection selector/proposal identity differs.')
    signature=manifest['signature'];requests=manifest['origins'];hashes=finished['originSha256']
    a.require(state['signature']==signature==finished['signature'] and finished['origins']==len(requests)==len(hashes),
              'Completion signature/count differs from declared origins.')
    a.require([r['index'] for r in requests]==list(range(len(requests))), 'Origins require contiguous declared indices.')
    a.require(len({r['seed'] for r in requests})==len(requests), 'Original-world seeds must be distinct.')
    pins={'collection.json':digest(directory/'collection.json'),'finished.json':digest(directory/'finished.json'),
          'source.json':digest(directory/'source.json')}
    for key,file in [('baseline','baseline.json'),('frozenPurchasePolicy','frozen-purchase-policy.json'),('continuation','continuation.json')]:
        identity=manifest.get(key)
        if identity is not None:
            a.require(digest(directory/file)==identity['sha256'],'Frozen model snapshot differs: '+key)
            pins[file]=identity['sha256']
    # Bind every compressed document before parsing any outcomes, including early origins.
    for index,expected in enumerate(hashes):
        filename=f'origin-{index}.json.gz'
        a.require(digest(directory/filename)==expected,'Completed origin bytes differ: '+filename)
        pins[filename]=expected
    result={name:[] for name in ('train','development','validation')};counts={name:0 for name in result};skips=[]
    indices_by_split={name:[] for name in result};raw_groups=merged_groups=0
    for request in requests:
        index=request['index'];split=request['split']
        a.require(split in result,'Unknown dataset split.')
        if split not in splits: continue
        with gzip.open(directory/f'origin-{index}.json.gz','rb') as f: raw=f.read(MAX_BYTES+1)
        a.require(len(raw)<=MAX_BYTES,'Expanded origin exceeds64MiB.')
        origin=json.loads(raw)
        a.require(origin['schemaVersion']==1 and origin['signature']==signature and origin['index']==index
                  and origin['split']==split and origin['seed']==request['seed'] and origin['seat']==request['seat']
                  and origin['request']==request,'Origin provenance differs from its frozen request.')
        if origin['status']=='skipped':
            a.require(origin['skipReason']=='requested-root-not-reached','Unknown skipped-origin disposition.')
            skips.append(index);continue
        a.require(origin['status']=='collected','Unknown origin status.')
        observations=origin['observations'];outcomes=origin['outcomes'];records=[]
        a.require(len(outcomes)<=52,'An origin exceeds its distinct-tail budget.')
        a.require(1<=len(origin['groups'])<=13,'An origin exceeds its root/child decision budget.')
        a.require(len({g['id'] for g in origin['groups']})==len(origin['groups']), 'Decision group IDs must be distinct.')
        for group in origin['groups']:
            observation_hash=group['observationSha256']
            a.require(re.fullmatch('[0-9a-f]{64}',observation_hash) is not None, 'Invalid JVM observation identity.')
            observation=observations[observation_hash]
            # Observation hashes use JVM JSON bytes; outer compressed-file hashes bind their content.
            a.require(observation['stateFeatures'].get('cardWindow.normal')==1
                      and observation['stateFeatures'].get('cardWindow.final',0)==0, 'Only ordinary card roots/children are supported.')
            indices=a.proposals(observation,group['botSeed'])
            a.require(indices==group['proposalIndices'] and group['referenceIndex']==observation['baselineIndex'],
                      'Collected proposals/reference differ from deployment.')
            returns={r['actionIndex']:r['outcomeIds'] for r in group['returns']}
            a.require(len(returns)==len(group['returns']) and set(returns)==set(indices), 'Every proposed action needs an outcome list.')
            a.require(group['kind'] in ('root','child'), 'Unknown decision group kind.')
            count=4 if group['kind']=='root' else 1
            a.require(all(len(ids)==count for ids in returns.values()), 'Unexpected paired continuation count.')
            base=returns[group['referenceIndex']];targets=[]
            for action in indices:
                delta=[]
                for oid,bid in zip(returns[action],base):
                    outcome=outcomes[oid];reference=outcomes[bid]
                    a.require(outcome['chanceSeed']==reference['chanceSeed'],'A paired comparison changed its future stream.')
                    for t in (outcome,reference):
                        for key in ('reward','winShare','rankReward'): common.number(t[key],key,0,1)
                        a.require(abs(t['reward']-(.8*t['winShare']+.2*t['rankReward']))<1e-12,'Composite terminal reward differs.')
                    delta.append(outcome['reward']-reference['reward'])
                mean=sum(delta)/len(delta);targets.append(0. if abs(mean)<1e-12 else mean)
            records.append({'observation':observation,'seed':group['botSeed'],'targets':targets,
                            'replicates':count,'id':group['id']})
        groups=merge_groups(records,schema,index,ablation)
        raw_groups+=len(records);merged_groups+=len(groups)
        result[split].append(groups);counts[split]+=1;indices_by_split[split].append(index)
    for file,expected in pins.items():a.require(digest(directory/file)==expected,'Collection changed while being decoded.')
    return result,{'signature':signature,'schema':{k:manifest[k] for k in ('encoderVersion','stateFeatureNames','actionFeatureNames')},
                   'counts':counts,'skippedOrigins':skips,'fileHashes':pins,'source':str(directory.resolve()),
                   'originIndices':indices_by_split,'rawDecisionGroups':raw_groups,'mergedDecisionGroups':merged_groups}

def merge_groups(records,schema,origin,ablation=False):
    """Average matched pairs before assigning one decision-group weight; provenance is not input."""
    merged={}
    for record in records:
        observation=record['observation'];seed=record['seed']
        key=common.digest({'observation':observation,'botSeed':seed,
                           'F':observation['baselineIndex'],'S':a.proposals(observation,seed)})
        if key not in merged: merged[key]={'observation':observation,'seed':seed,
            'sums':[0.0]*len(record['targets']),'count':0,'ids':[]}
        target=merged[key];count=record['replicates'];target['count']+=count;target['ids'].append(record['id'])
        for i,value in enumerate(record['targets']): target['sums'][i]+=count*value
    groups=[]
    for key,value in merged.items():
        group=a.prepare(value['observation'],value['seed'],schema,
            [x/value['count'] for x in value['sums']],f'{origin}:{key}',ablation)
        group.sourceGroups=tuple(value['ids']);group.replicates=value['count'];groups.append(group)
    return groups
