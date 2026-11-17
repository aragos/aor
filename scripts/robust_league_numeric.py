"""Fresh six-generation ES around a qualified four-head origin; Desktop Float64 only.

The numerical origin is distinct from the frozen selector subtraction reference. This adapter
reuses the old factor map and ascent arithmetic only, never its old experiment admission rules.
The runner owns verification of native outcomes before supplying the explicit win-share matrix.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import platform
import sys

import numpy as np
import torch
import coordinated_es_math as es
import coordinated_es_projection as projection


GENERATIONS = 6
MAXIMUM_TERMINAL_EXECUTIONS = 36920
RECIPE = dict(kind='robust-league-normalized-antithetic-es-2', dimensions=256,
    coordinatesPerHead=64, generations=GENERATIONS, directions=16, mutants=32, worlds=128,
    sigma=1., stepRms=1., centerRmsLimit=4., minimumGradientRms=1e-12,
    objective='WIN_SHARE_ONLY', candidate='FINAL_MEAN_6_ONLY', mutantProjection=False,
    valueUpdates=False, ranking=False, eliteSelection=False, dtype='float64', threads=1,
    projectionRecipe=projection.RECIPE)
ACTOR_BLOCKS = ('stateWeights', 'stateBias', 'actionWeights', 'actionBias', 'actorWeights')
HEADS = tuple(head.value for head in es.HEADS)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def exact(value, keys, message):
    require(type(value) is dict and set(value) == set(keys), message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def desktop_owner():
    require(platform.system() == 'Linux' and platform.machine() in ('x86_64', 'AMD64'),
        'Scientific factor draws and materialization execute only on the authoritative Desktop.')
    for name in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS',
            'VECLIB_MAXIMUM_THREADS'):
        require(os.environ.get(name) == '1', 'The numeric owner requires ' + name + '=1.')
    torch.set_num_threads(1)
    require(torch.get_num_threads() == 1, 'The numerical owner must remain single threaded.')


def confined(owner, name):
    require(isinstance(name, str), 'Portable file names must be strings.')
    parts = PurePosixPath(name)
    require(parts.parts and not parts.is_absolute() and parts.as_posix() == name and
        all(part not in ('.', '..') for part in parts.parts) and '\\' not in name,
        'Portable subjects must be normalized inside their owner directory.')
    current = owner.parent
    for part in parts.parts:
        current = current / part
        require(not current.is_symlink(), 'Portable subjects cannot traverse symlinks.')
    return current


class Inputs:
    def __init__(self):
        self.pins = {}

    def raw(self, item):
        exact(item, ('path', 'sha256'), 'Inputs require an exact raw path and SHA256.')
        path = Path(item['path']).absolute()
        require(path.is_file() and not path.is_symlink(), 'An input must be an ordinary retained file.')
        raw = path.read_bytes()
        require(sha(raw) == item['sha256'] and self.pins.get(str(path), item['sha256']) == item['sha256'],
            'A pinned numerical input changed.')
        self.pins[str(path)] = item['sha256']
        return raw

    def json(self, item):
        return json.loads(self.raw(item))

    def portable(self, owner, item):
        exact(item, ('path', 'sha256'), 'Portable subjects require exact path and SHA256.')
        return dict(path=str(confined(owner, item['path'])), sha256=item['sha256'])

    def unchanged(self):
        for path, digest in tuple(self.pins.items()):
            self.raw(dict(path=path, sha256=digest))


class Output:
    def __init__(self, path):
        self.path = Path(path).absolute()
        self.path.mkdir(parents=True, exist_ok=False)

    def raw(self, name, raw):
        path = confined(self.path / 'owner.json', name)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as target:
            target.write(raw)
            target.flush()
            os.fsync(target.fileno())
        return dict(path=name, sha256=sha(raw))

    def json(self, name, value):
        return self.raw(name, es.canonical(value))

    def bundle(self, prefix, actors):
        heads = {}
        for head in es.HEADS:
            item = self.raw(prefix + 'heads/' + head.value + '/policy.json', actors[head])
            heads[head.value] = dict(path='heads/' + head.value + '/policy.json',
                sha256=item['sha256'], dictionarySha256=projection.dictionary_sha(json.loads(actors[head])))
        return self.json(prefix + 'bundle.json', dict(kind='coordinated-es-bundle-1', heads=heads))

    def finish(self, kind, record, inputs):
        inputs.unchanged()
        sources = {Path(module.__file__).name: sha(Path(module.__file__).read_bytes())
            for module in (sys.modules[__name__], es, projection, projection.b,
                projection.card_policy, projection.expansion_mc, projection.token_paired_policy)}
        files = {path.relative_to(self.path).as_posix(): sha(path.read_bytes())
            for path in sorted(self.path.rglob('*')) if path.is_file()}
        return self.json('receipt.json', dict(kind=kind, complete=True, recipe=RECIPE,
            inputPins=inputs.pins, sourceFiles=sources, files=files,
            environment=dict(platform=platform.platform(), numpy=np.__version__,
                python=platform.python_version(), numericOwner='DESKTOP'), **record))


def load_bundle(inputs, item):
    value = inputs.json(item)
    exact(value, ('kind', 'heads'), 'A numerical origin requires a complete four-head bundle.')
    require(value['kind'] == 'coordinated-es-bundle-1' and set(value['heads']) == set(HEADS),
        'Exactly all four typed actor heads are required.')
    owner = Path(item['path']).absolute()
    actors = {}
    for head in es.HEADS:
        row = value['heads'][head.value]
        exact(row, ('path', 'sha256', 'dictionarySha256'), 'Each head requires its complete identity.')
        raw = inputs.raw(inputs.portable(owner, {key: row[key] for key in ('path', 'sha256')}))
        actor = projection.Original(head, raw, row['sha256'])
        require(projection.dictionary_sha(actor.model) == row['dictionarySha256'],
            'The actor dictionary must match its pinned head identity.')
        actors[head] = actor
    return actors


def read_receipt(inputs, item, kind):
    value = inputs.json(item)
    require(value['kind'] == kind and value['complete'] is True and value['recipe'] == RECIPE,
        'The numerical predecessor must be complete and use this exact six-generation recipe.')
    owner = Path(item['path']).absolute()
    for name, digest in value['files'].items():
        inputs.raw(inputs.portable(owner, dict(path=name, sha256=digest)))
    return value, owner


def prepared(inputs, item):
    value, owner = read_receipt(inputs, item, 'robust-league-preparation-1')
    origin_item = inputs.portable(owner, value['originBundle'])
    actors = load_bundle(inputs, origin_item)
    factor = projection.Projection(inputs.raw(inputs.portable(owner, value['projection'])))
    require(factor.record['scopeSha256'] == value['scopeSha256'] and
        factor.record['originalBundleSha256'] == value['numericalOriginBundleSha256'] and
        origin_item['sha256'] == value['numericalOriginBundleSha256'],
        'The fresh factor map must use the exact qualified numerical origin.')
    schedule = inputs.json(inputs.portable(owner, value['seedSchedule']))
    require(value['activeGenerations'] == GENERATIONS and len(value['directions']) == GENERATIONS and
        value['completeSeedScheduleSha256'] == value['seedSchedule']['sha256'] and
        value['usedDirectionSeeds'] == schedule['directionSeeds'][:GENERATIONS] and
        value['heldDirectionSeeds'] == schedule['directionSeeds'][GENERATIONS:] and
        value['heldGenerationBanks'] == ['generation-7', 'generation-8'],
        'The complete reservation stays frozen while only six explicit direction streams may execute.')
    return value, owner, actors, factor, schedule


def read_center(inputs, item, scope, expected=None):
    center = inputs.json(item)
    exact(center, ('kind', 'scopeSha256', 'generation', 'coordinates'),
        'A robust center requires explicit scope, generation and coordinates.')
    require(center['kind'] == 'robust-league-center-1' and center['scopeSha256'] == scope and
        type(center['generation']) is int and 0 <= center['generation'] <= GENERATIONS and
        (expected is None or center['generation'] == expected),
        'The center must be the exact immediate same-scope predecessor.')
    coordinates = es.vector(center['coordinates'], 256, 'robust-center')
    require(es.rms(coordinates) <= 4. + 2e-15 and
        (center['generation'] != 0 or all(value == 0 for value in coordinates)),
        'Centers obey RMS4 and the initial center is exactly zero.')
    return center


def center_record(scope, generation, coordinates):
    return dict(kind='robust-league-center-1', scopeSha256=scope,
        generation=generation, coordinates=list(coordinates))


def unchanged_actor_metadata(actors, materialized):
    for head in es.HEADS:
        original = actors[head].model
        current = json.loads(materialized[head])
        require({key: value for key, value in current.items() if key not in ACTOR_BLOCKS} ==
            {key: value for key, value in original.items() if key not in ACTOR_BLOCKS},
            'Projection cannot change metadata, dictionaries, value arrays or prior probabilities.')


def prepare(inputs, request, out):
    exact(request, ('kind', 'protocol', 'seedSchedule', 'baseInferenceAsset'),
        'Preparation requires only its explicit protocol, schedule and qualified base asset.')
    protocol = inputs.json(request['protocol'])
    require(protocol['numericRecipe'] == RECIPE and
        protocol['baseInferenceAssetSha256'] == request['baseInferenceAsset']['sha256'],
        'The fixed protocol must own this numerical recipe and base inference asset.')
    asset = inputs.json(request['baseInferenceAsset'])
    require(asset['kind'] == 'coordinated-inference-asset-1', 'Use an explicit frozen inference asset.')
    asset_owner = Path(request['baseInferenceAsset']['path']).absolute()
    origin_item = inputs.portable(asset_owner, asset['currentBundle'])
    actors = load_bundle(inputs, origin_item)
    schedule = inputs.json(request['seedSchedule'])
    require(schedule['kind'] == 'robust-league-schedule-1' and
        type(schedule['directionSeeds']) is list and len(schedule['directionSeeds']) == 8 and
        len({schedule['projectionSeed'], *schedule['directionSeeds']}) == 9,
        'Exactly one projection and eight distinct prospectively reserved direction integers are required.')
    for generation in range(1, GENERATIONS + 1):
        bank = schedule['banks']['generation-' + str(generation)]
        require(bank['count'] == 128 and bank['seeds'] ==
            [-1000000000 + bank['nativeOffset'] + index * 8191 for index in range(128)],
            'Every training generation retains its128 original ordered native world declarations.')
    used_direction_seeds = schedule['directionSeeds'][:GENERATIONS]
    held_direction_seeds = schedule['directionSeeds'][GENERATIONS:]
    scope = es.digest(dict(kind='robust-league-origin-scope-2',
        protocolSha256=request['protocol']['sha256'], assetSha256=request['baseInferenceAsset']['sha256'],
        seedScheduleSha256=request['seedSchedule']['sha256'],
        numericalOriginBundleSha256=origin_item['sha256'],
        selectorOriginalBundleSha256=asset['originalBundle']['sha256'], recipe=RECIPE,
        projectionSeed=schedule['projectionSeed'], usedDirectionSeeds=used_direction_seeds))
    factor = projection.generate(actors, scope, origin_item['sha256'], schedule['projectionSeed'])
    factor_item = out.raw('projection.json', factor.wire)
    initial = out.json('center.json', center_record(scope, 0, [0.] * 256))
    directions = []
    for generation, seed in enumerate(used_direction_seeds, 1):
        table, generation_receipt = projection.generate_directions(factor.scope, generation, seed)
        directions.append(out.json('directions/generation-' + str(generation) + '.json', table.record))
        out.json('directions/generation-' + str(generation) + '-draw.json', generation_receipt)
    zero = projection.materialize(factor, actors, [0.] * 256)
    require(zero == {head: source.raw for head, source in actors.items()},
        'The numerical zero must preserve all four exact qualified head bytes.')
    origin_record = inputs.json(origin_item)
    zero_item = out.raw('origin/bundle.json', inputs.raw(origin_item))
    for head in es.HEADS:
        out.raw('origin/' + origin_record['heads'][head.value]['path'], zero[head])
    protocol_item = out.raw('protocol.json', inputs.raw(request['protocol']))
    schedule_item = out.raw('schedule.json', inputs.raw(request['seedSchedule']))
    return out.finish('robust-league-preparation-1', dict(scopeSha256=scope,
        baseInferenceAssetSha256=request['baseInferenceAsset']['sha256'],
        numericalOriginBundleSha256=origin_item['sha256'],
        selectorOriginalBundleSha256=asset['originalBundle']['sha256'],
        selectorCalibrationSha256=asset['calibration']['sha256'], projection=factor_item,
        center=initial, originBundle=zero_item, protocol=protocol_item, seedSchedule=schedule_item,
        directions=directions, activeGenerations=GENERATIONS,
        completeSeedScheduleSha256=request['seedSchedule']['sha256'],
        usedDirectionSeeds=used_direction_seeds, heldDirectionSeeds=held_direction_seeds,
        heldGenerationBanks=['generation-7', 'generation-8'],
        zeroHeadsCopiedByteExact=True), inputs)


def population(inputs, request, out):
    exact(request, ('kind', 'preparation', 'center', 'generation'),
        'Population materialization requires its exact preparation, center and generation.')
    generation = request['generation']
    require(type(generation) is int and 1 <= generation <= GENERATIONS,
        'Generations are exactly1 through6; reserved7 and8 remain held unused.')
    prep, owner, actors, factor, schedule = prepared(inputs, request['preparation'])
    center = read_center(inputs, request['center'], prep['scopeSha256'], generation - 1)
    direction_item = inputs.portable(owner, prep['directions'][generation - 1])
    table = es.DirectionTable.from_record(inputs.json(direction_item))
    require(table.scope == factor.scope and table.generation == generation,
        'The population must use this exact prospectively generated direction table.')
    members = []
    for index, row in enumerate(es.mutant_coordinates(center['coordinates'], table.vectors)):
        materialized = projection.materialize(factor, actors, row['coordinates'])
        unchanged_actor_metadata(actors, materialized)
        bundle = out.bundle('members/member-' + str(index).zfill(2) + '/', materialized)
        members.append(dict(index=index, direction=row['direction'], sign=row['sign'], bundle=bundle,
            coordinatesSha256=es.digest(row['coordinates']),
            dictionarySha256={head.value: projection.dictionary_sha(actors[head].model) for head in es.HEADS}))
    bank = schedule['banks']['generation-' + str(generation)]
    record = dict(kind='robust-league-population-1', complete=True, scopeSha256=prep['scopeSha256'],
        generation=generation, centerSha256=request['center']['sha256'],
        directionsSha256=direction_item['sha256'], generationBankSha256=es.digest(bank),
        worldSeeds=bank['seeds'], numericalOriginBundleSha256=prep['numericalOriginBundleSha256'],
        members=members)
    population_item = out.json('population.json', record)
    out.raw('center.json', inputs.raw(request['center']))
    return out.finish('robust-league-population-receipt-1', dict(population=population_item,
        scopeSha256=prep['scopeSha256'], generation=generation, directions=direction_item,
        center=request['center'], preparation=request['preparation']), inputs)


def update(inputs, request, out):
    exact(request, ('kind', 'preparation', 'population', 'outcomes'),
        'A numerical update requires the exact complete population and verified native win matrix.')
    prep, owner, actors, factor, schedule = prepared(inputs, request['preparation'])
    pop_receipt, pop_owner = read_receipt(inputs, request['population'],
        'robust-league-population-receipt-1')
    require(pop_receipt['preparation'] == request['preparation'] and
        pop_receipt['scopeSha256'] == prep['scopeSha256'],
        'The population belongs to this exact preparation.')
    pop_item = inputs.portable(pop_owner, pop_receipt['population'])
    pop = inputs.json(pop_item)
    generation = pop['generation']
    require(type(generation) is int and 1 <= generation <= GENERATIONS,
        'Only the fixed six generations may update.')
    center = read_center(inputs, inputs.portable(pop_owner,
        dict(path='center.json', sha256=pop['centerSha256'])), prep['scopeSha256'], generation - 1)
    table = es.DirectionTable.from_record(inputs.json(inputs.portable(owner,
        prep['directions'][generation - 1])))
    require(table.scope == factor.scope and table.generation == generation and
        pop['directionsSha256'] == es.digest(table.record) and
        pop['generationBankSha256'] == es.digest(schedule['banks']['generation-' + str(generation)]),
        'The update must retain the original direction and independent-world bank identities.')
    outcomes = inputs.json(request['outcomes'])
    exact(outcomes, ('kind', 'complete', 'verified', 'generation', 'populationSha256',
        'generationBankSha256', 'worldSeeds', 'members'), 'Verified native outcomes require the exact explicit matrix.')
    require(outcomes['kind'] == 'robust-league-generation-outcomes-1' and
        outcomes['complete'] is True and outcomes['verified'] is True and
        outcomes['generation'] == generation and outcomes['populationSha256'] == pop_item['sha256'] and
        outcomes['generationBankSha256'] == pop['generationBankSha256'] and
        outcomes['worldSeeds'] == pop['worldSeeds'] and len(outcomes['members']) == 32,
        'All32 original members must retain the exact128 verified ordered self-seat terminal win shares.')
    means = []
    for index, (member, result) in enumerate(zip(pop['members'], outcomes['members'])):
        exact(result, ('index', 'direction', 'sign', 'bundleSha256', 'winShares'),
            'Each fitness row requires one complete original signed member and win-share vector.')
        require(member['index'] == result['index'] == index and
            member['direction'] == result['direction'] == index // 2 and
            member['sign'] == result['sign'] == (1 if index % 2 == 0 else -1) and
            member['bundle']['sha256'] == result['bundleSha256'],
            'Fitness rows cannot be reordered, substituted or rebound to a different member.')
        wins = es.vector(result['winShares'], 128, 'verified-win-share')
        require(all(0 <= number <= 1 for number in wins), 'Terminal win shares are in the unit interval.')
        means.append(math.fsum(wins) / 128)
    differences = [means[index * 2] - means[index * 2 + 1] for index in range(16)]
    step = es.step_coordinates(center['coordinates'], table.vectors, differences)
    next_center = out.json('center.json', center_record(prep['scopeSha256'], generation,
        step.pop('coordinates')))
    return out.finish('robust-league-update-1', dict(scopeSha256=prep['scopeSha256'],
        generation=generation, center=next_center, memberWinMeans=means,
        antitheticWinDifferences=differences, step=step, populationSha256=pop_item['sha256'],
        outcomesSha256=request['outcomes']['sha256'], fitness='WIN_SHARE_ONLY'), inputs)


def final(inputs, request, out):
    exact(request, ('kind', 'preparation', 'center'),
        'The sole final candidate requires the frozen preparation and exact mean6.')
    prep, owner, actors, factor, schedule = prepared(inputs, request['preparation'])
    center = read_center(inputs, request['center'], prep['scopeSha256'], GENERATIONS)
    materialized = projection.materialize(factor, actors, center['coordinates'])
    unchanged_actor_metadata(actors, materialized)
    bundle = out.bundle('', materialized)
    return out.finish('robust-league-final-mean-1', dict(scopeSha256=prep['scopeSha256'],
        generation=GENERATIONS, centerSha256=request['center']['sha256'], candidate='FINAL_MEAN_6_ONLY',
        bundle=bundle, numericalOriginBundleSha256=prep['numericalOriginBundleSha256'],
        selectorOriginalBundleSha256=prep['selectorOriginalBundleSha256'],
        selectorCalibrationSha256=prep['selectorCalibrationSha256']), inputs)


OPERATIONS = {'prepare': prepare, 'population': population, 'update': update, 'final': final}


def run(mode, request_path, output, owner_guard=desktop_owner):
    require(mode in OPERATIONS, 'Use an explicit supported numerical operation.')
    owner_guard()
    inputs = Inputs()
    request_path = Path(request_path).absolute()
    raw = request_path.read_bytes()
    request = inputs.json(dict(path=str(request_path), sha256=sha(raw)))
    require(request.get('kind') == 'robust-league-numeric-' + mode + '-1',
        'The requested numerical operation must match its explicit versioned request.')
    return OPERATIONS[mode](inputs, request, Output(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=tuple(OPERATIONS), required=True)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    arguments = parser.parse_args()
    print(json.dumps(run(arguments.mode, arguments.request, arguments.output), sort_keys=True))
