"""Compare the explicitly constructed native fixture with the real Python projection/forward.

This is a test entrypoint, not a model/preparation loader. It accepts only the dedicated
literal four-head fixture, uses no RNG and writes no actor or candidate artifacts.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import torch
import coordinated_es_math as es
import coordinated_es_calibration as cal
import coordinated_es_projection as p


def verify(path):
    raw=Path(path).read_bytes();fixture=json.loads(raw)
    es.require(fixture['kind']=='coordinated-es-browser-parity-1' and fixture['constructedOnly'] is True and
        fixture['newGames']==fixture['retainedModelsRead']==0 and fixture['tolerance']==2e-12 and
        len(fixture['cases'])==224,'Only the dedicated constructed cross-runtime fixture is permitted.')
    metadata=fixture['projection']
    es.require(metadata['kind']=='coordinated-es-literal-test-map-1' and metadata['headOrder']==[head.value for head in es.HEADS] and
        metadata['blockOrder']==list(p.BLOCKS) and
        metadata['factorRule']=='((k*3+j*5+headOrdinal+blockOrdinal)%7)<3 ? -1 : 1' and
        metadata['coordinates']==[((k%13)-6)/16+((k%7)-3)/64 for k in range(256)],
        'The independent literal factor map and arbitrary vector must be the exact declared test fixture.')
    torch.set_num_threads(1);original={};heads={}
    for ordinal,head in enumerate(es.HEADS):
        model=next(row['original'] for row in fixture['cases'] if row['head']==head.value)
        content=es.canonical(model);original[head]=p.Original(head,content,hashlib.sha256(content).hexdigest())
        states=len(model['stateFeatureNames']);actions=len(model['actionFeatureNames'])
        factors={name:[[float(-1 if (k*3+j*5+ordinal+block)%7<3 else 1) for j in range(shape[1])]
            for k in range(64)] for block,(name,shape) in enumerate(p.shapes(states,actions).items())}
        heads[head.value]=dict(modelSha256=original[head].sha256,dictionarySha256=p.dictionary_sha(model),
            stateCount=states,actionCount=actions,factors=factors)
    # The required positive seed field is a UNIT schema placeholder; no generator is called.
    projection=p.Projection.from_record(dict(kind='coordinated-es-projection-1',scopeSha256='a'*64,
        originalBundleSha256='b'*64,recipe=p.RECIPE,projectionSeed=17,numpyVersion=np.__version__,heads=heads))
    materialized={head:json.loads(value) for head,value in p.materialize(projection,original,metadata['coordinates']).items()}
    zero=p.materialize(projection,original,[0.]*256)
    es.require(zero=={head:source.raw for head,source in original.items()},'Zero projection must preserve exact original raw bytes.')
    maximum=dict(parameters=0.,referenceForward=0.,tensorForward=0.)
    def compare(values,expected,key):
        es.require(len(values)==len(expected),'A parity vector changed its full offered shape.')
        for value,prior in zip(values,expected):
            error=abs(value-prior);maximum[key]=max(maximum[key],error)
            es.require(math.isfinite(value) and error<=2e-12,'Constructed native/Python parity exceeded2e-12.')
    cache={};decisions=0
    for row in fixture['cases']:
        head=es.Head(row['head']);model=original[head].model if row['case']=='zero' else materialized[head]
        es.require(row['original']==original[head].model,'All cases must preserve the same original head.')
        es.require(row['model']['valueWeights']==model['valueWeights'] and row['model']['valueBias']==model['valueBias'],
            'Native materialization must preserve every exact original value array.')
        for name in p.b.PARAMETERS[:-1]:compare(model[name],row['model'][name],'parameters')
        view=row['observation'];key=(head,row['case'])
        es.require({name for name,value in view['stateFeatures'].items() if value!=0}==set(model['stateFeatureNames']) and
            {name for action in view['actions'] for name,value in action['features'].items() if value!=0}==set(model['actionFeatureNames']),
            'Constructed parity must exercise every complete state and action column nonzero.')
        if key not in cache:
            actor=p.RawPolicy(model,head);prepared=actor.public(view)
            if head in (es.Head.CARD,es.Head.PURCHASE):
                scalar=p.b.reference_forward(model,view,contract=p.contract(head))
                with torch.inference_mode():logp,values=p.b.ResidualPolicy.forward(actor,[prepared])
                cache[key]=(scalar,logp[0].exp().tolist(),float(values[0]))
            else:
                initial=original[head].model
                scalar=[a-b for a,b in zip(p.raw_residuals(model,head,view),p.raw_residuals(initial,head,view))]
                baseline=p.RawPolicy(initial,head)
                tensor=[a-b for a,b in zip(actor.raw([prepared])[0],baseline.raw([prepared])[0])]
                cache[key]=(scalar,tensor)
        expected=row['expected'];reference=view['baselineIndex']
        if head in (es.Head.CARD,es.Head.PURCHASE):
            scalar,probabilities,value=cache[key]
            compare(scalar['logits'],expected['logits'],'referenceForward')
            compare(scalar['probabilities'],expected['probabilities'],'referenceForward')
            compare([scalar['value']],[expected['value']],'referenceForward')
            compare(probabilities,expected['probabilities'],'tensorForward');compare([value],[expected['value']],'tensorForward')
            salt=0x3c6ef372 if head is es.Head.CARD else 0x6a09e667
            mixed=(row['botSeed']^salt)&0xffffffff;mixed=mixed if mixed<2**31 else mixed-2**32
            es.require(row['actorSeed']==mixed and row['scale']==1.,'Original signed C/P sampler identity changed.')
            uniform=p.b.selection_uniform(mixed);indices=[p.b.select(values,uniform) for values in (scalar['probabilities'],probabilities)]
            compare([math.log(scalar['probabilities'][indices[0]])],[expected['logProbability']],'referenceForward')
        else:
            scalar,tensor=cache[key]
            compare(scalar,expected['residualDifferences'],'referenceForward');compare(tensor,expected['residualDifferences'],'tensorForward')
            for values,label in ((scalar,'referenceForward'),(tensor,'tensorForward')):
                scores=[(1. if index==reference else 0.)+row['scale']*item for index,item in enumerate(values)]
                compare(scores,expected['scores'],label)
                if len(values)>1:compare([cal.signed_gap(values,reference)],[expected['strongestGap']],label)
                else:es.require(expected['strongestGap'] is None,'Singletons do not invent a signed alternative gap.')
            indices=[cal.choose(values,reference,row['scale']) for values in (scalar,tensor)]
            if row['case']=='zero':es.require(indices==[reference,reference],'Zero role preserves the actual incumbent.')
        es.require(indices==[expected['index']]*2 and view['actions'][indices[0]]['command']==expected['command'],
            'Every constructed native/Python index and command must match exactly.')
        decisions+=1
    return dict(kind='coordinated-es-constructed-python-native-parity-1',passed=True,
        fixtureSha256=hashlib.sha256(raw).hexdigest(),cases=decisions,heads=[head.value for head in es.HEADS],
        zeroCoordinateRawParity=True,arbitraryCoordinates=256,maximumAbsoluteError=maximum,tolerance=2e-12,
        factorSource='literal RMS1 signs; no RNG execution',allFiveActorBlocksCompared=True,
        valuesUnchanged=True,exactCommands=True,newGames=0,retainedModelsRead=0,optimizerSteps=0,
        sources={str(Path(module.__file__).absolute()):hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest()
            for module in (es,cal,p,p.b)})


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fixture',required=True)
    parser.add_argument('--output',required=True);args=parser.parse_args();receipt=verify(args.fixture)
    with Path(args.output).open('xb') as output:output.write(es.canonical(receipt))
    print(json.dumps({key:value for key,value in receipt.items() if key!='sources'},sort_keys=True))
