"""Fixed73 whole-world interaction report; no variant selection or policy promotion."""
import math
from statistics import NormalDist

STYLES = ('G3', 'D1_PLUS', 'D1_MINUS', 'D2_PLUS', 'D2_MINUS')
METRICS = ('reward', 'winShare', 'rankReward', 'chaos')
FAMILY_Z = NormalDist().inv_cdf(1-.05/(2*4))


def require(condition, message):
    if not condition: raise ValueError(message)


def interval(values, z):
    require(values and all(math.isfinite(v) for v in values), 'Finite whole-origin values are required.')
    mean = sum(values)/len(values)
    if len(values) < 2: return dict(count=len(values), mean=mean, lower=None, upper=None)
    variance = sum((x-mean)**2 for x in values)/(len(values)-1)
    radius = z*math.sqrt(variance/len(values))
    return dict(count=len(values), mean=mean, lower=mean-radius, upper=mean+radius)


def from_native(origin):
    """Compact one already hash-verified native origin without losing logical aliases."""
    from joint_economy import terminal_metrics
    index = origin['request']['index']
    require(type(index) is int and 0 <= index < 1024, 'An original bank index is required.')
    require(origin['request'] == dict(index=index,seat=index%4,epoch=1+(index//4)%3,
                ordinal=(0,2,4,6)[(index//12)%4]), 'The original public request schedule changed.')
    if origin['status'] == 'MISSING':
        require(not origin['continuations'] and origin['actorInput'] is None
                and origin['prefix']['terminal'] is True,
                'Missing roots retain their completed normal backbone only.')
        return dict(index=index, status='MISSING', reason=origin['missingReason'], continuations={})
    require(origin['status'] == 'COLLECTED' and origin['exactIncumbentVerified'] is True
            and [r['style'] for r in origin['continuations']] == list(STYLES),
            'The fixed five styles require the exact reproduced E root.')
    commands = list(dict.fromkeys([origin['incumbentCommand'], 'buy-expansion-card']))
    require(all(command in [a['command'] for a in origin['actorInput']['encoded']['actions']]
                for command in commands), 'Both original logical actions must be offered.')
    continuations = {}
    for row in origin['continuations']:
        require([a['command'] for a in row['arms']] == commands
                and row['incumbentIndex'] == 0
                and row['acquireIndex'] == commands.index('buy-expansion-card'),
                'Canonical executions must preserve original logical arm identities.')
        arms = []
        for arm in row['arms']:
            require(len(arm['outcomes']) == 2, 'Both original paired futures are required.')
            values = []
            for replicate, tail in enumerate(arm['outcomes']):
                terminal = tail['terminal']
                require(tail['replicate'] == replicate and tail['replayVerified'] is True
                        and terminal['continuationSeed'] == origin['streams']['future'][replicate],
                        'Every outcome must retain its exact paired stream and native replay proof.')
                checked = terminal_metrics(dict(controlledSeat=origin['request']['seat'],
                    terminal=dict(terminal, gameDecisions=terminal['decisions'])))
                values.append({m: float(checked[m]) for m in METRICS})
            arms.append(values)
        continuations[row['style']] = dict(incumbent=arms[0], acquire=arms[row['acquireIndex']])
    return dict(index=index, status='COLLECTED', sameAction=len(commands) == 1,
                continuations=continuations)


def summarize(origins):
    """Input is the completed fixed1024 bank; each outcome is a two-future metric matrix."""
    require(len(origins) == 1024 and [x['index'] for x in origins] == list(range(1024)),
            'Only the complete original bank may be analyzed; no refill or partial effects.')
    rows = []
    missing = {}
    for origin in origins:
        if origin['status'] == 'MISSING':
            require(origin['continuations'] == {}, 'Missing origins cannot invent outcomes.')
            missing[origin['reason']] = missing.get(origin['reason'], 0)+1
            continue
        require(origin['status'] == 'COLLECTED' and set(origin['continuations']) == set(STYLES),
                'Every eligible origin requires the fixed five continuation styles.')
        require(type(origin['sameAction']) is bool, 'Logical action identity must be explicit.')
        differences = {}
        for style in STYLES:
            arms = origin['continuations'][style]
            require(set(arms) == {'incumbent','acquire'}, 'Both exact logical arms must be retained.')
            for outcomes in arms.values():
                require(len(outcomes) == 2 and all(set(v) == set(METRICS) for v in outcomes),
                        'Every arm requires both original future outcomes and all metric fields.')
                require(all(type(v) in (int,float) and math.isfinite(v) and 0 <= v <= 1
                            for outcome in outcomes for v in outcome.values()),
                        'Terminal metrics must preserve finite normalized own-player outcomes.')
            if origin['sameAction']:
                require(arms['incumbent'] == arms['acquire'], 'Identical arms require identical execution returns.')
            differences[style] = {metric: sum(arms['acquire'][r][metric]-arms['incumbent'][r][metric]
                                               for r in range(2))/2 for metric in METRICS}
        rows.append(dict(index=origin['index'], sameAction=origin['sameAction'], acquisition=differences,
            interactions={style:{m:differences[style][m]-differences['G3'][m] for m in METRICS}
                          for style in STYLES[1:]}))
    primary = {style: interval([r['interactions'][style]['reward'] for r in rows], FAMILY_Z)
               for style in STYLES[1:]} if rows else {}
    descriptive = {style:{m:interval([r['interactions'][style][m] for r in rows],1.959963984540054)
                           for m in METRICS} for style in STYLES[1:]} if rows else {}
    dependence = any(v['lower'] is not None and (v['lower'] > 0 or v['upper'] < 0)
                     for v in primary.values())
    return dict(kind='card-continuation-interaction-report-1', requested=1024, eligible=len(rows),
        missing=missing, sameAction=sum(r['sameAction'] for r in rows), rows=rows,
        primary=primary, familyZ=FAMILY_Z, individualCoverage=.9875, familyCoverage=.95,
        dependenceDetected=dependence, descriptive=descriptive,
        meanFourInteraction=interval([sum(r['interactions'][s]['reward'] for s in STYLES[1:])/4
                                      for r in rows],1.959963984540054) if rows else None,
        antitheticPairMeans={name:interval([sum(r['interactions'][s]['reward'] for s in pair)/2
                                          for r in rows],1.959963984540054)
                             for name,pair in (('D1',STYLES[1:3]),('D2',STYLES[3:5]))} if rows else {},
        g3Acquisition=interval([r['acquisition']['G3']['reward'] for r in rows],1.959963984540054) if rows else None,
        limitedPower=len(rows) < 256, selectedVariant=None, qualificationPassed=False,
        runtimeActivation=False, interpretation='Dependence alone establishes neither stronger policy nor positive card value; negative interactions may reflect worse exploitation. Missing worlds are not zero effects.')
