"""Exact69 nominee/league admission for the existing bounded qualification executor.

This owner changes source roles and immutable subjects only. Native games, paired statistics,
canonical replays and lossless chunk storage remain with their existing owners.
"""
import automata_experiments as exp
import joint_chrono_continuation_data as continuation
import joint_chrono_evidence as evidence
import joint_credit_trial_data as joint
import purchase_ecology_data as ecology

KIND = 'joint-successor-fixed-qualification-1'
PROFILE = 'g3-successor-league-1'
CANDIDATE = '99fa1df3ead81e195228abcbd43f14268c1a779269429e3f59a48e6d10e8d8c3'
CARD = 'cc39dd677130f6fde17cfb234664d2aa81b08e763018e81d057c83b893257dd3'
PURCHASE = '77b21457c37d2059f6c86dcb32482385d255be600881562d3de41deeb3cd63aa'
G1 = '47f009bd670d1e8f77d10520f8af5e73d3dc96e873cdc7e33658ccae600266c7'
G3 = '65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d'
G3_CARD = '916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf'
G3_PURCHASE = '6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79'
AUDIT = 'b9f2cf3a8238e492963ec89b600ad5dd6b6bd9a4920c12fb6ce750cb71f85b4c'
DEVELOPMENT_AUDIT = '8c772b8acc9b3093eb57fc2547eee63a1808c78f045a6d9a4acfed92a7b2949c'
DEVELOPMENT_REPORT = 'fd1fe3954c087efbf9ee402a118a7951671466dadf093224d6fdda4840ad95ab'
NOMINATION_REVIEW = '6cade037c306432f31eca1c53450b733bd5caef1ef6f94697403a9e19a7c8ab5'
RETENTION = '22fe777d2115b584e6c2f5da6d48e36264136377ac33e1f40f760d3436492ecb'
LEARNING_REVIEW = '49b1ed2b74678fe4e601ae861a1d1609002531571a84e56b10ca3e24fbaefb69'
G3_REVIEW = '46424285f5d35b32ea6053eead57ba0aa1b8d676588777bbd170c92211bcf546'
LEAGUE = ('C64', 'G1', 'G2', 'G3') * 2
DISCLOSURE = (
    'Exact checkpoint16 was nominated by the predeclared62 positive-means/highest-win rule '
    'after all16/32/64 checkpoints used one common1024-world development bank. '
    'This selection is not independent qualification or proof of checkpoint superiority. '
    'Confirmation mixes homogeneous historical/current rival tables across fixed512-world '
    'chunks; heldout rivals and both strength incumbents are unchanged qualified G3. '
    'No runner-up fallback, tuning, top-up or automatic successor is permitted.')

read, sha, require = exp.read, exp.sha, exp.require


def evidence_subject(protocol, key, digest):
    path = protocol[key]
    require(sha(path) == digest, 'An exact69 admission subject changed: ' + key)
    return read(path)


def validate_nomination(protocol):
    audit = evidence_subject(protocol, 'developmentAudit', DEVELOPMENT_AUDIT)
    report = evidence_subject(protocol, 'developmentReport', DEVELOPMENT_REPORT)
    review = evidence_subject(protocol, 'nominationReview', NOMINATION_REVIEW)
    retention = evidence_subject(protocol, 'screenRetention', RETENTION)
    learning = evidence_subject(protocol, 'learningReview', LEARNING_REVIEW)
    require(audit['passed'] is True and audit['status'] == 'completed'
        and audit['allOwnedProcessesExited'] is True and audit['verifiedPairs'] == 3072
        and audit['verifiedHistoricalSideReplays'] == 24
        and audit['reachedCheckpoints'] == [16, 32, 64]
        and audit['commonIncumbentParityPassed'] is True
        and audit['screenReportSha256'] == DEVELOPMENT_REPORT,
        'Qualification requires all three complete development screens and original cleanup.')
    require(report['completed'] is True and report['metrics'] == audit['metrics']
        and report['nominee'] == audit['nominee'] == 16
        and report['nomineeBundle']['sha256'] == CANDIDATE
        and continuation.select_checkpoint({int(k):v for k,v in report['metrics'].items()}) == 16,
        'Only the original fixed-rule checkpoint16 nominee may enter independent qualification.')
    require(review['passed'] is True and review['terminalAuditSha256'] == DEVELOPMENT_AUDIT
        and review['screenReportSha256'] == DEVELOPMENT_REPORT
        and review['nominee'] == 16 and review['bundleSha256'] == CANDIDATE
        and review['selectionRecomputedFromCompleteAuditedBank'] is True,
        'The independently reviewed nomination must bind the exact complete development evidence.')
    require(retention['passed'] is True and retention['terminalAuditSha256'] == DEVELOPMENT_AUDIT
        and retention['verifiedPairs'] == 3072 and retention['verifiedHistoricalSides'] == 24
        and retention['verifiedArchiveMembers'] == 3942
        and retention['commonIncumbentParityPassed'] is True
        and retention['nominee'] == 16 and retention['metrics'] == report['metrics'],
        'Full lossless development archives and their original native worlds must be verified.')
    require(learning['passed'] is True and learning['completedBatches'] == 64
        and learning['newWorlds'] == 16384 and learning['historicalReproductions'] == 64
        and learning['acceptedEpochsPerHead'] == 256,
        'Preserve the completed64-batch learning provenance; no earlier scientific stop is substituted.')


def validate_roles(protocol):
    require(protocol['jointRivalProfile'] == PROFILE
        and set(protocol['rivalBundles']) == {'C64', 'G1', 'G2', 'G3'}
        and protocol['confirmationLeague'] == list(LEAGUE),
        'Use the fixed homogeneous-table historical/current league, never per-world adaptive rivals.')
    for key, digest in [('ordinary', joint.M8), ('expansion', joint.E), ('prior', ecology.F0),
                        ('candidateBundle', CANDIDATE), ('anchorBundle', G3)]:
        require(sha(protocol[key]) == digest, 'A fixed69 actor/overlay subject changed: ' + key)
    candidate = joint.bundle_subjects(protocol['candidateBundle'])
    anchor = joint.bundle_subjects(protocol['anchorBundle'])
    require(candidate['card'] is not None and anchor['card'] is not None
        and candidate['card']['sha256'] == CARD and candidate['purchase']['sha256'] == PURCHASE
        and anchor['card']['sha256'] == G3_CARD and anchor['purchase']['sha256'] == G3_PURCHASE,
        'Both actual learned heads must remain exact for candidate and qualified G3 incumbent.')
    for name, digest in [('C64', ecology.C), ('G1', G1), ('G2', joint.G2)]:
        bundle = joint.bundle_subjects(protocol['rivalBundles'][name])
        require(bundle['card'] is None and bundle['purchase']['sha256'] == digest,
            'Historical rival tables retain their actual raw M8 card policy and frozen purchase head.')
    require(sha(protocol['rivalBundles']['G3']) == G3,
        'Current rival tables use the unchanged qualified G3 bundle.')
    reviewed = evidence_subject(protocol, 'qualificationReview', G3_REVIEW)
    require(reviewed['passed'] is True and reviewed['qualificationPassed'] is True
        and reviewed['candidateBundleSha256'] == G3,
        'The G3 incumbent requires its exact completed independent qualification.')


def validate_inputs(protocol):
    require(protocol['kind'] == KIND and protocol['configuration'] == exp.CONFIGURATION
        and protocol['hostRole'] == 'mac' and protocol['workers'] == 6
        and protocol['heapMiB'] == 6144 and protocol['maximumRssBytes'] == 12*exp.GIB
        and protocol['maximumDataBytes'] == protocol['minimumFreeBytes'] == 8*exp.GIB
        and protocol['maximumSeconds'] == 28800 and protocol['stageSeconds'] == 14400
        and protocol['maximumAttempts'] == 1 and protocol['maximumFits'] == 0
        and protocol['maximumScientificTerminalGames'] == 16384
        and protocol['maximumHistoricalTerminalReproductions'] == 16
        and protocol['maximumTerminalExecutions'] == 16400
        and protocol['qualificationConfidence'] == .99
        and protocol['replayIndices'] == list(evidence.REPLAYS)
        and protocol['selectionDisclosure'] == DISCLOSURE
        and protocol['auditMaximumSeconds'] == 900
        and all(protocol[key] is False for key in ('interimChunkLooks', 'reselectionAllowed',
            'automaticResume', 'automaticSuccessor', 'productionActivation')),
        'Use the exact finite Mac69 resource, replay and dual99%-gate contract.')
    validate_nomination(protocol)
    validate_roles(protocol)
    audit = evidence_subject(protocol, 'seedAudit', AUDIT)
    require(audit['allIntersectionsZero'] is True and audit['humanInputsRead'] == 0
        and audit['liveMetadataScans'] == 0 and protocol['banks'] == audit['seedBanks']
        and set(protocol['banks']) == {'confirm', 'heldout'},
        'Use only the exact two fresh69 banks and frozen68/full ancestry.')
    for stage, bank in protocol['banks'].items():
        require(bank['count'] == 4096
            and bank['seeds'] == exp.arena_seeds(bank['nativeOffset'],4096),
            'Each stage has exactly4096 prospectively reserved independent paired worlds.')
        seeds = [seed for index in range(8)
                 for seed in exp.arena_seeds(evidence.source(protocol,stage,index)['seed'],512)]
        require(seeds == bank['seeds'], 'Chunking must preserve the original full bank and global rotations.')
    exp.validate_banks({stage:bank['seeds'] for stage,bank in protocol['banks'].items()})
