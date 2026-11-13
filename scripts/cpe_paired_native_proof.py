"""Source-only bridge for explicitly invented native C/P/E wire fixtures.

This helper refuses scientific evidence. It checks the exact retained snapshots rather
than resolving the fixture generator's expired temporary provenance paths.
"""
import argparse
import hashlib
import math
from pathlib import Path

import cpe_paired_campaign_plan as p
import cpe_paired_corpus as corpus
import cpe_paired_data as data
import cpe_paired_policy as policy
import cpe_paired_storage as storage
import purchase_policy as numeric
from test_cpe_paired_campaign_plan import fixture as constructed_plan


def verify(directory):
    directory=Path(directory).resolve();numeric.torch.set_num_threads(1)
    results={};pins={}
    for head in policy.HEADS:
        root=directory/head.value
        marker=p.read(root/'CONSTRUCTED-FIXTURE.json')
        p.require(marker['kind']=='cpe-native-wire-fixture-1' and marker['head']==head.value and
            marker['newGames']==marker['scientificFits']==marker['retainedModelsRead']==0 and
            marker['scientificAdmission'] is False and marker['terminalAndCanonicalReceiptsInvented'] is True,
            'This proof accepts only explicitly invented zero-game native fixtures.')
        files=storage.inventory(root);pins.update({str(root/name):row['sha256'] for name,row in files.items()})
        prepared=p.read(root/'prepared.json');source=p.read(root/'source.json')
        record=constructed_plan()
        record.update(originalBundleSha256=prepared['originalBundle']['sha256'],
            bundleScopeSha256=prepared['bundleScopeSha256'],ecologySha256=prepared['ecologySha256'])
        for phase in p.HEADS:
            dictionary=prepared['dictionaries'][phase]
            record['heads'][phase]=dict(originalActorSha256=prepared['originalBundleIdentity'][phase]['sha256'],
                dictionarySha256=prepared['dictionary']['sha256'] if phase==head.value else
                    hashlib.sha256(p.canonical(dictionary)+b'\n').hexdigest(),
                semanticDictionarySha256=p.digest(dictionary),
                scopeSha256=p.digest(dict(bundleScopeSha256=prepared['bundleScopeSha256'],head=phase)))
        record['iterations'][0]['heads'][head.value]['origins']=prepared['origins']
        # Constructed tensors intentionally do not claim the real fixed8268 initializer or audited RNG.
        plan=p.Plan(p.canonical(record))
        storage.prepared(plan,prepared,source,files['source.json']['sha256'],files,head.value,0,1,
            prepared['currentBundle']['sha256'])
        def actor(prefix):
            path=root/prefix/(head.value.lower()+'.json')
            return policy.FrozenActor(head,path.read_bytes(),files[str(path.relative_to(root))]['sha256'])
        current=actor('current');original=actor('original')
        context=data.Context(head=head,iteration=1,source_sha256=files['prepared.json']['sha256'],
            scope_sha256=prepared['scopeSha256'],bundle_scope_sha256=prepared['bundleScopeSha256'],
            ecology_sha256=prepared['ecologySha256'],request_bank_sha256=p.digest(prepared['origins']),
            dictionary_sha256=prepared['dictionary']['sha256'],dictionary_semantic_sha256=prepared['dictionarySha256'],
            original_model_sha256=original.sha256,current_model_sha256=current.sha256,
            original_bundle_sha256=prepared['originalBundle']['sha256'],current_bundle_sha256=prepared['currentBundle']['sha256'])
        maximum=0.;statuses=[];choices=0
        for receipt in marker['receipts']:
            index=receipt['globalIndex']
            for key in ('origin','public','labels'):
                p.require(files[receipt[key]['file']]['sha256']==receipt[key]['sha256'],
                    'Constructed native sidecars must retain their exact original raw bytes.')
            public=p.read(root/receipt['public']['file'])
            statuses.append(storage.public_record(public,receipt,prepared,plan,index))
            typed=data.PublicRoot.from_record(corpus.normalized_public(public),context,current.model,split='TRAIN')
            label=corpus.normalized_labels(p.read(root/receipt['labels']['file']),public,typed)
            target=None if label is None else data.PairedTarget.from_record(typed,label)
            data.AnchorWorld.from_record(corpus.normalized_anchor(public,original.sha256),original)
            if not typed.ready:
                p.require(target is None,'Missing constructed roots cannot invent targets.');continue
            row=public['publicRoot'];observation=row['observation'];network=policy.CpePolicy(current.model,head)
            with numeric.torch.no_grad():logp,values=network([observation])
            reference=numeric.reference_forward(current.model,observation,contract=head.contract)
            scalar_original=numeric.reference_forward(original.model,observation,contract=head.contract)
            for actual,expected in ((logp[0].exp().tolist(),row['currentProbabilities']),
                (reference['probabilities'],row['currentProbabilities']),
                (scalar_original['probabilities'],row['originalProbabilities'])):
                maximum=max(maximum,max(abs(a-b) for a,b in zip(actual,expected)))
            maximum=max(maximum,abs(float(values[0])-row['behaviorValue']),
                abs(float(logp[0][row['sampledCommandIndex']])-row['behaviorLogProbability']))
            p.require(numeric.select(reference['probabilities'],numeric.selection_uniform(row['actorSeed']))==
                row['sampledCommandIndex']==row['referenceIndex'],
                'The portable sampler must reproduce the original canonical current-bundle reference.')
            p.require(math.isfinite(maximum) and maximum<=2e-12,'Native/Python constructed probabilities or value differ.')
            choices+=1
        p.require(statuses==['READY','MISSING'] and choices==1,
            'Each head fixture must retain its original ready and missing independent positions.')
        results[head.value]=dict(statuses=statuses,constructedChoices=choices,maximumAbsoluteDifference=maximum)
    return dict(kind='cpe-native-python-wire-proof-1',passed=True,heads=results,artifactPins=pins,
        newGames=0,scientificFits=0,scientificModelsRead=0,scientificAdmission=False,
        constructedModelForwards=9,limitation='Invented native receipts only; no complete scientific shard or fixed initializer admission.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();storage.publish_json(args.output,verify(args.fixture))
