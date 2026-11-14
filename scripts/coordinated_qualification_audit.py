"""Pure two-bank terminal audit: one archive pass, no inference, simulation or process control."""
import argparse
import math
from pathlib import Path

import coordinated_qualification_plan as p
import coordinated_qualification as q
from token_paired_campaign_audit import Pins


def children(path):
    path = Path(path)
    if not path.exists(): return set()
    result = set()
    for child in path.iterdir():
        p.require(not child.is_symlink(), 'Audit directory inventories cannot follow symlinks.')
        if child.name != '.DS_Store': result.add(child.name)
    return result


def handoff(root, bank, chunk, item, pins):
    directory = Path(root)/'incoming'/q.tag(bank, chunk)
    accepted = pins.read(directory/'accepted.json')
    receipt = pins.subject(accepted['receipt']); archive = Path(accepted['archive']['path'])
    p.require(accepted['kind'] == p.PREFIX+'-handoff-1' and accepted['tag'] == q.tag(bank, chunk) and
        receipt.resolve() == (directory/'receipt.json').resolve() and
        archive.resolve() == (directory/'evidence.tar.gz').resolve() and accepted['receipt'] == item and
        accepted['archive']['sha256'] == pins.read(receipt)['archiveSha256'],
        'Received shards must retain their exact once-only mailbox acceptance and original bytes.')


def bank(plan, root, name, item, pins, gate_sha):
    owner = pins.subject(item); record = pins.read(owner)
    p.require(owner.resolve() == (Path(root)/'banks'/name/'complete.json').resolve() and
        record['kind'] == p.PREFIX+'-bank-evidence-1' and record['complete'] is True and record['bank'] == name and
        record['protocolSha256'] == plan.sha256 and record['candidateBundleSha256'] == p.CANDIDATE and
        record['worlds'] == 4096 and record['games'] == 8192 and record['reproductions'] == 8 and
        record['scientificTerminalExecutions'] == 8200 and len(record['chunks']) == 32,
        'A completed Desktop bank contains all32 original shards and exactly8200 executions.')
    worlds = []; native_completions = []; sciences = set()
    for chunk, subject in enumerate(record['chunks']):
        if p.owner(chunk) == 'MAC': handoff(root, name, chunk, subject, pins)
        else:
            expected = Path(root)/'banks'/name/'chunks'/f'chunk-{chunk}'/'evidence.tar.gz.json'
            p.require(Path(subject['path']).resolve() == expected.resolve(), 'Local shards retain their declared path.')
        receipt, finished, rows, manifest = q.archived(plan, subject, pins)
        p.require(receipt['bank'] == name and receipt['chunk'] == chunk,
            'Completed bank receipts remain ordered by all32 distinct global shards.')
        if gate_sha is not None:
            p.require(manifest['confirmation']['sha256'] == gate_sha,
                'Every homogeneous shard must consume the exact shared mixed confirmation.')
        if p.owner(chunk) == 'DESKTOP':
            pins.pin(Path(subject['path']).parent/'source.json', receipt['sourceSha256'])
        worlds.extend(rows); native_completions.append((receipt, finished)); sciences.add(receipt['scienceSha256'])
    p.require(sciences == {record['scienceSha256']}, 'The complete bank must share one original scientific identity.')
    compact_path = q.resolve(record['compact'], owner, pins); compact = pins.read(compact_path)
    p.require(compact['kind'] == p.PREFIX+'-screen-compact-1' and compact['worlds'] == worlds and
        len(compact['chunks']) == 32, 'Pure native reporting must consume the exact original compact worlds.')
    for item, (receipt, finished) in zip(compact['chunks'], native_completions):
        path = q.resolve(item, compact_path, pins)
        p.require(item['sha256'] == receipt['files']['finished.json']['sha256'] and
            path.read_text(encoding='utf-8') == receipt['completionJson'] and pins.read(path) == finished,
            'Every compact report completion must preserve its original native bytes.')
    report_path = q.resolve(record['report'], owner, pins)
    report_finished = q.resolve(record['reportFinished'], owner, pins)
    completed = pins.read(report_finished); inputs_path = report_finished.parent/'inputs.json'
    inputs = pins.read(inputs_path)
    p.require(report_path.resolve() == (report_finished.parent/'report.json').resolve() and
        completed['kind'] == p.PREFIX+'-screen-report-complete-1' and completed['complete'] is True and
        completed['worlds'] == 4096 and completed['newGames'] == completed['newFits'] == 0 and
        completed['files'] == {'report.json': p.file_sha(report_path), 'inputs.json': p.file_sha(inputs_path)} and
        inputs['sourceSha256'] == p.file_sha(compact_path) and inputs['chunks'] == compact['chunks'] and
        inputs['scienceSha256'] == record['scienceSha256'] and
        inputs['finalAdmissionSha256'] == plan.record['originAdmissionSha256'] and
        inputs['historicalAdmissionSha256'] == native_completions[0][1]['historicalAdmissionSha256'],
        'The completed native report must bind the exact original compact evidence and zero new execution.')
    passed = q.verify_report(worlds, pins.read(report_path))
    p.require(record['gatePassed'] is passed, 'Persisted gate status must equal independently recomputed99 statistics.')
    return dict(bank=name, gatePassed=passed, worlds=4096, games=8192, reproductions=8,
        terminalExecutions=8200, report=p.subject(report_path), evidence=p.subject(owner))


def local_bank(plan, root, name, pins, gate_sha):
    directory = Path(root)/'banks'/name/'chunks'
    p.require(children(directory) == {f'chunk-{chunk}' for chunk in p.CHUNKS['MAC']},
        'The Mac worker retains exactly its12 original shards in each attempted bank.')
    counts = dict(bank=name, worlds=0, games=0, reproductions=0, terminalExecutions=0)
    sciences = set()
    for chunk in p.CHUNKS['MAC']:
        path = directory/f'chunk-{chunk}'/'evidence.tar.gz.json'
        receipt, _, _, manifest = q.archived(plan, p.subject(path), pins)
        p.require(receipt['chunk'] == chunk and receipt['bank'] == name, 'Mac local shard identity changed.')
        pins.pin(path.parent/'source.json', receipt['sourceSha256'])
        if gate_sha is not None:
            p.require(manifest['confirmation']['sha256'] == gate_sha,
                'Mac heldout shards consume the exact downloaded confirmation.')
        handed = pins.read(path.parent/'handoff.json')
        p.require(handed['kind'] == p.PREFIX+'-handoff-1' and handed['tag'] == q.tag(name, chunk) and
            handed['receipt']['sha256'] == p.file_sha(path) and handed['archive']['sha256'] == receipt['archiveSha256'],
            'Mac originals require the remote acceptance of these exact original archive and receipt bytes.')
        sciences.add(receipt['scienceSha256'])
        for key in ('worlds', 'games', 'reproductions'): counts[key] += receipt[key]
        counts['terminalExecutions'] += receipt['scientificTerminalExecutions']
    p.require(len(sciences) == 1 and counts['worlds'] == 1536 and counts['games'] == 3072 and
        counts['reproductions'] == 4 and counts['terminalExecutions'] == 3076,
        'Mac evidence is its exact fixed local contribution, not a recomputed global gate.')
    return counts


def qualified(plan, root, terminal, banks, pins):
    expected = all(row['gatePassed'] for row in banks)
    p.require(terminal['qualified'] is expected and (terminal['qualification'] is not None) == expected,
        'Only both fixed complete paired99 banks may qualify the sole candidate.')
    path = Path(root)/'qualification.json'
    if not expected:
        p.require(not path.exists(), 'A failed99 bank cannot leave a qualified-unit receipt.')
        return None
    p.require(pins.subject(terminal['qualification']).resolve() == path.resolve(),
        'The qualified unit must be this exact final publication.')
    value = pins.read(path); protocol = plan.record
    expected_fields = dict(kind='coordinated-support-qualification-1', schemaVersion=1,
        passed=True, complete=True, profile=p.PROFILE, supportProfile=p.SUPPORT,
        **{key: protocol[key] for key in ('candidateBundleSha256', 'originalBundleSha256', 'calibrationSha256',
            'rulesVersion', 'dataVersion', 'references')}, configuration=p.APPLICATION_CONFIGURATION)
    p.require(set(value) == {*expected_fields, 'banks'} and
        all(value[key] == item for key, item in expected_fields.items()) and set(value['banks']) == set(p.BANKS),
        'Application qualification has one explicit configuration mapping and complete immutable policy identity.')
    for row in banks:
        item = value['banks'][row['bank']]
        p.require(set(item) == {'complete', 'pairs', 'win99Passed', 'reward99Passed', 'evidence', 'report'} and
            item['complete'] is True and item['pairs'] == 4096 and
            item['win99Passed'] is True and item['reward99Passed'] is True and
            p.file_sha(q.resolve(item['evidence'], path, pins)) == row['evidence']['sha256'] and
            p.file_sha(q.resolve(item['report'], path, pins)) == row['report']['sha256'],
            'The application receipt must bind these exact independently audited bank effects and evidence.')
    return terminal['qualification']


def verify(protocol_subject, run):
    root = Path(run); pins = Pins()
    protocol = pins.subject(protocol_subject); plan = p.Plan.from_file(protocol)
    ended = pins.read(root/'terminal.json'); clock = pins.read(root/'clock.json')
    original = p.Clock(clock['startedEpoch'], clock['deadlineEpoch'])
    p.require(clock['kind'] == p.PREFIX+'-clock-1' and clock['protocolSha256'] == plan.sha256 and
        ended['kind'] == p.PREFIX+'-terminal-1' and ended['clock'] == clock and
        ended['host'] in p.HOSTS and ended['status'] in ('completed', 'stopped', 'failed') and
        type(ended['updatedEpoch']) in (int, float) and math.isfinite(ended['updatedEpoch']) and
        original.started <= ended['updatedEpoch'], 'Terminal evidence must retain the actual original shared clock.')
    pins.deadline = min(ended['updatedEpoch']+p.AUDIT_SECONDS, original.deadline+p.AUDIT_SECONDS)
    pins.check_clock()
    owner = pins.read(root/'worker-owner.json')
    p.require(owner['kind'] == p.PREFIX+'-worker-owner-1' and
        type(owner['pid']) is int and owner['pid'] > 1 and
        type(owner['processGroup']) is int and owner['processGroup'] > 1,
        'Original worker ownership remains provenance; a terminal audit does not require live groups.')
    if ended['status'] == 'failed':
        pins.unchanged()
        return dict(kind=p.PREFIX+'-audit-1', passed=False, complete=False, status='failed', host=ended['host'],
            counts={}, qualified=False, eligibleQualifiedUnit=None, artifactPins=pins.values,
            execution=dict(newGames=0, newFits=0, modelForwards=0))
    p.require(ended['updatedEpoch'] <= original.deadline and
        ((ended['status'] == 'completed' and ended['reason'] == 'fixed-banks-complete' and ended['completedBanks'] == 2) or
         (ended['status'] == 'stopped' and ended['reason'] == 'mixed-confirmation-failed' and ended['completedBanks'] == 1)),
        'Successful completion or an honest mixed refusal must occur within the original science deadline.')
    inputs = pins.read(root/'inputs.json'); pins.subject(inputs['protocol'])
    profile = pins.read(pins.subject(inputs['profile']))
    p.require(inputs['protocol']['sha256'] == plan.sha256 and profile['kind'] == p.PREFIX+'-host-1' and
        profile['host'] == ended['host'] and profile['protocolSha256'] == plan.sha256 and
        profile['originProof']['sha256'] == plan.record['originProofSha256'][ended['host']] and
        all(profile[key] == value for key, value in p.host_limits(ended['host']).items()),
        'Terminal inputs and fixed host resources must bind the exact executing profile.')
    proof = pins.read(pins.subject(profile['originProof'])); q.origin_proof(plan, ended['host'], proof)
    p.require(all(profile['files'].get(path) == digest for path, digest in proof['inputPins'].items()),
        'The host audit must rehash the complete original83 proof closure, not only a proof label.')
    for path, digest in profile['files'].items(): pins.pin(path, digest)
    names = p.BANKS[:ended['completedBanks']]
    p.require(children(root/'banks') == set(names), 'Only the prospectively opened banks may have any native work.')
    gate_sha = None; rows = []
    if ended['host'] == 'DESKTOP':
        p.require(set(ended['banks']) == set(names), 'Desktop terminal must name its exact completed bank receipts.')
        for name in names:
            if name == 'homogeneous':
                gate_subject = p.subject(root/'confirmation/gate.json')
                gate = q.confirmation(plan, gate_subject, pins)
                p.require(gate['evidence']['sha256'] == ended['banks']['mixed']['sha256'],
                    'Heldout permission must come from this original completed mixed bank.')
                gate_sha = gate_subject['sha256']
            p.require(children(root/'banks'/name/'chunks') == {f'chunk-{i}' for i in p.CHUNKS['DESKTOP']},
                'Desktop local collection has exactly its20 fixed shard directories.')
            rows.append(bank(plan, root, name, ended['banks'][name], pins, gate_sha))
        if ended['status'] == 'stopped':
            p.require(rows[0]['gatePassed'] is False and ended['qualified'] is False and ended['qualification'] is None and
                not (root/'confirmation').exists() and not (root/'publications/homogeneous').exists() and
                not (root/'qualification.json').exists(), 'A negative mixed bank ends the attempt without heldout work or publication.')
            eligible = None
        else:
            p.require(rows[0]['gatePassed'] is True, 'A completed homogeneous bank requires a passing original mixed gate.')
            eligible = qualified(plan, root, ended, rows, pins)
        expected_incoming = {q.tag(name, chunk) for name in names for chunk in p.CHUNKS['MAC']}
        p.require(children(root/'incoming') == expected_incoming, 'No undeclared remote shard may enter the completed attempt.')
    else:
        p.require(ended['banks'] == {} and ended['qualified'] is False and ended['qualification'] is None,
            'Mac records local completion, never a fabricated global qualification claim.')
        if ended['status'] == 'stopped':
            peer_path = pins.subject(ended['peerTerminal']); peer = pins.read(peer_path)
            q.PeerStopped(dict(terminal=peer))
            p.require(peer['clock'] == clock and ended['peerTerminal']['sha256'] == ended['originalPeerSubject']['sha256'] and
                not (root/'downloads/homogeneous').exists(), 'The exact original Desktop mixed refusal stops the Mac before heldout work.')
        for name in names:
            if name == 'homogeneous':
                directory = root/'downloads/homogeneous'
                publication = pins.read(directory/'publication.json')
                gate_subject = p.subject(directory/'content/gate.json'); q.confirmation(plan, gate_subject, pins)
                p.require(publication['kind'] == p.PREFIX+'-publication-1' and publication['stage'] == 'homogeneous' and
                    publication['identity'] == dict(protocolSha256=plan.sha256, candidateBundleSha256=p.CANDIDATE,
                        confirmationSha256=gate_subject['sha256']), 'Mac must retain the exact original mixed-gate publication.')
                # This archive contains only three compact gate files, not another world archive.
                from token_paired_campaign_audit import stream_archive
                stream_archive(directory/publication['archive'], publication, pins, ())
                for member, value in publication['files'].items(): pins.pin(directory/'content'/member, value['sha256'])
                gate_sha = gate_subject['sha256']
            rows.append(local_bank(plan, root, name, pins, gate_sha))
        eligible = None
    counts = {key: sum(row[key] for row in rows) for key in ('worlds', 'games', 'reproductions', 'terminalExecutions')}
    p.require(counts['terminalExecutions'] <= p.MAXIMUM_TERMINALS, 'The original total execution ceiling is immutable.')
    pins.unchanged()
    return dict(kind=p.PREFIX+'-audit-1', passed=True, complete=ended['status'] == 'completed', status=ended['status'],
        host=ended['host'], protocolSha256=plan.sha256, counts=counts, qualified=eligible is not None,
        eligibleQualifiedUnit=eligible, fullGlobalStatisticsRecomputed=ended['host'] == 'DESKTOP',
        artifactPins=pins.values, execution=dict(newGames=0, newFits=0, modelForwards=0))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True); parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    q.storage.publish_json(args.output, verify(p.subject(args.protocol), args.run))
