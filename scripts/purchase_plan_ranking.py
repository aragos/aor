"""Fixed public prediction seal and whole-origin diagnostic; no actor fit or runtime export."""
import argparse
import math
from pathlib import Path
import statistics

import automata_experiments as exp

read, sha, require = exp.read, exp.sha, exp.require
VERSION = 'purchase-plan-ranking-1'
METRICS = ('reward', 'winShare', 'rankReward', 'chaos')


def verified_bank(directory):
    directory = Path(directory).resolve(strict=True)
    finished = read(directory / 'finished.json')
    manifest = read(directory / 'manifest.json')
    require(finished['kind'] == 'purchase-plan-public-complete-1'
        and finished['signature'] == manifest['signature'] and finished['origins'] == 256
        and finished['terminalTails'] == 0
        and set(finished['publicSha256']) == {str(i) for i in range(256)},
        'Prediction requires the completed public-only origin bank.')
    expected = {'manifest.json', *(item['snapshot'] for item in manifest['inputs']),
                *(f'{kind}-{i}.json' for i in range(256) for kind in ('public', 'backbone'))}
    require(set(finished['artifactSha256']) == expected,
            'All source snapshots, public plans and private backbones must be pinned.')
    for name, digest in finished['artifactSha256'].items():
        path = directory / name
        require(Path(name).name == name and not path.is_symlink() and sha(path) == digest,
                'A completed public artifact changed: ' + name)
    require(all(finished['publicSha256'][str(i)] == finished['artifactSha256'][f'public-{i}.json']
                for i in range(256)), 'Public file identities must agree in both inventories.')
    return directory, manifest, finished


def predict_bank(public, descriptor, output):
    """Only public JSON is decoded; executor backbones are opaque hash checks."""
    import purchase_plan_predictor as predictor
    directory, manifest, finished = verified_bank(public)
    original_pins = {directory / name: digest for name, digest in finished['artifactSha256'].items()}
    original_pins.update({directory / name: sha(directory / name)
                          for name in ('manifest.json', 'finished.json')})
    output = Path(output).resolve()
    require(not output.exists() and not output.is_relative_to(directory),
            'Prediction output must be new and separate from the frozen public bank.')
    require(sha(descriptor) == manifest['predictorDescriptorSha256'],
            'The predictor descriptor must be the one frozen before public collection.')
    model = predictor.FrozenPlanPredictor(descriptor)
    decisions = [predictor.rank_origin(model, read(directory / f'public-{i}.json'))
                 for i in range(256)]
    model.unchanged()
    require(all(sha(path) == digest for path, digest in original_pins.items()),
            'The exact original public bank changed while its fixed predictions were computed.')
    output.mkdir(parents=True)
    sealed = dict(kind='purchase-plan-predictions-1', signature=manifest['signature'],
        publicFinishedSha256=sha(directory / 'finished.json'), predictorDescriptorSha256=sha(descriptor),
        publicSha256=finished['publicSha256'], decisions=decisions,
        allPredictionsComplete=True, terminalTailsStarted=False)
    exp.document(output / 'predictions.json', sealed)
    exp.document(output / 'receipt.json', dict(kind='purchase-plan-prediction-receipt-1',
        predictionsSha256=sha(output / 'predictions.json'), descriptorSha256=sha(descriptor),
        publicFinishedSha256=sha(directory / 'finished.json'),
        passed=True, actualFits=0, newGames=0, terminalLabelsDecoded=False,
        origins=256, ready=sum(d['status'] == 'READY' for d in decisions),
        changed=sum(d['status'] == 'READY' and
                    (d['plans'][d['selectedIndex']]['aliasOf'] if
                     d['plans'][d['selectedIndex']]['aliasOf'] is not None else d['selectedIndex']) != 0
                    for d in decisions)))
    return output / 'predictions.json'


def interval(values):
    """Descriptive unadjusted normal95 interval over independent origin means, not tails."""
    values = list(values)
    if not values:
        return dict(count=0, mean=None, lower95=None, upper95=None)
    mean = statistics.fmean(values)
    half = 1.96 * statistics.stdev(values) / math.sqrt(len(values)) if len(values) > 1 else None
    return dict(count=len(values), mean=mean,
                lower95=None if half is None else mean-half,
                upper95=None if half is None else mean+half)


def summarize(origins, decisions, results, streams):
    """Input evidence is already hash/canonical verified; no outcome-based eligibility or choice."""
    require(len(origins) == len(decisions) == len(results) == len(streams) == 256,
            'The diagnostic retains the entire prospective256-origin bank.')
    rows, status_counts, exclusions = [], {}, {}
    aliases = logical = actual = changed = 0
    for index, (origin, decision, result, futures) in enumerate(zip(origins, decisions, results, streams, strict=True)):
        require(origin['originIndex'] == decision['originIndex'] == result['originIndex'] == index
            and origin['status'] == decision['status'] == result['status'],
            'Public support, frozen choice and terminal siblings must share the same origin.')
        status = origin['status']
        status_counts[status] = status_counts.get(status, 0) + 1
        for plan in origin['plans']:
            if plan['exclusion'] is not None:
                exclusions[plan['exclusion']] = exclusions.get(plan['exclusion'], 0) + 1
        if status != 'READY':
            require(not result['tails'] and not result['logicalPlanExecutions'],
                    'Missing or unsupported references have no fabricated return rows.')
            continue
        plans = {p['index']: p for p in origin['plans'] if p['status'] == 'SUPPORTED'}
        execution = {p: plan['aliasOf'] if plan['aliasOf'] is not None else p for p, plan in plans.items()}
        require(result['logicalPlanExecutions'] == {str(k): v for k, v in execution.items()},
                'All supported logical siblings must resolve to their exact canonical execution.')
        observed = {(t['planIndex'], t['replica']): t for t in result['tails']}
        require(len(observed) == len(result['tails']) and set(observed) ==
                {(p, r) for p in set(execution.values()) for r in range(4)},
                'Every unique supported plan requires all four shared futures exactly once.')
        def mean_return(plan):
            outcomes = []
            for replica in range(4):
                tail = observed[execution[plan], replica]
                outcome = tail['outcome']
                require(tail['continuationSeed'] == outcome['continuationSeed'] == futures[replica],
                        'Sibling outcomes must retain the exact prospective shared chance streams.')
                values = {key: outcome[key] for key in METRICS[:-1]}
                require(all(type(v) in (int, float) and math.isfinite(v) and 0 <= v <= 1 for v in values.values()),
                        'Terminal reward metrics must remain finite normalized values.')
                values['chaos'] = float(outcome['scores'][index % 4]['total'] is None)
                outcomes.append(values)
            return {key: statistics.fmean(value[key] for value in outcomes) for key in METRICS}
        selected = decision['selectedIndex']
        require(selected in plans and 0 in plans,
                'Every READY origin retains its exact supported incumbent reference and selected plan.')
        values = {plan: mean_return(plan) for plan in plans}
        primary = {key: values[selected][key]-values[0][key] for key in METRICS}
        secondary = ({key: values[selected][key]-values[1][key] for key in METRICS} if 1 in plans else None)
        rows.append(dict(originIndex=index, selectedIndex=selected, selectedMinusG3=primary,
                         selectedMinusPass=secondary, planReturns=values))
        logical += len(plans)
        aliases += sum(plan['aliasOf'] is not None for plan in plans.values())
        actual += len(result['tails'])
        changed += execution[selected] != execution[0]
    primary = {key: interval(row['selectedMinusG3'][key] for row in rows) for key in METRICS}
    secondary = {key: interval(row['selectedMinusPass'][key] for row in rows
                              if row['selectedMinusPass'] is not None) for key in METRICS}
    return dict(kind='purchase-plan-ranking-diagnostic-1', rows=rows,
        statusCounts=status_counts, exclusionCounts=exclusions,
        supportedLogicalPlans=logical, aliases=aliases, actualTailGames=actual, interventions=changed,
        selectedMinusG3=primary, selectedMinusPass=secondary,
        primaryDenominator='all READY origins, including unchanged and alias-only selections',
        secondaryDenominator='READY origins whose fixed advance-PASS plan is supported',
        missingTreatment='MISSING; no zero imputation',
        interval='unadjusted normal95 across whole-origin four-future paired means',
        strengthQualification=False, runtimeActivation=False, actorFits=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public', required=True)
    parser.add_argument('--descriptor', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    predict_bank(args.public, args.descriptor, args.output)


if __name__ == '__main__':
    main()
