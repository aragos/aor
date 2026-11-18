"""Independent complete-bank paired intervals and the two fixed win-only development gates."""
import math

import automata_experiments as intervals
import coordinated_win_plan as p

ROLES=('FINAL','START','CONTROL')
COMPARISONS={'finalMinusStart':('FINAL','START'),'finalMinusControl':('FINAL','CONTROL'),
    'startMinusControl':('START','CONTROL')}
METRICS=('wins','rewards','ranks','chaos')


def value(world,role,metric):
    game=world['roles'][role];seat=world['seat']
    if metric=='chaos':
        score=next(row for row in game['scores'] if row['player']==f'p{seat+1}')
        return int(score['total'] is None)
    return game[metric][seat]


def contrasts(worlds):
    p.require([world['index'] for world in worlds]==list(range(2048)) and
        all(set(world['roles'])==set(ROLES) and type(world['seat']) is int and
            world['seat']==world['index']%4 for world in worlds),
        'Only the complete fixed2048 paired worlds and all three final/start/control roles supply effects.')
    return {name:{metric:intervals.interval([value(world,first,metric)-value(world,second,metric)
        for world in worlds]) for metric in METRICS} for name,(first,second) in COMPARISONS.items()}


def verify(worlds,report):
    p.require(report['kind']==p.PREFIX+'-screen-report-1' and report['worlds']==2048 and
        report['intervalMultiplier']==1.96 and
        report['intervalMethod']=='Normal 95% across independent paired worlds',
        'The final screen retains its fixed independent-world95 interval method and complete bank.')
    expected=contrasts(worlds)
    for name,metrics in expected.items():
        p.exact(report[name],METRICS,'Paired continuation metrics')
        for metric,numbers in metrics.items():
            row=report[name][metric]
            p.exact(row,('count','mean','lower95','upper95'),'Whole-world95 interval')
            p.require(type(row['count']) is int and row['count']==2048 and
                all(type(row[field]) in (int,float) and math.isfinite(row[field]) and
                    math.isclose(row[field],numbers[field],rel_tol=1e-10,abs_tol=1e-12)
                    for field in ('mean','lower95','upper95')),
                'Every report interval must reproduce from the same complete controlled-seat paired worlds.')
    learning=expected['finalMinusStart']['wins']['lower95']>0
    development=learning and expected['finalMinusControl']['wins']['lower95']>0
    p.require(report['learningGatePassed'] is learning and report['developmentGatePassed'] is development and
        report['qualification'] is False and report['activated'] is False and report['automaticSuccessor'] is False,
        'Only both final win lower bounds may admit a development nominee; no fallback, qualification or successor exists.')
    return development
