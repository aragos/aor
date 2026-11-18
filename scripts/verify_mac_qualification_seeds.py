"""Independent arithmetic study91 reservation proof, without importing the reservation owner."""
import argparse
from collections import defaultdict
import datetime
import hashlib
import json
from pathlib import Path


PARENT_SHA = '3193943dee4dddd649d17e88e09dac29591b2f9a89b50eab3e9c50b3dd7c97a2'
METHOD_SHA = '0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b'
COUNTS = {'mixed': 2048, 'homogeneous': 2048}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_bytes())


def digest(values):
    result = hashlib.sha256()
    for value in sorted(values):
        result.update((str(value) + '\n').encode())
    return result.hexdigest()


def checked(item):
    assert sha(item['path']) == item['sha256'], item['path']
    return read(item['path'])


def declared(record):
    return {value for bank in record['seedBanks'].values()
        for key in ('seeds', 'continuationSeeds', 'proposalSeeds') for value in bank.get(key, [])}


def inventory(item, stack=()):
    assert item['path'] not in stack, 'Acyclic ancestry is required.'
    record = checked(item)
    if 'reservedSeeds' in record:
        values = set(record['reservedSeeds'])
        offsets = set(record['conservativelyExpandedOffsets'])
    else:
        assert record['protectionStorage'] == 'parent-dag-1'
        values, offsets = inventory(record['carriedForwardAudit'], (*stack, item['path']))
        assert len(values) == record['priorActualValues']
        assert digest(values) == record['priorActualValuesSha256']
        assert sorted(offsets) == record['inheritedConservativeOffsets']
        offsets.update(record['newlyProtectedProbeOffsets'])
    values.update(declared(record))
    values.update(record.get('heldRejectedNumericalValues', []))
    offsets.update(bank['nativeOffset'] for bank in record['seedBanks'].values()
        if 'nativeOffset' in bank)
    return values, offsets


def verify(audit_path, expected_sha):
    assert sha(audit_path) == expected_sha, 'The root-reviewed audit subject must be exact.'
    audit = read(audit_path)
    assert audit['kind'] == 'mac-qualification-frozen-ancestry-1' and audit['family'] == 91
    assert audit['protectionStorage'] == 'parent-dag-1' and 'reservedSeeds' not in audit
    assert audit['carriedForwardAudit']['sha256'] == PARENT_SHA
    assert audit['prospectiveMethod']['sha256'] == METHOD_SHA
    method = checked(audit['prospectiveMethod'])
    assert {bank['name']: bank['worlds'] for bank in method['banks']} == COUNTS
    assert method['maximumTerminalExecutions'] == 8208 and method['newFits'] == 0
    parent = checked(audit['carriedForwardAudit'])
    ancestors = [audit['carriedForwardAudit'], *audit['verifiedAncestors']]
    assert ancestors == [audit['carriedForwardAudit'], parent['carriedForwardAudit'],
        *parent['verifiedAncestors']]
    assert len(ancestors) == len({row['path'] for row in ancestors}) == 72
    for item in ancestors:
        checked(item)
    for item in audit.values():
        if isinstance(item, dict) and set(item) == {'path', 'sha256'}:
            checked(item)
    assert sha(Path(audit_path).with_name('audit_frozen.py')) == audit['auditScriptSha256']
    prior, offsets = inventory(audit['carriedForwardAudit'])
    assert len(prior) == audit['priorActualValues'] == 1094687
    assert digest(prior) == audit['priorActualValuesSha256']
    assert len(offsets) == 3153 and sorted(offsets) == audit['inheritedConservativeOffsets']
    # Explicitly witness the full unused89 banks, all nine numerical integers and88b/90 declarations.
    witnessed = {}
    for item in [audit['carriedForwardAudit'], *audit['verifiedAncestors'][:2]]:
        record = checked(item)
        assert declared(record).issubset(prior)
        assert set(record.get('heldRejectedNumericalValues', [])).issubset(prior)
        historical = set(record['conservativelyExpandedOffsets'])
        historical.update(bank['nativeOffset'] for bank in record['seedBanks'].values()
            if 'nativeOffset' in bank)
        assert historical.issubset(offsets)
        witnessed[str(record['family'])] = len(declared(record))
    residues = defaultdict(list)
    for offset in offsets:
        residues[offset % 8191].append(offset)
    probes, rejected = [], set()
    for first in range(91000000, 91000000 + len(audit['nativeReservationProbes'])):
        overlaps = {}
        for index, count in enumerate(COUNTS.values()):
            offset = first + index
            conflicts = [value for number in range(count)
                if (value := -1000000000 + offset + number * 8191) in prior or any(
                    0 <= (value + 1000000000 - old) // 8191 < 10000
                    for old in residues[(value + 1000000000) % 8191])]
            if conflicts:
                overlaps[str(offset)] = dict(count=len(conflicts), sha256=digest(conflicts))
        probes.append(dict(firstOffset=first, conflictingValuesByOffset=overlaps,
            collisions=sum(item['count'] for item in overlaps.values())))
        if overlaps:
            rejected.update(first + index for index in range(2))
    assert probes == audit['nativeReservationProbes']
    assert all(probe['collisions'] > 0 for probe in probes[:-1]) and probes[-1]['collisions'] == 0
    assert sorted(rejected) == audit['newlyProtectedProbeOffsets']
    assert sorted(offsets | rejected) == audit['conservativelyExpandedOffsets']
    first = probes[-1]['firstOffset']
    banks = {name: dict(nativeOffset=first+index, count=count,
        seeds=[-1000000000+first+index+number*8191 for number in range(count)])
        for index, (name, count) in enumerate(COUNTS.items())}
    assert banks == audit['seedBanks'] == checked(audit['streamBanks'])
    values = [value for bank in banks.values() for value in bank['seeds']]
    assert len(values) == len(set(values)) == 4096 and not prior.intersection(values)
    assert checked(audit['schedule']) == dict(kind='mac-qualification-schedule-1', banks=banks)
    assert checked(audit['rngStreams']) == {} and audit['numericalReservationProbes'] == []
    assert audit['declaredNumericalStreams'] == 0 and audit['maximumTerminalExecutions'] == 8208
    assert audit['scientificStreamUseAuthorized'] is False
    assert audit['modelsRead'] == audit['gamesRun'] == audit['fitsRun'] == audit['numericDraws'] == audit['humanInputsRead'] == 0
    return dict(kind='mac-qualification-independent-seed-proof-1', passed=True,
        createdUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), auditSha256=sha(audit_path),
        scheduleSha256=audit['schedule']['sha256'], independentVerifierSha256=sha(__file__),
        ancestryPinsRehashed=72, priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        declaredNewWorlds=4096, numericalStreams=0, rejectedPrefixes=len(probes)-1,
        nativeOffsets=[bank['nativeOffset'] for bank in banks.values()],
        explicitAncestryDeclarationsWitnessed=witnessed, gamesRun=0, numericDraws=0,
        scientificStreamUseAuthorized=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.audit, args.expected_sha)
    with args.output.open('x') as stream:
        json.dump(result, stream, sort_keys=True, indent=2)
        stream.write('\n')
    print(json.dumps(dict(proof=str(args.output), sha256=sha(args.output)), sort_keys=True))
