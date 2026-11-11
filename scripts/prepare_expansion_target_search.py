"""Prepare a relative portable target-search template after combined-incumbent qualification."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import sys
import sysconfig

import target_campaign as campaign
import expansion_target_search as target

exp,read,sha,document,require = target.exp,target.read,target.sha,target.document,target.require
COMBINATION_CAMPAIGN = '3bb730678fde779af31158f6e05d036db4729d6dec839988bd95643f9c79e887'
COMBINATION_PROTOCOL = '6c9050864b318ec30376c521bfdf4838a52420e516d8a3e40316fd113e38ef77'
C = '62dae4a44c84fca9ae39a50fdf93e1001d4b3e1ef55db736b1d7e387559a5a44'
F0 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0'


def verify_combination(root):
    audit,spec,protocol,state = [read(root/name) for name in
        ('completion-audit.json','campaign.json','protocol.json','run/campaign-state.json')]
    require(sha(root/'campaign.json') == audit['campaignSha256'] == COMBINATION_CAMPAIGN
            and sha(root/'protocol.json') == audit['protocolSha256'] == COMBINATION_PROTOCOL
            and audit['status'] == state['status'] == 'completed' and audit['qualificationPassed'] is True
            and all(state['stages'][stage]['status'] == 'passed' for stage in ('confirm','heldout')),
            'Require the exact independently audited combined policy with both final stages passed.')
    campaign.controller.verify_completed(spec,state,root/'run')
    reports = {}
    for stage in ('confirm','heldout'):
        result_path,report_path = root/'run/results'/(stage+'.json'),root/'run'/(stage+'-report.json')
        report = read(report_path)
        require(sha(result_path) == audit['stages'][stage]['resultSha256']
                and sha(report_path) == audit['stages'][stage]['reportSha256']
                and read(result_path)['metrics'] == report and report['candidateSha256'] == target.E,
                'Combined qualification receipt changed from its independent audit.')
        pairs = {}
        bank = protocol['banks'][stage]
        for arm in ('fixed-reference','expansion-reference'):
            directory = root/'run'/stage/arm
            pairs[arm] = [read(directory/f'pair-{index}.json') for index in range(bank['count'])]
            for index,pair in enumerate(pairs[arm]):
                target.search.validate_pair(pair,index,bank['seeds'][index],4 if stage == 'confirm' else 2)
            metrics = target.search.comparison(pairs[arm])
            require(metrics == report['rawMetrics'][arm] and target.evolution.qualification(metrics,stage)[0],
                    'Every combined-reference paired99 percent final bound must remain positive.')
        for first,second in zip(pairs['fixed-reference'],pairs['expansion-reference']):
            require(target.search.same_game(first['candidate'],second['candidate']),
                    'Combined candidate history changed between reference arms.')
        reports[stage] = dict(resultSha256=sha(result_path),reportSha256=sha(report_path))
    return dict(qualified=True,campaignSha256=COMBINATION_CAMPAIGN,protocolSha256=COMBINATION_PROTOCOL,
                auditSha256=sha(root/'completion-audit.json'),stages=reports)


def python_identity():
    executable = Path(sys.executable).resolve(strict=True)
    paths = {executable}
    library = Path(sysconfig.get_config_var('LIBDIR') or '')/(sysconfig.get_config_var('LDLIBRARY') or '')
    if library.is_file():
        paths.add(library.resolve())
    standard = Path(sysconfig.get_path('stdlib')).resolve(strict=True)
    for directory,folders,names in os.walk(standard):
        folders[:] = [name for name in folders if name not in {'site-packages','dist-packages','__pycache__'}]
        paths.update(Path(directory)/name for name in names if name.endswith(('.py','.so','.zip')))
    return dict(executable=str(executable),version=sys.version,sha256=sha(executable),
                stdlib=str(standard),files={str(path):sha(path) for path in sorted(paths)})


def prepare(options):
    root,parent,runtime = options.output.resolve(),options.combination_root.resolve(),options.runtime.resolve()
    joint = getattr(options,'joint_expansion',False)
    require(root.exists() and {path.name for path in root.iterdir()} <= {'audit'},
            'Prepare only into the fresh audited target directory.')
    require(sys.flags.ignore_environment and sys.flags.no_site and sys.flags.no_user_site and sys.dont_write_bytecode,
            'Prepare using the clean exact Python with -E -s -S -B.')
    qualified = verify_combination(parent)
    audit = read(root/'audit/seed-audit.json')
    require(audit['allIntersectionsZero'] and set(audit['seedBanks']) == set(target.COUNTS)|{'portability'},
            'Target preparation needs exactly seven learning/final banks plus independent portability.')
    world_banks = {name:audit['seedBanks'][name] for name in target.COUNTS}
    portable_bank = audit['seedBanks']['portability']
    local = getattr(options,'local_step',False)
    expected_portability = (portable_bank['nativeOffset'] >= 39000100 if local
                            else portable_bank['nativeOffset'] == (34000100 if joint else 28000100))
    require(portable_bank['count'] == 32 and expected_portability,
            'Keep the separately reserved32-world operational portability bank outside learning.')
    provenance = []
    def copy(source,relative):
        return str(exp.copy_input(Path(source),root/relative,provenance))
    require(all(sha(runtime/'lib'/name) == digest for name,digest in target.RUNTIME_JARS.items()),
            'Preparation requires exact target-feature AI/engine/training bytes, not just the unchanged training jar.')
    shutil.copytree(runtime,root/'runtime')
    require(exp.distribution_hashes(runtime) == exp.distribution_hashes(root/'runtime'),
            'Frozen target-feature runtime changed during copy.')
    shutil.copytree(parent/'scripts',root/'scripts')
    for path in sorted(Path(__file__).parent.glob('*.py')):
        copy(path,'scripts/'+path.name)
    baseline = copy(parent/'inputs/ordinary-f1.json','inputs/ordinary-f1.json')
    expansion = copy(parent/'inputs/lambda-1.0.json','inputs/lambda-1.0.json')
    purchase = dict(baseline=copy(parent/'inputs/purchase-prior-f0.json','inputs/purchase-prior-f0.json'),
                    policy=copy(parent/'inputs/purchase-c-p64.json','inputs/purchase-c-p64.json'))
    require(sha(baseline) == target.F1 and sha(expansion) == target.E
            and sha(purchase['baseline']) == F0 and sha(purchase['policy']) == C,
            'Use exact qualified E+T components and shared C/F0 Purchase.')
    for name in ('completion-audit.json','campaign.json','protocol.json','run/campaign-state.json',
                 'run/results/confirm.json','run/results/heldout.json','run/confirm-report.json','run/heldout-report.json'):
        copy(parent/name,'evidence/combination/'+name)
    parent_protocol = read(parent/'protocol.json')
    protocol = dict(schemaVersion=1,kind=target.VERSION,featureNames=list(target.models.FEATURES),
        normalization=target.models.PROFILE.normalization(),coordinateBounds=list(target.models.PROFILE.bounds),
        generations=4,populationSize=8,sigmas=[.25,.5,1.,2.],maximumProposalAttempts=4096,
        maximumArchiveModels=9,initialModels=[],maximumSeconds=21600,maximumAttempts=1,heapMiB=1536,
        maximumTerminalGames=29696,maximumHistoricalReplays=16,
        proposalSeeds=[world_banks[f'generation-{i}']['proposalSeeds'][0] for i in range(4)],
        worldBanks=world_banks,seedAudit='audit/seed-audit.json',baseline='inputs/ordinary-f1.json',expansionModel='inputs/lambda-1.0.json',
        frozenPurchase={key:str(Path(path).relative_to(root)) for key,path in purchase.items()},
        frozenPurchaseSha256={key:sha(path) for key,path in purchase.items()},
        runtime='runtime',runtimeCommit='8c4f625',runtimeTrainingJarSha256=sha(root/'runtime/lib/training.jar'),
        runtimeComponentSha256=target.RUNTIME_JARS,combinationQualification=qualified,
        **{key:parent_protocol[key] for key in ('configuration','dataVersion','sourceRulesVersion','targetRulesVersion','experimentalRulesTransfer')},
        learningMethod='bounded-whole-game-evolution',productionActivation=False,humanStrengthVerified=False,
        automaticNextCampaign=False,statisticalSelection=True,
        qualificationRule='Selected candidate only:2048 fresh mixed then4096 fresh homogeneous E+T; both reward/win99 percent lower bounds>0;8 exact replays per gate.',
        limitation='Finite learning in one configuration against fixed stronger rivals; no human-level or production-activation claim.',
        portability=dict(bank=portable_bank,coordinates=[1.,-1.,2.,-2.],usedForSelection=False,usedForStrength=False))
    if joint:
        import prepare_joint_expansion_search
        prepare_joint_expansion_search.configure(root,protocol,options,copy)
    document(root/'provenance.json',provenance)
    document(root/'protocol-template.json',protocol)
    print(dict(templateSha256=sha(root/'protocol-template.json'),newGames=0,
               next='Bind weighted portability and reviewed host, then exact-child preflight and freeze.'))



if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--combination-root',type=Path,required=True)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    prepare(parser.parse_args())
