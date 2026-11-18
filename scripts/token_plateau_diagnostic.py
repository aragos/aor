"""Post-run inference on existing simulated TOKEN2 states; no fitting, games or RNG draws.

Preparation freezes the archive members and model/source identities before inference. The
instrumented RawPolicy body is reused unchanged except for returning its already-computed
hidden tensors, so saturation measurement does not add model-state evaluations.
"""

import argparse
import ast
from collections import Counter
import hashlib
import inspect
import json
import math
from pathlib import Path
import sys
import tarfile
import textwrap
import time


ROOT = Path('/Users/aragos/Projects/aor')
STUDIES = ROOT / '.aor-training'
MODELS = {
    '94': ('automata-robust-league-20261117/mac-v3/models/final-mean-6/bundle.json',
           'fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e'),
    '96': ('automata-token-focus-step-20261118/mac/execution/numeric/final/artifacts/bundle.json',
           '669518d713f40078f505b3e4f48ff21d876389860ea86e6783e47b89d5d86883'),
    '97': ('automata-token-focus-second-20261118/mac/execution/numeric/final/artifacts/bundle.json',
           'd05556031f2f132de0a669c00a8cd54b06e9c7f2d633a38d918d6f7a355eb08f'),
    '4a': ('automata-robust-league-20261117/mac-v3/inputs/inference/original/bundle.json',
           '4a18d298cdfcc80f0e15f0e24cf10a2f8e9a5d36f9901da058b50560a43d72b3'),
}
CALIBRATION = STUDIES / ('automata-robust-league-20261117/mac-v3/inputs/inference/'
                         'calibration/calibration.json')
CALIBRATION_SHA = 'e33e72b5c6b5b76d77e6ee6136d5b69991c515ec3908bb093dc4a12946cb80eb'
HISTORICAL_PROTOCOL = '4f652983aca10adee79a30e401314bdcf54bac125411dc64efda8e4ee4b76786'
SELECTOR = dict(temperature=0.35, maxScoreGap=3.0, neighborRadius=4)
ACTOR_BLOCKS = ('stateWeights', 'stateBias', 'actionWeights', 'actionBias', 'actorWeights')


def require(value, message):
    if not value:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode() + b'\n'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def write(path, value):
    with path.open('xb') as stream:
        stream.write(canonical(value))


def subject(path):
    path = path.absolute()
    return dict(path=str(path), sha256=digest(path.read_bytes()))


def checked(row):
    raw = Path(row['path']).read_bytes()
    require(digest(raw) == row['sha256'], 'A frozen diagnostic input changed: ' + row['path'])
    return raw


def local_sources(script):
    """Freeze the recursively imported local Python owners, without importing heavy modules."""
    pending = [script, script.with_name('coordinated_es_projection.py')]
    found = {}
    while pending:
        path = pending.pop()
        if str(path) in found:
            continue
        found[str(path)] = digest(path.read_bytes())
        for node in ast.walk(ast.parse(path.read_text())):
            names = ([row.name for row in node.names] if isinstance(node, ast.Import) else
                     [node.module] if isinstance(node, ast.ImportFrom) and node.module else [])
            for name in names:
                candidate = script.with_name(name.split('.')[0] + '.py')
                if candidate.exists():
                    pending.append(candidate)
    return found


def prepare(output):
    require(not output.exists(), 'Preparation requires a new directory.')
    output.mkdir(parents=True)
    pins = local_sources(Path(__file__).absolute())
    models = {}
    for name, (relative, expected) in MODELS.items():
        bundle = subject(STUDIES / relative)
        require(bundle['sha256'] == expected, 'Only the four named post-run models are admitted.')
        row = json.loads(checked(bundle))['heads']['TOKEN']
        actor = subject(Path(bundle['path']).parent / row['path'])
        require(actor['sha256'] == row['sha256'], 'TOKEN actor bytes must match their bundle.')
        models[name] = dict(bundle=bundle, actor=actor)
        pins.update({bundle['path']: bundle['sha256'], actor['path']: actor['sha256']})
    calibration = subject(CALIBRATION)
    require(calibration['sha256'] == CALIBRATION_SHA, 'The original sealed calibration is required.')
    scale = json.loads(checked(calibration))['heads']['TOKEN']['scale']
    pins[calibration['path']] = calibration['sha256']
    sampler_source = STUDIES / ('automata-local-bid-mixture-20261118/native-source/source/ai/'
                               'src/commonMain/kotlin/dev/aragos/aor/ai')
    for name in ('NearOptimalTokenPolicy.kt', 'IncumbentResidualPolicy.kt'):
        row = subject(sampler_source / name)
        pins[row['path']] = row['sha256']
    archives = []
    states = []
    for chunk in (5, 6, 7):
        archive = subject(STUDIES / ('automata-coordinated-es-fitness-v2-20261113/mac/execution/'
            f'run/study/generations/generation-1/controls/chunk-{chunk}/evidence.tar.gz'))
        pins[archive['path']] = archive['sha256']
        with tarfile.open(archive['path']) as tar:
            def member(name):
                return tar.extractfile(name).read()
            finished_raw = member('finished.json')
            finished = json.loads(finished_raw)
            source_raw = member('source.json')
            source = json.loads(source_raw)
            require(finished['files']['source.json'] == digest(source_raw),
                    'Archive source must match its original finished manifest.')
            require(source['kind'] == 'coordinated-es-generation-1' and
                    source['stage'] == 'CONTROLS' and source['generation'] == 1 and
                    source['originalBundle']['sha256'] == MODELS['4a'][1] and
                    source['protocol']['sha256'] == HISTORICAL_PROTOCOL and
                    source['globalStart'] == chunk * 32 and source['count'] == 32 and
                    finished['attemptedGames'] == finished['completedGames'] == 32,
                    'Only complete pre-existing simulated G1 control archives are admitted.')
            members = {'source.json': digest(source_raw), 'finished.json': digest(finished_raw)}
            for index in range(chunk * 32, (chunk + 1) * 32):
                name = f'panel-{index}.json'
                raw = member(name)
                require(digest(raw) == finished['files'][name], 'A historical panel member changed.')
                members[name] = digest(raw)
                panel = json.loads(raw)
                require(panel['kind'] == 'coordinated-es-panel-1' and
                        panel['globalIndex'] == index and
                        panel['sourceSha256'] == digest(source_raw) and
                        panel['originalBundleSha256'] == MODELS['4a'][1],
                        'TOKEN observations must retain their simulated source identity.')
                for position, state in enumerate(panel['heads']['TOKEN']):
                    observation = state['observation']
                    require(observation['encoderVersion'] == 'aor-token-observation-2' and
                            state['referenceIndex'] == observation['baselineIndex'] and
                            [action['command'] for action in observation['actions']] ==
                            sorted(action['command'] for action in observation['actions']),
                            'Preserve complete lexical offers and the archived reference index.')
                    states.append(dict(world=index, position=position, number=state['number'],
                                       epoch=state['epoch'], observation=observation))
        archives.append(dict(archive=archive, members=members))
    require(len(states) == 166, 'Use all 166 existing states, without outcome-based selection.')
    write(output / 'states.json', states)
    pins[str(output / 'states.json')] = digest((output / 'states.json').read_bytes())
    interpreter = subject(Path(sys.executable))
    pins[interpreter['path']] = interpreter['sha256']
    plan = dict(kind='token-plateau-diagnostic-plan-1', models=models, archives=archives,
                calibration=calibration, scale=scale, selector=SELECTOR, inputPins=pins,
                states=subject(output / 'states.json'), statesCount=166, modelCount=4,
                maximumModelStateEvaluations=664, statesByEpoch=dict(Counter(
                    row['epoch'] for row in states)),
                worldsRepresented=len({row['world'] for row in states}),
                reference='Archived G1 RAW_G3 referenceIndex, not a newly recomputed live reference',
                prohibitions=['no fitting', 'no games', 'no RNG draws', 'no human records',
                              'no qualification', 'no promotion'])
    write(output / 'plan.json', plan)
    print(json.dumps(dict(plan=subject(output / 'plan.json'), states=len(states),
                          maximumModelStateEvaluations=664), sort_keys=True))


def distribution(scores, amounts, baseline, sampler=SELECTOR):
    require(len(scores) == len(amounts) and all(math.isfinite(x) for x in scores),
            'Scores must be finite and preserve the complete original action order.')
    maximum = max(scores)
    winner = baseline if scores[baseline] == maximum else scores.index(maximum)
    weights = [math.exp((score - maximum) / sampler['temperature'])
               if maximum - score <= sampler['maxScoreGap'] and
               abs(amount - amounts[winner]) <= sampler['neighborRadius'] else 0.
               for amount, score in zip(amounts, scores)]
    total = sum(weights)
    return winner, [weight / total for weight in weights]


def instrumented_raw(projection):
    """Reuse the exact trusted forward body and expose computed activations without a second pass."""
    tree = ast.parse(textwrap.dedent(inspect.getsource(projection.RawPolicy.raw)))
    original = ast.dump(tree, include_attributes=False)
    returns = [node for node in ast.walk(tree) if isinstance(node, ast.Return)]
    require(len(returns) == 1, 'The pinned raw owner must retain its single terminal return.')
    old = returns[0].value
    returns[0].value = ast.Tuple(elts=[old, ast.Name(id='hidden', ctx=ast.Load()),
                                    ast.Name(id='action_hidden', ctx=ast.Load())], ctx=ast.Load())
    tree = ast.fix_missing_locations(tree)
    restored = ast.parse(ast.unparse(tree))
    restored_return = next(node for node in ast.walk(restored) if isinstance(node, ast.Return))
    restored_return.value = restored_return.value.elts[0]
    require(ast.dump(restored, include_attributes=False) == original,
            'Instrumentation may only expose tensors already computed by the original forward.')
    namespace = {}
    exec(compile(tree, '<pinned-RawPolicy-activation-capture>', 'exec'),
         projection.RawPolicy.raw.__globals__, namespace)
    return namespace['raw']


def stats(values):
    values = sorted(values)
    return dict(count=len(values), minimum=values[0], maximum=values[-1],
                mean=math.fsum(values) / len(values),
                median=values[(len(values) - 1) // 2], p90=values[int((len(values) - 1) * .9)])


def same_uniform_disagreement(left, right):
    """Exact interval coupling for the same actor uniform, without drawing that uniform."""
    before_left = before_right = same = 0.
    for p, q in zip(left, right):
        after_left, after_right = before_left + p, before_right + q
        same += max(0., min(after_left, after_right) - max(before_left, before_right))
        before_left, before_right = after_left, after_right
    return min(1., max(0., 1. - same))


def run(plan_path, output):
    require(not output.exists(), 'Inference requires a new output directory; it cannot resume.')
    output.mkdir(parents=True)
    plan_raw = plan_path.read_bytes()
    plan = json.loads(plan_raw)
    for path, sha in plan['inputPins'].items():
        checked(dict(path=path, sha256=sha))
    require(plan['maximumModelStateEvaluations'] == 664 and plan['selector'] == SELECTOR,
            'The frozen diagnostic budget and selector cannot change.')
    import torch
    import coordinated_es_projection as projection
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    rng_before = torch.get_rng_state().clone()
    raw = instrumented_raw(projection)
    states = json.loads(checked(plan['states']))
    evaluations = calls = 0
    actors = {}
    models = {}
    for name, row in plan['models'].items():
        models[name] = json.loads(checked(row['actor']))
        actors[name] = projection.RawPolicy(models[name], projection.es.Head.TOKEN)
    require(len({actor.dictionary for actor in actors.values()}) == 1,
            'All models must use the same complete TOKEN dictionaries.')
    prepared = [actors['94'].public(row['observation']) for row in states]
    result = {}
    activations = {}
    started = time.time()
    for name, actor in actors.items():
        batch = []
        action_count = 0
        results = []
        saturation = dict(stateUnits=0, actionUnits=0, state99=0, state999=0,
                          action99=0, action999=0)
        def flush():
            nonlocal evaluations, calls
            if not batch:
                return
            evaluations += len(batch)
            calls += 1
            write(output / f'attempt-{calls}.json', dict(model=name, states=len(batch),
                attemptedModelStateEvaluations=evaluations, maximum=664))
            require(evaluations <= 664, 'The diagnostic forward budget is exhausted.')
            values, hidden, action_hidden = raw(actor, batch)
            results.extend(values)
            for prefix, tensor in (('state', hidden), ('action', action_hidden)):
                saturation[prefix + 'Units'] += tensor.numel()
                saturation[prefix + '99'] += (tensor.abs() >= .99).sum().item()
                saturation[prefix + '999'] += (tensor.abs() >= .999).sum().item()
        for item in prepared:
            if batch and (len(batch) == 256 or action_count + item.action_count > 16384):
                flush()
                batch = []
                action_count = 0
            batch.append(item)
            action_count += item.action_count
        flush()
        result[name] = results
        activations[name] = saturation
    require(torch.equal(rng_before, torch.get_rng_state()),
            'Loading and diagnostic inference must not advance the Torch RNG state.')
    rows = []
    for index, state in enumerate(states):
        obs = state['observation']
        commands = [row['command'] for row in obs['actions']]
        amounts = [int(command.removeprefix('buy-tokens ')) for command in commands]
        baseline = obs['baselineIndex']
        row = dict(world=state['world'], position=state['position'], epoch=state['epoch'],
                   number=state['number'], commands=commands, baselineIndex=baseline, models={})
        for name in ('94', '96', '97'):
            values = result[name][index]
            reference_values = result['4a'][index]
            standardized = [plan['scale'] * (a - b) for a, b in zip(values, reference_values)]
            scores = [value + (1. if action == baseline else 0.)
                      for action, value in enumerate(standardized)]
            winner, probabilities = distribution(scores, amounts, baseline)
            alternative = max(score for action, score in enumerate(scores) if action != winner)
            row['models'][name] = dict(raw=values, standardized=standardized, scores=scores,
                winner=winner, amount=amounts[winner], probabilities=probabilities,
                support=sum(value > 0 for value in probabilities),
                topProbability=max(probabilities), margin=scores[winner] - alternative,
                entropy=-math.fsum(p * math.log(p) for p in probabilities if p > 0))
        row['referenceRaw'] = result['4a'][index]
        rows.append(row)
    summary = dict(kind='token-plateau-diagnostic-results-1', complete=True,
        plan=dict(path=str(plan_path), sha256=digest(plan_raw)),
        modelStateEvaluations=evaluations, rawBatchCalls=calls, elapsedSeconds=time.time()-started,
        numpyVersion=projection.np.__version__, torchVersion=torch.__version__,
        torchThreads=torch.get_num_threads(), torchInteropThreads=torch.get_num_interop_threads(),
        torchRngUnchanged=True, states=len(rows), actions=sum(len(row['commands']) for row in rows),
        saturation=activations, models={}, comparisons={}, parameters={})
    for name in ('94', '96', '97'):
        decisions = [row['models'][name] for row in rows]
        summary['models'][name] = dict(
            winners=dict(Counter(decision['amount'] for decision in decisions)),
            baselineWinners=sum(row['models'][name]['winner'] == row['baselineIndex'] for row in rows),
            support=stats([decision['support'] for decision in decisions]),
            entropy=stats([decision['entropy'] for decision in decisions]),
            topProbability=stats([decision['topProbability'] for decision in decisions]),
            margin=stats([decision['margin'] for decision in decisions]),
            raw=stats([value for decision in decisions for value in decision['raw']]))
    for source, target in (('94', '96'), ('94', '97'), ('96', '97')):
        comparison = []
        for row in rows:
            left, right = row['models'][source], row['models'][target]
            delta = [a-b for a,b in zip(right['raw'], left['raw'])]
            common = math.fsum(delta)/len(delta)
            centered = [value-common for value in delta]
            p, q = left['probabilities'], right['probabilities']
            comparison.append(dict(rawCommonShift=common, rawMaxCentered=max(map(abs, centered)),
                rawCenteredRms=math.sqrt(math.fsum(x*x for x in centered)/len(centered)),
                scoreMaxCentered=plan['scale']*max(map(abs, centered)),
                totalVariation=.5*math.fsum(abs(a-b) for a,b in zip(p,q)),
                sameUniformDisagreement=same_uniform_disagreement(p,q),
                winnerChanged=left['winner'] != right['winner'],
                supportChanged=[i for i,v in enumerate(p) if v > 0] !=
                               [i for i,v in enumerate(q) if v > 0]))
        summary['comparisons'][source+'-'+target] = {
            key: sum(row[key] for row in comparison) if isinstance(comparison[0][key], bool)
            else stats([row[key] for row in comparison]) for key in comparison[0]}
        for block in ACTOR_BLOCKS:
            delta = [a-b for a,b in zip(models[target][block],models[source][block])]
            summary['parameters'][source+'-'+target+'.'+block] = dict(
                rms=math.sqrt(math.fsum(x*x for x in delta)/len(delta)), maximum=max(map(abs,delta)))
    for path, sha in plan['inputPins'].items():
        checked(dict(path=path, sha256=sha))
    write(output / 'decisions.json', rows)
    summary['decisions'] = subject(output / 'decisions.json')
    write(output / 'summary.json', summary)
    print(json.dumps(dict(summary=subject(output / 'summary.json'),
                          modelStateEvaluations=evaluations, rawBatchCalls=calls), sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('prepare', 'run'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--plan', type=Path)
    args = parser.parse_args()
    if args.mode == 'prepare':
        prepare(args.output.absolute())
    else:
        require(args.plan is not None, 'Inference requires the previously frozen plan.')
        run(args.plan.absolute(), args.output.absolute())


if __name__ == '__main__':
    main()
