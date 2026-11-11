"""Prospective final64 qualification against the qualified44 actor in every purchase rival seat.

The final development screen only allocates compute. Admission needs the complete independent
learning-curve audit; no earlier checkpoint can substitute. No fitting, checkpoint selection or
native policy change belongs to this runner.
"""
import argparse
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path

import automata_campaign as controller
import automata_experiments as exp
import automata_purchase_continuation as legacy
import purchase_baseline_pilot as matched
import purchase_ecology_data as data
import purchase_gae_qualification as qualified

read,sha,require=exp.read,exp.sha,exp.require
KIND='purchase-final64-strong-qualification-1'
ROLE_VERSION='purchase-arena-roles-1'
NATIVE='1b1b1c6cf2bc3c94258a4078d9d3bfeaa5361ca1'
REFERENCE_AUDIT='3efe9bb2935c034dcb8166a6ad58f4907533801f84937cd7ae481bcb29c1ace3'
SCALE_PROTOCOL='f058cbb1815e75619528a25d6efdb5044d256d7e4f86e9e55e47d237679a1f79'
SCALE_CAMPAIGN='a423a6b26827698219b24515da797367d57104ab277e40dc00005d4e9a6036f1'
DISCLOSURE=('Only completed46 batch64 may enter after its audited final512-pair development '
    'screen passes both positive95% lower bounds; this is a cost gate, not qualification. '
    'Earlier checkpoints cannot substitute. Independent gates validate this exact policy '
    'against qualified44 GAE, not GAE superiority over another learning algorithm.')


@dataclass(frozen=True)
class PurchaseArenaRoles:
    """Explicit prior, shared rival actor and evaluated-side actors; paths are frozen inputs."""
    prior: Path
    rivals: Path
    candidate: Path
    incumbent: Path

    @classmethod
    def read(cls, node):
        require(isinstance(node,dict) and set(node)=={'version','prior','rivals','candidate','incumbent'}
            and node['version']==ROLE_VERSION,'Declare all four typed purchase roles explicitly.')
        paths={}
        for role in ('prior','rivals','candidate','incumbent'):
            value=node[role]
            require(isinstance(value,dict) and set(value)=={'path','sha256'}
                and isinstance(value['path'],str) and bool(value['path'])
                and isinstance(value['sha256'],str) and len(value['sha256'])==64
                and all(char in '0123456789abcdef' for char in value['sha256']),
                'Every purchase role requires its exact path and SHA-256.')
            path=Path(value['path'])
            require(path.is_absolute() and sha(path)==value['sha256'],
                    'A declared purchase role changed: '+role)
            paths[role]=path
        require(node['prior']['sha256']==data.F0
            and node['rivals']['sha256']==node['incumbent']['sha256']==qualified.CANDIDATE,
            'Every rival and incumbent uses qualified44 GAE with the unchanged F0 prior.')
        return cls(**paths)

    def source(self):
        return dict(baseline=str(self.prior),policy=str(self.rivals),
                    candidatePolicy=str(self.candidate),incumbentPolicy=str(self.incumbent))


def pinned(value, description):
    require(isinstance(value,dict) and set(value)=={'path','sha256'}
        and isinstance(value['path'],str) and Path(value['path']).is_absolute()
        and isinstance(value['sha256'],str) and len(value['sha256'])==64,
        description+' must be explicitly frozen before execution.')
    require(sha(value['path'])==value['sha256'],description+' changed.')
    return read(value['path'])


def development_gate(report):
    """Two-sided95 bounds on exactly512 paired worlds; not a strength qualification."""
    for name in ('winShareDifference','rewardDifference'):
        metric=report['metrics'][name]
        values=[metric[key] for key in ('mean','lower95','upper95')]
        if (metric['count']!=512 or any(type(value) not in (int,float) or not math.isfinite(value)
                for value in values) or not 0<values[1]<=values[0]<=values[2]):
            return False
    return True


def validate_admission(protocol):
    """Require the terminal audited64 artifact, never a live progress file or earlier actor."""
    admission=protocol['admission']
    require(isinstance(admission,dict) and set(admission)=={'audit','finalReport','originalRunPath'}
        and isinstance(admission['originalRunPath'],str)
        and Path(admission['originalRunPath']).is_absolute(),
        'Bind the terminal46 audit, final report and original immutable run identity.')
    audit=pinned(admission['audit'],'The complete46 independent terminal audit')
    report=pinned(admission['finalReport'],'The audited final64 development report')
    candidate=protocol['purchaseRoles']['candidate']['sha256']
    require(audit['kind']=='purchase-gae-learning-curve-terminal-audit-1'
        and audit['passed'] is True and audit['status']=='completed'
        and audit['completedBatchAudits']==64 and audit['completedCheckpoints']==[16,32,64]
        and audit['stopReason']=='all64-batches-complete'
        and audit['finalCheckpointEligibleForReview'] is True
        and audit['eligibleActorSha256']==candidate and audit['allOwnedProcessesExited'] is True
        and audit['protocolSha256']==SCALE_PROTOCOL and audit['campaignSha256']==SCALE_CAMPAIGN
        and audit['qualificationPassed'] is False and audit['selectionPerformed'] is False,
        'Only a fully audited completed46 batch64 can enter; no partial or earlier fallback.')
    original=Path(admission['originalRunPath'])
    require(audit['artifactSha256'][str(original/'batches/batch-64/gae/update/policy.json')]==candidate
        and audit['artifactSha256'][str(original/'checkpoint-64-report.json')]
            ==admission['finalReport']['sha256']
        and [row for row in audit['checkpoints'] if row['batch']==64]==[report],
        'The candidate and final report must be the exact artifacts verified by the terminal audit.')
    require(report['batch']==64 and report['candidateSha256']==candidate
        and report['incumbentSha256']==qualified.CANDIDATE and report['rivalPurchaseSha256']==data.C
        and report['protocolSha256']==SCALE_PROTOCOL and report['developmentOnly'] is True
        and report['qualificationPassed'] is False and report['selectionPerformed'] is False
        and development_gate(report),
        'The final512-pair development screen must pass both positive95% lower bounds.')
    reference=pinned(protocol['referenceAudit'],'The qualified44 reference audit')
    require(protocol['referenceAudit']['sha256']==REFERENCE_AUDIT and reference['passed'] is True
        and reference['qualificationPassed'] is True and reference['status']=='completed'
        and reference['stageStatus']=={'confirm':'passed','heldout':'passed'}
        and reference['candidateSha256']==qualified.CANDIDATE,
        'All purchase rivals and the incumbent must bind the independently qualified44 policy.')


def arena_source(protocol,stage):
    require(stage in qualified.STAGES,'Only the two predeclared independent final banks are permitted.')
    roles=PurchaseArenaRoles.read(protocol['purchaseRoles'])
    source=exp.arena_source(protocol['baseline'],protocol['opponents'],
        protocol['banks'][stage]['nativeOffset'],4096,protocol['baseline'],'CONFIRMATION')
    return dict(source,**legacy.transfer_source(protocol),
        **data.bindings(protocol['expansionModel'],arena=True,rival_ecology=qualified.ECOLOGIES[stage]),
        frozenPurchase=roles.source())


def validate_inputs(protocol):
    require(protocol['kind']==KIND and protocol['nativeSourceCommit']==NATIVE
        and protocol['workers']==10 and protocol['maximumSeconds']==7200
        and protocol['maximumAttempts']==1 and protocol['maximumScientificTerminalGames']==16384
        and protocol['maximumHistoricalTerminalReproductions']==16
        and protocol['maximumTerminalExecutions']==16400 and protocol['qualificationConfidence']==.99
        and protocol['replayIndices']==list(qualified.REPLAYS) and protocol['maximumFits']==0
        and all(protocol[key] is False for key in ('productionActivation','automaticNextCampaign',
                                                 'reselectionAllowed','earlierCheckpointFallback'))
        and protocol['rivalEcologies']=={key:value.value for key,value in qualified.ECOLOGIES.items()}
        and protocol['selectionDisclosure']==DISCLOSURE,
        'Use the sole final64 actor, two4096-pair banks, ten workers, two hours and one attempt.')
    PurchaseArenaRoles.read(protocol['purchaseRoles'])
    require(protocol['configuration']==exp.CONFIGURATION
        and protocol['experimentalRulesTransfer']==legacy.RULES_TRANSFER
        and sha(protocol['baseline'])==matched.ORDINARY and sha(protocol['expansionModel'])==matched.EXPANSION
        and protocol['opponents']==[protocol['baseline']]*4,
        'Preserve exact qualified M8, E, T and F0; only purchase-role assignments differ.')
    validate_admission(protocol)
    audit=read(protocol['seedAudit'])
    require(audit['allIntersectionsZero'] and protocol['banks']==audit['seedBanks']
        and set(protocol['banks'])==set(qualified.STAGES),'Both fresh banks need their complete ancestry audit.')
    for bank in protocol['banks'].values():
        require(bank['count']==4096 and bank['seeds']==exp.arena_seeds(bank['nativeOffset'],4096),
                'Each final bank requires exactly4096 frozen native worlds.')
    exp.validate_banks({name:bank['seeds'] for name,bank in protocol['banks'].items()})


def stage(protocol,path,output,name):
    validate_inputs(protocol)
    return qualified.stage(protocol,path,output,name,
        candidate_sha256=protocol['purchaseRoles']['candidate']['sha256'],source_builder=arena_source)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('preflight','stage'))
    parser.add_argument('--protocol',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--stage',choices=qualified.STAGES)
    args=parser.parse_args();protocol=read(args.protocol)
    validate_inputs(protocol);matched.verify_execution(protocol)
    if args.command=='preflight':
        require(not args.output.exists(),'Preflight cannot reuse a game attempt.')
        own=[row for row in controller.processes.sample_group(os.getpgrp()) if row.pid==os.getpid()]
        require(len(own)==1 and own[0].rss_bytes>0,'The exact child must have external process-sampler access.')
        print(json.dumps(dict(passed=True,newGames=0,actualFits=0,externalPsSamplerPassed=True,
                              protocolSha256=sha(args.protocol))))
    else:stage(protocol,args.protocol,args.output,args.stage)


if __name__=='__main__':main()
