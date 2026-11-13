"""One signed-gap sensitivity calibration, with exact equal-world percentile weights."""
from dataclasses import dataclass
import json

import coordinated_es_math as es

VERSION='coordinated-es-signed-gap-calibration-1'
RECIPE=dict(kind=VERSION,margin=1.,quantileNumerator=9,quantileDenominator=10,
    minimumWorlds=128,minimumPositiveMass=.1,minimumQuantile=1e-8,
    marginExcess=2.**-20,minimumScale=1/32,maximumScale=32.,
    minimumMovement=.05,maximumMovement=.40)


def signed_gap(differences, reference):
    es.require(type(differences) in (list,tuple) and len(differences)>=2,
        'Calibration uses a complete branching offer.')
    values=es.vector(differences,len(differences),'residual-difference')
    es.require(type(reference) is int and 0<=reference<len(values),'The raw reference must be offered.')
    return max(es.finite(value-values[reference],'signed-gap') for index,value in enumerate(values) if index!=reference)


def witness(differences,reference):
    signed_gap(differences,reference)
    return dict(reference=float(differences[reference]),alternativeMaximum=max(float(value)
        for index,value in enumerate(differences) if index!=reference))


def witness_gap(value):
    es.exact(value,('reference','alternativeMaximum'),'Selector movement witness')
    return es.finite(es.finite(value['alternativeMaximum'],'alternative-maximum')-
        es.finite(value['reference'],'reference-difference'),'signed-gap')


def changed(value,scale):
    witness_gap(value)
    # Keep the native selector's exact Float64 parenthesization, including common mode.
    incumbent=es.finite(1.+scale*value['reference'],'selector-score')
    alternative=es.finite(scale*value['alternativeMaximum'],'selector-score')
    return alternative>incumbent


def choose(differences, reference, scale):
    es.require(type(differences) in (list,tuple) and len(differences)>=1,'An actual complete offer is required.')
    values=es.vector(differences,len(differences),'residual-difference')
    es.require(type(reference) is int and 0<=reference<len(values),'The raw reference must be offered.')
    scale=es.finite(scale,'selector-scale')
    es.require(1/32<=scale<=32,'Only the sealed positive calibration scale is executable.')
    scores=tuple(es.finite((1. if index==reference else 0.)+scale*value,'selector-score')
        for index,value in enumerate(values))
    maximum=max(scores)
    return reference if scores[reference]==maximum else scores.index(maximum)


def quantile90(weighted):
    """Lowest signed gap whose integer cumulative mass reaches90%; no interpolation."""
    es.require(weighted and all(type(weight) is int and weight>0 for _,weight in weighted),
        'A weighted inverse CDF requires positive integer mass.')
    rows=sorted((es.finite(value,'quantile-gap'),weight) for value,weight in weighted)
    total=sum(weight for _,weight in rows);cumulative=0
    for value,weight in rows:
        cumulative+=weight
        if cumulative*10>=total*9:return value
    raise AssertionError('Exact cumulative mass must reach the fixed percentile.')


@dataclass(frozen=True)
class GapPanel:
    """Immutable public gap evidence only; no return or future-outcome field is admitted."""
    wire: bytes

    def __post_init__(self):
        es.require(type(self.wire) is bytes,'Gap evidence must retain immutable canonical bytes.')
        record=json.loads(self.wire)
        es.exact(record,('kind','head','protocolSha256','originalModelSha256','projectionSha256',
            'directionsSha256','prescribedMeanSha256','publicPanelSha256','worlds'),'Calibration gap panel')
        es.require(record['kind']=='coordinated-es-gap-panel-1' and record['head'] in ('EXPANSION','TOKEN'),
            'Only the new E/T margin selector has a signed-gap calibration.')
        for name in ('protocolSha256','originalModelSha256','projectionSha256','directionsSha256',
            'prescribedMeanSha256','publicPanelSha256'):es.sha(record[name],name)
        es.require(type(record['worlds']) is list and len(record['worlds'])==256,
            'Retain all256 first-generation control worlds, including missing panels.')
        for index,world in enumerate(record['worlds']):
            es.exact(world,('index','states'),'Calibration world')
            es.require(type(world['index']) is int and world['index']==index and
                type(world['states']) is list and len(world['states'])<=2,
                'Panel worlds retain their fixed order and at most two deduplicated states.')
            identities=[]
            for state in world['states']:
                es.exact(state,('decisionNumber','batchStartNumber','observationSha256','referenceIndex',
                    'actionCount','mutantGaps','meanGap','mutantWitnesses','meanWitness'),'Calibration state')
                number,start=state['decisionNumber'],state['batchStartNumber']
                es.require(type(number) is int and type(start) is int and 0<=start<=number<=2**53,
                    'Panel states require exact absolute decision and sealed-batch identities.')
                es.sha(state['observationSha256'],'Public calibration observation')
                count=state['actionCount'];reference=state['referenceIndex']
                es.require(type(count) is int and 2<=count<=({'TOKEN':128,'EXPANSION':1024}[record['head']]) and
                    type(reference) is int and 0<=reference<count,'Calibration states require complete branching offers.')
                es.vector(state['mutantGaps'],32,'mutant-gap');es.finite(state['meanGap'],'mean-gap')
                es.require(type(state['mutantWitnesses']) is list and len(state['mutantWitnesses'])==32 and
                    all(witness_gap(value)==gap for value,gap in zip(state['mutantWitnesses'],state['mutantGaps'])) and
                    witness_gap(state['meanWitness'])==state['meanGap'],
                    'Signed gaps must equal the finite subtraction of their exact movement witnesses.')
                identities.append((number,start))
            es.require(identities==sorted(set(identities)),'First/last panel identities must be ordered and deduplicated.')
        es.require(es.canonical(record)==self.wire,'Gap panel identity is its exact canonical semantic payload.')

    @property
    def record(self):return json.loads(self.wire)

    @property
    def sha256(self):return es.digest(self.record)

    @classmethod
    def from_record(cls, record):return cls(es.canonical(record))


def weighted_rows(panel):
    es.require(isinstance(panel,GapPanel),'Only typed immutable public gap evidence is accepted.')
    rows=[];worlds=0
    for world in panel.record['worlds']:
        if not world['states']:continue
        worlds+=1
        for state in world['states']:
            # Each world contributes two integer units; one-state worlds receive both.
            rows.append((world['index'],state,2//len(world['states'])))
    return rows,worlds


def movement(weighted,scale):
    mass=sum(weight for _,weight in weighted)
    moved=sum(weight for value,weight in weighted if changed(value,scale))
    return dict(changedWeight=moved,totalWeight=mass,rate=moved/mass)


def calibrate(panel):
    rows,worlds=weighted_rows(panel)
    if worlds<128:raise es.OperationalStop('calibration-insufficient-worlds',dict(worlds=worlds))
    mutants=[(value,weight) for _,state,weight in rows for value in state['mutantGaps']]
    mean=[(state['meanGap'],weight) for _,state,weight in rows]
    distributions={}
    for name,weighted in (('mutants',mutants),('prescribedMean',mean)):
        total=sum(weight for _,weight in weighted)
        positive=sum(weight for value,weight in weighted if value>0)
        q=quantile90(weighted)
        distributions[name]=dict(positiveWeight=positive,totalWeight=total,positiveMass=positive/total,quantile90=q)
        if positive*10<total:raise es.OperationalStop('calibration-positive-gap-mass',distributions)
        if q<=1e-8:raise es.OperationalStop('calibration-small-signed-quantile',distributions)
    scale=(1.+2.**-20)/min(value['quantile90'] for value in distributions.values())
    if not 1/32<=scale<=32:raise es.OperationalStop('calibration-scale-bounds',dict(scale=scale,distributions=distributions))
    mutant_witnesses=[(value,weight) for _,state,weight in rows for value in state['mutantWitnesses']]
    mean_witnesses=[(state['meanWitness'],weight) for _,state,weight in rows]
    moved={name:movement(weighted,scale) for name,weighted in
        (('mutants',mutant_witnesses),('prescribedMean',mean_witnesses))}
    if not all(.05<=value['rate']<=.40 for value in moved.values()):
        raise es.OperationalStop('calibration-movement-bounds',dict(scale=scale,movement=moved))
    record=panel.record
    return dict(kind=VERSION,complete=True,head=record['head'],recipe=RECIPE,panelSha256=panel.sha256,
        subjects={key:record[key] for key in ('protocolSha256','originalModelSha256','projectionSha256',
            'directionsSha256','prescribedMeanSha256','publicPanelSha256')},representedWorlds=worlds,
        missingWorlds=256-worlds,states=len(rows),distributions=distributions,scale=scale,movement=moved,
        terminalLabelsRead=False,scaleAttempts=1,liveMovementIsGate=False)


def live_movement(panel, calibration, gaps):
    """No lower/upper movement gate: an unchanged live head is a valid diagnostic."""
    rows,_=weighted_rows(panel)
    es.require(calibration==calibrate(panel),'Live diagnostics require the one exact previously sealed calibration.')
    es.require(type(gaps) is list and len(gaps)==len(rows),'Live gaps must retain every fixed panel state.')
    weighted=[]
    for item,(index,state,weight) in zip(gaps,rows):
        es.exact(item,('index','decisionNumber','batchStartNumber','witness'),'Live mean diagnostic')
        es.require(item['index']==index and item['decisionNumber']==state['decisionNumber'] and
            item['batchStartNumber']==state['batchStartNumber'],'A live diagnostic cannot substitute panel states.')
        witness_gap(item['witness']);weighted.append((item['witness'],weight))
    return dict(kind='coordinated-es-live-movement-1',panelSha256=panel.sha256,
        calibrationSha256=es.digest(calibration),**movement(weighted,calibration['scale']),
        diagnosticOnly=True,eligibilityGate=False)
