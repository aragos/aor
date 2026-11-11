"""Independent artifact-only validation of the fixed weighted32-world host comparison."""
import copy
from pathlib import Path

import expansion_target_search as target

read,sha,require = target.read,target.sha,target.require
COORDINATES = [1.,-1.,2.,-2.]


def member(root,name):
    path = (root/name).resolve()
    require(not Path(name).is_absolute() and path.is_relative_to(root.resolve()),
            'Portability artifact paths must stay relative to their declared proof root.')
    return path


def without_elapsed(pair):
    result = copy.deepcopy(pair)
    for side in ('candidate','incumbent'):
        require('elapsedMillis' in result[side],'Portability pair must retain its original elapsed field.')
        del result[side]['elapsedMillis']
    return result


def verify(root,expected_sha,template):
    root = Path(root).resolve()
    path = root/'receipt.json'
    require(sha(path) == expected_sha,'Reviewed weighted-portability receipt changed.')
    receipt = read(path)
    require(receipt['schemaVersion'] == 1 and receipt['kind'] == 'expansion-target-weighted-portability-1'
            and receipt['passed'] is True and receipt['qualificationPassed'] is False and receipt['fitting'] is False
            and receipt['coordinates'] == COORDINATES
            and receipt['rawWeights'] == dict(zip(target.models.FEATURES,[8.,-8.,16.,-16.]))
            and receipt['bank'] == template['portability']['bank']
            and receipt['comparedPairs'] == 32 and receipt['allOtherPairFieldsExact'] is True
            and receipt['excludedFields'] == ['candidate.elapsedMillis','incumbent.elapsedMillis']
            and all(receipt['runtime'][name] == digest for name,digest in target.RUNTIME_JARS.items()),
            'Portability must retain exact operational scope, coordinates, bank and target runtime.')
    files = receipt['artifactHashes']
    require(files and all(sha(member(root,name)) == digest for name,digest in files.items()),
            'Compared weighted-portability artifacts changed.')
    bank = receipt['bank']
    require(bank['count'] == 32 and bank['seeds'] == target.exp.arena_seeds(bank['nativeOffset'],32),
            'Portability must retain its exact32 reserved origins.')
    pairs = {}
    for host,workers in (('mac',1),('desktop',6)):
        declaration = receipt['hosts'][host]
        require(declaration['workers'] == workers and declaration['pairs'] == 32
                and isinstance(declaration['artifactPrefix'],str) and declaration['artifactPrefix'].endswith('/')
                and declaration['pairDirectory'].startswith(declaration['artifactPrefix']),
                'Weighted parity must compare the declared Mac1 and desktop6 worker executions.')
        directory = member(root,declaration['pairDirectory'])
        require({path.name for path in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(32)},
                'Weighted portability requires exactly its32 complete pair files per host.')
        host_hashes = {digest for name,digest in files.items() if name.startswith(declaration['artifactPrefix'])}
        require(all(declaration[key] in host_hashes for key in
                    ('identitySha256','sourceSha256','nativeRunSha256','nativeFinishedSha256','statusSha256')),
                'Every declared host identity/source/completion hash must belong to its compared artifacts.')
        require(sha(directory/'run.json') == declaration['nativeRunSha256']
                and sha(directory/'finished.json') == declaration['nativeFinishedSha256'],
                'Native weighted portability completion bytes changed.')
        native,state,finished = [read(directory/name) for name in ('run.json','state.json','finished.json')]
        require(native['workers'] == workers and native['kind'] == 'strategy-arena'
                and native['signature'] == state['signature'] and state['phase'] == 'complete'
                and bool(finished['at'])
                and files.get(str((directory/'state.json').relative_to(root))) == sha(directory/'state.json'),
                'Weighted native worker count or complete-state identity changed.')
        pairs[host] = []
        for index in range(32):
            pair_path = directory/f'pair-{index}.json'
            require(files.get(str(pair_path.relative_to(root))) == sha(pair_path),
                    'Every compared weighted pair must be explicitly hash-bound.')
            pair = read(pair_path)
            target.search.validate_pair(pair,index,bank['seeds'][index],4)
            pairs[host].append(pair)
    require(all(without_elapsed(first) == without_elapsed(second)
                for first,second in zip(pairs['mac'],pairs['desktop'])),
            'Weighted host parity differs outside the two declared elapsed fields.')
    return receipt
