"""Fixed53 roles/banks and sampled native arena evidence; no learning or engine execution."""
from pathlib import Path

import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import purchase_ecology_data as overlays

read, sha, require = exp.read, exp.sha, exp.require
ARMS = ('chronological', 'phase-separated')
COMPARISONS = ('chronological-anchor', 'phase-separated-anchor', 'chronological-separated')
PHASES = ('card', 'purchase')
BATCHES, WORLDS, PAIRS = 32, 256, 2048
REPLAYS = (0, 682, 1365, 2047)
M8 = 'b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4'
E = 'feb80d4363a93b266c94bc46e0d75ff4b0cb1f17a55d0f6d77782715862f44ef'
G2 = '09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af'
CARD = '98dd71c7911da94a6dc03151cde304589b1bc513b7c319e2d3c525bc3cc8689c'
CRITIC = 'ffdceb830f97513d07b3ab63c5b7f425545f91749dba3fba81e80ed3e63773eb'
DICTIONARY = 'b8e11b9d6fb4e3e66e64ecaf4ab39e139d78a8b70e6704bf6235c0e34554d883'
AUDIT = '95d50fea9478aa7fd45954b169500089a1bcd354aa666bed5aacff5a6b51d4a5'


def replay_index(index):
    require(type(index) is int and 1 <= index <= BATCHES, 'Use one of the32 fixed batch indices.')
    return (0, 85, 170, 255)[(index-1) % 4]


def collection_source(protocol, index, bundle):
    return dict(schemaVersion=1, kind='joint-collect', configuration=exp.CONFIGURATION,
        games=WORLDS, seed=protocol['banks'][f'training-{index}']['nativeOffset'],
        baseline=protocol['ordinary'], bundle=str(bundle), opponents=[protocol['anchorBundle']],
        jointStateChronology='joint-state-chronology-1', sharedPurchaseBaseline=protocol['prior'],
        experimentalRulesTransfer=legacy.RULES_TRANSFER, continuationProtocol='ARENA_OVERLAYS_1',
        expansionImitation=dict(candidateModel=protocol['expansion'], opponentModels=[protocol['expansion']]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15', opponentSelectors=['FIXED_16_TO_15']),
        replayIndices=[replay_index(index)])


def configs(protocol, index):
    replay_index(index)
    seeds = protocol['banks']['updates']['seeds']
    return {phase:dict(protocol['updateConfig'], seed=seeds[8*(index-1)+4*offset])
            for offset, phase in enumerate(PHASES)}


def bundle_subjects(path):
    path = Path(path).resolve(strict=True); bundle = read(path)
    require(set(bundle) == {'schemaVersion','kind','configuration','baselineSha256','card','purchase'}
        and bundle['schemaVersion'] == 1 and bundle['kind'] == 'joint-card-purchase-1'
        and bundle['configuration'] == exp.CONFIGURATION and bundle['baselineSha256'] == overlays.F0,
        'A native joint bundle needs the exact setup and separate F0 purchase prior.')
    result = dict(path=str(path), sha256=sha(path), kind=bundle['kind'],
                  configuration=bundle['configuration'], baselineSha256=bundle['baselineSha256'])
    for phase in PHASES:
        value = bundle[phase]
        if value is None:
            require(phase == 'card', 'Every bundle requires its explicit purchase actor.')
            result[phase] = None
            continue
        require(set(value) == {'path','sha256'}, 'Each component binds its exact raw model bytes.')
        subject = (path.parent/value['path']).resolve(strict=True)
        require(sha(subject) == value['sha256'], 'A joint bundle component changed.')
        model = read(subject)
        result[phase] = dict(path=str(subject), sha256=value['sha256'],
            encoderVersion=model['encoderVersion'], actorVersion=f'aor-{phase}-policy-1',
            stateFeatureNames=model['stateFeatureNames'], actionFeatureNames=model['actionFeatureNames'])
    return result


def validate_inputs(protocol):
    require(protocol['configuration'] == exp.CONFIGURATION and protocol['maximumAttempts'] == 1
        and protocol['arms'] == list(ARMS) and protocol['batchesPerArm'] == BATCHES
        and protocol['maximumTerminalExecutions'] == 28760 and protocol['maximumCriticFits'] == 0
        and protocol['maximumConsecutiveZeroAcceptedBatches'] == 4
        and all(protocol[key] is False for key in ('intermediateLooks','qualificationPassed',
            'productionActivation','automaticResume','automaticSuccessor','checkpointSelection')),
        'Use the finite two-clock sampled trial with final-only comparisons and no promotion.')
    for key, digest in (('ordinary',M8),('expansion',E),('prior',overlays.F0),('card',CARD),
                        ('purchase',G2),('critic',CRITIC),('dictionary',DICTIONARY),('seedAudit',AUDIT)):
        require(sha(protocol[key]) == digest, 'A fixed53 subject changed: '+key)
    anchor, initial = [bundle_subjects(protocol[key]) for key in ('anchorBundle','initialBundle')]
    require(anchor['card'] is None and anchor['purchase']['sha256'] == G2
        and initial['card']['sha256'] == CARD and initial['purchase']['sha256'] == G2,
        'Keep the actual raw-M8 G2 anchor distinct from the sampled zero-card learning initializer.')
    audit = read(protocol['seedAudit']); banks = protocol['banks']
    require(audit['allIntersectionsZero'] is True and audit['liveMetadataScans'] == 0
        and audit['humanInputsRead'] == 0 and banks == audit['seedBanks']
        and set(banks) == {*[f'training-{i}' for i in range(1,33)], 'screen','updates'},
        'Only the frozen53 ancestry, common worlds and common epoch shuffles are permitted.')
    seeds = banks['updates']['seeds']
    require(len(seeds) == 256 and seeds == list(range(seeds[0],seeds[0]+256)),
        'Reserve four disjoint epoch streams per phase and batch, shared across the two arms.')
    for name, bank in banks.items():
        if name == 'updates': continue
        count = PAIRS if name == 'screen' else WORLDS
        require(bank['count'] == count and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],count),
                'A frozen game bank differs from its native world formula.')
    exp.validate_banks({name:bank['seeds'] for name,bank in banks.items()})
    expected = dict(learningRate=.0003, epochs=4, batchSize=64, clip=.2, valueCoefficient=0.,
        entropyCoefficient=0., maxGradientNorm=.5, maxBehaviorKl=.01, threads=1, maxSeconds=900.,
        cardReferenceKlCoefficient=0., cardReferenceVersion='aor-card-physical-reference-1')
    require(protocol['updateConfig'] == expected, 'Both clocks retain the exact original actor-only PPO recipe.')
    require(sha(protocol['criticReview']) == '26ae2b7cae17bb100caaabf8291870803118e20a4237f928f0c9c0414606d1e8',
        'The exact independently reviewed52 critic prerequisite is required.')


def arena_source(protocol, bundles, name):
    require(name in COMPARISONS, 'Only three predeclared terminal contrasts are permitted.')
    roles = {'chronological-anchor':('chronological','anchor'),
             'phase-separated-anchor':('phase-separated','anchor'),
             'chronological-separated':('chronological','phase-separated')}
    candidate, incumbent = roles[name]
    models = dict(bundles, anchor=protocol['anchorBundle'])
    source = exp.arena_source(protocol['ordinary'], [protocol['ordinary']]*4,
        protocol['banks']['screen']['nativeOffset'], PAIRS, purpose='SCREENING')
    return dict(source, experimentalRulesTransfer=legacy.RULES_TRANSFER,
        **overlays.bindings(protocol['expansion'], arena=True,
                           rival_ecology=overlays.ArenaRivalEcology.HOMOGENEOUS),
        jointPolicy=dict(baseline=protocol['prior'], sharedBundle=protocol['anchorBundle'],
            candidateBundle=str(models[candidate]), incumbentBundle=str(models[incumbent])))


def joint_binding(source_path):
    source_path = Path(source_path).resolve(strict=True); source = read(source_path)
    roles = source['jointPolicy']
    result = dict(version='arena-joint-policy-1', source=dict(path=str(source_path),
        sha256=sha(source_path), snapshot='source.json'), baseline=dict(path=str(Path(roles['baseline']).resolve()),
        sha256=sha(roles['baseline']), snapshot='joint-purchase-baseline.json'))
    for key, prefix in (('sharedBundle','shared'),('candidateBundle','candidate'),('incumbentBundle','incumbent')):
        bundle = bundle_subjects(roles[key])
        result[key] = dict(bundle, snapshot=f'joint-{prefix}-bundle.json', **{phase:
            None if bundle[phase] is None else dict(bundle[phase],snapshot=f'joint-{prefix}-{phase}-policy.json')
            for phase in PHASES})
    return result


def verify_joint_roles(source_path, directory, reports):
    expected = joint_binding(source_path)
    require(all(report.get('jointPolicy') == expected for report in reports),
            'Arena/replay roles must bind actual sampled bundles and the separate raw-card anchor.')
    subjects = [expected['source'], expected['baseline']]
    for key in ('sharedBundle','candidateBundle','incumbentBundle'):
        bundle = expected[key]; subjects += [bundle]+[bundle[p] for p in PHASES if bundle[p] is not None]
    require(all(sha(Path(directory)/item['snapshot']) == item['sha256'] for item in subjects),
            'Joint role raw snapshots differ from their declared models.')
    source = read(source_path)
    overlays.verify_overlays(source,Path(directory),reports,arena=True,expected_expansion=E,
        rival_ecology=overlays.ArenaRivalEcology.HOMOGENEOUS)
    return expected


def verify_arena(protocol, source_path, directory):
    exp.verify_arena(directory,source_path)
    source = read(source_path)
    report, run, state = [read(directory/name) for name in ('evaluation.json','run.json','state.json')]
    expected = verify_joint_roles(source_path,directory,[report,run])
    require(run['kind'] == state['kind'] == 'strategy-arena' and run['signature'] == state['signature']
        and run['source'] == str(source_path) and run['workers'] == protocol['workers'],
        'Arena must retain its original source, signature and worker bound.')
    for metadata in (report,run):
        legacy.verify_transferred_sources(source,metadata,directory,
            {role:protocol['ordinary'] for role in ('candidate','incumbent',*[f'opponent-{i}' for i in range(4)])})
    require({path.name for path in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(PAIRS)},
            'Only the complete2048 paired terminal bank is permitted.')
    pairs = [read(directory/f'pair-{i}.json') for i in range(PAIRS)]
    for index, pair in enumerate(pairs):
        search.validate_pair(pair,index,protocol['banks']['screen']['seeds'][index],4)
        require(pair.get('jointPolicy') == expected, 'Each pair must retain exact sampled role identities.')
    metrics = search.comparison(pairs)
    search.verify_metrics(report['overall'],metrics)
    return pairs,metrics


def common_games(pairs):
    require(set(pairs) == set(COMPARISONS) and all(len(rows) == PAIRS for rows in pairs.values()),
            'All three final contrasts require the same complete world bank.')
    for chrono,separate,direct in zip(*(pairs[name] for name in COMPARISONS)):
        require(all(chrono[k] == separate[k] == direct[k] for k in
                    ('index','configuration','seed','seat','opponentIndices'))
            and search.same_game(chrono['incumbent'],separate['incumbent'])
            and search.same_game(chrono['candidate'],direct['candidate'])
            and search.same_game(separate['candidate'],direct['incumbent']),
            'Shared final worlds must reproduce both terminal actors and the actual anchor exactly.')
