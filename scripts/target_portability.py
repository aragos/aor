"""Independent artifact-only validation of the fixed weighted32-world host comparison."""
import copy
from pathlib import Path

import expansion_target_search as target

read,sha,require = target.read,target.sha,target.require
COORDINATES = [1.,-1.,2.,-2.]
JOINT_COORDINATES = COORDINATES*3


def verify_joint_models(directory, native, template, receipt):
    """Bind nonzero12-coordinate exports and actual native phase/rival snapshots."""
    ordinary, expanded, baseline, expansion = [read(directory/name) for name in (
        'candidate.json','expansion-imitation-candidate.json','incumbent.json',
        'expansion-imitation-incumbent.json')]
    settings = template['jointExpansion']
    expected = target.models.joint.ANCHORS[settings['anchor']]
    require((sha(directory/'incumbent.json'),sha(directory/'expansion-imitation-incumbent.json')) == expected,
            'Joint portability must preserve the exact declared qualified anchor.')
    candidate = target.models.coordinate.export_model(JOINT_COORDINATES,expansion,
        target.models.profile(template),expansion['rulesVersion'])
    expected_ordinary,expected_expansion = target.models.joint.export_models(candidate,baseline,expansion,settings)
    require(ordinary == expected_ordinary and expanded == expected_expansion
            and receipt['rawWeights'] == {name:expanded['weights'][name] for name in target.models.joint.FEATURES},
            'Weighted joint portability must exercise exactly all12 declared nonzero coordinates.')
    source = read(directory/'source.json')
    binding = target.models.bindings(dict(baseline=source['incumbent'],
        expansionModel=source['expansionImitation']['incumbentModel']),source['candidate'],
        source['expansionImitation']['candidateModel'],'mixed')
    require(all(source[key] == value for key,value in binding.items())
            and native['tokenOrder'] == binding['tokenOrder'],
            'Joint portability must preserve all mixed rival roles and token selectors.')
    require(set(native['ordinaryModelSources']) == {'candidate','incumbent',*(f'opponent-{i}' for i in range(4))}
            and set(native['frozenPurchase']) == {'baseline','policy'},
            'Joint portability must retain every ordinary and shared Purchase role.')
    for role,descriptor in native['ordinaryModelSources'].items():
        expected_hash = sha(directory/'candidate.json') if role == 'candidate' else expected[0]
        require(sha(directory/descriptor['snapshot']) == descriptor['sha256'] == expected_hash,
                'Joint portability ordinary model role changed.')
    expansion_roles = native['expansionImitation']
    for role,model in (('candidateModel',expanded),('incumbentModel',expansion)):
        descriptor = expansion_roles[role]
        require(read(directory/descriptor['snapshot']) == model
                and sha(directory/descriptor['snapshot']) == descriptor['sha256'],
                'Joint portability expansion model role changed.')
    require(len(expansion_roles['opponentModels']) == 4,'Joint portability requires all four rival roles.')
    for index,descriptor in enumerate(expansion_roles['opponentModels']):
        if index in (0,2):
            require(descriptor is None,'Ordinary rivals must not receive an expansion overlay.')
        else:
            require(descriptor is not None and sha(directory/descriptor['snapshot']) == descriptor['sha256'] == expected[1],
                    'Expanded rivals must retain the qualified expansion overlay.')
    for role,descriptor in native['frozenPurchase'].items():
        require(sha(directory/descriptor['snapshot']) == descriptor['sha256'] == template['frozenPurchaseSha256'][role],
                'Joint portability must preserve the shared purchase policy and prior.')


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
    joint = 'jointExpansion' in template
    require(receipt['schemaVersion'] == 1 and receipt['kind'] == (
                'expansion-joint-weighted-portability-1' if joint else 'expansion-target-weighted-portability-1')
            and receipt['passed'] is True and receipt['qualificationPassed'] is False and receipt['fitting'] is False
            and receipt['coordinates'] == (JOINT_COORDINATES if joint else COORDINATES)
            and (joint or receipt['rawWeights'] == dict(zip(target.models.FEATURES,[8.,-8.,16.,-16.])))
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
    for host,workers in (('mac',1),('desktop',10 if joint else 6)):
        declaration = receipt['hosts'][host]
        require(declaration['workers'] == workers and declaration['pairs'] == 32
                and isinstance(declaration['artifactPrefix'],str) and declaration['artifactPrefix'].endswith('/')
                and declaration['pairDirectory'].startswith(declaration['artifactPrefix']),
                'Weighted parity must compare the exact declared Mac and desktop worker executions.')
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
        if joint:
            snapshots = list(directory.glob('*.json'))
            require(all(files.get(str(path.relative_to(root))) == sha(path) for path in snapshots),
                    'Every joint native snapshot and receipt must be hash-bound.')
            verify_joint_models(directory,native,template,receipt)
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
