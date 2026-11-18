"""Prove fixed resource prefixes against immutable RNG reservations, without new declarations."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path


METHOD_92 = '193edfd32bad8c1143350ed51831a6b229f731cc2cfeb25ec13c791994d822f9'
METHOD_93 = '0ee2ed67c272bc5e9035d7ad77ba8204b6a53d81a3eb9cefc78fcebe09c4aef2'
COUNTS_92 = {'development': 64, 'mixed': 256, 'homogeneous': 256, 'reference': 256}
COUNTS_93 = {'development': 64, 'mixed': 256, 'homogeneous': 256}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()+b'\n'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def subject(path):
    return dict(path=str(Path(path).resolve()), sha256=sha(path))


def checked(path, expected):
    require(sha(path) == expected, 'A frozen metadata subject changed: '+str(path))
    return json.loads(Path(path).read_bytes())


def partition(banks, counts):
    require(set(banks) == set(counts), 'Every original bank must remain accounted for.')
    active, unused = {}, {}
    for name, count in counts.items():
        bank = banks[name]
        require(type(count) is int and 0 < count <= len(bank['seeds']) == bank['count'],
            'An active bank must be a positive original prefix without top-up.')
        active[name], unused[name] = bank['seeds'][:count], bank['seeds'][count:]
    return active, unused


def prepare_proofs(training_root):
    base = Path(training_root).resolve()
    roots = {92: base/'automata-learned-bid-search-20261118',
        93: base/'automata-mac-leader-search-20261118',
        94: base/'automata-mac-qualification-small-20261118'}
    pins = {
        92: ('7df8f7ab2ddce6807731f55218b4646d2ffed6da2b644b7df74fb51bd576b3f4',
            'a49c271f9a1c1ecf69ce02345713b777209dbd1a5b6657bdefe8880e8616c987',
            '66fa3c163699a2ac1e062a271f29af132eef81a76eeccb7b066e5c8600ef4a07'),
        93: ('78c59913811288b71a8c1bfa08848f8a82b592061066a819d7801bb5dda442cb',
            'e72aac1b0fae40b0cbc92b452888f7152378ea812e1b3b6d9ecbcb18b4a72a35',
            'c5350c2234637651c01437fbb0e9501edec83b10cb7bfaa894a267c8d975a974'),
        94: ('55b4d7c184cd65a87ce50a5ece4b3fe3fb7ca5a22524ac2dd96064c92ed75a30',
            'ef73c0c82f3e1864a6ed168f4b08c150f48f851dda224465361ca08cee390706',
            '38868e0f21a940fda2cc23aa965defa2501f2ec6f38322d5ad1c496f646d894f'),
    }
    audits, schedules, root_proofs = {}, {}, {}
    for family, root in roots.items():
        audits[family] = checked(root/'audit/seed-audit.json', pins[family][0])
        schedules[family] = checked(root/'audit/schedule.json', pins[family][1])
        root_proofs[family] = checked(root/'audit/root-independent-verification.json', pins[family][2])
        require(root_proofs[family]['passed'] is True and
            root_proofs[family]['auditSha256'] == pins[family][0] and
            root_proofs[family]['scheduleSha256'] == pins[family][1],
            'Every original complete root RNG proof must bind its immutable audit and schedule.')
        require(schedules[family]['banks'] == audits[family]['seedBanks'],
            'Original schedules must retain the complete original reservation.')
    ancestors = [audits[94]['carriedForwardAudit'], *audits[94]['verifiedAncestors']]
    require(len(ancestors) == len({row['path'] for row in ancestors}) == 75,
        'All 75 original ancestry pins, including every unused bank, must stay intact.')
    for item in ancestors:
        checked(item['path'], item['sha256'])
    latest = {value for bank in schedules[94]['banks'].values() for value in bank['seeds']}
    result = {}
    for family, counts, filename, expected, old_sha, executions in (
        (92, COUNTS_92, 'prospective-method-v3.json', METHOD_92,
            '82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36', 2648),
        (93, COUNTS_93, 'prospective-method-v2.json', METHOD_93,
            '20a3a84a7c03a601fffffe20fe08c691ff22e691b87b992b22a1d5fb17ee79fa', 2128),
    ):
        root = roots[family]
        old = checked(root/'prospective-method.json', old_sha)
        method = checked(root/filename, expected)
        require({bank['name']: bank['worlds'] for bank in method['banks']} == counts and
            method['maximumTerminalExecutions'] == executions and
            method['caseConfigs'] == old['caseConfigs'] and method['noNewFits'] is True,
            'Only the root-fixed resource prefixes may change; every original case remains fixed.')
        require(method['globalDeadlineEpoch'] == old['globalDeadlineEpoch'] == 1795020399.0,
            'The original user deadline cannot be renewed by a resource refinement.')
        absent = ['preparation', 'preparation-v2', 'preparation-v3', 'execution', 'mac', 'mac-v2', 'desktop']
        require(not any((root/name).exists() for name in absent),
            'The refinement must be frozen before actual search preparation or data exists.')
        active, unused = partition(schedules[family]['banks'], counts)
        original_values = {value for bank in schedules[family]['banks'].values() for value in bank['seeds']}
        active_values = {value for bank in active.values() for value in bank}
        unused_values = {value for bank in unused.values() for value in bank}
        require(not active_values.intersection(unused_values) and
            active_values | unused_values == original_values and not original_values.intersection(latest),
            'Every original suffix remains held and all original declarations are disjoint from94.')
        # The side proof references older and newer audits without rewriting either parent edge.
        require(any(item['sha256'] == pins[family][0] for item in ancestors),
            'The latest94 DAG must already protect the complete original search reservation.')
        records = {name: dict(fullReservedWorlds=len(schedules[family]['banks'][name]['seeds']),
            activeReservedWorlds=len(active[name]), unusedReservedWorlds=len(unused[name]),
            nativeOffset=schedules[family]['banks'][name]['nativeOffset'],
            activePrefixSha256=hashlib.sha256(canonical(active[name])).hexdigest(),
            heldSuffixSha256=hashlib.sha256(canonical(unused[name])).hexdigest()) for name in counts}
        result[family] = dict(kind='mac-search-resource-refinement-proof-1', passed=True,
            createdUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), study=family,
            auditSha256=pins[family][0], scheduleSha256=pins[family][1], methodSha256=expected,
            originalAudit=subject(root/'audit/seed-audit.json'),
            originalSchedule=subject(root/'audit/schedule.json'),
            originalFullRootProof=subject(root/'audit/root-independent-verification.json'),
            originalMethod=subject(root/'prospective-method.json'), method=subject(root/filename),
            compatibility94Audit=subject(roots[94]/'audit/seed-audit.json'),
            compatibility94RootProof=subject(roots[94]/'audit/root-independent-verification.json'),
            activeReservedWorlds=len(active_values), fullReservedWorlds=len(original_values),
            unusedReservedWorlds=len(unused_values), banks=records, maximumTerminalExecutions=executions,
            globalDeadlineEpoch=method['globalDeadlineEpoch'], originalUserDeadlineUnchanged=True,
            ancestryPinsRehashed=75, allOriginalMethodsAuditsSchedulesUnchanged=True,
            allUnusedSuffixesProtected=True, exactOriginalPrefixesOnly=True,
            allOriginalSearchDeclarationsDisjointFrom94=True, noActualSearchDataOrPreparation=True,
            absentPathsVerified=[str(root/name) for name in absent], originalCasesUnchanged=True,
            original91OutcomeQualityReads=0, newScientificGames=0, newFits=0, newNumericalDraws=0,
            newStreamDeclarations=0, originalScheduleRewritten=False, circularAncestryIntroduced=False,
            verifier=subject(__file__))
    require(result[92]['activeReservedWorlds'] == result[92]['unusedReservedWorlds'] == 832 and
        result[92]['fullReservedWorlds'] == 1664 and result[93]['activeReservedWorlds'] == 576 and
        result[93]['unusedReservedWorlds'] == 0, 'The exact active/full/held inventories must match.')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--training-root', type=Path, required=True)
    parser.add_argument('--output92', type=Path, required=True)
    parser.add_argument('--output93', type=Path, required=True)
    args = parser.parse_args()
    proofs = prepare_proofs(args.training_root)
    for family, path in [(92, args.output92), (93, args.output93)]:
        with path.open('xb') as stream:
            stream.write(canonical(proofs[family]))
        print(json.dumps(dict(study=family, proof=str(path), sha256=sha(path)), sort_keys=True))
