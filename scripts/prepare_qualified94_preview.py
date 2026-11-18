"""Package the closed, root-qualified94 policy without fitting, inference, exports or game access."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


FOUNDATION_MANIFEST = '558208f7d45647e285b1186677afe061cbfeb4858ad3aefe9bc364af1377b6f1'
BASE_ASSET = 'c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a'
CANDIDATE = 'fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e'
PROFILE = 'aor-coordinated-robust-support-1'
PINS = {
    'preparation/root-qualification-review.json':
        '90c5909b578e60c142fda3137fe0107c1af6c5aa68b916019479130971eb62c1',
    'preparation/physical-closure.json':
        'ec29fb21d8208ceb5008e50e29eb68c79116604d9eb63effa0b81b702a2c4a0d',
    'mac/execution/completion-audit.json':
        'f492054baa18962951ed65175cd27acb14e91febb76358d819a34d21a6f4f684',
    'mac/execution/terminal.json':
        '1fd867bb3f6264a87cdd82dfa020c36ed7f903a5f9a5f34d68ca1e346253cd15',
    'preparation/protocol.json':
        '140427b0db6635b7842b1b51213a5db174382e8088a0d43fc2917aa7a1c37e5e',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            result.update(block)
    return result.hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def checked(path, expected):
    require(sha(path) == expected, 'The original qualified subject changed: '+str(path))
    return read(path)


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x') as stream:
        json.dump(value, stream, sort_keys=True, separators=(',', ':'), allow_nan=False)
        stream.write('\n')
    return path


def copy(path, output, expected):
    path, output = Path(path), Path(output)
    require(path.is_file() and not path.is_symlink() and sha(path) == expected,
        'Only the exact regular original subject can be copied: '+str(path))
    require(not output.exists(), 'Package subjects are new-only: '+str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, output)
    require(sha(output) == expected, 'A copied subject changed bytes: '+str(output))
    return output


def relative(root, name):
    path = Path(name)
    require(not path.is_absolute() and '..' not in path.parts and str(path) != '.',
        'Portable subjects require confined relative paths.')
    return Path(root)/path


def subject(owner, path):
    return dict(path=str(Path(path).relative_to(Path(owner))), sha256=sha(path))


def prepare(research, foundation, output):
    research, foundation, output = [Path(path).resolve() for path in (research, foundation, output)]
    records = {name: checked(research/name, expected) for name, expected in PINS.items()}
    review = records['preparation/root-qualification-review.json']
    audit = records['mac/execution/completion-audit.json']
    closure = records['preparation/physical-closure.json']
    terminal = records['mac/execution/terminal.json']
    protocol = records['preparation/protocol.json']
    require(review['passed'] is True and review['qualified'] is True and review['counts'] == 2064 and
        audit['passed'] is True and audit['complete'] is True and audit['qualified'] is True and
        audit['terminalExecutions'] == 2064 and audit['originalReproductionsVerified'] is True and
        closure['passed'] is True and closure['allOwnedProcessesExited'] is True and
        closure['knownCompletedTerminalExecutions'] == 2064 and terminal['qualified'] is True and
        terminal['complete'] is True and terminal['status'] == 'completed',
        'Only the fully qualified and physically closed actual94 can become this asset.')
    require(protocol['candidateBundleSha256'] == review['candidateBundleSha256'] == CANDIDATE and
        protocol['selector'] == dict(kind='near-optimal-token-softmax-1', temperature=.35,
            maxScoreGap=3.0, neighborRadius=4) and protocol['baseInferenceAssetSha256'] == BASE_ASSET,
        'The complete qualified fca+c05 policy is fixed; no candidate or sampler can change.')
    old = checked(foundation/'manifest.json', FOUNDATION_MANIFEST)
    base = checked(foundation/old['inferenceAsset']['path'], BASE_ASSET)
    inventory = read(foundation/'package-receipt.json')['files']
    require(set(inventory) | {'package-receipt.json'} == {
        str(path.relative_to(foundation)) for path in foundation.rglob('*') if path.is_file()},
        'Every reused foundation file must belong to the original verified inventory.')
    output.mkdir(parents=True, exist_ok=False)
    for name, expected in inventory.items():
        copy(relative(foundation, name), relative(output/'foundation', name), expected)
    copy(foundation/'package-receipt.json', output/'foundation/package-receipt.json',
        sha(foundation/'package-receipt.json'))
    host = checked(research/'mac/profile.json', audit['profileSha256'])
    candidate = Path(host['candidateBundle']['path'])
    bundle = checked(candidate, CANDIDATE)
    copy(candidate, output/'candidate/bundle.json', CANDIDATE)
    for head in bundle['heads'].values():
        copy(relative(candidate.parent, head['path']),
            relative(output/'candidate', head['path']), head['sha256'])
    qualification = output/'qualification'
    evidence = {}
    permitted_origin = research.parent/'automata-robust-league-20261117'
    for name, item in protocol['nomineeEvidence'].items():
        source = Path(item['path']).resolve()
        require(permitted_origin in source.parents,
            'Nominee evidence must come only from the approved original89 research tree.')
        evidence[name] = subject(qualification, copy(source,
            qualification/'nominee'/f'{name}.json', item['sha256']))
    copies = {
        'preparation/root-qualification-review.json': 'root-review.json',
        'preparation/physical-closure.json': 'physical-closure.json',
        'mac/execution/completion-audit.json': 'completion-audit.json',
        'mac/execution/terminal.json': 'terminal.json',
        'preparation/protocol.json': 'protocol.json',
    }
    for original, name in copies.items():
        copy(research/original, qualification/name, PINS[original])
    banks = {}
    for name in ('mixed', 'homogeneous'):
        path = research/'mac/execution/reports'/f'{name}.json'
        report = read(path)
        require(report['complete'] is True and report['win99Passed'] is True and
            report['statistics']['wins']['count'] == 512 and
            report['statistics']['wins']['lower99'] > 0,
            'Each exact complete512-world bank must pass its original99% win gate.')
        require(audit['artifactPins'][str(path)] == sha(path),
            'Each original bank report must remain pinned by the original completed audit.')
        copied = copy(path, qualification/'reports'/f'{name}.json', sha(path))
        banks[name] = dict(complete=True, pairs=512, win99Passed=True,
            evidence=subject(qualification, qualification/'root-review.json'),
            report=subject(qualification, copied))
    scope = {name: base[name] for name in
        ('supportProfile', 'rulesVersion', 'dataVersion', 'configuration')}
    descriptor = write(output/'inference-asset.json', dict(scope,
        kind='coordinated-robust-inference-asset-1', schemaVersion=1, profile=PROFILE,
        baseInferenceAsset=subject(output, output/'foundation'/old['inferenceAsset']['path']),
        currentBundle=subject(output, output/'candidate/bundle.json'),
        tokenSelector=protocol['selector'], expansionDenial=None))
    certificate = write(qualification/'qualification.json', dict(scope,
        kind='coordinated-robust-win-qualification-1', schemaVersion=1, passed=True, complete=True,
        profile=PROFILE, inferenceAssetSha256=sha(descriptor), evaluationVersion='study94-QUAL1',
        protocol=subject(qualification, qualification/'protocol.json'), nomineeEvidence=evidence,
        banks=banks))
    manifest = write(output/'manifest.json', dict(kind='coordinated-robust-support-preview-1',
        schemaVersion=1, profile=PROFILE, inferenceAsset=subject(output, descriptor),
        qualification=subject(output, certificate)))
    files = {str(path.relative_to(output)): sha(path)
        for path in sorted(output.rglob('*')) if path.is_file()}
    receipt = dict(kind='qualified94-preview-package-1', complete=True,
        sourceCommit=subprocess.check_output(['git', '-C', str(Path(__file__).resolve().parent.parent),
            'rev-parse', 'HEAD'], text=True).strip(), profile=PROFILE,
        manifest=subject(output, manifest), inferenceAsset=subject(output, descriptor),
        qualification=subject(output, certificate), candidateBundleSha256=CANDIDATE,
        foundationManifestSha256=FOUNDATION_MANIFEST, baseInferenceAssetSha256=BASE_ASSET,
        tokenSelector=protocol['selector'], expansionDenial=None, rootQualificationReview=PINS[
            'preparation/root-qualification-review.json'], files=files,
        fullScientificCorpusIncluded=False, originalScientificCorpusRetained=str(research),
        qualificationEvidenceCopiedByteExact=True, originalAuditsRerun=0, newGames=0,
        newFits=0, modelForwards=0, old91OutcomeQualityReads=0, humanRecordsRead=0,
        remoteContacts=0, activated=False, serverStarted=False)
    write(output/'package-receipt.json', receipt)
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--research', type=Path, required=True)
    parser.add_argument('--foundation', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    value = prepare(args.research, args.foundation, args.output)
    print(json.dumps({key: value[key] for key in ('manifest', 'inferenceAsset', 'qualification',
        'candidateBundleSha256', 'tokenSelector', 'activated', 'serverStarted')}, sort_keys=True))
