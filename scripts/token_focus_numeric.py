"""One TOKEN-only antithetic update; immutable C/P/E and original selector references.

The numeric origin is the protocol's exact four-head bundle, independently of the
old subtraction reference. File subjects/model validation/factor materialization
reuse their existing owners. This adapter alone owns the64-active-coordinate step.
"""
import argparse
import json
import math
import os
from pathlib import Path
import platform
import sys

import numpy as np
import torch
import coordinated_es_math as es
import coordinated_es_projection as projection
import robust_league_numeric as wire

METHOD_SHA = '1a13821a64de5c1dfdf685f46128969c793a09a03667e6431d55e9c8fe467369'
RECIPE = dict(kind='token-focus-normalized-antithetic-es-1', dimensions=256,
    activeStart=192, activeDimensions=64, generations=1, directions=16, mutants=32,
    worlds=16, sigma=1., stepRmsActive64=1., minimumGradientRms=1e-12,
    objective='WIN_SHARE_ONLY', valueUpdates=False, ranking=False,
    eliteSelection=False, mutantProjection=False, dtype='float64', threads=1,
    candidate='SOLE_FINAL_MEAN_1', projectionRecipe=projection.RECIPE)
KINDS = dict(prepare='token-focus-step-preparation-1',
    population='token-focus-step-population-receipt-1', update='token-focus-step-update-1',
    final='token-focus-step-final-mean-1')
require, exact = es.require, es.exact


def mac_owner():
    require(platform.system() == 'Darwin' and platform.machine() == 'arm64',
        'The declared numerical owner is the local arm64 Mac.')
    for name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'VECLIB_MAXIMUM_THREADS'):
        require(os.environ.get(name) == '1', 'The numeric owner requires ' + name + '=1.')
    require(np.__version__ == '2.5.4' and torch.__version__.split('+')[0] == '2.14.0',
        'The fixed Mac NumPy2.5.4/Torch2.14.0 numerical runtime must remain unchanged.')
    torch.set_num_threads(1)
    if torch.get_num_interop_threads() != 1:
        torch.set_num_interop_threads(1)
    require(torch.get_num_threads() == torch.get_num_interop_threads() == 1,
        'Numerical materialization must stay single threaded, including Torch inter-op work.')


class Output(wire.Output):
    def finish(self, mode, record, inputs):
        inputs.unchanged()
        modules = (sys.modules[__name__], wire, es, projection, projection.b,
            projection.card_policy, projection.expansion_mc, projection.token_paired_policy)
        sources = {Path(module.__file__).name: wire.sha(Path(module.__file__).read_bytes())
            for module in modules}
        files = {path.relative_to(self.path).as_posix(): wire.sha(path.read_bytes())
            for path in sorted(self.path.rglob('*')) if path.is_file()}
        return self.json('receipt.json', dict(kind=KINDS[mode], complete=True, recipe=RECIPE,
            inputPins=inputs.pins, sourceFiles=sources, files=files,
            environment=dict(platform=platform.platform(), numpy=np.__version__,
                torch=torch.__version__, python=platform.python_version(), numericOwner='MAC',
                dtype='float64', threads=1), **record))


def read_receipt(inputs, item, mode):
    value = inputs.json(item)
    require(value['kind'] == KINDS[mode] and value['complete'] is True and value['recipe'] == RECIPE,
        'The complete numerical predecessor must belong to this one-step TOKEN recipe.')
    owner = Path(item['path']).absolute()
    for path, digest in value['inputPins'].items():
        inputs.raw(dict(path=path, sha256=digest))
    for name, digest in value['files'].items():
        inputs.raw(inputs.portable(owner, dict(path=name, sha256=digest)))
    return value, owner


def coordinates(values, *, initial=False):
    result = es.vector(values, 256, 'token-center')
    require(all(value == 0.0 for value in result[:192]),
        'All192 CARD/PURCHASE/EXPANSION coordinates must remain exactly zero.')
    require(all(value == 0.0 for value in result) if initial else
        abs(es.rms(result[192:]) - 1.0) <= 2e-14,
        'The sole mean is zero initially and exactly active64-RMS1 after one update.')
    return result


def center_record(scope, generation, values):
    return dict(kind='token-focus-step-center-1', scopeSha256=scope,
        generation=generation, coordinates=list(values))


def read_center(inputs, item, scope, generation):
    value = inputs.json(item)
    exact(value, ('kind', 'scopeSha256', 'generation', 'coordinates'), 'TOKEN center')
    require(value['kind'] == 'token-focus-step-center-1' and value['scopeSha256'] == scope and
        type(value['generation']) is int and value['generation'] == generation,
        'Only the exact same-scope initial or sole final mean is admitted.')
    coordinates(value['coordinates'], initial=generation == 0)
    return value


def prepared(inputs, item):
    value, owner = read_receipt(inputs, item, 'prepare')
    origin = inputs.portable(owner, value['originBundle'])
    actors = wire.load_bundle(inputs, origin)
    factor = projection.Projection(inputs.raw(inputs.portable(owner, value['projection'])))
    require(origin['sha256'] == value['numericalOriginBundleSha256'] == factor.record['originalBundleSha256'] and
        factor.record['scopeSha256'] == value['scopeSha256'],
        'The factor map must use the exact protocol-owned numerical origin, never the selector reference.')
    schedule = inputs.json(inputs.portable(owner, value['seedSchedule']))
    protocol = inputs.json(inputs.portable(owner, value['protocol']))
    asset = inputs.json(value['inferenceAsset'])
    require(protocol['scheduleSha256'] == value['seedSchedule']['sha256'] and
        protocol['reservedSchedule'] == schedule and
        factor.record['projectionSeed'] == schedule['projectionSeed'],
        'The retained factors and complete schedule must match their original protocol seed declarations.')
    require(value['scopeSha256'] == scope_identity(value['protocol']['sha256'],
        value['seedSchedule']['sha256'], origin['sha256'], value['inferenceAsset']['sha256'], asset),
        'The complete protocol, full schedule, numerical origin and selector foundation must retain their scope.')
    table = es.DirectionTable.from_record(inputs.json(inputs.portable(owner, value['directions'])))
    require(table.scope == factor.scope and table.generation == 1 and
        all(all(number == 0 for number in row[:192]) for row in table.vectors),
        'Only the original16 TOKEN-only Gaussian directions may materialize or update.')
    return value, owner, actors, factor, schedule, table


def immutable_heads(actors, current):
    wire.unchanged_actor_metadata(actors, current)
    require(all(current[head] == actors[head].raw for head in es.HEADS if head is not es.Head.TOKEN),
        'CARD/PURCHASE/EXPANSION must retain their exact original raw bytes.')


def scope_identity(protocol_sha, schedule_sha, origin_sha, asset_sha, asset):
    return es.digest(dict(kind='token-focus-step-scope-1', protocolSha256=protocol_sha,
        seedScheduleSha256=schedule_sha, originBundleSha256=origin_sha, inferenceAssetSha256=asset_sha,
        selectorOriginalBundleSha256=asset['originalBundle']['sha256'],
        selectorCalibrationSha256=asset['calibration']['sha256'], recipe=RECIPE))


def generate_directions(scope, seed):
    rng = np.random.Generator(np.random.PCG64(projection.seed(seed)))
    active = rng.standard_normal((16, 64), dtype=np.float64)
    rows = tuple((0.0,) * 192 + tuple(row) for row in active.tolist())
    table = es.DirectionTable(scope, 1, rows)
    return table, dict(kind='token-focus-step-direction-generation-1', seed=seed,
        generator='numpy-PCG64', distribution='independent-standard-normal', drawShape=[16, 64],
        expandedShape=[16, 256], zeroPrefix=192, renormalized=False,
        numpyVersion=np.__version__, directionsSha256=es.digest(table.record))


def prepare(inputs, request, out):
    exact(request, ('kind', 'protocol', 'seedSchedule', 'originBundle', 'inferenceAsset'), 'TOKEN preparation')
    protocol = inputs.json(request['protocol'])
    asset = inputs.json(request['inferenceAsset'])
    require(protocol['kind'] == 'token-focus-step-campaign-1' and protocol['study'] == 96 and
        protocol['numericRecipe'] == RECIPE and protocol['methodSha256'] == METHOD_SHA and
        protocol['originWeightsSha256'] == request['originBundle']['sha256'] and
        protocol['inferenceAssetSha256'] == request['inferenceAsset']['sha256'] and
        asset['kind'] == 'coordinated-inference-asset-1',
        'The exact one-step protocol must bind its numerical origin and unchanged inference foundation.')
    actors = wire.load_bundle(inputs, request['originBundle'])
    schedule = inputs.json(request['seedSchedule'])
    require(protocol['seedSchedule'] == request['seedSchedule'] and
        protocol['scheduleSha256'] == request['seedSchedule']['sha256'] and
        protocol['reservedSchedule'] == schedule,
        'The complete numerical schedule must be the exact subject and body frozen in the protocol.')
    require(schedule['kind'] == 'token-focus-step-schedule-1' and
        projection.seed(schedule['projectionSeed']) != projection.seed(schedule['directionSeed']) and
        set(schedule['banks']) == {'training', 'holdout'} and
        all(schedule['banks'][name]['count'] == len(schedule['banks'][name]['seeds']) == size
            for name, size in (('training', 16), ('holdout', 128))),
        'The full fresh144-world schedule and two distinct positive numerical seeds must stay fixed.')
    worlds = schedule['banks']['training']['seeds'] + schedule['banks']['holdout']['seeds']
    require(all(type(seed) is int and -(2**31) <= seed < 2**31 for seed in worlds) and
        len(set(worlds)) == 144, 'The complete native world reservation must contain144 distinct Int32 seeds.')
    scope = scope_identity(request['protocol']['sha256'], request['seedSchedule']['sha256'],
        request['originBundle']['sha256'], request['inferenceAsset']['sha256'], asset)
    factor = projection.generate(actors, scope, request['originBundle']['sha256'], schedule['projectionSeed'])
    table, draws = generate_directions(factor.scope, schedule['directionSeed'])
    zero = projection.materialize(factor, actors, [0.] * 256)
    require(zero == {head: actor.raw for head, actor in actors.items()},
        'The exact numerical zero must preserve every origin head byte.')
    origin_record = inputs.json(request['originBundle'])
    origin = out.raw('origin/bundle.json', inputs.raw(request['originBundle']))
    for head in es.HEADS:
        out.raw('origin/' + origin_record['heads'][head.value]['path'], zero[head])
    return out.finish('prepare', dict(scopeSha256=scope,
        numericalOriginBundleSha256=request['originBundle']['sha256'],
        inferenceAsset=request['inferenceAsset'],
        inferenceAssetSha256=request['inferenceAsset']['sha256'],
        selectorOriginalBundleSha256=asset['originalBundle']['sha256'],
        selectorCalibrationSha256=asset['calibration']['sha256'], originBundle=origin,
        protocol=out.raw('protocol.json', inputs.raw(request['protocol'])),
        seedSchedule=out.raw('schedule.json', inputs.raw(request['seedSchedule'])),
        projection=out.raw('projection.json', factor.wire),
        directions=out.json('directions.json', table.record),
        directionDraw=out.json('direction-draw.json', draws),
        center=out.json('center.json', center_record(scope, 0, [0.] * 256))), inputs)


def population(inputs, request, out):
    exact(request, ('kind', 'preparation', 'center'), 'TOKEN population')
    prep, owner, actors, factor, schedule, table = prepared(inputs, request['preparation'])
    center = read_center(inputs, request['center'], prep['scopeSha256'], 0)
    require(inputs.raw(request['center']) == inputs.raw(inputs.portable(owner, prep['center'])),
        'Only the original zero center may generate the single population.')
    members = []
    shared = {}
    for head in es.HEADS[:-1]:
        item = out.raw('members/shared/' + head.value + '/policy.json', actors[head].raw)
        shared[head.value] = dict(path=item['path'].removeprefix('members/'),
            sha256=item['sha256'], dictionarySha256=projection.dictionary_sha(actors[head].model))
    for index, row in enumerate(es.mutant_coordinates(center['coordinates'], table.vectors)):
        current = projection.materialize(factor, actors, row['coordinates'])
        immutable_heads(actors, current)
        name = f'{"PLUS" if row["sign"] == 1 else "MINUS"}_{row["direction"]:02d}'
        token = out.raw('members/tokens/' + name + '/policy.json', current[es.Head.TOKEN])
        heads = dict(shared, TOKEN=dict(path=token['path'].removeprefix('members/'),
            sha256=token['sha256'], dictionarySha256=projection.dictionary_sha(actors[es.Head.TOKEN].model)))
        bundle = out.json('members/' + name + '-bundle.json', dict(kind='coordinated-es-bundle-1', heads=heads))
        members.append(dict(index=index, id=name, direction=row['direction'], sign=row['sign'],
            bundle=bundle,
            coordinatesSha256=es.digest(row['coordinates'])))
    bank = schedule['banks']['training']
    value = dict(kind='token-focus-step-population-1', complete=True, scopeSha256=prep['scopeSha256'],
        generationBankSha256=es.digest(bank), worldSeeds=bank['seeds'],
        directionsSha256=prep['directions']['sha256'], centerSha256=request['center']['sha256'], members=members)
    return out.finish('population', dict(scopeSha256=prep['scopeSha256'],
        population=out.json('population.json', value), preparation=request['preparation'],
        center=request['center']), inputs)


def active_step(vectors, differences):
    rows = [es.vector(row, 256, 'token-direction') for row in vectors]
    require(len(rows) == 16 and all(all(number == 0 for number in row[:192]) for row in rows),
        'Exactly16 directions may move only the final64 TOKEN coordinates.')
    values = es.vector(differences, 16, 'paired-win-difference')
    try:
        gradient = [es.finite(math.fsum(es.finite(value * row[index], 'gradient-product')
            for value, row in zip(values, rows)) / 32, 'gradient') for index in range(192, 256)]
    except (OverflowError, ValueError) as error:
        if isinstance(error, es.OperationalStop):
            raise
        raise es.OperationalStop('nonfinite-gradient') from error
    norm = es.rms(gradient)
    if not math.isfinite(norm):
        raise es.OperationalStop('nonfinite-gradient-rms')
    if norm <= 1e-12:
        raise es.OperationalStop('zero-gradient')
    result = [0.] * 192 + [es.finite(value / norm, 'normalized-token-coordinate') for value in gradient]
    coordinates(result)
    return dict(gradient=gradient, gradientRmsActive64=norm, coordinates=result,
        activeCenterRms=es.rms(result[192:]), fullCenterRms=es.rms(result))


def update_values(inputs, preparation, population, outcomes_item):
    prep, _, _, _, schedule, table = prepared(inputs, preparation)
    pop_receipt, pop_owner = read_receipt(inputs, population, 'population')
    require(pop_receipt['preparation'] == preparation and
        pop_receipt['scopeSha256'] == prep['scopeSha256'], 'The population must retain this exact preparation.')
    pop_item = inputs.portable(pop_owner, pop_receipt['population'])
    pop = inputs.json(pop_item)
    outcomes = inputs.json(outcomes_item)
    exact(outcomes, ('kind', 'complete', 'verified', 'populationSha256', 'generationBankSha256',
        'worldSeeds', 'members'), 'TOKEN outcomes')
    require(outcomes['kind'] == 'token-focus-step-outcomes-1' and outcomes['complete'] is True and
        outcomes['verified'] is True and outcomes['populationSha256'] == pop_item['sha256'] and
        pop['scopeSha256'] == prep['scopeSha256'] and
        outcomes['generationBankSha256'] == pop['generationBankSha256'] == es.digest(schedule['banks']['training']) and
        outcomes['worldSeeds'] == pop['worldSeeds'] == schedule['banks']['training']['seeds'] and
        len(pop['members']) == len(outcomes['members']) == 32,
        'Only the original complete32x16 ordered terminal candidate win shares may update.')
    means = []
    for index, (member, result) in enumerate(zip(pop['members'], outcomes['members'])):
        exact(result, ('index', 'direction', 'sign', 'bundleSha256', 'winShares'), 'TOKEN fitness member')
        require(member['index'] == result['index'] == index and
            member['direction'] == result['direction'] == index // 2 and
            member['sign'] == result['sign'] == (1 if index % 2 == 0 else -1) and
            member['bundle']['sha256'] == result['bundleSha256'],
            'No signed member, direction or bundle may be substituted or reordered.')
        wins = es.vector(result['winShares'], 16, 'win-share')
        require(all(0 <= value <= 1 for value in wins), 'Every terminal win share must lie in[0,1].')
        means.append(math.fsum(wins) / 16)
    differences = [means[2 * index] - means[2 * index + 1] for index in range(16)]
    step = active_step(table.vectors, differences)
    return prep, means, differences, step


def update(inputs, request, out):
    exact(request, ('kind', 'preparation', 'population', 'outcomes'), 'TOKEN update')
    prep, means, differences, step = update_values(inputs, request['preparation'],
        request['population'], request['outcomes'])
    center = out.json('center.json', center_record(prep['scopeSha256'], 1, step['coordinates']))
    return out.finish('update', dict(scopeSha256=prep['scopeSha256'], preparation=request['preparation'],
        population=request['population'], outcomes=request['outcomes'], center=center,
        memberWinMeans=means, antitheticWinDifferences=differences, step=step, fitness='WIN_SHARE_ONLY'), inputs)


def final(inputs, request, out):
    exact(request, ('kind', 'preparation', 'update'), 'TOKEN final mean')
    prep, _, actors, factor, _, _ = prepared(inputs, request['preparation'])
    updated, owner = read_receipt(inputs, request['update'], 'update')
    require(updated['preparation'] == request['preparation'] and updated['scopeSha256'] == prep['scopeSha256'],
        'The sole final mean must descend from this exact completed update and preparation.')
    # Recompute arithmetic only: no RNG, projection generation, game or neural forward.
    _, means, differences, step = update_values(inputs, request['preparation'],
        updated['population'], updated['outcomes'])
    require(updated['memberWinMeans'] == means and updated['antitheticWinDifferences'] == differences and
        updated['step'] == step, 'The completed update must equal its sole verified win-only arithmetic result.')
    center_item = inputs.portable(owner, updated['center'])
    center = read_center(inputs, center_item, prep['scopeSha256'], 1)
    require(center['coordinates'] == updated['step']['coordinates'],
        'The final center must be the exact sole arithmetic output, never an elite or alternate step.')
    current = projection.materialize(factor, actors, center['coordinates'])
    immutable_heads(actors, current)
    return out.finish('final', dict(scopeSha256=prep['scopeSha256'], update=request['update'],
        preparation=request['preparation'], center=center_item, bundle=out.bundle('', current),
        numericalOriginBundleSha256=prep['numericalOriginBundleSha256'],
        selectorOriginalBundleSha256=prep['selectorOriginalBundleSha256'],
        selectorCalibrationSha256=prep['selectorCalibrationSha256'], candidate='SOLE_FINAL_MEAN_1'), inputs)


OPERATIONS = dict(prepare=prepare, population=population, update=update, final=final)


def run(mode, request_path, output, owner_guard=mac_owner):
    require(mode in OPERATIONS, 'Use an explicit TOKEN numerical operation.')
    owner_guard()
    inputs = wire.Inputs()
    path = Path(request_path).absolute()
    request = inputs.json(dict(path=str(path), sha256=wire.sha(path.read_bytes())))
    require(request.get('kind') == 'token-focus-step-numeric-' + mode + '-1',
        'The explicit numerical request kind must match its operation.')
    return OPERATIONS[mode](inputs, request, Output(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=tuple(OPERATIONS), required=True)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.mode, args.request, args.output), sort_keys=True))
