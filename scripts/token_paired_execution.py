"""One fixed host stage under the existing resource/owned-group campaign controller.

Both hosts receive identical original clock bytes before launch. This adapter does not create
or renew that clock, retry work, select policies, or implement another process supervisor.
The reviewed package dispatcher owns controller/caffeinate/watcher cleanup; its pure terminal
audit is capped by the earlier of terminal+900 seconds and the shared science deadline+900.
"""
import argparse
from pathlib import Path
import time

import automata_campaign as controller
import token_paired_campaign as worker
import token_paired_campaign_plan as p
import token_paired_storage as storage


def specification(protocol_path, profile_path, clock_path):
    """Build one finite DAG stage without opening models or executing any work."""
    plan = p.Plan.from_record(p.read(protocol_path)); profile = p.read(profile_path)
    launch = p.read(clock_path); protocol_sha = p.file_sha(protocol_path)
    p.require(profile['kind'] == 'token-paired-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256'] == protocol_sha and launch['kind'] == 'token-paired-clock-1' and
        launch['protocolSha256'] == protocol_sha, 'Host and original clock must bind the shared protocol.')
    clock = p.Clock(launch['startedEpoch'], launch['deadlineEpoch'])
    limits = plan.record['hosts'][profile['host']]
    p.require(all(profile[name] == value for name, value in limits.items()),
        'The controller cannot override the fixed host resource profile.')
    inputs = dict(profile['files'])
    for path in (protocol_path, profile_path, clock_path, __file__):
        inputs[str(Path(path).resolve())] = p.file_sha(path)
    result = dict(schemaVersion=1, kind='automata-campaign', inputs=inputs,
        limits=dict(maximumSeconds=21600, absoluteDeadlineEpoch=clock.deadline,
            maximumDataBytes=limits['maximumDataBytes'], minimumFreeBytes=limits['minimumFreeBytes'],
            maximumRssBytes=limits['maximumRssBytes']),
        stages=[dict(id='token-paired', maximumSeconds=21600, absoluteDeadlineEpoch=clock.deadline,
            maximumAttempts=1, result='stage-result.json', command=[profile['python']['executable'],
                '-E', '-s', '-B', str(Path(__file__).resolve()), 'stage', '--protocol', str(Path(protocol_path).resolve()),
                '--profile', str(Path(profile_path).resolve()), '--clock', str(Path(clock_path).resolve()),
                '--output', '{output}'])])
    return result


def host(protocol_path, profile_path, clock_path, output):
    """The sole host entrypoint; the established controller enforces resources and child cleanup."""
    output = Path(output).resolve()
    p.require(not output.exists(), 'A host execution permits one new output and no resume.')
    worker.check_profile(protocol_path, profile_path)
    launch = p.read(clock_path); clock = p.Clock(launch['startedEpoch'], launch['deadlineEpoch'])
    p.require(clock.start <= time.time(), 'The shared original attempt must have started before either host.')
    clock.remaining(time.time())
    spec = specification(protocol_path, profile_path, clock_path)
    controller.validate_spec(spec, output/'run')
    output.mkdir(parents=True)
    storage.publish_json(output/'campaign.json', spec)
    return controller.main(['--spec', str(output/'campaign.json'), '--output', str(output/'run')])


def stage(protocol_path, profile_path, clock_path, output):
    """Translate operational completion/stopping into the existing controller result contract."""
    output = Path(output)
    p.require(output.is_dir() and not (output/'stage-result.json').exists(),
        'The existing controller owns this single original stage directory.')
    result = worker.run(protocol_path, profile_path, clock_path, output/'study')
    p.require(result['status'] in ('completed', 'stopped'), 'A failed worker cannot publish a passed stage.')
    receipt = dict(schemaVersion=1, status='passed' if result['status']=='completed' else 'rejected',
        summary='Fixed TOKEN work '+result['status']+'; no successor or activation.',
        artifacts={'study/terminal.json':p.file_sha(output/'study/terminal.json')})
    storage.publish_json(output/'stage-result.json', receipt)
    return receipt


def verify(protocol_path, output):
    """Pure completed-evidence admission; outer watcher separately proves every owned group exited."""
    import token_paired_campaign_audit as evidence
    output = Path(output).resolve(); run = output/'run'; study = run/'study'
    state = p.read(run/'campaign-state.json'); spec = p.read(output/'campaign.json')
    manifest = p.read(run/'campaign-manifest.json')
    p.require(state['manifestSha256'] == p.file_sha(run/'campaign-manifest.json') and
        manifest['identity']['sourceSha256'] == p.file_sha(output/'campaign.json') ==
            p.file_sha(run/'campaign-spec.json') and
        manifest['startedEpoch'] == state['startedEpoch'] and
        manifest['deadlineEpoch'] == state['deadlineEpoch'],
        'The terminal controller must retain its original manifest, exact specification bytes and clock.')
    clock_wire = p.read(study/'clock.json')
    clock = p.Clock(clock_wire['startedEpoch'], clock_wire['deadlineEpoch'])
    p.require(clock_wire['protocolSha256'] == p.file_sha(protocol_path) and
        state['status'] == 'completed' and set(state['stages']) == {'token-paired'} and
        state['deadlineEpoch'] == spec['limits']['absoluteDeadlineEpoch'] == clock.deadline and
        clock.start <= state['startedEpoch'] <= state['updatedEpoch'],
        'One completed controller must retain the shared original deadline and exact single stage.')
    entry = state['stages']['token-paired']; terminal = p.read(study/'terminal.json')
    expected = 'passed' if terminal['status']=='completed' else 'rejected'
    p.require(entry['attempts'] == 1 and entry['status'] == expected and entry['exitCode'] == 0 and
        entry['deadlineEpoch'] == clock.deadline and entry['result'] == 'stage-result.json' and
        entry['resultSha256'] == p.file_sha(run/'stage-result.json'),
        'The accepted stage must have one attempt, its exact result, and no renewed science allowance.')
    result = controller.validate_result(run/'stage-result.json', run)
    p.require(result['status'] == expected and result['artifacts'] ==
        {'study/terminal.json':p.file_sha(study/'terminal.json')},
        'A stage must bind exactly its original terminal; stopping cannot become completed science.')
    audit = evidence.verify(p.subject(protocol_path), study)
    p.require(audit['passed'] and audit['status'] == terminal['status'],
        'The pure independent evidence audit must agree with the original host terminal.')
    audit['controllerSubjects'] = {name:p.subject(path) for name,path in
        (('specification',output/'campaign.json'),('state',run/'campaign-state.json'),
         ('manifest',run/'campaign-manifest.json'),('copiedSpecification',run/'campaign-spec.json'),
         ('stageResult',run/'stage-result.json'))}
    return audit


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('host','stage','audit'))
    parser.add_argument('--protocol', type=Path, required=True)
    parser.add_argument('--profile', type=Path); parser.add_argument('--clock', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    if args.mode == 'audit':
        p.require(args.receipt is not None, 'The pure audit requires a new immutable receipt path.')
        storage.publish_json(args.receipt, verify(args.protocol,args.output))
    else:
        p.require(args.profile is not None and args.clock is not None,
            'Host execution requires its frozen profile and the original shared clock.')
        code = (host if args.mode=='host' else stage)(args.protocol,args.profile,args.clock,args.output)
        if args.mode=='host': raise SystemExit(code)
