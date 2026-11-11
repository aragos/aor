"""Freeze relative scientific inputs for one adaptive-token experiment, without games."""
import argparse
import os
from pathlib import Path
import shutil
import sys
import sysconfig

import adaptive_token_campaign as campaign
import automata_adaptive_token as target

exp, read, sha, document, require = target.exp, target.read, target.sha, target.document, target.require
COMBINATION_CAMPAIGN = '3bb730678fde779af31158f6e05d036db4729d6dec839988bd95643f9c79e887'
COMBINATION_PROTOCOL = '6c9050864b318ec30376c521bfdf4838a52420e516d8a3e40316fd113e38ef77'
COMBINATION_AUDIT = 'b19ca00a45e6af165c31ae537ef430610b20d10e0e6bdff2a8b8a6d5418d0064'
NATIVE_COMMIT = 'b5bffa3af5b1622e1a7a7e680d4a18160dc02d95'
NATIVE_RECEIPT = '816ab8616cd2245ff2f072e90c0b04adf436a9631dc86326f3aa6adda63d28c4'
NATIVE_JAR = '1ef58507eb7e979a6693e6fbf870b180252fe56c31e428d7fababb743322e85a'
LOCK = 'ca1fb35c2e1fff2cf813577aed472dff2f60b32679b882d201564c651b347754'
SCRIPT_NAMES = ('adaptive_token_arena.py', 'adaptive_token_campaign.py',
    'adaptive_token_environment.py', 'automata_adaptive_token.py', 'prepare_adaptive_token.py',
    'finalize_adaptive_token.py', 'test_adaptive_token.py', 'automata_campaign.py',
    'automata_experiments.py', 'automata_evolution.py', 'automata_policy_search.py',
    'automata_token_order.py', 'automata_purchase_continuation.py', 'purchase_storage.py',
    'train-opponents.py', 'purchase_policy.py', 'purchase_policy_toy.py', 'territory_value.py')


def verify_combination(root):
    """Recompute both component comparisons, retaining all original receipt hashes."""
    audit, spec, protocol, state = [read(root/name) for name in
        ('completion-audit.json', 'campaign.json', 'protocol.json', 'run/campaign-state.json')]
    require(sha(root/'completion-audit.json') == COMBINATION_AUDIT
            and sha(root/'campaign.json') == audit['campaignSha256'] == COMBINATION_CAMPAIGN
            and sha(root/'protocol.json') == audit['protocolSha256'] == COMBINATION_PROTOCOL
            and audit['status'] == state['status'] == 'completed' and audit['qualificationPassed'] is True,
            'The incumbent requires its exact completed and independently audited E+T qualification.')
    campaign.controller.verify_completed(spec, state, root/'run')
    stages = {}
    for stage, count in (('confirm', 2048), ('heldout', 4096)):
        result_path, report_path = root/'run/results'/(stage+'.json'), root/'run'/(stage+'-report.json')
        report = read(report_path)
        require(state['stages'][stage]['status'] == 'passed'
                and sha(result_path) == audit['stages'][stage]['resultSha256']
                and sha(report_path) == audit['stages'][stage]['reportSha256']
                and read(result_path)['metrics'] == report and report['candidateSha256'] == target.E,
                'Original combined-policy qualification receipts changed.')
        arms = {}
        bank = protocol['banks'][stage]
        require(bank['count'] == count, 'The incumbent qualification count changed.')
        for arm in ('fixed-reference', 'expansion-reference'):
            directory = root/'run'/stage/arm
            pairs = [read(directory/f'pair-{index}.json') for index in range(count)]
            for index, pair in enumerate(pairs):
                target.arena.search.validate_pair(pair, index, bank['seeds'][index], 4 if stage == 'confirm' else 2)
            metrics = target.arena.search.comparison(pairs)
            require(metrics == report['rawMetrics'][arm]
                    and target.final_passes({'qualificationConfidence': .99}, metrics, stage)[0],
                    'Both incumbent reference comparisons must retain strictly positive paired99 bounds.')
            arms[arm] = pairs
        for first, second in zip(arms['fixed-reference'], arms['expansion-reference']):
            require(target.arena.search.same_game(first['candidate'], second['candidate']),
                    'The qualified combined candidate must be identical across both reference comparisons.')
        stages[stage] = dict(resultSha256=sha(result_path), reportSha256=sha(report_path))
    return dict(qualified=True, auditSha256=COMBINATION_AUDIT, campaignSha256=COMBINATION_CAMPAIGN,
                protocolSha256=COMBINATION_PROTOCOL, stages=stages)


def python_identity():
    executable = Path(sys.executable).resolve(strict=True)
    paths = {executable}
    library = Path(sysconfig.get_config_var('LIBDIR') or '')/(sysconfig.get_config_var('LDLIBRARY') or '')
    if library.is_file():
        paths.add(library.resolve())
    standard = Path(sysconfig.get_path('stdlib')).resolve(strict=True)
    for directory, folders, names in os.walk(standard):
        folders[:] = [name for name in folders if name not in {'site-packages', 'dist-packages', '__pycache__'}]
        paths.update(Path(directory)/name for name in names if name.endswith(('.py', '.so', '.zip')))
    return dict(executable=str(executable), version=sys.version, sha256=sha(executable),
                stdlib=str(standard), files={str(path):sha(path) for path in sorted(paths)})


def prepare(options):
    root, parent, native = options.output.resolve(), options.combination_root.resolve(), options.native_root.resolve()
    evidence, runtime = options.native_evidence.resolve(), options.runtime.resolve()
    require(root.exists() and {path.name for path in root.iterdir()} <= {'audit'},
            'Prepare only into the fresh reserved adaptive-token directory.')
    require(sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Prepare with the clean pure interpreter: -E -s -S -B.')
    qualified = verify_combination(parent)
    implementation = read(evidence/'implementation-verification.json')
    require(sha(evidence/'implementation-verification.json') == NATIVE_RECEIPT
            and implementation['commit'] == NATIVE_COMMIT and implementation['trainingJarSha256'] == NATIVE_JAR
            and implementation['freshTrainingBanksOpened'] == 0 and implementation['checks']['failures'] == 0
            and sha(evidence/'runtime-pins.json') == implementation['runtimePinsSha256']
            and sha(evidence/'source-pins.json') == implementation['sourcePinsSha256'],
            'Require the reviewed overlay collector, reproduction and evaluated minus-two implementation.')
    runtime_pins, source_pins = read(evidence/'runtime-pins.json'), read(evidence/'source-pins.json')
    require(exp.distribution_hashes(runtime) == runtime_pins
            and all(sha(native/path) == digest for path, digest in source_pins.items()),
            'Native runtime or reviewed source bytes changed after verification.')
    audit, control = read(root/'audit/seed-audit.json'), read(root/'audit/screen-control-reuse.json')
    require(audit['allIntersectionsZero'] and set(audit['seedBanks']) == set(target.COUNTS)
            and control['seedAuditSha256'] == sha(root/'audit/seed-audit.json'),
            'Use all five fresh banks and the predeclared common-world simple control.')
    provenance = []
    def copy(source, relative):
        return str(exp.copy_input(Path(source), root/relative, provenance))
    shutil.copytree(runtime, root/'runtime')
    require(exp.distribution_hashes(root/'runtime') == runtime_pins, 'Runtime copy changed bytes.')
    shutil.copytree(parent/'scripts', root/'scripts')
    for name in SCRIPT_NAMES:
        copy(Path(__file__).parent/name, 'scripts/'+name)
    for name in ('token_order_policy.py', 'token_order_metadata.py', 'token_order_fixed_reference.py',
                 'token_order_proposals.py', 'token_order_reference_neighbors.py', 'test_token_order_metadata.py', 'test_token_order_policy.py'):
        copy(native/'scripts'/name, 'scripts/'+name)
    # Benchmark verifier and its dependencies retain their qualified desktop bytes.
    for name in ('ordinary-f1.json', 'lambda-1.0.json', 'purchase-prior-f0.json', 'purchase-c-p64.json'):
        copy(parent/'inputs'/name, 'inputs/'+name)
    for name in ('completion-audit.json', 'campaign.json', 'protocol.json', 'run/campaign-state.json',
                 'run/results/confirm.json', 'run/results/heldout.json', 'run/confirm-report.json', 'run/heldout-report.json'):
        copy(parent/name, 'evidence/combination/'+name)
    for path in sorted(evidence.rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts:
            copy(path, 'evidence/native/'+str(path.relative_to(evidence)))
    for path in source_pins:
        copy(native/path, 'evidence/native-source/'+path)
    parent_protocol = read(parent/'protocol.json')
    fit = dict(target.legacy.FIT, seed=audit['seedBanks']['training']['fittingSeed'])
    document(root/'inputs/fit-config.json', fit)
    protocol = dict(schemaVersion=1, kind=target.KIND, banks=audit['seedBanks'],
        maximumSeconds=21600, maximumAttempts=1, heapMiB=1536, maximumTerminalGames=20864,
        maximumHistoricalArenaReplays=32, maximumHistoricalOriginReplays=8,
        maximumHistoricalTerminalBranches=168, trainingRoots=256, validationRoots=128,
        continuationReplicates=2, continuationProtocol='ARENA_OVERLAYS_1', replayOrigins=target.REPLAY_ORIGINS,
        screenRule=target.SCREEN, qualificationConfidence=.99,
        seedAudit='audit/seed-audit.json', screenControlAudit='audit/screen-control-reuse.json',
        baseline='inputs/ordinary-f1.json', expansionModel='inputs/lambda-1.0.json', expansionSha256=target.E,
        frozenPurchase={'baseline':'inputs/purchase-prior-f0.json', 'policy':'inputs/purchase-c-p64.json'},
        frozenPurchaseSha256={'baseline':target.F0, 'policy':target.C}, fitConfig='inputs/fit-config.json',
        runtime='runtime', scripts='scripts', collectionSource='sources/collection.json',
        runtimeCommit=NATIVE_COMMIT, runtimeTrainingJarSha256=NATIVE_JAR, runtimeFiles=runtime_pins,
        metadataValidatorSha256=source_pins['scripts/token_order_metadata.py'],
        nativeImplementationSha256=NATIVE_RECEIPT, nativeContractVerified=True,
        incumbentQualified=True, incumbentQualification=qualified, numericalRequirementsLockSha256=LOCK,
        **{key:parent_protocol[key] for key in ('configuration', 'dataVersion', 'sourceRulesVersion', 'targetRulesVersion', 'experimentalRulesTransfer')},
        productionActivation=False, humanStrengthVerified=False, automaticNextCampaign=False,
        controlPromotionAllowed=False, controlFallbackAllowed=False, learningNecessityEstablished=False,
        limitation='One configuration and fixed stronger rivals. Validation selects one fixed token model; development screen includes a descriptive minus-two control. No human-level or activation claim.')
    document(root/'protocol-template.json', protocol)
    document(root/'provenance.json', provenance)
    print(dict(templateSha256=sha(root/'protocol-template.json'), newGames=0, hostUnbound=True))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('combination-root', 'native-root', 'native-evidence', 'runtime', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    prepare(parser.parse_args())
