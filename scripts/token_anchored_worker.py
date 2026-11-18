"""Bounded warm-campaign worker over the existing original-task executor and transport.

No scheduler, retry or scientific defaults live here. The pinned protocol owns each task and
model transition; the coordinator can install only the sole sealed predecessor residual.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import sys
import time

import robust_token_campaign as common
import robust_token_worker as execution
import token_anchored_plan as protocol_owner
from token_anchored_recipe import RECIPE
from token_paired_identity import ACTOR_PARAMETERS


PROFILE_KIND = 'token-anchored-host-1'


def environment():
    import os
    return {key: value for key, value in os.environ.items()
        if key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}


def plan_for(profile):
    item = profile['protocol']
    common.require(common.sha(item['path']) == item['sha256'] and
        profile['pins'].get(item['path']) == item['sha256'], 'The executing warm protocol must be a frozen host input.')
    plan = protocol_owner.Plan.from_record(common.read(item['path']), raw_sha256=item['sha256'])
    common.require(profile['nativeJar']['sha256'] == plan.record['nativeJarSha256'] and
        profile['anchor']['sha256'] == plan.record['anchorDescriptorSha256'] and
        profile['originalActor']['sha256'] == plan.record['originalActorSha256'],
        'Warm host runtime, frozen anchor and zero residual must match the original protocol.')
    return plan


def current_actor(profile, plan, round_number):
    if round_number == 1:
        return profile['originalActor']
    directory = Path(profile['root'])/'models'/f'round-{round_number-1}'
    done = common.read(directory/'finished.json')
    common.require(done['kind'] == 'token-anchored-paired-fit-complete-1' and done['complete'] is True and
        done['identity']['recipe'] == RECIPE and done['identity']['context']['round'] == round_number-1 and
        done['identity']['context']['anchor_sha256'] == plan.record['anchorDescriptorSha256'] and
        done['identity']['context']['original_model_sha256'] == plan.record['originalActorSha256'] and
        done['outputModelSha256'] == common.sha(directory/'policy.json'),
        'A later warm round must consume only its exact sealed predecessor residual.')
    return dict(path=str(directory/'policy.json'), sha256=done['outputModelSha256'])


def validate_source(profile, request):
    common.require(request['kind'] == 'token-anchored-worker-task-1', 'Unknown warm worker request.')
    plan = plan_for(profile); round_number = request['round']; chunk = request['chunk']
    common.require(type(round_number) is int and 1 <= round_number <= len(plan.record['rounds']) and
        plan.owner(chunk) == profile['host'], 'A warm task must belong to this declared round and original host.')
    expected = plan.collection_source(round_number, chunk, profile, current_actor(profile, plan, round_number))
    common.require(request['source'] == expected, 'The original warm native source cannot change at dispatch.')
    operation = request['operation']
    expected_id = f'round-{round_number}/chunk-{chunk}/collect'
    if operation == 'reproduce':
        index = request['index']; start = expected['globalStart']
        common.require(index in expected['replayIndices'] and start <= index < start+expected['count'],
            'A warm reproduction must be prescribed and owned by this original native chunk.')
        expected_id = f'round-{round_number}/chunk-{chunk}/replay-{index}'
        original = Path(profile['root'])/'tasks'/f'round-{round_number}'/f'chunk-{chunk}'/'collect'
        common.require((original/'source.json').read_bytes() == common.canonical(expected) and
            common.read(original/'native/finished.json')['complete'] is True,
            'The prescribed reproduction requires its complete byte-exact original collection.')
    else:
        common.require(operation == 'collect', 'Only declared collection and reproduction operations exist.')
    common.require(request['taskId'] == expected_id, 'A warm task ID must identify its exact operation and source.')


def command(profile, request, source, output, remaining):
    result = [profile['java'], '-Xmx'+str(profile['heapMiB'])+'m', *execution.java_options(profile),
        '-cp', profile['classpath'], 'dev.aragos.aor.training.TrainingMainKt', '--mode',
        'token-anchored-paired-collect' if request['operation'] == 'collect' else 'token-anchored-paired-reproduce',
        '--source', str(source), '--output', str(output), '--seconds', str(min(21600, remaining))]
    if request['operation'] == 'collect':
        return result+['--workers', str(profile['workers'])]
    original = Path(profile['root'])/'tasks'/f'round-{request["round"]}'/f'chunk-{request["chunk"]}'/'collect/native'
    return result+['--original', str(original), '--index', str(request['index'])]


def validate_completion(value, request):
    if request['operation'] == 'collect':
        common.require(value['kind'] == 'token-anchored-paired-finished-1' and value['complete'] is True and
            value['round'] == request['round'] and value['range'] == dict(
                globalStart=request['source']['globalStart'], count=request['source']['count']),
            'Only the complete original warm collection may be transported.')
    else:
        common.require(value['kind'] == 'token-anchored-paired-reproduction-1' and
            value['globalIndex'] == request['index'] and value['fullBackboneEqual'] is True and
            value['publicRecordsEqual'] is True and value['terminalEqual'] is True and
            value['scientificTerminalGames'] == 1 and value['tailGames'] == 0,
            'Only the complete prescribed exact warm backbone reproduction may be transported.')


def execute(profile_path, request):
    filename = 'finished.json' if request['operation'] == 'collect' else f'replay-{request["index"]}.json'
    return execution.execute(profile_path, request, profile_kind=PROFILE_KIND,
        source_validator=validate_source, native_environment=environment, command_factory=command,
        completion_name=filename, completion_validator=validate_completion)


def install_model(profile_path, request, *, now=time.time):
    common.require(common.sha(profile_path) == request['profileSha256'], 'Model installation requires the pinned host.')
    profile = common.read(profile_path); plan = plan_for(profile)
    common.require(profile['kind'] == PROFILE_KIND and request['kind'] == 'token-anchored-install-model-1' and
        request['clock']['deadlineEpoch'] > now() and (Path(profile['root'])/'clock.json').read_bytes() ==
        common.canonical(request['clock']), 'Only an active original warm campaign may install its next residual.')
    common.require(all(common.sha(path) == digest for path, digest in profile['pins'].items()),
        'Every static warm source/runtime/model input must remain byte-exact during installation.')
    number = request['round']
    common.require(type(number) is int and 1 <= number < len(plan.record['rounds']) and
        set(request['files']) == {'policy.json', 'finished.json'},
        'A model publication contains only the sole predecessor policy and sealed completion.')
    raw = {name: base64.b64decode(item['base64'], validate=True) for name, item in request['files'].items()}
    common.require(sum(map(len, raw.values())) <= 16*1024**2 and all(hashlib.sha256(value).hexdigest() ==
        request['files'][name]['sha256'] for name, value in raw.items()), 'Warm model transport changed or exceeded16MiB.')
    done = json.loads(raw['finished.json']); model = json.loads(raw['policy.json'])
    row = plan.record['rounds'][number-1]; context = done['identity']['context']
    preceding = current_actor(profile, plan, number)
    common.require(done['kind'] == 'token-anchored-paired-fit-complete-1' and done['complete'] is True and
        done['identity']['recipe'] == RECIPE and done['identity']['context']['round'] == number and
        done['identity']['context']['anchor_sha256'] == plan.record['anchorDescriptorSha256'] and
        done['identity']['context']['original_model_sha256'] == plan.record['originalActorSha256'] and
        context['current_model_sha256'] == preceding['sha256'] and
        context['request_bank_sha256'] == protocol_owner.shared.digest(row['origins']) and
        context['train_count'] == row['trainCount'] and context['validation_count'] == row['validationCount'] and
        context['probability_contract_sha256'] == protocol_owner.shared.digest(plan.record['probabilityContract']) and
        done['outputModelSha256'] == request['files']['policy.json']['sha256'] and
        done['qualificationClaimed'] is False and done['activated'] is False,
        'Only the exact sealed pure-win fit may supply the next warm residual.')
    common.require((done['identity']['predecessor'] is None if number == 1 else
        done['identity']['predecessor']['finishedSha256'] == common.sha(
            Path(profile['root'])/'models'/f'round-{number-1}'/'finished.json')),
        'An installed warm residual must retain its exact sole completed predecessor lineage.')
    original = common.read(profile['originalActor']['path'])
    common.require({key: value for key, value in model.items() if key not in ACTOR_PARAMETERS} ==
        {key: value for key, value in original.items() if key not in ACTOR_PARAMETERS},
        'Installing a warm residual may change only the five actor arrays, never dictionary, values or metadata.')
    directory = Path(profile['root'])/'models'/f'round-{number}'
    common.require(not directory.exists(), 'A generated model publication is once-only and cannot overwrite an attempt.')
    directory.mkdir(parents=True)
    for name, value in raw.items():
        with (directory/name).open('xb') as stream:
            stream.write(value)
    result = dict(kind='token-anchored-model-installed-1', complete=True, round=number,
        profileSha256=request['profileSha256'], files={name: item['sha256'] for name, item in request['files'].items()},
        installedEpoch=now(), originalDeadlineEpoch=request['clock']['deadlineEpoch'])
    common.write(directory/'installed.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', type=Path, required=True)
    parser.add_argument('--cancel-active', action='store_true'); parser.add_argument('--inspect', action='store_true')
    args = parser.parse_args()
    if args.cancel_active:
        result = dict(stoppedNativeGroups=execution.cancel_active(args.profile))
    elif args.inspect:
        result = execution.inspect(args.profile)
    else:
        request = json.load(sys.stdin)
        result = install_model(args.profile, request) if request.get('kind') == 'token-anchored-install-model-1' \
            else execute(args.profile, request)
    print(json.dumps(result, separators=(',', ':'), allow_nan=False))


if __name__ == '__main__':
    main()
