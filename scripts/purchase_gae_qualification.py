"""Independent strength validation of one fixed exploratory GAE actor; no fitting or selection."""
import argparse
from pathlib import Path
import os

import automata_campaign as controller
import automata_evolution as evolution
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology as ecology
import purchase_ecology_data as data

read,sha,document,require=exp.read,exp.sha,exp.document,exp.require
KIND='purchase-fixed-gae-qualification-1'
CANDIDATE='47f009bd670d1e8f77d10520f8af5e73d3dc96e873cdc7e33658ccae600266c7'
PILOT_AUDIT='149a6c966926012d1128d5326e8d3c2928ff2029d6eec9e23167a56607b15bd7'
STAGES=('confirm','heldout')
REPLAYS=(0,1365,2730,4095)
ECOLOGIES={'confirm':data.ArenaRivalEcology.MIXED,'heldout':data.ArenaRivalEcology.HOMOGENEOUS}


def arena_source(protocol,stage):
    require(stage in STAGES,'Only the two predeclared independent final banks are permitted.')
    source=exp.arena_source(protocol['baseline'],protocol['opponents'],
        protocol['banks'][stage]['nativeOffset'],4096,protocol['baseline'],'CONFIRMATION')
    return dict(source,**legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'],arena=True,rival_ecology=ECOLOGIES[stage]),
        frozenPurchase=dict(baseline=protocol['prior'],policy=protocol['purchasePolicy'],
            candidatePolicy=protocol['candidate'],incumbentPolicy=protocol['purchasePolicy']))


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['workers']==10 and protocol['maximumSeconds']==7200
        and protocol['maximumAttempts']==1 and protocol['maximumScientificTerminalGames']==16384
        and protocol['maximumHistoricalTerminalReproductions']==16
        and protocol['maximumTerminalExecutions']==16400 and protocol['qualificationConfidence']==.99
        and protocol['replayIndices']==list(REPLAYS) and protocol['maximumFits']==0
        and protocol['productionActivation'] is False and protocol['automaticNextCampaign'] is False
        and protocol['reselectionAllowed'] is False
        and protocol['rivalEcologies']=={key:value.value for key,value in ECOLOGIES.items()},
        'Use exactly two4096-pair banks, ten workers, two hours and one unchanged policy.')
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and sha(protocol['purchasePolicy'])==data.C and sha(protocol['prior'])==data.F0
        and sha(protocol['candidate'])==CANDIDATE and protocol['opponents']==[protocol['baseline']]*4,
        'Preserve exact qualified ETM8/C64/F0 and the sole exploratory44 final GAE actor.')
    require(sha(protocol['pilotAudit'])==PILOT_AUDIT
        and protocol['selectionDisclosure']=='Selected after inspecting exploratory44; validates this exact policy, not GAE superiority over MC.',
        'The positive but inconclusive pilot remains disclosed and cannot become qualification evidence.')
    pilot=read(protocol['pilotAudit'])
    require(pilot['passed'] and pilot['status']=='completed' and not pilot['qualificationPassed']
        and [cell['modelSha256'] for cell in pilot['cells'] if cell['arm']=='gae']==[CANDIDATE],
        'Candidate must be the exact independently audited final GAE actor.')
    audit=read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and protocol['banks']==audit['seedBanks']
        and set(protocol['banks'])==set(STAGES),'Both banks must match their complete fresh ancestry audit.')
    for bank in protocol['banks'].values():
        require(bank['count']==4096 and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],4096),
                'Each final bank requires exactly4096 frozen native worlds.')
    exp.validate_banks({name:bank['seeds'] for name,bank in protocol['banks'].items()})


def estimates(metrics):
    return evolution.qualification_estimates({'qualificationConfidence':.99},metrics)


def gate(metrics):
    qualified=estimates(metrics)
    return all(qualified[name]['count']==4096 and qualified[name]['lowerConfidence'] is not None
               and qualified[name]['lowerConfidence']>0
               for name in ('winShareDifference','rewardDifference'))


def heldout_ready(output):
    state=read(output/'campaign-state.json')['stages']['confirm']
    prior=controller.validate_result(output/'results/confirm.json',output)
    report=read(output/'confirm-report.json')
    require(state['status']==prior['status']=='passed'
        and state['resultSha256']==sha(output/'results/confirm.json')
        and report['candidateSha256']==CANDIDATE and gate(report['metrics'])
        and prior['metrics']==report,'Heldout requires this exact actor to pass both complete99% confirmation gates.')


def stage(protocol,path,output,name):
    require(name in STAGES,'Only the two declared stages may execute.')
    if name=='heldout':heldout_ready(output)
    source_path=output/f'{name}-source.json';source=arena_source(protocol,name)
    directory=output/'screens'/name
    require(not source_path.exists() and not directory.exists(),'Each final bank permits one attempt, no top-ups.')
    document(source_path,source);exp.native(protocol,'arena',source_path,directory)
    metrics=legacy.verify_arena(protocol,source_path,directory)
    data.verify_overlays(source,directory,[read(directory/'run.json'),read(directory/'evaluation.json')],
        arena=True,expected_expansion=matched.EXPANSION,rival_ecology=ECOLOGIES[name])
    require({p.name for p in directory.glob('pair-*.json')}=={f'pair-{i}.json' for i in range(4096)},
            'Qualification requires every frozen pair, including losses and ties.')
    pairs=[read(directory/f'pair-{i}.json') for i in range(4096)]
    replays=ecology.arena_replays(protocol,source_path,pairs,output/'replays'/name,
        expected_expansion=matched.EXPANSION,rival_ecology=ECOLOGIES[name],indices=REPLAYS)
    passed=gate(metrics)
    report=dict(stage=name,protocolSha256=sha(path),candidateSha256=CANDIDATE,
        rivalEcology=ECOLOGIES[name].value,metrics=estimates(metrics),replays=replays,
        passed=passed,qualificationPassed=name=='heldout' and passed,productionActivation=False,
        automaticNextCampaign=False,actualFits=0,selectionDisclosure=protocol['selectionDisclosure'])
    report_path=output/f'{name}-report.json';document(report_path,report)
    artifacts=[source_path,report_path]
    for root in (directory,output/'replays'/name):artifacts.extend(p for p in root.rglob('*') if p.is_file())
    return exp.stage_result(output,name,passed,
        'Both99% lower bounds passed for this fixed policy; no activation.' if passed else
        'Fixed-policy qualification rejected; retain the qualified anchor and stop.',report,artifacts)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=STAGES)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    if args.command=='preflight':
        import json
        require(not args.output.exists(),'Preflight cannot reuse a game attempt.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'The exact child must have external process-sampler access.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    else:stage(protocol,args.protocol,args.output,args.stage)


if __name__=='__main__':main()
