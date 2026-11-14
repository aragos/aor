"""Pure whole-grid diagnostic audit; each native archive is streamed once, no policy execution."""
import argparse
import math
from pathlib import Path

import coordinated_diagnostic_plan as p
import coordinated_diagnostic as q
from coordinated_qualification_audit import children
from token_paired_campaign_audit import Pins


def handoff(root, selected, chunk, item, pins):
    directory = Path(root)/'incoming'/q.tag(selected.identity, chunk)
    accepted = pins.read(directory/'accepted.json')
    receipt = pins.subject(accepted['receipt']); archive = Path(accepted['archive']['path'])
    p.require(accepted['kind'] == p.PREFIX+'-handoff-1' and accepted['tag'] == q.tag(selected.identity, chunk) and
        receipt.resolve() == (directory/'receipt.json').resolve() and
        archive.resolve() == (directory/'evidence.tar.gz').resolve() and accepted['receipt'] == item and
        accepted['archive']['sha256'] == pins.read(receipt)['archiveSha256'],
        'Remote ranges retain exact once-only acceptance of the original archive and receipt.')


def complete_case(plan, root, selected, item, pins):
    owner = pins.subject(item); record = pins.read(owner)
    p.require(owner.resolve() == (Path(root)/'cases'/selected.identity/'complete.json').resolve() and
        record['kind'] == p.PREFIX+'-case-evidence-1' and record['complete'] is True and
        record['caseId'] == selected.identity and record['protocolSha256'] == plan.sha256 and
        record['pairedWorldRecords'] == 512 and record['games'] == 1024 and record['reproductions'] == 2 and
        record['scientificTerminalExecutions'] == 1026 and len(record['chunks']) == 2,
        'Every completed case contains both host ranges and exactly1026 terminal executions.')
    worlds = []; sciences = set()
    for chunk, item in enumerate(record['chunks']):
        if p.owner(chunk) == 'MAC': handoff(root, selected, chunk, item, pins)
        else:
            expected = Path(root)/'cases'/selected.identity/'chunks'/f'chunk-{chunk}'/'evidence.tar.gz.json'
            p.require(Path(item['path']).resolve() == expected.resolve(), 'Local ranges retain the declared original path.')
        receipt, _, rows, _ = q.archived(plan, item, pins)
        p.require(receipt['caseId'] == selected.identity and receipt['chunk'] == chunk,
            'The two original ranges retain exact case identity and global ordering.')
        if p.owner(chunk) == 'DESKTOP': pins.pin(Path(item['path']).parent/'source.json', receipt['sourceSha256'])
        worlds.extend(rows); sciences.add(receipt['scienceSha256'])
    p.require(sciences == {record['scienceSha256']}, 'Both host ranges retain one complete scientific identity.')
    compact_path = q.resolve(record['compact'], owner, pins)
    p.require(compact_path.resolve() == (owner.parent/'compact.json').resolve(), 'A case retains its own original compact evidence.')
    compact = pins.read(compact_path)
    p.require(compact == dict(kind=p.PREFIX+'-case-compact-1', caseId=selected.identity,
        scienceSha256=record['scienceSha256'], worlds=worlds),
        'The compact outcome table must be exactly the original native archive records.')
    return worlds


def local_case(plan, root, selected, pins):
    directory = Path(root)/'cases'/selected.identity/'chunks'
    p.require(children(directory) == {'chunk-1'}, 'Mac owns exactly its one192-world range per case.')
    path = directory/'chunk-1/evidence.tar.gz.json'
    receipt, _, _, _ = q.archived(plan, p.subject(path), pins)
    p.require(receipt['chunk'] == 1 and receipt['caseId'] == selected.identity, 'Mac range identity cannot change.')
    pins.pin(path.parent/'source.json', receipt['sourceSha256'])
    handed = pins.read(path.parent/'handoff.json')
    p.require(handed['kind'] == p.PREFIX+'-handoff-1' and handed['tag'] == q.tag(selected.identity, 1) and
        handed['receipt']['sha256'] == p.file_sha(path) and handed['archive']['sha256'] == receipt['archiveSha256'],
        'Mac originals retain remote acceptance of these exact completed bytes.')


def verify(protocol_subject, run):
    root = Path(run); pins = Pins()
    protocol = pins.subject(protocol_subject); plan = p.Plan.from_file(protocol)
    ended = pins.read(root/'terminal.json'); clock = pins.read(root/'clock.json')
    original = p.Clock(clock['startedEpoch'], clock['deadlineEpoch'])
    p.require(clock['kind'] == p.PREFIX+'-clock-1' and clock['protocolSha256'] == plan.sha256 and
        ended['kind'] == p.PREFIX+'-terminal-1' and ended['clock'] == clock and
        ended['host'] in p.HOSTS and ended['status'] in ('completed', 'failed') and
        type(ended['updatedEpoch']) in (int, float) and math.isfinite(ended['updatedEpoch']) and
        original.started <= ended['updatedEpoch'], 'Diagnostic evidence retains its original single host/clock identity.')
    pins.deadline = min(ended['updatedEpoch']+p.AUDIT_SECONDS, original.deadline+p.AUDIT_SECONDS)
    pins.check_clock()
    owner = pins.read(root/'worker-owner.json')
    p.require(owner['kind'] == p.PREFIX+'-worker-owner-1' and type(owner['pid']) is int and owner['pid'] > 1 and
        type(owner['processGroup']) is int and owner['processGroup'] > 1,
        'Recorded original worker PID/group are provenance; the terminal audit requires no live processes.')
    p.require(ended['diagnosticOnly'] is True and ended['qualified'] is False and ended['promotionAllowed'] is False and
        ended['nominee'] is None, 'A diagnostic terminal cannot acquire selection or qualification authority.')
    if ended['status'] == 'failed':
        pins.unchanged()
        return dict(kind=p.PREFIX+'-audit-1', passed=False, complete=False, status='failed', host=ended['host'],
            protocolSha256=plan.sha256, counts={}, diagnosticOnly=True, qualified=False, nominee=None,
            artifactPins=pins.values, execution=dict(newGames=0, newFits=0, modelForwards=0))
    p.require(ended['updatedEpoch'] <= original.deadline and ended['reason'] == 'fixed-diagnostic-complete' and
        ended['completedCases'] == 32, 'Only all32 completed cases within the original clock permit aggregate diagnostics.')
    inputs = pins.read(root/'inputs.json'); pins.subject(inputs['protocol'])
    profile = pins.read(pins.subject(inputs['profile']))
    p.require(inputs['protocol']['sha256'] == plan.sha256 and profile['kind'] == p.PREFIX+'-host-1' and
        profile['host'] == ended['host'] and profile['protocolSha256'] == plan.sha256 and
        profile['originProof']['sha256'] == plan.record['originProofSha256'][ended['host']] and
        all(profile[key] == value for key, value in p.host_limits(ended['host']).items()),
        'Terminal inputs retain their exact executing profile, original proof and fixed resource limits.')
    proof = pins.read(pins.subject(profile['originProof'])); q.origin_proof(plan, ended['host'], proof)
    p.require(all(profile['files'].get(path) == digest for path, digest in proof['inputPins'].items()),
        'The original83 proof retains its complete exact local subject closure.')
    for path, digest in profile['files'].items(): pins.pin(path, digest)
    p.require(children(root/'cases') == {item.identity for item in p.CASES} and
        not any((root/name).exists() for name in ('publications', 'qualification.json', 'selected.json')),
        'The fixed diagnostic has exactly32 cases and no promotion or selected-policy artifacts.')
    if ended['host'] == 'DESKTOP':
        p.require(set(ended['cases']) == {item.identity for item in p.CASES},
            'Desktop completion must name all32 complete original case receipts.')
        worlds = {}
        for selected in p.CASES:
            p.require(children(root/'cases'/selected.identity/'chunks') == {'chunk-0'},
                'Desktop retains exactly its one320-world native range per case.')
            worlds[selected.identity] = complete_case(plan, root, selected, ended['cases'][selected.identity], pins)
        p.require(children(root/'incoming') == {q.tag(item.identity, 1) for item in p.CASES},
            'Every Mac range must be accepted once, without undeclared or repeated transfers.')
        report_path = pins.subject(ended['report'])
        p.require(report_path.resolve() == (root/'report.json').resolve(), 'Only the original whole-grid report may complete.')
        report = pins.read(report_path)
        p.require(report == q.aggregate(plan, worlds),
            'All fixed512-world main/interaction/context statistics must reproduce from original archived outcomes.')
        counts = p.counts(); full = True
    else:
        p.require(ended['cases'] == {} and ended['report'] is None and not (root/'report.json').exists() and
            not (root/'incoming').exists(), 'Mac attests only its local ranges, never fabricated global effects.')
        for selected in p.CASES: local_case(plan, root, selected, pins)
        counts = p.counts('MAC'); full = False
    p.require(counts['terminalExecutions'] <= p.MAXIMUM_TERMINALS, 'The total original terminal budget is immutable.')
    pins.unchanged()
    return dict(kind=p.PREFIX+'-audit-1', passed=True, complete=True, status='completed', host=ended['host'],
        protocolSha256=plan.sha256, counts=counts, diagnosticOnly=True, qualified=False, nominee=None,
        fullGlobalStatisticsRecomputed=full, artifactPins=pins.values,
        execution=dict(newGames=0, newFits=0, modelForwards=0))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=Path, required=True); parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    q.storage.publish_json(args.output, verify(p.subject(args.protocol), args.run))
