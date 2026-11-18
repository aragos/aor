"""Freeze and supervise one finite descriptive purchase campaign without a model loop."""
import argparse
import json
import math
import os
from pathlib import Path
import platform
import subprocess
import sys
import time

import automata_campaign as controller
import automata_experiments as exp
import purchase_ecology_environment as numerical

read, sha, document, require = exp.read, exp.sha, exp.document, exp.require
STAGES = ('train', 'screen')
STAGE_SECONDS = (10800, 3600)
MAXIMUM_SECONDS = 14400
TREES = ('scripts', 'runtime', 'inputs', 'audit', 'evidence', 'sources')
DYNAMIC_ENV = {'AOR_CAMPAIGN_OUTPUT', 'AOR_CAMPAIGN_STAGE_ID', 'AOR_CAMPAIGN_DEADLINE_EPOCH',
               'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH'}


def execution_limits(protocol):
    """A reviewed mechanical recovery can consume only its original remaining allowance."""
    recovery = protocol.get('recovery')
    if recovery is None:
        return MAXIMUM_SECONDS, STAGE_SECONDS
    fields = ('originalStartedEpoch', 'originalDeadlineEpoch', 'originalTrainStartedEpoch',
              'originalTrainDeadlineEpoch', 'launchByEpoch', 'maximumSeconds', 'trainMaximumSeconds')
    require(isinstance(recovery, dict) and all(
        type(recovery.get(name)) in (int, float) and math.isfinite(recovery[name]) for name in fields),
        'Recovery requires finite original clocks and fixed remaining ceilings.')
    require(protocol['maximumSeconds'] == MAXIMUM_SECONDS
            and recovery['originalDeadlineEpoch'] == recovery['originalStartedEpoch'] + MAXIMUM_SECONDS
            and recovery['originalTrainDeadlineEpoch'] == min(recovery['originalDeadlineEpoch'],
                recovery['originalTrainStartedEpoch'] + STAGE_SECONDS[0])
            and recovery['originalStartedEpoch'] <= recovery['originalTrainStartedEpoch']
            < recovery['launchByEpoch'] < recovery['originalTrainDeadlineEpoch']
            and recovery['maximumSeconds'] == recovery['originalDeadlineEpoch'] - recovery['launchByEpoch']
            and recovery['trainMaximumSeconds'] == recovery['originalTrainDeadlineEpoch'] - recovery['launchByEpoch'],
            'Recovery may not renew, move or enlarge the original campaign or training deadlines.')
    require(isinstance(recovery.get('prefixReceipt'), str) and recovery['prefixReceipt']
            and isinstance(recovery.get('originalInputPins'), dict) and recovery['originalInputPins'],
            'Recovery requires an explicit verified prefix receipt and immutable original input pins.')
    require(protocol['python']['executable'] == protocol['numerical']['resolvedExecutable']
            and protocol['python']['version'] == protocol['numerical']['pythonVersion'],
            'Recovery verification must use the exact same interpreter and version as numerical learning.')
    return recovery['maximumSeconds'], (recovery['trainMaximumSeconds'], STAGE_SECONDS[1])


def check_launch_window(protocol):
    recovery = protocol.get('recovery')
    if recovery is not None:
        execution_limits(protocol)
        require(time.time() <= recovery['launchByEpoch'],
                'The reviewed recovery launch window expired; no automatic rescheduling or retry.')


def validate_science(protocol):
    # Import only at execution boundaries: preparation can test this generic supervisor
    # independently while the scientific stage adapter is being implemented.
    import purchase_ecology as target
    require(tuple(target.STAGES) == STAGES, 'Purchase pilot stages must remain train then screen.')
    execution_limits(protocol)
    target.validate_inputs(protocol)


def verify_execution(protocol, path, output):
    execution_limits(protocol)
    root = Path(path).resolve().parent
    require(root == Path(__file__).resolve().parents[1] and Path(output).resolve() == root / 'run',
            'Execute only in the declared isolated purchase package and its run directory.')
    environment = protocol['environment']
    require(platform.platform() == environment['platform'] and platform.machine() == environment['machine']
            and os.cpu_count() == environment['logicalCpus'], 'The frozen purchase host identity changed.')
    require(str(Path(sys.executable).resolve()) == protocol['python']['executable']
            and sys.version == protocol['python']['version'] and sys.flags.ignore_environment
            and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'The pure supervisor requires its exact clean interpreter with -E -s -S -B.')
    require({key: value for key, value in os.environ.items() if key not in DYNAMIC_ENV}
            == environment['variables'] and read(root / 'environment.json') == environment,
            'The frozen purchase supervisor environment changed.')
    for name, digest in protocol['frozenFiles'].items():
        require(sha(name) == digest, 'A frozen purchase package input changed: ' + name)
    for name in TREES:
        actual = {str(path) for path in (root / name).rglob('*') if path.is_file()}
        declared = {path for path in protocol['frozenFiles'] if Path(path).is_relative_to(root / name)}
        require(actual == declared, 'Unexpected or missing purchase package files in ' + name)


def campaign_spec(root, protocol, files):
    maximum_seconds, stage_seconds = execution_limits(protocol)
    spec = dict(schemaVersion=1, kind='automata-campaign', inputs=files,
        limits=dict(maximumSeconds=maximum_seconds, maximumDataBytes=4 * exp.GIB,
                    maximumRssBytes=4 * exp.GIB, minimumFreeBytes=2 * exp.GIB),
        stages=[dict(id=name, dependsOn=[] if index == 0 else [STAGES[index - 1]],
            maximumSeconds=stage_seconds[index], maximumAttempts=1, result=f'results/{name}.json',
            command=[protocol['python']['executable'], '-E', '-s', '-S', '-B',
                str(root / 'scripts/purchase_ecology.py'), 'stage', '--protocol', str(root / 'protocol.json'),
                '--output', '{output}', '--stage', name]) for index, name in enumerate(STAGES)])
    recovery = protocol.get('recovery')
    if recovery is not None:
        spec['limits']['absoluteDeadlineEpoch'] = recovery['originalDeadlineEpoch']
        spec['stages'][0]['absoluteDeadlineEpoch'] = recovery['originalTrainDeadlineEpoch']
        spec['stages'][1]['absoluteDeadlineEpoch'] = recovery['originalDeadlineEpoch']
    return spec


def preflight_child(path, output):
    protocol = read(path)
    verify_execution(protocol, path, output)
    validate_science(protocol)
    require(not output.exists(), 'Preflight precedes any purchase campaign attempt.')
    require(numerical.inspect(protocol['numerical']['executable']) == protocol['numerical'],
            'The clean supervisor must reproduce the exact CPU numerical identity.')
    own = [sample for sample in controller.processes.sample_group(os.getpgrp()) if sample.pid == os.getpid()]
    require(len(own) == 1 and own[0].rss_bytes > 0, 'The exact child must pass external process sampling.')
    print(json.dumps(dict(passed=True, exactChildEnvironment=True, numericalEnvironmentVerified=True,
        externalPsSamplerPassed=True, nativeGames=0, protocolSha256=sha(path))), flush=True)


def external_child_preflight(root, path):
    protocol = read(path)
    maximum_seconds, stage_seconds = execution_limits(protocol)
    now = time.time()
    recovery = protocol.get('recovery')
    deadline = recovery['originalDeadlineEpoch'] if recovery else now + maximum_seconds
    train_deadline = recovery['originalTrainDeadlineEpoch'] if recovery else now + stage_seconds[0]
    environment = dict(protocol['environment']['variables'], AOR_CAMPAIGN_OUTPUT=str(root / 'run'),
        AOR_CAMPAIGN_STAGE_ID='train', AOR_CAMPAIGN_DEADLINE_EPOCH=str(deadline),
        AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH=str(train_deadline))
    command = [protocol['python']['executable'], '-E', '-s', '-S', '-B',
        str(root / 'scripts/purchase_ecology_campaign.py'), 'preflight-child',
        '--protocol', str(path), '--output', str(root / 'run')]
    child = subprocess.run(command, cwd=root, env=environment, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True,
        timeout=180, check=False)
    require(child.returncode == 0, 'Exact purchase child preflight failed: ' + child.stderr[-4000:])
    result = json.loads(child.stdout)
    require(result['passed'] and result['nativeGames'] == 0 and result['protocolSha256'] == sha(path),
            'Exact purchase preflight did not verify the current protocol.')
    return dict(passed=True, checkedAt=time.time(), command=command, protocolSha256=sha(path),
        environmentSha256=sha(root / 'environment.json'), verifierSha256=sha(__file__),
        validatorSha256=sha(root / 'scripts/purchase_ecology.py'), childResult=result,
        stdout=child.stdout, stderr=child.stderr, nativeGames=0)


def input_files(root, protocol):
    files = {str(path): sha(path) for path in sorted(root.rglob('*'))
             if path.is_file() and '__pycache__' not in path.parts}
    for identity in ('java', 'python', 'numerical'):
        files.update(protocol[identity]['files'])
    for path, digest in protocol.get('recovery', {}).get('originalInputPins', {}).items():
        require(path not in files or files[path] == digest, 'Recovery pin conflicts with a current input: ' + path)
        require(sha(path) == digest, 'An original recovery-prefix input changed: ' + path)
        files[path] = digest
    return files


def prepare_preflight(root):
    require(not any((root / name).exists() for name in
                    ('preflight-protocol.json', 'exact-child-preflight.json', 'protocol.json', 'campaign.json', 'run')),
            'Prepare one exact-child preflight before freezing or launching.')
    protocol = read(root / 'protocol-draft.json')
    protocol['frozenFiles'] = input_files(root, protocol)
    path = root / 'preflight-protocol.json'
    document(path, protocol)
    receipt = external_child_preflight(root, path)
    document(root / 'exact-child-preflight.json', receipt)
    print(json.dumps(dict(passed=True, receiptSha256=sha(root / 'exact-child-preflight.json'), nativeGames=0)))


def freeze(root):
    require(not any((root / name).exists() for name in ('protocol.json', 'campaign.json', 'run')),
            'Freeze exactly once before purchase execution.')
    protocol, preflight = read(root / 'protocol-draft.json'), read(root / 'exact-child-preflight.json')
    require(preflight['passed'] and preflight['nativeGames'] == 0
            and preflight['protocolSha256'] == sha(root / 'preflight-protocol.json')
            and preflight['environmentSha256'] == sha(root / 'environment.json')
            and preflight['verifierSha256'] == sha(__file__)
            and preflight['validatorSha256'] == sha(root / 'scripts/purchase_ecology.py'),
            'Freeze requires exact successful child, numerical and sampler verification.')
    previous = read(root / 'preflight-protocol.json')
    require({key: value for key, value in previous.items() if key != 'frozenFiles'} == protocol,
            'Purchase scientific inputs changed after the exact-child preflight.')
    for path, digest in previous['frozenFiles'].items():
        require(sha(path) == digest, 'An input changed after purchase preflight: ' + path)
    protocol['frozenFiles'] = input_files(root, protocol)
    validate_science(protocol)
    document(root / 'protocol.json', protocol)
    spec = campaign_spec(root, protocol, dict(protocol['frozenFiles'],
                         **{str(root / 'protocol.json'): sha(root / 'protocol.json')}))
    controller.validate_spec(spec, root / 'run')
    document(root / 'campaign.json', spec)
    print(json.dumps(dict(protocolSha256=sha(root / 'protocol.json'), campaignSha256=sha(root / 'campaign.json'),
                         pins=len(spec['inputs']), unlaunched=True)))


def run(root, options):
    require(sha(root / 'protocol.json') == options.protocol_sha
            and sha(root / 'campaign.json') == options.campaign_sha, 'Reviewed purchase protocol/campaign changed.')
    protocol = read(root / 'protocol.json')
    os.environ.clear()
    os.environ.update(protocol['environment']['variables'])
    verify_execution(protocol, root / 'protocol.json', root / 'run')
    validate_science(protocol)
    expected = campaign_spec(root, protocol, dict(protocol['frozenFiles'],
                             **{str(root / 'protocol.json'): options.protocol_sha}))
    require(read(root / 'campaign.json') == expected, 'Purchase commands, limits or pins changed.')
    require(not (root / 'run').exists(), 'One purchase attempt only: no retry, resume or next campaign.')
    document(root / 'launch-child-preflight.json', external_child_preflight(root, root / 'protocol.json'))
    check_launch_window(protocol)
    return controller.main(['--spec', str(root / 'campaign.json'), '--output', str(root / 'run')])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('prepare-preflight')
    commands.add_parser('freeze')
    child = commands.add_parser('preflight-child')
    child.add_argument('--protocol', type=Path, required=True)
    child.add_argument('--output', type=Path, required=True)
    launch = commands.add_parser('run')
    launch.add_argument('--protocol-sha', required=True)
    launch.add_argument('--campaign-sha', required=True)
    options = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if options.command == 'prepare-preflight':
        prepare_preflight(root)
    elif options.command == 'freeze':
        freeze(root)
    elif options.command == 'preflight-child':
        preflight_child(options.protocol, options.output)
    else:
        sys.exit(run(root, options))
