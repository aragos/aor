"""Pure local comparison of retained Linux engineering results with old Mac evidence."""
import argparse
import hashlib
import json
from pathlib import Path

import native95_linux_parity as parity


def audit(folder):
    remote = folder / 'remote-results'
    plan = json.loads((folder / 'package/plan.json').read_bytes())
    closure = json.loads((remote / 'execution/closure.json').read_bytes())
    physical = json.loads((remote / 'physical-exit.json').read_bytes())
    parity.require(closure['passed'] and physical['passed'] and
                   closure['actualCompletedLowerBound'] == closure['attemptedUpperBound'] == 16 and
                   closure['allOwnedProcessesExited'] and physical['allOwnedProcessesExited'] and
                   all(not row['procExists'] for row in physical['owners']),
                   'All sixteen owned engineering executions must complete and physically exit.')
    evidence = []
    for bank, index in parity.CASES:
        old = parity.OLD / 'cases' / bank / f'world-{index}' / 'native'
        new = remote / 'execution' / f'{bank}-{index}' / 'native'
        expected = next(row for row in plan['cases'] if row['name'] == f'{bank}-{index}')
        finished = json.loads((new / 'finished.json').read_bytes())
        parity.require(finished['complete'] and
                       finished['attemptedGames'] == finished['completedGames'] == 4 and
                       finished['scientificTerminalExecutions'] == 4 and
                       finished['games'] == finished['reproductions'] == 2 and
                       finished['scienceSha256'] == expected['expectedScienceSha256'],
                       'The native completion counts and immutable science identity must agree.')
        for name, digest in finished['files'].items():
            parity.require(parity.sha(new / name) == digest,
                           'Every native output must match its own finished manifest.')
        for name in ('worlds.jsonl', 'reproductions.jsonl'):
            left = [json.loads(line) for line in (old / name).read_text().splitlines()]
            right = [json.loads(line) for line in (new / name).read_text().splitlines()]
            parity.require(parity.canonical(left) == parity.canonical(right),
                           'Compare complete normalized records, including all diagnostics and hashes.')
        worlds = [json.loads(line) for line in (new / 'worlds.jsonl').read_text().splitlines()]
        repro = [json.loads(line) for line in (new / 'reproductions.jsonl').read_text().splitlines()]
        parity.require(len(worlds) == 1 and len(repro) == 2 and all(row['matched'] for row in repro),
                       'Each world must retain both successful canonical role reproductions.')
        parity.require(hashlib.sha256(parity.canonical(worlds[0])).hexdigest() ==
                       expected['expectedWholeWorldSha256'] and
                       hashlib.sha256(parity.canonical(repro)).hexdigest() ==
                       expected['expectedReproductionsSha256'],
                       'Rejoin actual records to the hashes frozen before transfer and execution.')
        evidence.append(dict(bank=bank, index=index, terminalExecutions=4,
            fullWorldEqual=True, fullReproductionsEqual=True,
            originalWorldsSha256=parity.sha(old / 'worlds.jsonl'),
            remoteWorldsSha256=parity.sha(new / 'worlds.jsonl'),
            remoteFinishedSha256=parity.sha(new / 'finished.json')))
    for relative, digest in json.loads((folder / 'package/inventory.json').read_bytes()).items():
        parity.require(parity.sha(folder / 'package' / relative) == digest,
                       'The retained package must still match its frozen inventory.')
    result = dict(kind='native95-linux-independent-local-audit-1', passed=True, complete=True,
        actualAdditionalEngineeringExecutions=16, newQualificationWorlds=0, fittingCalls=0,
        allOwnedProcessesExited=True, fullWorldsCompared=4, canonicalRoleReproductionsCompared=8,
        matches=evidence, inputPins={str(path): parity.sha(path) for path in (
            Path(__file__).absolute(), folder / 'package/plan.json', folder / 'package/inventory.json',
            folder / 'remote-results.tar.gz', remote / 'execution/closure.json',
            remote / 'physical-exit.json')},
        qualificationClaimed=False,
        limitations='CPU JVM engineering parity for four fixed old worlds only; no CUDA, numerical '
        'training, general seed-space guarantee, new qualification or strength improvement tested.')
    parity.write(folder / 'local-audit.json', result)
    print(json.dumps(dict(passed=True, actualEngineeringExecutions=16,
                          auditSha256=parity.sha(folder / 'local-audit.json')), sort_keys=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    audit(parser.parse_args().root.absolute())
