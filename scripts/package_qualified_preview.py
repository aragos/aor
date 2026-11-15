"""Copy an audited win-qualified unit into a new offline preview directory; never launch it.

The request supplies raw SHA256 subjects, including the independent root release review.
Existing certificate and evidence bytes are preserved. Timed scientific audits are not rerun.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil


PROFILE = 'aor-coordinated-win-support-1'
SUBJECTS = ('inferenceAsset', 'nomination', 'qualification', 'desktopWatcher',
    'desktopEvidence', 'macWatcher', 'macEvidence', 'rootReleaseReview')
COUNTS = {
    'DESKTOP': dict(worlds=8192, games=16384, reproductions=16, terminalExecutions=16400),
    'MAC': dict(worlds=3072, games=6144, reproductions=8, terminalExecutions=6152),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            value.update(block)
    return value.hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def checked(subject):
    require(type(subject) is dict and set(subject) == {'path', 'sha256'},
        'A supplied subject requires exactly path and sha256.')
    path = Path(subject['path'])
    require(path.is_file() and not path.is_symlink() and sha(path) == subject['sha256'],
        'A supplied subject is absent, symlinked or changed.')
    return path.resolve()


def relative(owner, subject):
    require(type(subject) is dict and set(subject) == {'path', 'sha256'},
        'A portable subject requires exactly path and sha256.')
    name = subject['path']
    path = PurePosixPath(name)
    require(isinstance(name, str) and path.parts and not path.is_absolute() and
        path.as_posix() == name and all(part not in ('.', '..') for part in path.parts) and
        '\\' not in name, 'Portable subjects must remain normalized inside their owner directory.')
    result = owner.parent
    for part in path.parts:
        result = result/part
        require(not result.is_symlink(), 'Portable subjects cannot traverse symlinks.')
    return checked(dict(path=str(result), sha256=subject['sha256']))


def package(request_path, output):
    request_path = Path(request_path).resolve()
    request_sha = sha(request_path)
    request = read(request_path)
    require(set(request) == {'kind', 'protocolSha256', 'clockSha256', *SUBJECTS} and
        request['kind'] == 'coordinated-win-preview-package-request-1',
        'Use the explicit offline package request with all original audit subjects.')
    sources = {key: checked(request[key]) for key in SUBJECTS}
    records = {key: read(path) for key, path in sources.items()}
    release = records['rootReleaseReview']
    require(release.get('kind') == 'coordinated-win-preview-release-review-1' and
        release.get('passed') is True and release.get('complete') is True and
        release.get('protocolSha256') == request['protocolSha256'] and
        release.get('clockSha256') == request['clockSha256'] and
        release.get('subjects') == {key: request[key]['sha256'] for key in SUBJECTS[:-1]} and
        release.get('allOwnedProcessesExitedIndependently') is True and
        release.get('packagingAuthorized') is True,
        'The independent final release review must authorize this exact complete subject set.')
    for host, prefix in (('DESKTOP', 'desktop'), ('MAC', 'mac')):
        watcher, evidence = records[prefix+'Watcher'], records[prefix+'Evidence']
        require(watcher['kind'] == 'coordinated-win-qualification-host-terminal-audit-1' and
            watcher['host'] == host and watcher['passed'] is True and watcher['complete'] is True and
            watcher['status'] == 'completed' and watcher['allOwnedProcessesExited'] is True and
            watcher['clockGuardStopped'] is True and
            watcher['protocolSha256'] == request['protocolSha256'] and
            watcher['clockSha256'] == request['clockSha256'] and
            watcher['evidence']['sha256'] == request[prefix+'Evidence']['sha256'],
            'Both original completed watcher audits must bind this protocol, clock and pure evidence.')
        require(evidence['kind'] == 'coordinated-win-qualification-audit-1' and
            evidence['host'] == host and evidence['passed'] is True and evidence['complete'] is True and
            evidence['status'] == 'completed' and evidence['protocolSha256'] == request['protocolSha256'] and
            evidence['counts'] == COUNTS[host] and watcher['counts'] == COUNTS[host] and
            evidence['execution'] == dict(newGames=0, newFits=0, modelForwards=0),
            'The sealed pure audits must retain exact global/subset counts and zero audit execution.')
    audit = records['desktopEvidence']
    require(audit['qualified'] is True and audit['fullGlobalStatisticsRecomputed'] is True and
        audit['eligibleQualifiedUnit']['sha256'] == request['qualification']['sha256'],
        'Only the exact Desktop-audited qualified certificate can be packaged.')
    asset, nomination, certificate = (records[name] for name in
        ('inferenceAsset', 'nomination', 'qualification'))
    require(asset['kind'] == 'coordinated-inference-asset-1' and asset['profile'] == PROFILE and
        nomination['kind'] == 'coordinated-policy-nomination-1' and nomination['passed'] is True and
        nomination['complete'] is True and nomination['assetSha256'] == request['inferenceAsset']['sha256'],
        'The package must retain the nominated inference descriptor.')
    require(certificate['kind'] == 'coordinated-win-qualification-1' and
        certificate['schemaVersion'] == 1 and certificate['profile'] == PROFILE and
        certificate['passed'] is True and certificate['complete'] is True and
        certificate['inferenceAssetSha256'] == request['inferenceAsset']['sha256'] and
        certificate['nominationSha256'] == request['nomination']['sha256'] and
        set(certificate['banks']) == {'mixed', 'homogeneous'},
        'The existing successful two-bank certificate must bind this exact asset and nomination.')

    copies = {}

    def add(path, destination, digest):
        require(sha(path) == digest, 'A source changed after its subject was admitted.')
        require(destination not in copies or copies[destination] == (path, digest),
            'Two subjects cannot overwrite one package member.')
        copies[destination] = (path, digest)

    def add_subject(owner, subject, base, prefix):
        path = relative(owner, subject)
        add(path, prefix/path.relative_to(base), subject['sha256'])
        return path

    for key, prefix in (('inferenceAsset', 'inference'), ('nomination', 'nomination'),
            ('qualification', 'qualification')):
        owner = sources[key]
        add(owner, Path(prefix)/owner.name, request[key]['sha256'])
    owner = sources['inferenceAsset']; base = owner.parent
    for key in ('currentBundle', 'originalBundle'):
        bundle = add_subject(owner, asset[key], base, Path('inference'))
        heads = read(bundle)['heads']
        require(set(heads) == {'CARD', 'PURCHASE', 'EXPANSION', 'TOKEN'},
            'Each inference bundle must contain all four heads.')
        for row in heads.values():
            add_subject(bundle, {key: row[key] for key in ('path', 'sha256')}, base, Path('inference'))
    add_subject(owner, asset['calibration'], base, Path('inference'))
    for key in ('qualifiedOrdinary', 'cardM8', 'purchaseF0', 'expansionE'):
        add_subject(owner, asset['references'][key], base, Path('inference'))
    owner = sources['nomination']
    for key in ('completionAudit', 'developmentReport'):
        add_subject(owner, nomination[key], owner.parent, Path('nomination'))
    owner = sources['qualification']
    original_owner = PurePosixPath(audit['eligibleQualifiedUnit']['path'])
    require(audit['artifactPins'][str(original_owner)] == request['qualification']['sha256'],
        'The certificate must be an original audited output.')
    for bank in certificate['banks'].values():
        require(bank['complete'] is True and bank['pairs'] == 4096 and bank['win99Passed'] is True,
            'Both complete banks must pass; a refusal is never packaged.')
        for key in ('evidence', 'report'):
            subject = bank[key]
            add_subject(owner, subject, owner.parent, Path('qualification'))
            require(audit['artifactPins'][str(original_owner.parent/subject['path'])] == subject['sha256'],
                'Every copied bank subject must retain its original audited raw hash.')
    for key in SUBJECTS[3:]:
        add(sources[key], Path('release-evidence')/(key+'.json'), request[key]['sha256'])
    add(request_path, Path('release-evidence/package-request.json'), request_sha)

    output = Path(output)
    require(not output.exists(), 'A preview package is new-only; saved asset identities cannot be rebound.')
    require(shutil.disk_usage(output.parent).free >= 16*1024**3 + sum(path.stat().st_size
        for path, _ in copies.values()), 'Packaging must retain at least16GiB free.')
    output.mkdir()
    for name, (path, digest) in sorted(copies.items()):
        target = output/name; target.parent.mkdir(parents=True, exist_ok=True)
        with path.open('rb') as source, target.open('xb') as destination:
            shutil.copyfileobj(source, destination)
        require(sha(path) == sha(target) == digest, 'A source changed during lossless packaging.')
    manifest = dict(kind='coordinated-win-support-preview-1', schemaVersion=1, profile=PROFILE,
        inferenceAsset=dict(path='inference/'+sources['inferenceAsset'].name,
            sha256=request['inferenceAsset']['sha256']),
        qualification=dict(path='qualification/'+sources['qualification'].name,
            sha256=request['qualification']['sha256']))
    write(output/'manifest.json', manifest)
    receipt = dict(kind='coordinated-win-preview-package-1', complete=True,
        manifest=dict(path=str((output/'manifest.json').resolve()), sha256=sha(output/'manifest.json')),
        request=dict(path=str(request_path), sha256=request_sha),
        files={str(path.relative_to(output)): sha(path) for path in sorted(output.rglob('*')) if path.is_file()},
        qualificationCopiedByteExact=True, fullScientificCorpusIncluded=False,
        newGames=0, modelForwards=0, auditReruns=0, serverStarted=False, activated=False)
    write(output/'package-receipt.json', receipt)
    return receipt


def write(path, value):
    with Path(path).open('xb') as stream:
        stream.write(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()+b'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.request, args.output), sort_keys=True))
