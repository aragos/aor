"""Independent study96 arithmetic proof; never imports the reservation machinery."""
import argparse
from collections import defaultdict
import datetime
import json
from pathlib import Path

import verify_local_bid_seeds as inventory_owner


PARENT_SHA = 'e0f1a49cb228e71fb5cdd35182910b0b81bf53f02f05c70aa1388132edc8c227'
METHOD_SHA = '1a13821a64de5c1dfdf685f46128969c793a09a03667e6431d55e9c8fe467369'
COUNTS = {'training': 16, 'holdout': 128}
NUMERICAL = ('projectionSeed', 'directionSeed')


def verify(audit_path, expected_sha):
    p = inventory_owner
    assert p.sha(audit_path) == expected_sha, 'The independently reviewed audit bytes must be exact.'
    audit = p.read(audit_path)
    assert audit['kind'] == 'token-focus-step-frozen-ancestry-1' and audit['family'] == 96
    assert audit['protectionStorage'] == 'parent-dag-1'
    assert audit['carriedForwardAudit']['sha256'] == PARENT_SHA
    assert audit['prospectiveMethod']['sha256'] == METHOD_SHA
    method = p.checked(audit['prospectiveMethod'])
    assert method['trainingWorlds'] == 16 and method['holdoutWorlds'] == 128
    assert method['freshGameWorlds'] == 144 and method['freshNumericStreams'] == 2
    assert method['maximumTerminalExecutions'] == 1560 and method['generations'] == 1
    assert method['globalDeadlineEpoch'] == 1795020399.0 and method['localOnly'] is True
    assert 1 <= len(audit['nativeReservationProbes']) <= 8191
    assert 1 <= len(audit['numericalReservationProbes']) <= 8191
    parent = p.checked(audit['carriedForwardAudit'])
    ancestors = [audit['carriedForwardAudit'], *audit['verifiedAncestors']]
    assert ancestors == [audit['carriedForwardAudit'], parent['carriedForwardAudit'],
        *parent['verifiedAncestors']]
    assert len(ancestors) == len({item['path'] for item in ancestors}) == 77
    for item in ancestors:
        p.checked(item)
    for item in audit.values():
        if isinstance(item, dict) and set(item) == {'path', 'sha256'}:
            assert p.sha(item['path']) == item['sha256'], item['path']
    assert p.sha(Path(audit_path).with_name('audit_frozen.py')) == audit['auditScriptSha256']
    prior, offsets = p.inventory(audit['carriedForwardAudit'])
    assert len(prior) == audit['priorActualValues'] == 1102879
    assert p.digest(prior) == audit['priorActualValuesSha256']
    assert len(offsets) == 3179 and sorted(offsets) == audit['inheritedConservativeOffsets']
    residues = defaultdict(list)
    for offset in offsets:
        residues[offset % 8191].append(offset)

    def overlaps(values, blocked, progression_residues):
        return [value for value in values if value in blocked or any(
            0 <= (value+1000000000-old)//8191 < 10000
            for old in progression_residues[(value+1000000000) % 8191])]

    probes, rejected = [], set()
    for first in range(96000000, 96000000 + len(audit['nativeReservationProbes'])):
        found = {}
        for index, count in enumerate(COUNTS.values()):
            offset = first + index
            conflicts = overlaps([-1000000000+offset+number*8191 for number in range(count)],
                prior, residues)
            if conflicts:
                found[str(offset)] = dict(count=len(conflicts), sha256=p.digest(conflicts))
        probes.append(dict(firstOffset=first, conflictingValuesByOffset=found,
            collisions=sum(item['count'] for item in found.values())))
        if found:
            rejected.update((first, first+1))
    assert probes == audit['nativeReservationProbes']
    assert all(item['collisions'] > 0 for item in probes[:-1]) and probes[-1]['collisions'] == 0
    assert sorted(rejected) == audit['newlyProtectedProbeOffsets']
    assert sorted(offsets | rejected) == audit['conservativelyExpandedOffsets']
    first = probes[-1]['firstOffset']
    banks = {name: dict(nativeOffset=first+index, count=count,
        seeds=[-1000000000+first+index+number*8191 for number in range(count)])
        for index, (name, count) in enumerate(COUNTS.items())}
    native = [value for bank in banks.values() for value in bank['seeds']]
    assert len(native) == len(set(native)) == 144 and not prior.intersection(native)
    assert banks == p.checked(audit['streamBanks'])
    accepted_offsets = {bank['nativeOffset'] for bank in banks.values()}
    numerical_offsets = offsets | rejected | accepted_offsets
    numerical_residues = defaultdict(list)
    for offset in numerical_offsets:
        numerical_residues[offset % 8191].append(offset)
    numeric_probes, held = [], set()
    for first in range(96001000, 96001000 + len(audit['numericalReservationProbes'])):
        values = [first, first+1]
        conflicts = overlaps(values, prior | set(native), numerical_residues)
        numeric_probes.append(dict(firstSeed=first, collisions=len(conflicts),
            conflictingValues=conflicts))
        if conflicts:
            held.update(values)
    assert numeric_probes == audit['numericalReservationProbes']
    assert all(item['collisions'] > 0 for item in numeric_probes[:-1])
    assert numeric_probes[-1]['collisions'] == 0
    assert sorted(held) == audit['heldRejectedNumericalValues']
    first_numeric = numeric_probes[-1]['firstSeed']
    streams = dict(zip(NUMERICAL, (first_numeric, first_numeric+1)))
    assert streams == p.checked(audit['rngStreams'])
    expected_banks = dict(banks, **{name: dict(count=1, seeds=[value])
        for name, value in streams.items()})
    assert expected_banks == audit['seedBanks']
    assert p.checked(audit['schedule']) == dict(kind='token-focus-step-schedule-1',
        banks=banks, **streams)
    actual = set(native) | set(streams.values())
    assert len(actual) == 146 and not prior.intersection(actual)
    expanded = {-1000000000+offset+index*8191
        for offset in accepted_offsets for index in range(10000)}
    symmetric_actual = len(prior.intersection(expanded))
    symmetric_progressions = sum(max(0,
        (min(old+9999*8191, new+9999*8191)-max(old, new))//8191+1)
        for old in offsets for new in accepted_offsets if (old-new) % 8191 == 0)
    assert symmetric_actual == symmetric_progressions == 0
    assert not set(streams.values()).intersection(expanded)
    assert audit['declaredNewActualValues'] == 146 and audit['declaredNewWorlds'] == 144
    assert audit['declaredNumericalStreams'] == 2 and audit['maximumTerminalExecutions'] == 1560
    assert audit['scientificStreamUseAuthorized'] is False
    assert audit['modelsRead'] == audit['gamesRun'] == audit['fitsRun'] == audit['numericDraws'] == audit['humanInputsRead'] == 0
    return dict(kind='token-focus-step-independent-seed-proof-1', passed=True,
        createdUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        auditSha256=p.sha(audit_path), scheduleSha256=audit['schedule']['sha256'],
        independentVerifierSha256=p.sha(__file__),
        independentInventoryOwner=dict(path=str(Path(p.__file__).resolve()), sha256=p.sha(p.__file__)),
        ancestryPinsRehashed=77, priorActualValues=len(prior), inheritedConservativeOffsets=len(offsets),
        declaredNewWorlds=144, declaredNumericalStreams=2, declaredNewActualValues=146,
        nativeOffsets=sorted(accepted_offsets), numericalIntegers=streams,
        rejectedNativePrefixes=len(probes)-1, rejectedNumericPrefixes=len(numeric_probes)-1,
        allPriorFreshness=dict(priorActualAgainstAcceptedExpanded=symmetric_actual,
            acceptedActualAndNumericAgainstPriorExpanded=0,
            priorProgressionsAgainstAcceptedExpanded=symmetric_progressions,
            newNumericAgainstAcceptedExpanded=0),
        parentBytesUnchanged=True, circularAncestryIntroduced=False,
        gamesRun=0, numericDraws=0, modelsRead=0, scientificStreamUseAuthorized=False)


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
    print(json.dumps(dict(proof=str(args.output), sha256=inventory_owner.sha(args.output))))
