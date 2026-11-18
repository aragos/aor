"""Build a new portable90 payload only after the root comparator freeze; never launch it."""
import argparse
import json
from pathlib import Path, PurePosixPath
import shutil
import time

import leader_denial_contract as policy
import robust_token_campaign as c


def checked(subject):
    c.require(type(subject) is dict and set(subject) == {'path', 'sha256'},
        'Every preparation subject requires exactly path and raw sha256.')
    path = Path(subject['path']).resolve()
    c.require(path.is_file() and c.sha(path) == subject['sha256'], 'An approved preparation input changed.')
    return path


def portable(owner, item):
    name = PurePosixPath(item['path'])
    c.require(name.parts and not name.is_absolute() and name.as_posix() == item['path'] and
        all(part not in ('.', '..') for part in name.parts) and '\\' not in item['path'],
        'Portable model subjects must remain normalized and confined to their descriptor.')
    path = owner.parent/name
    c.require(not path.is_symlink() and path.resolve().is_relative_to(owner.parent.resolve()) and
        c.sha(path) == item['sha256'], 'A declared portable model subject is absent or changed.')
    return path


def protocol(method, reservation, seed_review, freeze, freeze_sha, arena_sha, *, now=time.time):
    c.require(c.sha(method) == policy.METHOD_SHA and c.sha(reservation) == policy.RESERVATION_SHA and
        c.sha(seed_review) == policy.SEED_REVIEW_SHA,
        'Only the exact pre-outcome method and already-reviewed complete RNG reservation may prepare90.')
    method, reservation, seed_review = c.read(method), c.read(reservation), c.read(seed_review)
    c.require(seed_review['passed'] is True and seed_review['rngDeclarationsApproved'] is True and
        seed_review['scheduleSha256'] == policy.RESERVATION_SHA and seed_review['freshWorlds'] == 4608,
        'The existing root RNG review must approve the original full4608-world reservation.')
    frozen_epoch = now(); data_mode = policy.data_mode(frozen_epoch)
    c.require(data_mode is not None, 'Insufficient original wall time for90; defer without preparing or launching science.')
    mode = policy.DATA_MODES[data_mode]
    plan = dict(kind=policy.KIND, methodSha256=policy.METHOD_SHA,
        seedReviewSha256=policy.SEED_REVIEW_SHA, seedReservationSha256=policy.RESERVATION_SHA,
        reservedSchedule=reservation, unusedReservedWorlds=mode['unusedReservedWorlds'],
        dataMode=data_mode, modeFrozenEpoch=frozen_epoch,
        maximumScienceSeconds=7200, auditSeconds=600, maximumTerminalExecutions=mode['maximumTerminalExecutions'],
        chunkSize=64, minimumDevelopmentDelta=0.0, confidenceMultiplier=c.MULTIPLIER,
        globalDeadlineEpoch=policy.GLOBAL_DEADLINE, caseConfigs=method['caseConfigs'],
        banks={name:dict(seeds=reservation['banks'][name]['seeds'][:count], replayIndices=replays,
            opponentStyle='QUALIFIED' if name == 'homogeneous' else 'BALANCED')
            for name, (count, replays) in mode['banks'].items()}, resources=method['resources'],
        comparator={**{key:freeze[key] for key in policy.COMPARATOR_KEYS}, 'rootFreezeSha256':freeze_sha},
        nativeJarSha256=policy.NATIVE_SHA, inferenceAssetSha256=policy.ASSET_SHA,
        nominationSha256=policy.NOMINATION_SHA, arenaSha256=arena_sha,
        automaticRetry=False, automaticSuccessor=False, activationAuthorized=False)
    policy.validate(plan); policy.validate_freeze(freeze, plan)
    return plan


def prepare(request_path, output):
    request_path = Path(request_path).resolve(); request = c.read(request_path)
    c.require(request.get('kind') == 'leader-denial-payload-request-1',
        'Use the explicit reviewed-source and frozen-comparator payload request.')
    subjects = {key:checked(request[key]) for key in ('method', 'reservation', 'seedReview',
        'comparatorFreeze', 'controlBundle', 'nativeReadiness', 'sourceReadiness')}
    native, source = c.read(subjects['nativeReadiness']), c.read(subjects['sourceReadiness'])
    c.require(native['passed'] is True and source['passed'] is True and source['sourceOnly'] is True,
        'Only the passing reviewed native and finite-runner source closures may be packaged.')
    for owner in (native['runtimeFiles'], source['sourceFiles']):
        c.require(all(c.sha(path) == digest for path, digest in owner.items()),
            'Reviewed executable source/runtime bytes changed before packaging.')
    base = Path(request['baseInputs']).resolve(); output = Path(output).resolve()
    c.require(not output.exists(), 'An experiment payload is new-only and cannot replace an earlier attempt.')
    freeze = c.read(subjects['comparatorFreeze'])
    c.require(c.sha(subjects['controlBundle']) == freeze['bundleSha256'],
        'The prepared comparator must be the exact root-frozen descriptor, without rewriting it.')
    copies = {}

    def add(path, destination):
        path = Path(path); name = str(destination)
        c.require(name not in copies or copies[name] == path, 'Two declared inputs cannot overwrite one payload member.')
        copies[name] = path

    def bundle(owner, prefix):
        record = c.read(owner)
        c.require(record['kind'] == 'coordinated-es-bundle-1' and
            set(record['heads']) == {'CARD', 'PURCHASE', 'EXPANSION', 'TOKEN'},
            'Only a complete immutable four-head descriptor may enter the payload.')
        add(owner, Path(prefix)/'bundle.json')
        for item in record['heads'].values(): add(portable(owner, item), Path(prefix)/item['path'])

    asset = base/'inference/inference-asset.json'; nomination = base/'nomination/nomination.json'
    c.require(c.sha(asset) == policy.ASSET_SHA and c.sha(nomination) == policy.NOMINATION_SHA,
        'The numerical subtraction anchors and eligibility foundation must retain the exact87 subjects.')
    add(asset, 'inputs/inference/inference-asset.json'); add(nomination, 'inputs/nomination/nomination.json')
    value = c.read(asset)
    for key in ('currentBundle', 'originalBundle'):
        owner = portable(asset, value[key]); prefix = Path('inputs/inference')/Path(value[key]['path']).parent
        c.require(owner.name == 'bundle.json', 'The existing portable inference bundle name must remain unchanged.')
        bundle(owner, prefix)
    for item in [value['calibration'], *value['references'].values()]:
        add(portable(asset, item), Path('inputs/inference')/item['path'])
    for key in ('completionAudit', 'developmentReport'):
        item = c.read(nomination)[key]; add(portable(nomination, item), Path('inputs/nomination')/item['path'])
    arena = base/'homogeneous-arena.json'; add(arena, 'inputs/homogeneous-arena.json')
    bundle(subjects['controlBundle'], 'inputs/control')
    plan = protocol(subjects['method'], subjects['reservation'], subjects['seedReview'], freeze,
        request['comparatorFreeze']['sha256'], c.sha(arena))
    runtime = Path(native['runtimeRoot'])
    c.require(c.sha(runtime/'lib/training.jar') == policy.NATIVE_SHA and
        {str(path) for path in runtime.rglob('*') if path.is_file()} == set(native['runtimeFiles']),
        'The reviewed native runtime must remain complete and contain no unreviewed payload members.')
    size = sum(path.stat().st_size for path in copies.values()) + sum(path.stat().st_size
        for path in runtime.rglob('*') if path.is_file())
    c.require(not output.is_relative_to(base) and not output.is_relative_to(runtime),
        'A new payload cannot modify its immutable input/runtime directory.')
    output.parent.mkdir(parents=True, exist_ok=True)
    c.require(shutil.disk_usage(output.parent).free >= 16*1024**3 + size,
        'Payload construction must retain at least16GiB free after all copied subjects.')
    output.mkdir(parents=True)
    for destination, path in copies.items():
        target = output/destination; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target); c.require(c.sha(target) == c.sha(path), 'An immutable copied model changed.')
    shutil.copytree(runtime, output/'runtime')
    for path in source['sourceFiles']:
        path = Path(path)
        if path.suffix == '.py' and not path.name.startswith('test_'):
            shutil.copyfile(path, output/'runtime/bin'/path.name)
    for key, filename in (('comparatorFreeze','comparator-freeze.json'), ('method','prospective-method.json'),
        ('reservation','reserved-schedule.json'), ('seedReview','root-seed-review.json')):
        shutil.copyfile(subjects[key], output/filename)
    c.write(output/'protocol.json', plan)
    c.write(output/'payload-manifest.json', dict(kind='leader-denial-payload-manifest-1',
        files={str(path.relative_to(output)):c.sha(path) for path in sorted(output.rglob('*')) if path.is_file()}))
    receipt = dict(kind='leader-denial-payload-prepared-1', passed=True,
        protocolSha256=c.sha(output/'protocol.json'), comparatorFreezeSha256=c.sha(output/'comparator-freeze.json'),
        manifestSha256=c.sha(output/'payload-manifest.json'), files=len(c.read(output/'payload-manifest.json')['files']),
        sourceCommit=source['sourceCommit'], scientificGames=0, modelForwards=0,
        clockCreated=False, nativePrepared=False, launchAuthorized=False)
    c.write(output/'preparation-receipt.json', receipt)
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.request, args.output), sort_keys=True))
