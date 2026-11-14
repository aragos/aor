"""Two finite paired99 banks, lossless native shards and one outbound Mac mailbox.

This adapter never trains, selects another candidate or renews the shared clock. The existing
campaign controller owns resource enforcement and descendants. Native owns actual simulation;
the pure functions below also serve the read-only terminal auditor.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import time

import automata_policy_search as arena_math
import coordinated_qualification_plan as p
import expansion_on_policy_loop as native
import expansion_training_evidence as terminals
import purchase_storage as durable
import token_paired_handoff as transport
import token_paired_process as process
import token_paired_screen as screen
import token_paired_storage as storage
from token_paired_campaign_audit import Pins, decode, stream_archive

PREFIX = p.PREFIX
Clock = p.Clock
STAGES = ('homogeneous',)
MAXIMUM_PUBLICATION_BYTES = 1024**3
KIND = PREFIX+'-screen-1'


def contract():
    return sys.modules[__name__]


class PeerStopped(Exception):
    def __init__(self, response):
        value = response['terminal']
        p.require(value['kind'] == PREFIX+'-terminal-1' and value['host'] == 'DESKTOP' and
            value['status'] == 'stopped' and value['reason'] == 'mixed-confirmation-failed' and
            value['completedBanks'] == 1 and value['qualified'] is False,
            'Only the fixed completed mixed-bank refusal may propagate.')
        self.response = response
        super().__init__('The completed mixed bank did not open homogeneous qualification.')


def tag(bank, chunk, screen=False):
    p.bank_name(bank)
    p.require(not screen and p.owner(chunk) == 'MAC', 'Only fixed Mac bank shards use this mailbox.')
    return f'{bank}-chunk-{chunk}'


def allowed_tag(value):
    p.require(value in {tag(bank, chunk) for bank in p.BANKS for chunk in p.CHUNKS['MAC']},
        'The qualification mailbox has exactly24 prospective shard slots.')
    return value


def receipt_tag(record):
    return tag(record['bank'], record['chunk'])


def terminal(root):
    return transport.terminal(root, contract=contract())


def health(root):
    return transport.health(root, contract=contract())


def worker_alive(owner):
    return transport.worker_alive(owner, contract=contract())


def notify_failure(root, name):
    return transport.notify_failure(root, name, contract=contract())


def stage(root, name):
    return transport.stage(root, name, contract=contract())


def reserve(root, name):
    return transport.reserve(root, name, contract=contract())


def accept(root, name):
    return transport.accept(root, name, contract=contract())


def resolve(item, owner, pins):
    p.require(set(item) == {'path', 'sha256'} and isinstance(item['path'], str),
        'A retained subject must have exactly its declared path and SHA256.')
    path = Path(item['path'])
    path = path if path.is_absolute() else Path(owner).parent/path
    pins.pin(path, p.sha(item['sha256']))
    return path.absolute()


def relative(path, owner):
    path = Path(path).resolve()
    return dict(path=os.path.relpath(path, Path(owner).resolve().parent), sha256=p.file_sha(path))


def copied(source, target, expected=None):
    source = Path(source); target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    durable.publish(target, lambda destination: destination.write(source.read_bytes()),
        expected or p.file_sha(source))
    return p.subject(target)


def report_gate(report):
    """Validate the complete paired99 scalar wire without treating it as recomputed evidence."""
    p.require(report['kind'] == PREFIX+'-screen-report-1' and report['worlds'] == 4096 and
        report['confidence'] == .99 and report['intervalMultiplier'] == p.MULTIPLIER and
        report['qualification'] is False and report['activated'] is False and
        set(report['candidateMinusControl']) == {'wins', 'rewards', 'ranks', 'chaos'},
        'Only a complete paired99 bank report may supply a fixed qualification gate.')
    passes = {}
    for metric, row in report['candidateMinusControl'].items():
        p.require(set(row) == {'count', 'mean', 'standardError', 'lower99', 'upper99'} and
            row['count'] == 4096 and all(type(row[key]) in (int, float) and math.isfinite(row[key])
            for key in ('mean', 'standardError', 'lower99', 'upper99')) and row['standardError'] >= 0 and
            row['lower99'] == row['mean']-p.MULTIPLIER*row['standardError'] and
            row['upper99'] == row['mean']+p.MULTIPLIER*row['standardError'],
            'Each native99 interval must retain its exact finite arithmetic and complete count.')
        passes[metric] = row['lower99'] > 0
    expected = dict(wins=passes['wins'], rewards=passes['rewards'],
        passed=passes['wins'] and passes['rewards'])
    p.require(report['gates'] == expected and all(type(value) is bool for value in report['gates'].values()),
        'Both exact lower99 bounds, with strict positivity, own the fixed gate.')
    return expected['passed']


def verify_report(worlds, report):
    p.require([world['index'] for world in worlds] == list(range(4096)),
        'Effects open only after every independent world in the whole fixed bank completes.')
    passed = report_gate(report)
    for metric in ('wins', 'rewards', 'ranks', 'chaos'):
        def value(world, role):
            game = world['roles'][role]; seat = world['seat']
            if metric == 'chaos':
                own = next(score for score in game['scores'] if score['player'] == f'p{seat+1}')
                return int(own['total'] is None)
            return game[metric][seat]
        differences = [value(world, 'CANDIDATE')-value(world, 'CONTROL') for world in worlds]
        mean = math.fsum(differences)/4096
        error = math.sqrt(math.fsum((value-mean)**2 for value in differences)/(4095*4096))
        expected = dict(mean=mean, standardError=error,
            lower99=mean-p.MULTIPLIER*error, upper99=mean+p.MULTIPLIER*error)
        actual = report['candidateMinusControl'][metric]
        p.require(all(math.isclose(actual[key], number, rel_tol=1e-10, abs_tol=1e-12)
            for key, number in expected.items()) and (actual['lower99'] > 0) == (expected['lower99'] > 0),
            'Native99 statistics must reproduce from the original paired independent-world outcomes.')
    return passed


def confirmation(plan, item, pins):
    owner = pins.subject(item); gate = pins.read(owner)
    p.require(set(gate) == {'kind', 'passed', 'complete', 'protocolSha256', 'candidateBundleSha256',
        'bank', 'worlds', 'report', 'evidence'} and gate['kind'] == 'coordinated-support-confirmation-1' and
        gate['passed'] is True and gate['complete'] is True and gate['protocolSha256'] == plan.sha256 and
        gate['candidateBundleSha256'] == p.CANDIDATE and gate['bank'] == 'mixed' and gate['worlds'] == 4096,
        'The heldout bank requires the exact completed mixed confirmation for this sole candidate.')
    report = resolve(gate['report'], owner, pins); evidence = resolve(gate['evidence'], owner, pins)
    record = pins.read(evidence)
    p.require(set(record) == {'kind', 'complete', 'bank', 'protocolSha256', 'candidateBundleSha256',
        'scienceSha256', 'worlds', 'games', 'reproductions', 'scientificTerminalExecutions', 'gatePassed',
        'chunks', 'compact', 'report', 'reportFinished'} and
        record['kind'] == PREFIX+'-bank-evidence-1' and record['complete'] is True and
        record['bank'] == 'mixed' and record['protocolSha256'] == plan.sha256 and
        record['candidateBundleSha256'] == p.CANDIDATE and record['worlds'] == 4096 and
        record['games'] == 8192 and record['reproductions'] == 8 and
        record['scientificTerminalExecutions'] == 8200 and record['gatePassed'] is True and
        len(record['chunks']) == 32 and resolve(record['report'], evidence, pins) == report and
        report_gate(pins.read(report)), 'Mixed confirmation requires exact full-bank evidence and both99 gates.')
    p.sha(record['scienceSha256'])
    return gate


def validate_world(plan, bank, world, index, science):
    p.require(world['kind'] == KIND and world['scienceSha256'] == science and
        set(world['roles']) == set(p.ROLES), 'Qualification worlds retain exactly the two supported roles.')
    pair = dict(world, candidate=world['roles']['CANDIDATE'], incumbent=world['roles']['CONTROL'])
    arena_math.validate_pair(pair, index, plan.record['banks'][bank]['seeds'][index],
        4 if bank == 'mixed' else 1)
    for game in world['roles'].values():
        p.require(all(0 <= value <= 1 for name in ('wins', 'rewards', 'ranks') for value in game[name]),
            'All terminal metrics retain their original normalized units.')
        for seat in range(4):
            terminals.terminal_reward(dict(controlledSeat=seat, terminal=dict(scores=game['scores'],
                winShare=game['wins'][seat], rankReward=game['ranks'][seat], reward=game['rewards'][seat],
                gameDecisions=game['decisions'])))


def chunk_evidence(plan, bank, chunk, files, values, expected_source=None):
    """Pure original-member admission; one128-world shard is the largest decoded archive unit."""
    p.bank_name(bank); p.owner(chunk)
    manifest = values['manifest.json']; finished = values['finished.json']
    snapshots = dict(source='study-source.json', protocol='protocol.json', originArena='origin-arena.json', originProof='origin-proof.json',
        arena='arena-source.json', admission='admission.json', historicalAdmission='historical-admission.json')
    if bank == 'homogeneous': snapshots['confirmation'] = 'confirmation.json'
    p.require({'manifest.json', 'started.json', *snapshots.values()} <= set(files) and
        ('confirmation' in manifest) == (bank == 'homogeneous') and
        all(files[name]['sha256'] == manifest[key]['sha256'] and manifest[key]['snapshot'] == name
            for key, name in snapshots.items()), 'All exact native request and admission snapshots must survive.')
    source = values['study-source.json']; protocol = values['protocol.json']
    started = values['started.json']
    p.require(started['signature'] == manifest['signature'] and
        started['workers'] == p.host_limits(p.owner(chunk))['workers'] and
        type(started['maximumSeconds']) is int and 1 <= started['maximumSeconds'] <= p.SCIENCE_SECONDS,
        'Native execution must retain its fixed host pool and original bounded stage allowance.')
    expected_subjects = dict(protocol=source['protocol'], originArena=source['originArena'], originProof=source['originProof'],
        originAdmission=source['originAdmission'], arena=source['arena'], confirmation=source['confirmation'])
    p.require(protocol == plan.record and source == plan.source(bank, chunk, expected_subjects) and
        all(source[key]['sha256'] == manifest[field]['sha256'] for key, field in
            (('protocol', 'protocol'), ('originArena', 'originArena'), ('originProof', 'originProof'),
             ('originAdmission', 'admission'), ('arena', 'arena'))) and
        (expected_source is None or manifest['source']['sha256'] == expected_source),
        'The original request cannot rebind the origin, protocol or separate evaluation arena.')
    if bank == 'homogeneous':
        p.require(source['confirmation']['sha256'] == manifest['confirmation']['sha256'],
            'The heldout request must consume the exact passing confirmation subject.')
    owned = p.replays(chunk); expected = 256+2*len(owned)
    p.require(manifest['kind'] == finished['kind'] == KIND and finished['complete'] is True and
        finished['stage'] == 'chunk' and finished['globalStart'] == manifest['globalStart'] == chunk*128 and
        finished['count'] == manifest['count'] == 128 and finished['signature'] == manifest['signature'] and
        finished['scienceSha256'] == manifest['scienceSha256'] and
        finished['scienceIdentity'] == manifest['scienceIdentity'] and
        finished['finalAdmissionSha256'] == manifest['admission']['sha256'] == plan.record['originAdmissionSha256'] and
        finished['historicalAdmissionSha256'] == manifest['historicalAdmission']['sha256'] and
        finished['files'] == {name: row['sha256'] for name, row in files.items() if name != 'finished.json'} and
        finished['reproductions'] == 2*len(owned) and manifest['maximumTerminalExecutions'] == expected and
        finished['scientificTerminalExecutions'] == finished['attemptedGames'] == finished['completedGames'] == expected,
        'Every prescribed native world and reproduction must complete exactly once with a full raw inventory.')
    identity = finished['scienceIdentity']
    p.require(identity['kind'] == KIND and identity['protocolSha256'] == plan.sha256 and
        identity['originAdmissionSha256'] == plan.record['originAdmissionSha256'] and
        identity['bankName'] == bank and identity['profile'] == p.PROFILE and identity['supportProfile'] == p.SUPPORT and
        all(identity[key] == plan.record[key] for key in
            ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256')) and
        identity['seeds'] == plan.record['banks'][bank]['seeds'] and identity['roles'] == list(p.ROLES) and
        identity['replayIndices'] == list(p.REPLAYS) and p.digest(identity) == manifest['scienceSha256'],
        'All shards must retain the exact full bank and immutable supported candidate/control identity.')
    origin_proof(plan, p.owner(chunk), values['origin-proof.json'], identity)
    for key, current in (('policyBindings', True), ('controlPolicyBindings', False)):
        binding = manifest[key]['coordinatedEs']
        p.require(binding['version'] == 'coordinated-support-arena-1' and
            binding['supportProfile'] == p.SUPPORT and binding['supportRoles'] == 'ALL_SEATS_CP' and
            binding['originalBundle']['sha256'] == plan.record['originalBundleSha256'] and
            binding['execution'] == ('CALIBRATED' if current else 'ZERO_REFERENCE') and
            (set(binding['roles']) == {'CANDIDATE'} if current else binding['roles'] == {}) and
            ((binding['roles']['CANDIDATE']['sha256'] == p.CANDIDATE and
              binding['calibration']['sha256'] == plan.record['calibrationSha256']) if current else
             'calibration' not in binding), 'Both exact supported roles must retain their original versus final bundle identity.')
    worlds = {}; replays = {}; canonicals = {}; metadata = {}
    for name, value in values.items(): screen.parse_value(name, value, worlds, replays, canonicals, metadata)
    indices = list(p.indices(chunk))
    p.require(sorted(worlds) == indices and [row['index'] for row in finished['worlds']] == indices and
        {name for name in files if name.startswith('world-')} == {f'world-{index}.json.gz' for index in indices},
        'No independent global world may be omitted, duplicated or replaced.')
    for index, row in zip(indices, finished['worlds']):
        p.require(row['file'] == f'world-{index}.json.gz' and files[row['file']]['sha256'] == row['sha256'] and
            worlds[index]['signature'] == manifest['signature'], 'Original world raw hashes and source signatures must match.')
        validate_world(plan, bank, worlds[index], index, manifest['scienceSha256'])
    p.require(set(replays) == set(canonicals) == {(index, role) for index in owned for role in p.ROLES} and
        manifest['ownedReplayIndices'] == list(owned), 'Only the four declared global two-role replays may occur.')
    for (index, role), replay in replays.items():
        world = worlds[index]
        bindings = manifest['policyBindings' if role == 'CANDIDATE' else 'controlPolicyBindings']
        p.require(replay['replayVerified'] is True and all(replay[key] == world[key] for key in
            ('index', 'seed', 'seat', 'configuration', 'opponentIndices')) and replay['side'] == 'CANDIDATE' and
            arena_math.same_game(replay['game'], world['roles'][role]) and
            screen.canonical(replay) == canonicals[index, role] and replay['coordinatedEs'] == bindings['coordinatedEs'],
            'Each supported role must reproduce its exact terminal, canonical history and execution binding.')
    return manifest, finished, [worlds[index] for index in indices]


def wanted(files):
    return {name for name in files if name.startswith(('world-', 'canonical-', 'replay-')) or
        name in ('manifest.json', 'finished.json', 'started.json', 'study-source.json', 'protocol.json', 'origin-proof.json')}


def origin_proof(plan, host, proof, identity=None):
    """The root-pinned old-runtime receipt is explicit provenance, never a reinterpreted83 hash."""
    subjects = ('protocol', 'originalBundle', 'projection', 'calibration', 'center', 'finalBundle',
        'centerMaterialization', 'generationAudit', 'historical')
    p.require(proof['kind'] == PREFIX+'-origin-proof-1' and proof['host'] == host and
        proof['passed'] is True and proof['complete'] is True and
        proof['admission']['sha256'] == plan.record['originAdmissionSha256'] and
        proof['arena']['sha256'] == plan.record['originArenaSha256'][host] and
        proof['execution'] == dict(newGames=0, policyCalls=0, actualFits=0) and
        len(proof['runtime']) == 9 and all(Path(name).name == name and name.endswith('.jar') for name in proof['runtime']) and
        set(proof['subjects']) == set(subjects) and proof['inputPins'] and
        all(proof['subjects'][key]['sha256'] == plan.record[field] for key, field in
            (('originalBundle', 'originalBundleSha256'), ('finalBundle', 'candidateBundleSha256'), ('calibration', 'calibrationSha256'))),
        'Each host must retain its explicit zero-game frozen83-runtime proof and complete unchanged admission closure.')
    for row in (proof['admission'], proof['arena'], *proof['runtime'].values(), *proof['subjects'].values()):
        p.require(set(row) == {'path', 'sha256'} and Path(row['path']).is_absolute(),
            'The old-runtime proof retains actual absolute host-local subjects without relocation.')
        p.sha(row['sha256'])
        p.require(proof['inputPins'].get(row['path']) == row['sha256'],
            'Every named old-runtime proof subject must belong to its explicit immutable input closure.')
    for path, digest in proof['inputPins'].items():
        p.require(Path(path).is_absolute(), 'Original proof input pins retain absolute local paths.'); p.sha(digest)
    for key in ('scopeSha256', 'ecologySha256'): p.sha(proof[key])
    if identity is not None:
        p.require(identity['originScopeSha256'] == proof['scopeSha256'] and
            identity['originEcologySha256'] == proof['ecologySha256'],
            'New supported ecology must preserve the separately verified original83 scope/ecology identity.')


def archived(plan, item, pins):
    path = pins.subject(item); receipt = pins.read(path)
    bank = p.bank_name(receipt['bank']); chunk = receipt['chunk']; p.owner(chunk)
    p.require(receipt['kind'] == PREFIX+'-screen-chunk-receipt-1' and receipt['host'] == p.owner(chunk) and
        receipt['protocolSha256'] == plan.sha256, 'Each immutable archive has one fixed bank, host and global shard.')
    values = stream_archive(path.with_name(receipt['archive']), receipt, pins, wanted(receipt['files']))
    manifest, finished, worlds = chunk_evidence(plan, bank, chunk, receipt['files'], values, receipt['sourceSha256'])
    raw = receipt['completionJson'].encode('utf-8')
    p.require(hashlib.sha256(raw).hexdigest() == receipt['files']['finished.json']['sha256'] and
        decode(raw) == finished and receipt['scienceSha256'] == manifest['scienceSha256'] and
        receipt['worlds'] == 128 and receipt['games'] == 256 and receipt['reproductions'] == 2*len(p.replays(chunk)) and
        receipt['scientificTerminalExecutions'] == 256+receipt['reproductions'],
        'Compact completion copies and wrapper counts must match their exact native archive members.')
    return receipt, finished, worlds, manifest


def check_profile(protocol_path, profile_path):
    plan = p.Plan.from_file(protocol_path); profile = p.read(profile_path)
    p.require(profile['kind'] == PREFIX+'-host-1' and profile['host'] in p.HOSTS and
        profile['protocolSha256'] == plan.sha256 and
        all(profile[key] == value for key, value in p.host_limits(profile['host']).items()) and
        profile['maximumGameSeconds'] == 300 and profile['maximumGameActions'] == 12000 and
        os.cpu_count() == profile['machineCpus'] and
        os.environ.get('AOR_TRAINING_MACHINE_CPUS') == str(profile['machineCpus']),
        'The qualification worker must match its actual fixed CPU, game and resource profile.')
    p.require(profile['files'] and all(p.file_sha(path) == digest for path, digest in profile['files'].items()) and
        profile['files'].get('/bin/ps') == p.file_sha('/bin/ps'), 'Frozen inputs, runtime and liveness owner must remain exact.')
    p.require(profile['originArena']['sha256'] == plan.record['originArenaSha256'][profile['host']] and
        profile['originAdmission']['sha256'] == plan.record['originAdmissionSha256'] and
        profile['originProof']['sha256'] == plan.record['originProofSha256'][profile['host']] and
        set(profile['arenas']) == set(p.BANKS), 'Both explicit evaluation arenas retain the original83 admission separately.')
    for item in (profile['originArena'], profile['originAdmission'], profile['originProof'], *profile['arenas'].values()):
        p.checked_subject(item)
    proof = p.read(p.checked_subject(profile['originProof'])); origin_proof(plan, profile['host'], proof)
    p.require(all(profile['files'].get(path) == digest for path, digest in proof['inputPins'].items()),
        'The complete verified original83 subject closure must be included in immutable host input pins.')
    return plan, profile


def live(profile):
    process.live(profile, mailbox=contract())


def native_run(profile, mode, source, output, log, extra=()):
    command = native.native_command(profile, mode, source, output, extra, maximum_seconds=p.SCIENCE_SECONDS)
    process.run_child(profile, command, log, mailbox=contract())


def collect(plan, profile, protocol, root, bank, chunk, gate):
    p.require(p.owner(chunk) == profile['host'], 'Native shards cannot migrate between hosts.')
    live(profile)
    directory = Path(root)/'banks'/bank/'chunks'/f'chunk-{chunk}'
    directory.mkdir(parents=True, exist_ok=False)
    subjects = dict(protocol=p.subject(protocol), originArena=profile['originArena'],
        originAdmission=profile['originAdmission'], originProof=profile['originProof'], arena=profile['arenas'][bank], confirmation=gate)
    if gate is not None: confirmation(plan, gate, Pins(profile['_clock'].deadline))
    source = directory/'source.json'; storage.publish_json(source, plan.source(bank, chunk, subjects))
    output = directory/'native'
    native_run(profile, PREFIX+'-screen-chunk', source, output, directory/'screen.log',
        ('--workers', str(profile['workers'])))
    files = storage.inventory(output); values = {}
    for name in wanted(files):
        raw = (output/name).read_bytes()
        p.require(hashlib.sha256(raw).hexdigest() == files[name]['sha256'], 'Native bytes changed before semantic admission.')
        values[name] = decode(__import__('gzip').decompress(raw) if name.endswith('.json.gz') else raw)
    manifest, finished, _ = chunk_evidence(plan, bank, chunk, files, values, p.file_sha(source))
    receipt = dict(kind=PREFIX+'-screen-chunk-receipt-1', protocolSha256=plan.sha256, bank=bank,
        chunk=chunk, host=profile['host'], sourceSha256=p.file_sha(source), files=files,
        completionJson=(output/'finished.json').read_text(encoding='utf-8'), scienceSha256=manifest['scienceSha256'],
        worlds=128, games=256, reproductions=finished['reproductions'],
        scientificTerminalExecutions=finished['scientificTerminalExecutions'])
    archive = directory/'evidence.tar.gz'; storage.archive_collection(output, archive, receipt)
    return p.subject(archive.with_name(archive.name+'.json'))


def wait_receipts(profile, root, bank, local):
    result = list(local)
    for chunk in p.CHUNKS['MAC']:
        directory = Path(root)/'incoming'/tag(bank, chunk)
        while not (directory/'accepted.json').exists():
            live(profile); time.sleep(min(10, profile['_clock'].remaining(time.time())))
        record = p.read(directory/'accepted.json')
        p.require(record['kind'] == PREFIX+'-handoff-1' and record['tag'] == tag(bank, chunk) and
            p.checked_subject(record['receipt']).resolve() == (directory/'receipt.json').resolve() and
            p.checked_subject(record['archive']).resolve() == (directory/'evidence.tar.gz').resolve(),
            'A received shard must be admitted in its exact once-only mailbox slot.')
        result.append(record['receipt'])
    return result


def complete_bank(plan, profile, root, bank, receipts):
    p.require(profile['host'] == 'DESKTOP' and len(receipts) == 32,
        'Only Desktop may aggregate all32 original shards, after the whole bank completes.')
    directory = Path(root)/'banks'/bank; report_input = directory/'report-input'
    report_input.mkdir(exist_ok=False)
    pins = Pins(profile['_clock'].deadline); worlds = []; chunks = []; sciences = set()
    ordered = sorted(receipts, key=lambda item: p.read(p.checked_subject(item))['chunk'])
    for chunk, item in enumerate(ordered):
        receipt, _, rows, _ = archived(plan, item, pins)
        p.require(receipt['chunk'] == chunk and receipt['bank'] == bank, 'The bank requires all32 distinct original shards.')
        worlds.extend(rows); sciences.add(receipt['scienceSha256'])
        target = report_input/f'chunk-{chunk}-finished.json'
        durable.publish(target, lambda output: output.write(receipt['completionJson'].encode('utf-8')),
            receipt['files']['finished.json']['sha256'])
        chunks.append(relative(target, report_input/'compact.json'))
    p.require(len(sciences) == 1, 'A bank cannot combine different supported ecologies or scientific identities.')
    compact = storage.publish_json(report_input/'compact.json', dict(kind=PREFIX+'-screen-compact-1', chunks=chunks, worlds=worlds))
    command = [profile['java']['executable'], f'-Xmx{profile["heapMiB"]}m', '-XX:ActiveProcessorCount=1',
        '-XX:ParallelGCThreads=1', '-XX:ConcGCThreads=1', '-cp', str(Path(profile['runtime'])/'lib/*'),
        'dev.aragos.aor.training.TrainingMainKt', '--mode', PREFIX+'-screen-report',
        '--worlds', compact['path'], '--output', str(directory/'report')]
    process.run_child(profile, command, directory/'report.log', mailbox=contract())
    passed = verify_report(worlds, p.read(directory/'report/report.json'))
    owner = directory/'complete.json'
    record = dict(kind=PREFIX+'-bank-evidence-1', complete=True, bank=bank, protocolSha256=plan.sha256,
        candidateBundleSha256=p.CANDIDATE, scienceSha256=next(iter(sciences)), worlds=4096, games=8192,
        reproductions=8, scientificTerminalExecutions=8200, gatePassed=passed, chunks=ordered,
        compact=relative(compact['path'], owner), report=relative(directory/'report/report.json', owner),
        reportFinished=relative(directory/'report/finished.json', owner))
    pins.unchanged()
    return storage.publish_json(owner, record)


def publish_confirmation(plan, root, evidence):
    owner = p.checked_subject(evidence); record = p.read(owner)
    p.require(record['gatePassed'] is True and record['bank'] == 'mixed', 'A negative mixed bank has no successor publication.')
    directory = Path(root)/'confirmation'; directory.mkdir(exist_ok=False)
    copied(owner, directory/'evidence.json', evidence['sha256'])
    report = resolve(record['report'], owner, Pins())
    copied(report, directory/'report/report.json')
    gate_path = directory/'gate.json'
    result = storage.publish_json(gate_path, dict(kind='coordinated-support-confirmation-1', passed=True, complete=True,
        protocolSha256=plan.sha256, candidateBundleSha256=p.CANDIDATE, bank='mixed', worlds=4096,
        report=relative(directory/'report/report.json', gate_path), evidence=relative(directory/'evidence.json', gate_path)))
    confirmation(plan, result, Pins())
    transport.bundle(Path(root)/'publications/homogeneous', 'homogeneous',
        {name: p.subject(directory/name) for name in storage.inventory(directory)},
        dict(protocolSha256=plan.sha256, candidateBundleSha256=p.CANDIDATE, confirmationSha256=result['sha256']),
        contract=contract())
    return result


def qualification(plan, root, banks):
    p.require(set(banks) == set(p.BANKS), 'Both complete independent banks are required before qualification.')
    owner = Path(root)/'qualification.json'; records = {}
    for bank in p.BANKS:
        evidence = p.checked_subject(banks[bank]); record = p.read(evidence)
        report = resolve(record['report'], evidence, Pins())
        p.require(record['bank'] == bank and record['gatePassed'] is True and report_gate(p.read(report)),
            'Qualification requires both99 metrics from both full independent banks.')
        records[bank] = dict(complete=True, pairs=4096, win99Passed=True, reward99Passed=True,
            evidence=relative(evidence, owner), report=relative(report, owner))
    value = plan.record
    return storage.publish_json(owner, dict(kind='coordinated-support-qualification-1', schemaVersion=1,
        passed=True, complete=True, profile=p.PROFILE, supportProfile=p.SUPPORT,
        **{key: value[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256',
            'rulesVersion', 'dataVersion', 'references')},
        configuration=p.APPLICATION_CONFIGURATION, banks=records))


def worker(protocol, profile_path, output, started, deadline):
    root = Path(output); root.mkdir(parents=True, exist_ok=False)
    clock = p.Clock(started, deadline); clock.remaining(time.time())
    record = dict(kind=PREFIX+'-clock-1', protocolSha256=p.file_sha(protocol), startedEpoch=started, deadlineEpoch=deadline)
    storage.publish_json(root/'clock.json', record)
    storage.publish_json(root/'worker-owner.json', dict(kind=PREFIX+'-worker-owner-1', pid=os.getpid(), processGroup=os.getpgrp()))
    status = dict(kind=PREFIX+'-terminal-1', host=None, clock=record, completedBanks=0,
        qualified=False, qualification=None, banks={})
    profile = None
    try:
        plan, profile = check_profile(protocol, profile_path); status['host'] = profile['host']
        storage.publish_json(root/'inputs.json', dict(protocol=p.subject(protocol), profile=p.subject(profile_path)))
        p.require(all(float(os.environ[name]) == deadline for name in
            ('AOR_CAMPAIGN_DEADLINE_EPOCH', 'AOR_CAMPAIGN_STAGE_DEADLINE_EPOCH')),
            'The worker and inherited outer controller retain the same original science deadline.')
        profile = dict(profile, _root=root, _clock=clock)
        if profile['host'] == 'MAC':
            profile['_peer'] = transport.Outbound(profile['desktop'], clock, root, contract=contract())
            profile['_peer'].await_active(plan.sha256)
        gate = None
        for bank in p.BANKS:
            if bank == 'homogeneous' and profile['host'] == 'MAC':
                peer = profile['_peer']; ready = peer.await_stage('homogeneous')
                content = peer.fetch('homogeneous', ready, root/'downloads/homogeneous')
                gate = p.subject(content/'gate.json'); confirmation(plan, gate, Pins(clock.deadline))
                p.require(ready['publication']['identity'] == dict(protocolSha256=plan.sha256,
                    candidateBundleSha256=p.CANDIDATE, confirmationSha256=gate['sha256']),
                    'The portable stage must retain the exact passed mixed gate.')
            receipts = []
            for chunk in p.CHUNKS[profile['host']]:
                item = collect(plan, profile, protocol, root, bank, chunk, gate); receipts.append(item)
                if profile['host'] == 'MAC':
                    receipt_path = Path(item['path'])
                    handed = profile['_peer'].push(receipt_path.with_name('evidence.tar.gz'), receipt_path, bank, chunk)
                    storage.publish_json(receipt_path.parent/'handoff.json', handed)
            if profile['host'] == 'DESKTOP':
                item = complete_bank(plan, profile, root, bank, wait_receipts(profile, root, bank, receipts))
                status['banks'][bank] = item
                status['completedBanks'] += 1
                if not p.read(item['path'])['gatePassed'] and bank == 'mixed':
                    status.update(status='stopped', reason='mixed-confirmation-failed')
                    break
                if bank == 'mixed': gate = publish_confirmation(plan, root, item)
            else:
                status['completedBanks'] += 1
        else:
            status.update(status='completed', reason='fixed-banks-complete')
            if profile['host'] == 'DESKTOP' and all(p.read(item['path'])['gatePassed'] for item in status['banks'].values()):
                status.update(qualified=True, qualification=qualification(plan, root, status['banks']))
    except PeerStopped as stopped:
        response = stopped.response; peer_terminal = response['terminal']
        p.require(peer_terminal['clock'] == record and peer_terminal['completedBanks'] == status['completedBanks'] == 1,
            'A peer refusal must retain the original shared clock after the local mixed bank.')
        raw = p.canonical(peer_terminal)+b'\n'
        p.require(hashlib.sha256(raw).hexdigest() == response['terminalSubject']['sha256'],
            'The peer terminal must be the exact canonical published original bytes.')
        peer_subject = storage.publish_json(root/'peer-terminal.json', peer_terminal)
        status.update(status='stopped', reason='mixed-confirmation-failed', peerTerminal=peer_subject,
            originalPeerSubject=response['terminalSubject'])
    except BaseException as error:
        status.update(status='failed', reason=type(error).__name__, failure=str(error))
        if profile is not None and profile.get('_peer') is not None:
            try: profile['_peer'].remote('fail', 'MAC')
            except Exception as notification:
                status['peerNotificationFailure'] = type(notification).__name__
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
    for name in ('probe', 'reserve', 'accept', 'health', 'fail'):
        command = sub.add_parser(name); command.add_argument('--root', type=Path, required=True); command.add_argument('--name', required=True)
    args = parser.parse_args()
    result = worker(args.protocol, args.profile, args.output, args.started_epoch, args.deadline_epoch) if args.mode == 'worker' else \
        transport.serve(args.mode, args.root, args.name, contract=contract())
    print(json.dumps(dict(status=result.get('status'), completedBanks=result.get('completedBanks')) if args.mode == 'worker' else result))


if __name__ == '__main__':
    main()
