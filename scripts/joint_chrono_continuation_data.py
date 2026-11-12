"""Fixed62 continuation roles, carry admission and compact final-screen evidence.

No native execution or learning lives here. The existing joint role, storage and numerical
owners retain their original contracts; this profile supplies only its fixed inputs/schedule.
"""
from pathlib import Path

import automata_experiments as exp
import automata_policy_search as search
import automata_purchase_continuation as legacy
import joint_chrono_evidence as chunks
import joint_chrono_qualification as qualified
import joint_credit_trial_data as original
import purchase_ecology_data as ecology

read, sha, require = exp.read, exp.sha, exp.require
PHASES = original.PHASES
BATCHES, WORLDS, PAIRS, CHUNK_PAIRS = 64, 256, 1024, 512
CHECKPOINTS, REPLAYS = (16, 32, 64), (0, 341, 682, 1023)
AUDIT = '56466d6bdbe7e8cd1d6d903b4caaddfe710e8b3aa4125482b431d9e1dc4bbfde'
CARRY_REVIEW = 'a167ae41f09c267925c1e8c152dc25d534255e57efd86a3b6ae1cba38231f473'
QUALIFICATION_REVIEW = '46424285f5d35b32ea6053eead57ba0aa1b8d676588777bbd170c92211bcf546'
INITIAL_CHECKPOINTS = dict(
    card='4fca17cdfb1387e17816d880176adb604b8cf35e8d1f6b0aad52ff1a480cd7e6',
    purchase='a525e093ed090db2b71b754fbf307a02976f6e65d4265b6c5f66649c619e5a25')
UPDATE_CONFIG = dict(learningRate=.0003, epochs=4, batchSize=64, clip=.2,
    valueCoefficient=0., entropyCoefficient=0., maxGradientNorm=.5, maxBehaviorKl=.01,
    threads=1, maxSeconds=900., cardReferenceKlCoefficient=0.,
    cardReferenceVersion='aor-card-physical-reference-1')
DISCLOSURE = ('Prescribed reached checkpoints16/32/64 are screened only after scientific '
    'termination on the same1024 development worlds. Positive win and reward means admit '
    'nomination; highest win mean wins, with later checkpoint breaking exact ties. '
    'This reused development bank and checkpoint selection are not independent qualification.')


def replay_index(index):
    require(type(index) is int and 1 <= index <= BATCHES, 'Use a fixed batch index1..64.')
    return (0, 85, 170, 255)[(index - 1) % 4]


def configs(protocol, index):
    replay_index(index)
    return {phase: dict(protocol['updateConfig'],
        seed=protocol['banks']['updates']['seeds'][8 * (index - 1) + 4 * number])
        for number, phase in enumerate(PHASES)}


def collection_source(protocol, index, bundle):
    replay_index(index)
    return dict(schemaVersion=1, kind='joint-collect', configuration=exp.CONFIGURATION,
        games=WORLDS, seed=protocol['banks'][f'training-{index}']['nativeOffset'],
        baseline=protocol['ordinary'], bundle=str(bundle), opponents=[protocol['initialBundle']],
        jointStateChronology='joint-state-chronology-1', sharedPurchaseBaseline=protocol['prior'],
        experimentalRulesTransfer=legacy.RULES_TRANSFER, continuationProtocol='ARENA_OVERLAYS_1',
        expansionImitation=dict(candidateModel=protocol['expansion'],
                               opponentModels=[protocol['expansion']]),
        tokenOrder=dict(candidateSelector='FIXED_16_TO_15', opponentSelectors=['FIXED_16_TO_15']),
        replayIndices=[replay_index(index)])


def subject(pin):
    require(set(pin) == {'path', 'sha256'} and sha(pin['path']) == pin['sha256'],
            'A reviewed input subject changed or has unsupported fields.')
    return read(pin['path'])


def validate_inputs(protocol):
    require(protocol['configuration'] == exp.CONFIGURATION and protocol['maximumAttempts'] == 1
        and protocol['batches'] == BATCHES and protocol['checkpoints'] == list(CHECKPOINTS)
        and protocol['maximumTerminalExecutions'] == 22616
        and protocol['maximumPhaseUpdates'] == 128 and protocol['maximumCriticFits'] == 0
        and protocol['maximumConsecutiveZeroAcceptedBatches'] == 4
        and protocol['selectionDisclosure'] == DISCLOSURE
        and protocol['updateConfig'] == UPDATE_CONFIG
        and all(protocol[key] is False for key in ('intermediateLooks', 'qualificationPassed',
            'productionActivation', 'automaticResume', 'automaticSuccessor')),
        'Use only the fixed64-batch chronological continuation and terminal development screen.')
    for key, digest in (('ordinary', original.M8), ('expansion', original.E),
                        ('prior', ecology.F0), ('initialBundle', qualified.CANDIDATE),
                        ('seedAudit', AUDIT)):
        require(sha(protocol[key]) == digest, 'A fixed62 subject changed: ' + key)
    initial = original.bundle_subjects(protocol['initialBundle'])
    require(initial['card']['sha256'] == qualified.CARD
        and initial['purchase']['sha256'] == qualified.PURCHASE,
        'Both qualified G3 learned heads remain the initial policy and fixed rival population.')
    review = subject(protocol['qualificationReview'])
    require(protocol['qualificationReview']['sha256'] == QUALIFICATION_REVIEW
        and review['passed'] is True and review['qualificationPassed'] is True
        and review['candidateBundleSha256'] == qualified.CANDIDATE
        and review['candidateCardSha256'] == qualified.CARD
        and review['candidatePurchaseSha256'] == qualified.PURCHASE
        and review['independentlyRecomputedPairs'] == 8192,
            'Initial G3 requires an exact independently reviewed completed qualification.')
    audit = read(protocol['seedAudit']); banks = protocol['banks']
    require(audit['allIntersectionsZero'] is True and audit['liveMetadataScans'] == 0
        and audit['humanInputsRead'] == 0 and banks == audit['seedBanks']
        and set(banks) == {*[f'training-{i}' for i in range(1, 65)], 'screen', 'updates'},
        'Use the exact frozen62 ancestry and every reserved training/screen/shuffle bank.')
    seeds = banks['updates']['seeds']
    require(len(seeds) == 512 and seeds == list(range(seeds[0], seeds[0] + 512)),
            'Each batch/head receives four consecutive disjoint actor epoch-order seeds.')
    for name, bank in banks.items():
        if name == 'updates':
            continue
        count = PAIRS if name == 'screen' else WORLDS
        require(bank['count'] == count and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'], count),
                'A frozen62 bank differs from its exact native world formula.')
    exp.validate_banks({name: bank['seeds'] for name, bank in banks.items()})


def initial_carry_subjects(protocol):
    """Raw file/review guard; the unchanged typed Adam owner validates all moments separately."""
    require(set(protocol['initialCheckpoints']) == set(PHASES), 'Carry both actual original phase checkpoints.')
    review = subject(protocol['initialCarryReview'])
    require(protocol['initialCarryReview']['sha256'] == CARRY_REVIEW
        and review['passed'] is True and review['arm'] == 'chronological'
        and review['terminalBundleSha256'] == qualified.CANDIDATE
        and review['receiptChainVerified'] == 32 and review['scientificTerminalGames'] == 8192
        and review['historicalReproductions'] == 32 and review['screenAdmitted'] is True,
        'Initial carry requires the independently reviewed completed original53 Mac arm.')
    initial = original.bundle_subjects(protocol['initialBundle'])
    roots = set()
    for phase, pin in protocol['initialCheckpoints'].items():
        require(pin['sha256'] == INITIAL_CHECKPOINTS[phase] and sha(pin['path']) == pin['sha256'],
                'The actual final53 Adam checkpoint bytes must remain exact; no reset or substitution.')
        path = Path(pin['path'])
        require(path.is_absolute() and path.name == 'checkpoint.json'
            and path.parent.name == phase and path.parent.parent.name == 'pair'
            and path.parent.parent.parent.name == 'numeric'
            and path.parent.parent.parent.parent.name == 'batch-32',
            'The initial carry must be the retained original53 Mac batch32, not a reset or selected checkpoint.')
        run = path.parents[5]; roots.add(str(run))
        require(sha(run/'arm-report.json') == review['armReportSha256'],
                'The original terminal review must bind the actual arm report.')
        report = read(run/'arm-report.json'); receipt_path = path.parents[3]/'receipt.json'
        require(report['portableBundle']['sha256'] == qualified.CANDIDATE
            and report['batches'][-1] == dict(index=32, path=str(receipt_path), sha256=sha(receipt_path)),
            'The reviewed terminal report must bind the exact final batch receipt.')
        receipt = read(receipt_path)
        require(receipt['completed'] is True and receipt['batch'] == 32 and receipt['arm'] == 'chronological',
                'Both initial carries must come from the completed original chronological batch32.')
        for name in ('checkpoint', 'identity', 'finished', 'policy'):
            require(sha(path.parent/(name + '.json')) == receipt['artifacts'][f'numeric/pair/{phase}/{name}.json'],
                    'A completed original carry sibling changed from its reviewed receipt.')
        require(sha(path.parent/'policy.json') == initial[phase]['sha256'],
                'Initial phase Adam must belong to the exact qualified G3 actor.')
    require(len(roots) == 1, 'Both actual phase carries must belong to the same original Mac run.')
    return {phase: Path(pin['path']) for phase, pin in protocol['initialCheckpoints'].items()}


def arena_source(protocol, bundle, chunk):
    require(type(chunk) is int and 0 <= chunk < 2, 'Use one of two fixed512-pair chunks.')
    offset = protocol['banks']['screen']['nativeOffset'] + chunk * CHUNK_PAIRS * 8191
    result = exp.arena_source(protocol['ordinary'], [protocol['ordinary']] * 4,
                              offset, CHUNK_PAIRS, purpose='SCREENING')
    return dict(result, experimentalRulesTransfer=legacy.RULES_TRANSFER,
        **ecology.bindings(protocol['expansion'], arena=True,
                           rival_ecology=ecology.ArenaRivalEcology.HOMOGENEOUS),
        jointPolicy=dict(baseline=protocol['prior'], sharedBundle=protocol['initialBundle'],
            candidateBundle=str(bundle), incumbentBundle=protocol['initialBundle']))


def verify_chunk(protocol, source_path, directory, bundle, chunk):
    source_path, directory = Path(source_path), Path(directory)
    source = read(source_path)
    require(source == arena_source(protocol, bundle, chunk), 'Completed screen source changed its prescribed roles.')
    report, run, state = [read(directory/name) for name in ('evaluation.json', 'run.json', 'state.json')]
    require(report['complete'] is True and report['purpose'] == 'SCREENING'
        and report['promoted'] is False and report['humanStrengthVerified'] is False
        and state['phase'] == 'complete' and bool(read(directory/'finished.json')['at'])
        and run['kind'] == state['kind'] == 'strategy-arena'
        and run['signature'] == state['signature'] and run['source'] == str(source_path)
        and run['workers'] == protocol['workers'] and run['pairs'] == CHUNK_PAIRS
        and run['seed'] == source['seed'] and run['opponentModels'] == 4,
        'Require all completed native chunk reports and their exact source/worker bounds.')
    roles = original.verify_joint_roles(source_path, directory, [report, run])
    for metadata in (report, run):
        legacy.verify_transferred_sources(source, metadata, directory,
            {role: protocol['ordinary'] for role in ('candidate', 'incumbent', *[f'opponent-{i}' for i in range(4)])})
    require({path.name for path in directory.glob('pair-*.json')} == {f'pair-{i}.json' for i in range(CHUNK_PAIRS)},
            'Every screen chunk must contain exactly512 paired worlds.')
    rows = []
    for index in range(CHUNK_PAIRS):
        path = directory/f'pair-{index}.json'; global_index = chunk * CHUNK_PAIRS + index
        rows.append(chunks.compact_pair(read(path), roles, index, global_index,
            protocol['banks']['screen']['seeds'][global_index], sha(path), sha(source_path)))
    metrics = search.comparison(rows)
    search.verify_metrics(report['overall'], metrics)
    require(set(report['byConfiguration']) == {exp.CONFIGURATION}, 'Only one frozen setup is permitted.')
    search.verify_metrics(report['byConfiguration'][exp.CONFIGURATION], metrics)
    return dict(rows=rows, metrics=metrics, roles=roles)


def verify_replay(protocol, source_path, directory, row):
    directory = Path(directory)
    replay, native, state = [read(directory/name) for name in ('replay.json', 'run.json', 'state.json')]
    side = replay['side'].lower()
    require(row['globalIndex'] in REPLAYS and row['index'] == row['globalIndex'] % CHUNK_PAIRS
        and sha(source_path) == row['sourceSha256']
        and side in ('candidate', 'incumbent') and replay['replayVerified'] is True
        and replay['historicalPairVerified'] is True and native['source'] == str(source_path)
        and native['workers'] == 1 and native['signature'] == state['signature']
        and state['phase'] == 'complete' and bool(read(directory/'finished.json')['at'])
        and all(replay[key] == row[key] for key in chunks.IDENTITY)
        and search.same_game(replay['game'], row[side])
        and sha(native['recordedPair']) == row['pairSha256'],
        'Historical replay must reproduce its exact original sampled side and pair.')
    roles = original.verify_joint_roles(source_path, directory, [native, state])
    require(replay['jointPolicy'] == roles, 'Historical replay roles changed.')
    source = read(source_path)
    references = {role: protocol['ordinary'] for role in
                  ('candidate', 'incumbent', *[f'opponent-{i}' for i in range(4)])}
    for metadata in (native, state):
        legacy.verify_transferred_sources(source, metadata, directory, references)
    return dict(globalIndex=row['globalIndex'], index=row['index'], side=side,
                replaySha256=sha(directory/'replay.json'))


def common_incumbent(screens):
    require(bool(screens) and all(len(rows) == PAIRS for rows in screens.values()),
            'Every reached checkpoint uses the full common1024-world screen.')
    first = next(iter(screens.values()))
    for rows in screens.values():
        require(all(all(a[key] == b[key] for key in (*chunks.IDENTITY, 'globalIndex'))
            and search.same_game(a['incumbent'], b['incumbent']) for a, b in zip(first, rows)),
            'The unchanged G3 incumbent must reproduce exactly across checkpoint comparisons.')


def select_checkpoint(metrics):
    require(set(metrics) <= set(CHECKPOINTS), 'Only prescribed reached checkpoints may be nominated.')
    eligible = [index for index, value in metrics.items()
                if value['winShareDifference']['mean'] > 0 and value['rewardDifference']['mean'] > 0]
    return max(eligible, key=lambda index: (metrics[index]['winShareDifference']['mean'], index), default=None)
