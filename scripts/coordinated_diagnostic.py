"""One fixed four-head factorial diagnostic; lossless evidence, no training or selection."""
import argparse
import hashlib
from itertools import combinations
import json
import math
import os
from pathlib import Path
import sys
import time

import automata_policy_search as arena_math
import coordinated_diagnostic_plan as p
import coordinated_qualification as origin
import expansion_training_evidence as terminals
import purchase_storage as durable
import token_paired_handoff as transport
import token_paired_process as process
import token_paired_screen as screen
import token_paired_storage as storage
from token_paired_campaign_audit import Pins, decode, stream_archive

PREFIX = p.PREFIX
Clock = p.Clock
STAGES = ()
KIND = p.KIND
resolve = origin.resolve
relative = origin.relative
copied = origin.copied
origin_proof = origin.origin_proof


def contract(): return sys.modules[__name__]


class PeerStopped(Exception):
    def __init__(self, response):
        super().__init__('This diagnostic has no result-dependent stop or successor; peer stopped unexpectedly.')


def tag(case_id, chunk, screen=False):
    p.case(case_id)
    p.require(not screen and p.owner(chunk) == 'MAC', 'Only fixed Mac-owned diagnostic shards use the mailbox.')
    return f'{case_id}-chunk-{chunk}'


def allowed_tag(value):
    p.require(value in {tag(item.identity, chunk) for item in p.CASES for chunk in p.CHUNKS['MAC']},
        'The diagnostic mailbox has exactly32 immutable Mac range slots.')
    return value


def receipt_tag(record): return tag(record['caseId'], record['chunk'])
def terminal(root): return transport.terminal(root, contract=contract())
def health(root): return transport.health(root, contract=contract())
def worker_alive(owner): return transport.worker_alive(owner, contract=contract())
def notify_failure(root, name): return transport.notify_failure(root, name, contract=contract())
def reserve(root, name): return transport.reserve(root, name, contract=contract())
def accept(root, name): return transport.accept(root, name, contract=contract())


def stage(root, name):
    raise ValueError('A diagnostic cannot publish or await a selected policy stage.')


def live(profile): process.live(profile, mailbox=contract())


def interval(values):
    """Every estimand has exactly512 values, already averaged within independent world."""
    p.require(len(values) == 512 and all(type(value) in (int, float) and math.isfinite(value) for value in values),
        'Diagnostic intervals use exactly512 finite independent-world values.')
    mean = math.fsum(values)/512
    error = math.sqrt(math.fsum((value-mean)**2 for value in values)/(511*512))
    return dict(count=512, mean=mean, standardError=error, lower95=mean-1.96*error, upper95=mean+1.96*error)


def controlled(game, seat):
    own = next(row for row in game['scores'] if row['player'] == f'p{seat+1}')
    return dict(wins=game['wins'][seat], rewards=game['rewards'][seat], ranks=game['ranks'][seat],
        chaos=int(own['total'] is None))


def validate_world(plan, selected, world, index, science):
    p.require(world['kind'] == KIND and world['scienceSha256'] == science and set(world['roles']) == set(p.ROLES),
        'Each diagnostic world requires exactly its two complementary roles.')
    arena_math.validate_pair(dict(world, candidate=world['roles']['CANDIDATE'], incumbent=world['roles']['INCUMBENT']),
        index, plan.record['bank']['seeds'][index], 4 if selected.ecology == 'MIXED' else 1)
    for game in world['roles'].values():
        p.require(all(0 <= value <= 1 for name in ('wins', 'rewards', 'ranks') for value in game[name]),
            'All recorded terminal metrics retain normalized authoritative units.')
        for seat in range(4):
            terminals.terminal_reward(dict(controlledSeat=seat, terminal=dict(scores=game['scores'],
                winShare=game['wins'][seat], rankReward=game['ranks'][seat], reward=game['rewards'][seat],
                gameDecisions=game['decisions'])))


def mask_bindings(selected, original, final):
    """Descriptor metadata only: raw actor tensors never enter this pure evidence owner."""
    for value in (original, final):
        p.require(set(value) == {'kind', 'heads'} and value['kind'] == 'coordinated-es-bundle-1' and
            set(value['heads']) == set(p.HEADS), 'Every diagnostic mask retains its complete original/final descriptors.')
        for row in value['heads'].values():
            p.require(set(row) == {'path', 'sha256', 'dictionarySha256'} and isinstance(row['path'], str),
                'A head descriptor retains raw bytes and its complete dictionary identity.')
            p.sha(row['sha256']); p.sha(row['dictionarySha256'])
    p.require(all(original['heads'][head]['dictionarySha256'] == final['heads'][head]['dictionarySha256'] for head in p.HEADS),
        'A diagnostic head cannot change the original dictionary.')
    return {role: {head: {key: (final if mask & bit else original)['heads'][head][key]
        for key in ('sha256', 'dictionarySha256')} for head, bit in p.HEADS.items()}
        for role, mask in selected.masks.items()}


def chunk_evidence(plan, selected, chunk, files, values, expected_source=None):
    """One original native host range, with role-specific reproductions and selected-byte proof."""
    manifest = values['manifest.json']; finished = values['finished.json']; start, count = p.RANGES[chunk]
    snapshots = dict(source='study-source.json', protocol='protocol.json', originArena='origin-arena.json',
        originProof='origin-proof.json', arena='arena-source.json', admission='admission.json',
        historicalAdmission='historical-admission.json', originalBundle='original-bundle.json',
        finalBundle='final-bundle.json', calibration='calibration.json')
    owned = selected.replays(chunk); index_set = list(p.indices(chunk))
    expected_files = {'manifest.json', 'started.json', 'finished.json', *snapshots.values(),
        *(f'world-{index}.json.gz' for index in index_set),
        *(f'{kind}-{index}-{role}.json' for index, role in owned for kind in ('canonical', 'replay'))}
    p.require(set(files) == expected_files and all(files[name]['sha256'] == manifest[key]['sha256'] and
        manifest[key]['snapshot'] == name for key, name in snapshots.items()),
        'The exact range inventory must retain all snapshots, worlds and only prescribed role reproductions.')
    source = values['study-source.json']; protocol = values['protocol.json']; started = values['started.json']
    subjects = {key: source[key] for key in ('protocol', 'originArena', 'originAdmission', 'originProof', 'arena')}
    p.require(protocol == plan.record and source == plan.source(selected, chunk, subjects) and
        manifest['host'] == p.owner(chunk) and all(source[key]['sha256'] == manifest[field]['sha256'] for key, field in
        (('protocol', 'protocol'), ('originArena', 'originArena'), ('originAdmission', 'admission'),
         ('originProof', 'originProof'), ('arena', 'arena'))) and
        (expected_source is None or manifest['source']['sha256'] == expected_source),
        'Original range source, case, host and old83 provenance cannot be rebound.')
    p.require(started['signature'] == manifest['signature'] and
        started['workers'] == p.host_limits(p.owner(chunk))['workers'] and
        type(started['maximumSeconds']) is int and 1 <= started['maximumSeconds'] <= p.SCIENCE_SECONDS,
        'Native ranges retain the declared worker pool and original bounded allowance.')
    expected = count*2+len(owned)
    p.require(manifest['kind'] == finished['kind'] == KIND and finished['complete'] is True and
        finished['stage'] == 'chunk' and finished['globalStart'] == manifest['globalStart'] == start and
        finished['count'] == manifest['count'] == count and finished['signature'] == manifest['signature'] and
        finished['scienceSha256'] == manifest['scienceSha256'] and finished['scienceIdentity'] == manifest['scienceIdentity'] and
        finished['finalAdmissionSha256'] == manifest['admission']['sha256'] == plan.record['originAdmissionSha256'] and
        finished['historicalAdmissionSha256'] == manifest['historicalAdmission']['sha256'] and
        finished['files'] == {name: row['sha256'] for name, row in files.items() if name != 'finished.json'} and
        finished['reproductions'] == len(owned) and manifest['maximumTerminalExecutions'] == expected and
        finished['scientificTerminalExecutions'] == finished['attemptedGames'] == finished['completedGames'] == expected,
        'Every role/world and fixed reproduction must finish once; attempted and completed executions remain exact.')
    for key, name in (('candidateBundleSha256', 'finalBundle'), ('originalBundleSha256', 'originalBundle'),
            ('calibrationSha256', 'calibration')):
        p.require(manifest[name]['sha256'] == plan.record[key], 'Frozen83 original/final/calibration raw bytes cannot change.')
    expected_bindings = mask_bindings(selected, values['original-bundle.json'], values['final-bundle.json'])
    identity = manifest['scienceIdentity']; role_replays = {role: p.REPLAYS[mask%4] for role, mask in selected.masks.items()}
    p.require(identity['kind'] == KIND and identity['protocolSha256'] == plan.sha256 and
        identity['originAdmissionSha256'] == plan.record['originAdmissionSha256'] and
        (identity['ecology'], identity['support'], identity['pair']) == (selected.ecology, selected.support, selected.pair) and
        identity['diagnosticOnly'] is True and identity['promotionAllowed'] is False and
        identity['roleMasks'] == manifest['roleMasks'] == selected.masks and
        identity['maskBindings'] == manifest['maskBindings'] == expected_bindings and
        all(identity[key] == plan.record[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256')) and
        identity['seeds'] == plan.record['bank']['seeds'] and identity['roles'] == list(p.ROLES) and
        identity['roleReplayIndices'] == role_replays and identity['replayIndices'] == sorted(role_replays.values()) and
        p.digest(identity) == manifest['scienceSha256'],
        'The path-independent science identity must bind every mask, full common bank, context and fixed reproduction.')
    origin_proof(plan, p.owner(chunk), values['origin-proof.json'], identity)
    binding = manifest['policyBindings']['coordinatedEs']; supported = selected.support == 'SUPPORT1'
    calibration = values['calibration.json']
    p.require(calibration['kind'] == 'coordinated-es-calibration-2' and calibration['passed'] is True and
        calibration['complete'] is True and calibration['originalBundleSha256'] == plan.record['originalBundleSha256'] and
        binding['version'] == ('coordinated-support-arena-1' if supported else 'coordinated-es-arena-1') and
        binding['execution'] == 'CALIBRATED' and binding['reference'] == 'complete-joint-token-expansion-composition' and
        binding['originalBundle']['sha256'] == plan.record['originalBundleSha256'] and
        binding['baseline']['sha256'] == plan.record['references']['purchaseF0Sha256'] and
        binding['calibration']['sha256'] == plan.record['calibrationSha256'] and
        set(binding['roles']) == set(p.ROLES) and
        all(binding['roles'][role]['heads'] == expected_bindings[role] for role in p.ROLES) and
        binding['selectors'] == {head: dict(kind='incumbent-residual-margin-1', margin=1., scale=calibration['scales'][head])
            for head in ('EXPANSION', 'TOKEN')} and
        ((binding.get('supportProfile') == 'known-own-chaos-support-1' and binding.get('supportRoles') == 'ALL_SEATS_CP')
            if supported else ('supportProfile' not in binding and 'supportRoles' not in binding)),
        'All masks preserve original subtraction anchors, sealed selectors, priors and exact all-seat support composition.')
    def metadata(node):
        if isinstance(node, dict):
            if isinstance(node.get('path'), str) and isinstance(node.get('sha256'), str):
                p.require(manifest['inputPins'].get(node['path']) == node['sha256'],
                    'Every typed policy subject must belong to the immutable native input closure.')
            for value in node.values(): metadata(value)
        elif isinstance(node, list):
            for value in node: metadata(value)
    metadata(manifest['policyBindings'])
    worlds = {}; replays = {}; canonicals = {}; retained = {}
    for name, value in values.items(): screen.parse_value(name, value, worlds, replays, canonicals, retained)
    p.require(sorted(worlds) == index_set and [row['index'] for row in finished['worlds']] == index_set,
        'A host range must retain all and only its original global worlds.')
    for index, row in zip(index_set, finished['worlds']):
        p.require(row['file'] == f'world-{index}.json.gz' and files[row['file']]['sha256'] == row['sha256'] and
            worlds[index]['signature'] == manifest['signature'], 'Original native world bytes and source signature must agree.')
        validate_world(plan, selected, worlds[index], index, manifest['scienceSha256'])
    p.require(set(replays) == set(canonicals) == set(owned) and
        manifest['ownedReplayIndices'] == sorted(index for index, _ in owned) and
        manifest['ownedRoleReplays'] == [dict(index=index, role=role) for index, role in sorted(owned)],
        'Each mask/context receives only its single prescribed role-specific reproduction.')
    for (index, role), replay in replays.items():
        world = worlds[index]
        p.require(replay['replayVerified'] is True and all(replay[key] == world[key] for key in
            ('index', 'seed', 'seat', 'configuration', 'opponentIndices')) and replay['side'] == role and
            arena_math.same_game(replay['game'], world['roles'][role]) and screen.canonical(replay) == canonicals[index, role] and
            replay['coordinatedEs'] == binding, 'Each role reproduces its exact original terminal, history and mask composition.')
    return manifest, finished, [worlds[index] for index in index_set]


def wanted(files):
    return {name for name in files if name.startswith(('world-', 'canonical-', 'replay-')) or name in
        ('manifest.json', 'finished.json', 'started.json', 'study-source.json', 'protocol.json', 'origin-proof.json',
         'original-bundle.json', 'final-bundle.json', 'calibration.json')}


def archived(plan, item, pins):
    path = pins.subject(item); receipt = pins.read(path)
    selected = p.case(receipt['caseId']); chunk = receipt['chunk']; count = len(p.indices(chunk))
    p.require(receipt['kind'] == PREFIX+'-screen-chunk-receipt-1' and receipt['host'] == p.owner(chunk) and
        receipt['protocolSha256'] == plan.sha256, 'An archive has one exact diagnostic case and host range.')
    values = stream_archive(path.with_name(receipt['archive']), receipt, pins, wanted(receipt['files']))
    manifest, finished, worlds = chunk_evidence(plan, selected, chunk, receipt['files'], values, receipt['sourceSha256'])
    raw = receipt['completionJson'].encode('utf-8')
    p.require(hashlib.sha256(raw).hexdigest() == receipt['files']['finished.json']['sha256'] and decode(raw) == finished and
        receipt['scienceSha256'] == manifest['scienceSha256'] and receipt['pairedWorldRecords'] == count and
        receipt['games'] == 2*count and receipt['reproductions'] == len(selected.replays(chunk)) and
        receipt['scientificTerminalExecutions'] == 2*count+receipt['reproductions'],
        'Wrapper counts and compact completion must equal exact archived native bytes.')
    return receipt, finished, worlds, manifest


def aggregate(plan, cases):
    """Fixed factorial contrasts; all64 cells and all32 cases precede any effects report."""
    p.require(set(cases) == {item.identity for item in p.CASES}, 'A partial diagnostic cannot publish effects.')
    cells = {}
    for selected in p.CASES:
        worlds = cases[selected.identity]
        p.require([row['index'] for row in worlds] == list(range(512)),
            'Every context/pair requires all512 original world IDs without replacement.')
        for role, mask in selected.masks.items():
            values = [controlled(world['roles'][role], world['seat']) for world in worlds]
            cells[selected.ecology, selected.support, mask] = {
                metric: [row[metric] for row in values] for metric in p.METRICS}
    def difference(first, second): return {metric: [a-b for a, b in zip(first[metric], second[metric])]
        for metric in p.METRICS}
    def statistics(values): return {metric: interval(values[metric]) for metric in p.METRICS}
    cell_rows = []; zero_rows = []; effect_rows = []; interactions = []; effects = {}
    for ecology in p.ECOLOGIES:
        for support in p.SUPPORTS:
            for mask in range(16):
                identity = dict(ecology=ecology, support=support, mask=mask)
                cell_rows.append(dict(identity, metrics=statistics(cells[ecology, support, mask])))
                zero_rows.append(dict(identity, referenceMask=0,
                    metrics=statistics(difference(cells[ecology, support, mask], cells[ecology, support, 0]))))
            for head, bit in p.HEADS.items():
                backgrounds = [mask for mask in range(16) if not mask & bit]
                values = {metric: [math.fsum(cells[ecology, support, mask|bit][metric][index]-
                    cells[ecology, support, mask][metric][index] for mask in backgrounds)/8
                    for index in range(512)] for metric in p.METRICS}
                effects[ecology, support, head] = values
                effect_rows.append(dict(ecology=ecology, support=support, head=head, backgroundMasks=backgrounds,
                    withinWorldBackgroundCount=8, metrics=statistics(values)))
            for first, second in combinations(p.HEADS, 2):
                a, b = p.HEADS[first], p.HEADS[second]
                backgrounds = [mask for mask in range(16) if not mask & (a|b)]
                values = {metric: [math.fsum(cells[ecology, support, mask|a|b][metric][index]-
                    cells[ecology, support, mask|a][metric][index]-cells[ecology, support, mask|b][metric][index]+
                    cells[ecology, support, mask][metric][index] for mask in backgrounds)/4
                    for index in range(512)] for metric in p.METRICS}
                interactions.append(dict(ecology=ecology, support=support, heads=[first, second],
                    backgroundMasks=backgrounds, withinWorldBackgroundCount=4, metrics=statistics(values)))
    support_masks = []; support_heads = []; ecology_masks = []; ecology_heads = []
    for ecology in p.ECOLOGIES:
        for mask in range(16):
            support_masks.append(dict(ecology=ecology, mask=mask, contrast='SUPPORT1-minus-RAW',
                metrics=statistics(difference(cells[ecology, 'SUPPORT1', mask], cells[ecology, 'RAW', mask]))))
        for head in p.HEADS:
            support_heads.append(dict(ecology=ecology, head=head, contrast='SUPPORT1-minus-RAW',
                metrics=statistics(difference(effects[ecology, 'SUPPORT1', head], effects[ecology, 'RAW', head]))))
    for support in p.SUPPORTS:
        for mask in range(16):
            ecology_masks.append(dict(support=support, mask=mask, contrast='MIXED-minus-HOMOGENEOUS',
                metrics=statistics(difference(cells['MIXED', support, mask], cells['HOMOGENEOUS', support, mask]))))
        for head in p.HEADS:
            ecology_heads.append(dict(support=support, head=head, contrast='MIXED-minus-HOMOGENEOUS',
                metrics=statistics(difference(effects['MIXED', support, head], effects['HOMOGENEOUS', support, head]))))
    return dict(kind=PREFIX+'-report-1', complete=True, protocolSha256=plan.sha256,
        **{key: plan.record[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256')},
        counts=p.counts(), contexts=4, masks=16, interval=p.INTERVAL, diagnosticOnly=True,
        qualified=False, promotionAllowed=False, nominee=None, cellMeans=cell_rows, maskMinusZero=zero_rows,
        headEffects=effect_rows, headPairInteractions=interactions, supportMaskContrasts=support_masks,
        supportHeadEffectContrasts=support_heads, ecologyMaskContrasts=ecology_masks,
        ecologyHeadEffectContrasts=ecology_heads,
        limitations=['Pointwise diagnostic95 intervals have no multiplicity or qualification claim.',
            'All background contrasts are averaged within world; cells and backgrounds are not independent samples.',
            'Support1 contrasts change C/P support on all seats, not only the controlled policy.',
            'Frozen head-mask contrasts do not identify causal credit from the ES training gradient.',
            'Common streams couple worlds but policy divergence may consume future randomness differently.'])


def check_profile(protocol_path, profile_path):
    plan = p.Plan.from_file(protocol_path); profile = p.read(profile_path)
    p.require(profile['kind'] == PREFIX+'-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256'] == plan.sha256 and
        all(profile[key] == value for key, value in p.host_limits(profile['host']).items()) and
        profile['maximumGameSeconds'] == 300 and profile['maximumGameActions'] == 12000 and
        os.cpu_count() == profile['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS') == str(profile['machineCpus']),
        'The diagnostic must execute under its fixed real host and per-game resource limits.')
    p.require(profile['files'] and all(p.file_sha(path) == digest for path, digest in profile['files'].items()) and
        profile['files'].get('/bin/ps') == p.file_sha('/bin/ps'), 'All frozen inputs, runtime and process owner must remain exact.')
    p.require(profile['originArena']['sha256'] == plan.record['originArenaSha256'][profile['host']] and
        profile['originAdmission']['sha256'] == plan.record['originAdmissionSha256'] and
        profile['originProof']['sha256'] == plan.record['originProofSha256'][profile['host']] and
        set(profile['arenas']) == {item.identity for item in p.CASES},
        'All32 explicit complementary arenas require the exact original83 host admission.')
    for item in (profile['originArena'], profile['originAdmission'], profile['originProof'], *profile['arenas'].values()):
        p.checked_subject(item)
    proof = p.read(p.checked_subject(profile['originProof'])); origin_proof(plan, profile['host'], proof)
    p.require(all(profile['files'].get(path) == digest for path, digest in proof['inputPins'].items()),
        'The complete frozen83 origin closure must be included in the actual host file pins.')
    return plan, profile


def collect(plan, profile, protocol, root, selected, chunk):
    p.require(p.owner(chunk) == profile['host'], 'A fixed diagnostic host range cannot migrate.')
    live(profile)
    directory = Path(root)/'cases'/selected.identity/'chunks'/f'chunk-{chunk}'
    directory.mkdir(parents=True, exist_ok=False)
    source = directory/'source.json'
    storage.publish_json(source, plan.source(selected, chunk, dict(protocol=p.subject(protocol),
        originArena=profile['originArena'], originAdmission=profile['originAdmission'], originProof=profile['originProof'],
        arena=profile['arenas'][selected.identity])))
    output = directory/'native'
    seconds = max(1, min(p.SCIENCE_SECONDS, int(profile['_clock'].remaining(time.time()))))
    command = [profile['java']['executable'], f'-Xmx{profile["heapMiB"]}m',
        f'-XX:ActiveProcessorCount={profile["machineCpus"]}', '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1',
        '-cp', str(Path(profile['runtime'])/'lib/*'), 'dev.aragos.aor.training.TrainingMainKt',
        '--mode', PREFIX+'-screen-chunk', '--source', str(source), '--output', str(output),
        '--seconds', str(seconds), '--workers', str(profile['workers'])]
    process.run_child(profile, command, directory/'native.log', mailbox=contract())
    files = storage.inventory(output); values = {}
    for name in wanted(files):
        raw = (output/name).read_bytes()
        p.require(hashlib.sha256(raw).hexdigest() == files[name]['sha256'], 'Native member bytes changed before decoding.')
        values[name] = decode(__import__('gzip').decompress(raw) if name.endswith('.json.gz') else raw)
    manifest, finished, _ = chunk_evidence(plan, selected, chunk, files, values, p.file_sha(source))
    count = len(p.indices(chunk))
    receipt = dict(kind=PREFIX+'-screen-chunk-receipt-1', protocolSha256=plan.sha256, caseId=selected.identity,
        chunk=chunk, host=profile['host'], sourceSha256=p.file_sha(source), files=files,
        completionJson=(output/'finished.json').read_text(encoding='utf-8'), scienceSha256=manifest['scienceSha256'],
        pairedWorldRecords=count, games=2*count, reproductions=finished['reproductions'],
        scientificTerminalExecutions=finished['scientificTerminalExecutions'])
    archive = directory/'evidence.tar.gz'; storage.archive_collection(output, archive, receipt)
    return p.subject(archive.with_name(archive.name+'.json'))


def wait_receipts(profile, root, selected, local):
    result = list(local)
    for chunk in p.CHUNKS['MAC']:
        directory = Path(root)/'incoming'/tag(selected.identity, chunk)
        while not (directory/'accepted.json').exists():
            live(profile); time.sleep(min(10, profile['_clock'].remaining(time.time())))
        record = p.read(directory/'accepted.json')
        p.require(record['kind'] == PREFIX+'-handoff-1' and record['tag'] == tag(selected.identity, chunk) and
            p.checked_subject(record['receipt']).resolve() == (directory/'receipt.json').resolve() and
            p.checked_subject(record['archive']).resolve() == (directory/'evidence.tar.gz').resolve(),
            'The received range must retain its original immutable mailbox slot.')
        result.append(record['receipt'])
    return result


def complete_case(plan, profile, root, selected, receipts):
    p.require(profile['host'] == 'DESKTOP' and len(receipts) == 2,
        'A complete case requires both original host ranges, without duplicate controls.')
    pins = Pins(profile['_clock'].deadline); worlds = []; sciences = set()
    ordered = sorted(receipts, key=lambda item: p.read(p.checked_subject(item))['chunk'])
    for chunk, item in enumerate(ordered):
        receipt, _, rows, _ = archived(plan, item, pins)
        p.require(receipt['chunk'] == chunk and receipt['caseId'] == selected.identity,
            'Complete cases require their two distinct declared host ranges in global order.')
        worlds.extend(rows); sciences.add(receipt['scienceSha256'])
    p.require(len(sciences) == 1 and [row['index'] for row in worlds] == list(range(512)),
        'Both host ranges must share exact scientific identity and original512-world order.')
    directory = Path(root)/'cases'/selected.identity
    compact = storage.publish_json(directory/'compact.json', dict(kind=PREFIX+'-case-compact-1',
        caseId=selected.identity, scienceSha256=next(iter(sciences)), worlds=worlds))
    owner = directory/'complete.json'
    record = dict(kind=PREFIX+'-case-evidence-1', complete=True, caseId=selected.identity,
        protocolSha256=plan.sha256, scienceSha256=next(iter(sciences)), pairedWorldRecords=512,
        games=1024, reproductions=2, scientificTerminalExecutions=1026, chunks=ordered,
        compact=relative(compact['path'], owner))
    pins.unchanged()
    return storage.publish_json(owner, record)


def complete_report(plan, root, cases, deadline):
    p.require(set(cases) == {item.identity for item in p.CASES}, 'All32 cases must complete before any effects report.')
    rows = {}; pins = Pins(deadline)
    for selected in p.CASES:
        owner = pins.subject(cases[selected.identity]); record = pins.read(owner)
        p.require(record['caseId'] == selected.identity and record['complete'] is True and record['protocolSha256'] == plan.sha256,
            'The final diagnostic must consume the same32 completed original cases.')
        compact = pins.read(resolve(record['compact'], owner, pins))
        p.require(compact['caseId'] == selected.identity and compact['scienceSha256'] == record['scienceSha256'],
            'The final aggregate cannot swap original paired outcome cells.')
        rows[selected.identity] = compact['worlds']
    report = aggregate(plan, rows)
    pins.unchanged()
    return storage.publish_json(Path(root)/'report.json', report)


def worker(protocol, profile_path, output, started, deadline):
    root = Path(output); root.mkdir(parents=True, exist_ok=False)
    clock = p.Clock(started, deadline); clock.remaining(time.time())
    record = dict(kind=PREFIX+'-clock-1', protocolSha256=p.file_sha(protocol), startedEpoch=started, deadlineEpoch=deadline)
    storage.publish_json(root/'worker-owner.json', dict(kind=PREFIX+'-worker-owner-1', pid=os.getpid(), processGroup=os.getpgrp()))
    storage.publish_json(root/'clock.json', record)
    status = dict(kind=PREFIX+'-terminal-1', host=None, clock=record, completedCases=0, cases={}, report=None,
        diagnosticOnly=True, qualified=False, promotionAllowed=False, nominee=None)
    profile = None
    try:
        plan, profile = check_profile(protocol, profile_path); status['host'] = profile['host']
        storage.publish_json(root/'inputs.json', dict(protocol=p.subject(protocol), profile=p.subject(profile_path)))
        p.require(all(float(os.environ[name]) == deadline for name in
            ('AOR_CAMPAIGN_DEADLINE_EPOCH', 'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH')),
            'The diagnostic worker retains its inherited controller deadline without renewal.')
        profile = dict(profile, _root=root, _clock=clock)
        if profile['host'] == 'MAC':
            profile['_peer'] = transport.Outbound(profile['desktop'], clock, root, contract=contract())
            profile['_peer'].await_active(plan.sha256)
        for selected in p.CASES:
            receipts = []
            for chunk in p.CHUNKS[profile['host']]:
                item = collect(plan, profile, protocol, root, selected, chunk); receipts.append(item)
                if profile['host'] == 'MAC':
                    path = Path(item['path'])
                    handed = profile['_peer'].push(path.with_name('evidence.tar.gz'), path, selected.identity, chunk)
                    storage.publish_json(path.parent/'handoff.json', handed)
            if profile['host'] == 'DESKTOP':
                status['cases'][selected.identity] = complete_case(plan, profile, root, selected,
                    wait_receipts(profile, root, selected, receipts))
            status['completedCases'] += 1
        if profile['host'] == 'DESKTOP':
            live(profile)
            status['report'] = complete_report(plan, root, status['cases'], deadline)
        clock.remaining(time.time())
        status.update(status='completed', reason='fixed-diagnostic-complete')
    except BaseException as error:
        status.update(status='failed', reason=type(error).__name__, failure=str(error))
        if profile is not None and profile.get('_peer') is not None:
            try: profile['_peer'].remote('fail', 'MAC')
            except Exception as notification: status['peerNotificationFailure'] = type(notification).__name__
        raise
    finally:
        status['updatedEpoch'] = time.time()
        storage.publish_json(root/'terminal.json', status)
    return status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    work = sub.add_parser('worker')
    for name in ('protocol', 'profile', 'output'): work.add_argument('--'+name, type=Path, required=True)
    for name in ('started-epoch', 'deadline-epoch'): work.add_argument('--'+name, type=float, required=True)
    for name in ('reserve', 'accept', 'health', 'fail'):
        command = sub.add_parser(name); command.add_argument('--root', type=Path, required=True); command.add_argument('--name', required=True)
    args = parser.parse_args()
    result = worker(args.protocol, args.profile, args.output, args.started_epoch, args.deadline_epoch) if args.mode == 'worker' else \
        transport.serve(args.mode, args.root, args.name, contract=contract())
    print(json.dumps(dict(status=result.get('status'), completedCases=result.get('completedCases')) if args.mode == 'worker' else result))


if __name__ == '__main__': main()
