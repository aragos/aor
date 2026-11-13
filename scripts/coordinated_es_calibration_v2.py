"""Prospective one-scale calibration from both exact discrete movement intervals.

The interval is analytic; final eligibility uses the unchanged Float64 score witnesses.
No search, neighboring scale, forward, return or legacy calibration fallback is used.
"""
import math

import coordinated_es_calibration as base
import coordinated_es_math as es

VERSION='coordinated-es-feasible-gap-calibration-2'
OUTER_VERSION='coordinated-es-calibration-2'
RECIPE=dict(kind=VERSION,margin=1.,quantileNumerator=9,quantileDenominator=10,
    minimumWorlds=128,minimumPositiveMass=.1,minimumQuantile=1e-8,
    minimumScale=1/32,maximumScale=32.,minimumMovement=.05,maximumMovement=.40,
    minimumChangedNumerator=1,minimumChangedDenominator=20,
    maximumChangedNumerator=2,maximumChangedDenominator=5,
    intervalRule='OPEN_LOWER_CLOSED_UPPER_WEIGHTED_ATOMS',
    scaleSelection='SQRT_COMMON_INTERVAL_PRODUCT',requireStrictFloat64Interior=True)
PROTOCOL_RECIPE=dict(kind=VERSION,generation=1,heads=['EXPANSION','TOKEN'],
    windows='FIRST_LAST_BRANCHING_ASSIGNED_EPOCH',minimumWorlds=128,
    weighting='EQUAL_WORLD_THEN_UNIQUE_STATE',quantile=.9,minimumPositiveMass=.1,
    minimumQuantile=1e-8,minimumScale=1/32,maximumScale=32.,minimumMovement=.05,maximumMovement=.40,
    meanProbe='ALTERNATING_SIGN_LINEAR_FITNESS',margin=1.,scoreClamp=False,
    ties='REFERENCE_THEN_LOWEST_ORIGINAL_INDEX',recalibration=False,
    intervalRule=RECIPE['intervalRule'],scaleSelection=RECIPE['scaleSelection'],
    requireStrictFloat64Interior=True)
STOPS={'calibration-insufficient-worlds','calibration-positive-gap-mass',
    'calibration-small-signed-quantile','calibration-empty-feasible-interval',
    'calibration-no-interior-scale','calibration-movement-bounds','calibration-nonfinite-movement'}


def weighted_interval(weighted):
    """Keep full signed mass; order positive atoms by their strict movement thresholds."""
    base.quantile90(weighted)  # Own finite values and strictly positive integer weights.
    total=sum(weight for _,weight in weighted)
    minimum=(total+19)//20;maximum=2*total//5
    atoms={}
    for value,weight in weighted:
        if value>0:atoms[value]=atoms.get(value,0)+weight
    above=0;lower=None;upper=None
    for gap,weight in sorted(atoms.items(),reverse=True):
        if lower is None and above+weight>=minimum:lower=(gap,above,weight)
        if upper is None and above+weight>maximum:upper=(gap,above,weight)
        above+=weight
    es.require(lower is not None,'Sufficient positive mass must precede interval construction.')
    lower_gap,lower_above,lower_at=lower
    raw_lower=1./lower_gap
    lower_scale=max(RECIPE['minimumScale'],raw_lower)
    # Upper reciprocals outside the fixed scale cap are irrelevant and may overflow.
    upper_gap,upper_above,upper_at=(None,above,0) if upper is None else upper
    upper_scale=(RECIPE['maximumScale'] if upper_gap is None or upper_gap<=1/32 else 1./upper_gap)
    return dict(minimumChangedWeight=minimum,maximumChangedWeight=maximum,
        lowerGap=lower_gap,lowerAboveWeight=lower_above,lowerAtWeight=lower_at,
        upperGap=upper_gap,upperAboveWeight=upper_above,upperAtWeight=upper_at,
        lowerScale=lower_scale,upperScale=upper_scale,
        lowerInclusive=RECIPE['minimumScale']>raw_lower,upperInclusive=True)


def common_interval(intervals):
    es.exact(intervals,('mutants','prescribedMean'),'The two fixed movement intervals')
    lower=max(row['lowerScale'] for row in intervals.values())
    upper=min(row['upperScale'] for row in intervals.values())
    value=dict(lowerScale=lower,upperScale=upper,
        lowerInclusive=all(row['lowerInclusive'] for row in intervals.values() if row['lowerScale']==lower),
        upperInclusive=all(row['upperInclusive'] for row in intervals.values() if row['upperScale']==upper))
    if not math.isfinite(lower) or not math.isfinite(upper) or lower>=upper:
        raise es.OperationalStop('calibration-empty-feasible-interval',dict(intervals=intervals,commonInterval=value))
    return value


def interior_scale(interval):
    """One prescribed Float64 midpoint, with no endpoint repair or alternate trial."""
    lower,upper=interval['lowerScale'],interval['upperScale']
    scale=math.sqrt(lower*upper)
    if not math.isfinite(scale) or not lower<scale<upper:
        raise es.OperationalStop('calibration-no-interior-scale',dict(commonInterval=interval,scale=scale))
    return scale


def calibrate(panel):
    rows,worlds=base.weighted_rows(panel)
    if worlds<128:raise es.OperationalStop('calibration-insufficient-worlds',dict(worlds=worlds))
    weighted=dict(mutants=[(value,weight) for _,state,weight in rows for value in state['mutantGaps']],
        prescribedMean=[(state['meanGap'],weight) for _,state,weight in rows])
    distributions={}
    for name,values in weighted.items():
        total=sum(weight for _,weight in values);positive=sum(weight for value,weight in values if value>0)
        quantile=base.quantile90(values)
        distributions[name]=dict(positiveWeight=positive,totalWeight=total,positiveMass=positive/total,quantile90=quantile)
        if positive*10<total:raise es.OperationalStop('calibration-positive-gap-mass',distributions)
        if quantile<=1e-8:raise es.OperationalStop('calibration-small-signed-quantile',distributions)
    intervals={name:weighted_interval(values) for name,values in weighted.items()}
    common=common_interval(intervals);scale=interior_scale(common)
    witnesses=dict(mutants=[(value,weight) for _,state,weight in rows for value in state['mutantWitnesses']],
        prescribedMean=[(state['meanWitness'],weight) for _,state,weight in rows])
    try:moved={name:base.movement(values,scale) for name,values in witnesses.items()}
    except es.OperationalStop as failure:
        raise es.OperationalStop('calibration-nonfinite-movement',dict(scale=scale,scoreFailure=failure.reason)) from failure
    if not all(value['changedWeight']*20>=value['totalWeight'] and
        value['changedWeight']*5<=2*value['totalWeight'] for value in moved.values()):
        raise es.OperationalStop('calibration-movement-bounds',dict(scale=scale,movement=moved))
    record=panel.record
    return dict(kind=VERSION,complete=True,head=record['head'],recipe=RECIPE,panelSha256=panel.sha256,
        subjects={key:record[key] for key in ('protocolSha256','originalModelSha256','projectionSha256',
            'directionsSha256','prescribedMeanSha256','publicPanelSha256')},representedWorlds=worlds,
        missingWorlds=256-worlds,states=len(rows),distributions=distributions,intervals=intervals,
        commonInterval=common,scale=scale,movement=moved,terminalLabelsRead=False,
        scaleAttempts=1,liveMovementIsGate=False)


def live_movement(panel,calibration,gaps):
    """The same live witness diagnostic, bound intrinsically to the V2 sealed receipt."""
    rows,_=base.weighted_rows(panel)
    es.require(calibration==calibrate(panel),'Live diagnostics require the exact V2 sealed calibration.')
    es.require(type(gaps) is list and len(gaps)==len(rows),'Live gaps must retain every fixed panel state.')
    weighted=[]
    for item,(index,state,weight) in zip(gaps,rows):
        es.exact(item,('index','decisionNumber','batchStartNumber','witness'),'Live mean diagnostic')
        es.require(item['index']==index and item['decisionNumber']==state['decisionNumber'] and
            item['batchStartNumber']==state['batchStartNumber'],'A live diagnostic cannot substitute panel states.')
        base.witness_gap(item['witness']);weighted.append((item['witness'],weight))
    return dict(kind='coordinated-es-live-movement-1',panelSha256=panel.sha256,
        calibrationSha256=es.digest(calibration),**base.movement(weighted,calibration['scale']),
        diagnosticOnly=True,eligibilityGate=False)
