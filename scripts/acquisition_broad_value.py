"""Repeat the fixed acquisition diagnostic on all eight audited family56 chunks.

Compressed compact origins retain actor inputs and paired outcomes; full canonical
evidence remains in separately hashed archives. Global origin index, cohort and
archive provenance are bookkeeping only, never numeric model columns. This is a
development diagnostic, with five fits and no policy export or activation.
"""
import argparse
import gzip
import json
import os
from pathlib import Path
import platform
import signal
import sys
import time

import numpy as np

import acquisition_conditional_value as fixed


KIND = 'acquisition-broad-conditional-value-1'
HOSTS = {'desktop': [1, 2, 3, 4], 'mac': [5, 6, 7, 8]}
read, sha, write, require = fixed.read, fixed.sha, fixed.write, fixed.require


def validate_origin_identity(origin, chunk, local_index):
    global_index = (chunk - 1) * 256 + local_index
    cohort = 'INCUMBENT' if chunk % 2 else 'RIVAL_ACQUISITION'
    require(origin['chunk'] == chunk and origin['localIndex'] == local_index
            and origin['globalIndex'] == global_index and origin['cohort'] == cohort,
            'Each compact origin requires its exact global chunk/index/cohort identity.')
    require(origin['request']['index'] == local_index,
            'Native request index must retain its original local meaning.')
    require(len(origin['originalMemberSha256']) == 64,
            'Compact origins must retain their full original archive-member hash.')
    return global_index, cohort


def admit(directory, host):
    retention, verified = [read(directory / name) for name in
                           ('retention.json', 'local-verification.json')]
    require(retention['kind'] == 'acquisition-broad-retention-1' and retention['passed']
            and retention['status'] == 'completed' and retention['host'] == host
            and verified['passed'] and verified['status'] == 'completed'
            and verified['retentionSha256'] == sha(directory / 'retention.json')
            and verified['exactCompactOrigins'] == 1024 and verified['fullCanonicalDataRetained'],
            'Both host corpora require complete, locally verified lossless retention.')
    audit = directory / 'metadata/completion-audit.json'
    document = read(audit)
    require(sha(audit) == retention['completionAuditSha256'] == verified['completionAuditSha256']
            and document['passed'] and document['status'] == 'completed'
            and document['cleanup']['allOwnedProcessesExited'],
            'Both data host attempts must have completed and exited before fitting.')
    pins = {}
    for name, digest in retention['files'].items():
        path = directory / name
        require(path.resolve().is_relative_to(directory) and sha(path) == digest,
                'A retained archive, fixture or metadata file changed.')
        pins[str(path)] = digest
    for name in ('retention.json', 'local-verification.json'):
        path = directory / name
        pins[str(path)] = sha(path)
    fixtures = []
    for chunk in HOSTS[host]:
        path = directory / f'chunk-{chunk:02d}-origins.json.gz'
        require(str(path) in pins, 'Every compact chunk must be part of verified retention.')
        fixtures.append(dict(chunk=chunk, path=str(path), sha256=pins[str(path)]))
    return pins, fixtures


def prepare(desktop, mac, output):
    require(not output.exists(), 'Prepare the diagnostic in a fresh directory.')
    pins, fixtures = {}, []
    for host, directory in [('desktop', desktop), ('mac', mac)]:
        added_pins, added_fixtures = admit(directory, host)
        pins.update(added_pins)
        fixtures.extend(added_fixtures)
    for name in ('acquisition_broad_value.py', 'acquisition_conditional_value.py',
                 'test_acquisition_broad_value.py', 'test_acquisition_conditional_value.py'):
        path = Path(__file__).resolve().with_name(name)
        pins[str(path)] = sha(path)
    output.mkdir(parents=True)
    plan = dict(kind=KIND, inputFiles=pins, fixtures=fixtures, originWorlds=2048,
                featureNames=list(fixed.FEATURE_NAMES), lambdaValue=fixed.LAMBDA,
                folds=fixed.FOLDS, fits=5, threads=1, dtype='float64',
                fold='globalIndex % 5; both routes remain together',
                weights='Equal rooted origins; equal available routes within each origin',
                target='Mean of two paired BUY-minus-REFERENCE composite reward differences',
                objective='weightedMeanMSE + 10 * sum(beta^2); intercept unpenalized',
                baselines=['zero', 'training-fold weighted route mean'],
                cohortUse='Fixed descriptive summaries only; never model columns or selection',
                limits=dict(seconds=600, attempts=1), fullDataFit=False, actorExport=False,
                limitations=['Development cross-validation with overlapping training folds',
                             'Origin-level intervals are descriptive, not a fresh qualification',
                             'Two streams retain the same origin hidden hands and fixed deck',
                             'Acquisition-rival exploration may create weaker opponents',
                             'Root intervention proxy does not establish closed-loop strength'])
    write(output / 'plan.json', plan)
    write(output / 'freeze.json', dict(planSha256=sha(output / 'plan.json'),
                                      frozenAtEpoch=time.time(), fitsBeforeFreeze=0))
    return dict(planSha256=sha(output / 'plan.json'), inputPins=len(pins), fits=5, newGames=0)


def validate(output):
    plan = read(output / 'plan.json')
    require(sha(output / 'plan.json') == read(output / 'freeze.json')['planSha256']
            and plan['kind'] == KIND and plan['featureNames'] == list(fixed.FEATURE_NAMES)
            and plan['lambdaValue'] == fixed.LAMBDA and plan['folds'] == fixed.FOLDS
            and plan['originWorlds'] == 2048 and plan['fits'] == 5,
            'The fixed five-fit diagnostic plan changed.')
    require(all(sha(path) == digest for path, digest in plan['inputFiles'].items()),
            'An admitted diagnostic input changed.')
    return plan


def load(plan):
    rows, features, labels, missing = [], [], [], []
    require([item['chunk'] for item in plan['fixtures']] == list(range(1, 9)),
            'Use all eight prospectively assigned chunks in global order.')
    for fixture in plan['fixtures']:
        with gzip.open(fixture['path'], 'rt') as stream:
            origins = json.load(stream)
        require(len(origins) == 256, 'Every chunk must retain all 256 independent origins.')
        for local_index, origin in enumerate(origins):
            index, cohort = validate_origin_identity(origin, fixture['chunk'], local_index)
            added = fixed.origin_rows(origin, index, local_index, origin['originalMemberSha256'])
            for row in added[0]:
                row.update(chunk=fixture['chunk'], localIndex=local_index, cohort=cohort)
            for target, values in zip((rows, features, labels, missing), added):
                target.extend(values)
    return rows, np.asarray(features, dtype=np.float64), np.asarray(labels, dtype=np.float64), missing


def run(output):
    require(all(os.environ.get(name) == '1' for name in fixed.THREAD_ENV),
            'Use one numerical thread in every supported backend.')
    plan = validate(output)
    write(output / 'attempt.json', dict(startedEpoch=time.time(), pid=os.getpid(), attempt=1))
    def expired(*_):
        raise TimeoutError('The fixed acquisition diagnostic exceeded 600 seconds.')
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(600)
    rows, x, y, missing = load(plan)
    write(output / 'features.json', dict(featureNames=list(fixed.FEATURE_NAMES), rows=rows, values=x.tolist()))
    write(output / 'labels.json', dict(target=plan['target'], values=y.tolist()))
    fitted, baseline, folds = fixed.cross_validate(rows, x, y)
    for fold in folds:
        write(output / f"fold-{fold['fold']}.json", fold)
    write(output / 'predictions.json', dict(rows=rows, ridge=fitted.tolist(), routeMean=baseline.tolist()))
    cohorts = {}
    for name in ('INCUMBENT', 'RIVAL_ACQUISITION'):
        indices = np.array([i for i, row in enumerate(rows) if row['cohort'] == name])
        cohorts[name] = fixed.summarize([rows[i] for i in indices], y[indices],
                                        fitted[indices], baseline[indices])
    result = dict(kind=KIND, originWorlds=2048, rootedWorlds=len({row['index'] for row in rows}),
                  missingOrigins=missing, rows=len(rows), features=x.shape[1], fits=5,
                  combined=fixed.summarize(rows, y, fitted, baseline),
                  routes={route: fixed.summarize(rows, y, fitted, baseline, route)
                          for route in fixed.ROUTES}, cohorts=cohorts, limitations=plan['limitations'])
    write(output / 'results.json', result)
    validate(output)
    write(output / 'receipt.json', dict(passed=True, kind=KIND + '-receipt', fits=5, newGames=0,
          fullDataFit=False, actorExport=False, inputPins=len(plan['inputFiles']),
          outputFiles={path.name: sha(path) for path in output.iterdir() if path.is_file()},
          numpyVersion=np.__version__, python=sys.version, platform=platform.platform(),
          completedAtEpoch=time.time()))
    signal.alarm(0)
    return dict(passed=True, resultsSha256=sha(output / 'results.json'), fits=5, newGames=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'run'))
    parser.add_argument('--desktop', type=Path)
    parser.add_argument('--mac', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'prepare':
        require(args.desktop is not None and args.mac is not None, 'Preparation requires both host corpora.')
        result = prepare(args.desktop.resolve(), args.mac.resolve(), args.output.resolve())
    else:
        result = run(args.output.resolve())
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
