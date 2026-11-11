"""Prepare the separately versioned eight-tail experiment; never open game worlds."""
import argparse
from pathlib import Path
import shutil

import automata_token_eight_tail as target
import prepare_adaptive_token as previous
import token_eight_tail_campaign as campaign

exp, read, sha, document, require = target.exp, target.read, target.sha, target.document, target.require
LOCK = previous.LOCK
python_identity = previous.python_identity
MILITARY_AUDIT = 'd78e141f6dd1c2b09425911997f9b7fbd5c2c04587f86b7662ed87c837366a3a'
MILITARY_REVIEW = '1164c871a0a7c035c94671510d44f24a863e55a21a46265d3a50f9dc628b2428'
NATIVE_RECEIPT = 'cce36d98d93890934d2fad30b81bafa7dcff145a53bbf90ebe996ee837adeaeb'
SCRIPT_NAMES = (*previous.SCRIPT_NAMES, 'token_order_policy.py','token_order_metadata.py',
    'token_order_ridge.py','token_order_eight_tail.py','automata_token_eight_tail.py',
    'token_eight_tail_campaign.py','prepare_token_eight_tail.py','finalize_token_eight_tail.py',
    'test_token_order_eight_tail.py','test_token_eight_tail_campaign.py')


def prepare(options):
    root, parent, military, native = [getattr(options,name).resolve() for name in
                                    ('output','adaptive_root','military_root','native_root')]
    evidence, runtime = options.native_evidence.resolve(), options.runtime.resolve()
    require(root.exists() and {path.name for path in root.iterdir()} <= {'audit'},
            'Prepare only the new seed-reserved eight-tail package.')
    review = read(options.military_review)
    require(sha(military/'completion-audit.json') == MILITARY_AUDIT
            and sha(options.military_review) == MILITARY_REVIEW and review['qualificationPassed']
            and review['auditPassed'] and review['pairedWorlds'] == 6144 and review['canonicalReplays'] == 16,
            'The fixed military ecology must retain its independently audited completed qualification.')
    spec, state = read(military/'campaign.json'), read(military/'run/campaign-state.json')
    require(state['status'] == 'completed' and all(row['status']=='passed' for row in state['stages'].values()),
            'The military qualification must have completed both original final gates.')
    campaign.controller.verify_completed(spec,state,military/'run')
    implementation = read(evidence/'implementation-verification.json')
    require(sha(evidence/'implementation-verification.json') == NATIVE_RECEIPT
            and implementation['newExperimentGames'] == 0 and implementation['nativeFocusedTests'] == 59
            and implementation['pythonTests'] == 90 and implementation['formatCheckPassed']
            and implementation['installDistPassed'], 'Use the reviewed isolated eight-tail implementation.')
    runtime_pins, source_pins = read(evidence/'runtime-pins.json'), read(evidence/'source-pins.json')
    require(sha(evidence/'runtime-pins.json') == implementation['runtimePinsSha256']
            and sha(evidence/'source-pins.json') == implementation['sourcePinsSha256']
            and exp.distribution_hashes(runtime) == runtime_pins
            and all(sha(native/path)==digest for path,digest in source_pins.items()),
            'Verified native/runtime source bytes changed before preparation.')
    audit = read(root/'audit/seed-audit.json')
    peer = read(root/'audit/joint-audit-binding.json')
    require(audit['allIntersectionsZero'] and peer['allBankIntersectionsZero']
            and peer['eightTailAuditSha256'] == sha(root/'audit/seed-audit.json')
            and peer['peerAuditSha256'] == sha(peer['peerAuditPath']),
            'Fresh eight-tail banks must exclude all carried history and the final peer reservations.')
    provenance = []
    def copy(source, relative):
        return exp.copy_input(Path(source),root/relative,provenance)
    shutil.copytree(runtime,root/'runtime')
    shutil.copytree(parent/'scripts',root/'scripts')
    for name in dict.fromkeys(SCRIPT_NAMES):
        copy(Path(__file__).parent/name,'scripts/'+name)
    for source,name in [(military/'inputs/ordinary-plus-8.json','ordinary-military-8.json'),
                        (military/'inputs/expansion-plus-8.json','expansion-military-8.json'),
                        (military/'inputs/purchase-c-p64.json','purchase-c-p64.json'),
                        (military/'inputs/purchase-prior-f0.json','purchase-prior-f0.json'),
                        (parent/'run/initializer.json','original-initializer.json'),
                        (parent/'run/fit/identity.json','original-basis-identity.json')]:
        copy(source,'inputs/'+name)
    require(sha(root/'inputs/ordinary-military-8.json') == target.BASELINE
            and sha(root/'inputs/expansion-military-8.json') == target.EXPANSION
            and sha(root/'inputs/original-initializer.json') == target.INITIALIZER
            and sha(root/'inputs/original-basis-identity.json') == target.BASIS_IDENTITY,
            'Military models and original untrained basis must retain their exact accepted identities.')
    for name in ('completion-audit.json','campaign.json','protocol.json','run/campaign-state.json',
                 'run/results/confirm.json','run/results/heldout.json','run/confirm-report.json','run/heldout-report.json'):
        copy(military/name,'evidence/military/'+name)
    copy(options.military_review,'evidence/military/root-review.json')
    copy(parent/'completion-audit.json','evidence/original-token-completion-audit.json')
    copy(Path(peer['peerAuditPath']),'audit/peer-joint-audit.json')
    for path in sorted(evidence.rglob('*')):
        if path.is_file():
            copy(path,'evidence/native/'+str(path.relative_to(evidence)))
    for name in source_pins:
        copy(native/name,'evidence/native-source/'+name)
    parent_protocol = read(parent/'protocol.json')
    document(root/'inputs/fit-config.json',target.FIT)
    protocol = dict(schemaVersion=1,kind=target.KIND,banks=audit['seedBanks'],workers=6,
        maximumSeconds=21600,maximumAttempts=1,heapMiB=1536,maximumTerminalGames=54912,
        maximumHistoricalArenaReplays=24,maximumHistoricalOriginReplays=8,maximumHistoricalTerminalBranches=512,
        trainingRoots=512,validationRoots=128,continuationReplicates=8,continuationProtocol=target.CONTINUATION,
        replayOrigins=target.REPLAY_ORIGINS,screenRule=target.SCREEN,qualificationConfidence=.99,
        diagnosticPrefixes=[2,4],diagnosticPrefixesAffectSelection=False,seedAudit='audit/seed-audit.json',
        baseline='inputs/ordinary-military-8.json',baselineSha256=target.BASELINE,
        expansionModel='inputs/expansion-military-8.json',expansionSha256=target.EXPANSION,
        frozenPurchase={'baseline':'inputs/purchase-prior-f0.json','policy':'inputs/purchase-c-p64.json'},
        frozenPurchaseSha256={'baseline':target.previous.F0,'policy':target.previous.C},
        initializer='inputs/original-initializer.json',originalBasisIdentity='inputs/original-basis-identity.json',
        fitConfig='inputs/fit-config.json',runtime='runtime',scripts='scripts',collectionSource='sources/collection.json',
        runtimeCommit=implementation['commit'],runtimeTrainingJarSha256=implementation['trainingJarSha256'],
        runtimeFiles=runtime_pins,metadataValidatorSha256=source_pins['scripts/token_order_metadata.py'],
        nativeImplementationSha256=NATIVE_RECEIPT,nativeContractVerified=True,incumbentQualified=True,
        incumbentQualification={'auditSha256':MILITARY_AUDIT,'rootReviewSha256':MILITARY_REVIEW},
        numericalRequirementsLockSha256=LOCK,
        executionAllocation={'thisWorkers':6,'otherDesktopWorkers':10,'desktopLogicalCpus':16},
        **{key:parent_protocol[key] for key in ('configuration','dataVersion','sourceRulesVersion','targetRulesVersion','experimentalRulesTransfer')},
        productionActivation=False,humanStrengthVerified=False,automaticNextCampaign=False,
        limitation='Fresh eight-tail evidence in one fixed M8/E/T/C64 ecology. Validation selects one fixed head; prefix metrics are diagnostic only. No guaranteed human-level improvement.')
    document(root/'protocol-template.json',protocol)
    document(root/'provenance.json',provenance)
    print(dict(templateSha256=sha(root/'protocol-template.json'),newGames=0,hostUnbound=True))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('output','adaptive-root','military-root','military-review','native-root','native-evidence','runtime'):
        parser.add_argument('--'+name,type=Path,required=True)
    prepare(parser.parse_args())
