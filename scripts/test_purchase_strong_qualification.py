"""Synthetic role/admission contracts; no unfinished campaign reads, games or fitting."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import purchase_ecology as ecology
import purchase_gae_qualification as old
import purchase_strong_qualification as strong


class StrongPurchaseQualificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name)
        self.roles={'version':strong.ROLE_VERSION}
        for role,value in [('prior','f0'),('rivals','44'),('candidate','64'),('incumbent','44')]:
            path=self.root/(role+'.json');path.write_text(json.dumps({'synthetic':value}))
            self.roles[role]=dict(path=str(path),sha256=strong.sha(path))
        self.addCleanup(patch.stopall)
        patch.object(strong.data,'F0',self.roles['prior']['sha256']).start()
        patch.object(old,'CANDIDATE',self.roles['incumbent']['sha256']).start()
        self.protocol=dict(purchaseRoles=self.roles,baseline='/m8',opponents=['/m8']*4,
            expansionModel='/em8',experimentalRulesTransfer=strong.legacy.RULES_TRANSFER,
            banks={'confirm':{'nativeOffset':1},'heldout':{'nativeOffset':2}})

    def write(self,name,value):
        path=self.root/name;path.write_text(json.dumps(value))
        return dict(path=str(path),sha256=strong.sha(path))

    def metrics(self,lower=.01):
        return {name:dict(count=512,mean=.05,lower95=lower,upper95=.09)
                for name in ('winShareDifference','rewardDifference')}

    def admission(self):
        report=dict(batch=64,candidateSha256=self.roles['candidate']['sha256'],
            incumbentSha256=old.CANDIDATE,rivalPurchaseSha256=strong.data.C,
            protocolSha256=strong.SCALE_PROTOCOL,developmentOnly=True,
            qualificationPassed=False,selectionPerformed=False,metrics=self.metrics())
        original=self.root/'original/run'
        report_pin=self.write('final-report.json',report)
        audit=dict(kind='purchase-gae-learning-curve-terminal-audit-1',passed=True,status='completed',
            completedBatchAudits=64,completedCheckpoints=[16,32,64],stopReason='all64-batches-complete',
            finalCheckpointEligibleForReview=True,eligibleActorSha256=self.roles['candidate']['sha256'],
            allOwnedProcessesExited=True,protocolSha256=strong.SCALE_PROTOCOL,campaignSha256=strong.SCALE_CAMPAIGN,
            qualificationPassed=False,selectionPerformed=False,checkpoints=[report],artifactSha256={
                str(original/'batches/batch-64/gae/update/policy.json'):self.roles['candidate']['sha256'],
                str(original/'checkpoint-64-report.json'):report_pin['sha256']})
        self.protocol['admission']=dict(audit=self.write('audit.json',audit),
            finalReport=report_pin,originalRunPath=str(original))
        reference=dict(passed=True,qualificationPassed=True,status='completed',
            stageStatus={'confirm':'passed','heldout':'passed'},candidateSha256=old.CANDIDATE)
        self.protocol['referenceAudit']=self.write('reference.json',reference)
        patch.object(strong,'REFERENCE_AUDIT',self.protocol['referenceAudit']['sha256']).start()
        return audit,report

    def update_audit(self,audit):
        self.protocol['admission']['audit']=self.write('audit.json',audit)

    def test_all_rivals_and_incumbent_use44_with_candidate64(self):
        source=strong.arena_source(self.protocol,'confirm')
        self.assertEqual(dict(baseline=self.roles['prior']['path'],policy=self.roles['rivals']['path'],
            candidatePolicy=self.roles['candidate']['path'],incumbentPolicy=self.roles['incumbent']['path']),
            source['frozenPurchase'])

    def test_homogeneous_heldout_retains_exact_purchase_roles(self):
        confirm=strong.arena_source(self.protocol,'confirm')
        heldout=strong.arena_source(self.protocol,'heldout')
        expected=copy.deepcopy(confirm);expected['seed']=2
        expected['expansionImitation']['opponentModels']=['/em8']*4
        expected['tokenOrder']['opponentSelectors']=[strong.data.FIXED]*4
        self.assertEqual(expected,heldout)

    def test_shared_rivals_cannot_silently_keep_c64(self):
        roles=copy.deepcopy(self.roles);roles['rivals']=roles['candidate']
        with self.assertRaisesRegex(ValueError,'Every rival and incumbent'):
            strong.PurchaseArenaRoles.read(roles)

    def test_changed_candidate_bytes_fail_at_role_boundary(self):
        Path(self.roles['candidate']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError,'declared purchase role changed: candidate'):
            strong.PurchaseArenaRoles.read(self.roles)

    def test_unresolved_template_cannot_supply_a_role(self):
        roles=copy.deepcopy(self.roles);roles['candidate']=dict(path=None,sha256=None)
        with self.assertRaisesRegex(ValueError,'exact path and SHA'):
            strong.PurchaseArenaRoles.read(roles)

    def test_complete_audited_final64_passes_admission(self):
        self.admission()
        strong.validate_admission(self.protocol)

    def test_positive_checkpoint32_cannot_replace_completed64(self):
        audit,_=self.admission();audit['completedBatchAudits']=32
        self.update_audit(audit)
        with self.assertRaisesRegex(ValueError,'Only a fully audited'):
            strong.validate_admission(self.protocol)

    def test_terminal_process_cleanup_is_required(self):
        audit,_=self.admission();audit['allOwnedProcessesExited']=False
        self.update_audit(audit)
        with self.assertRaisesRegex(ValueError,'Only a fully audited'):
            strong.validate_admission(self.protocol)

    def test_actor_must_match_audited_final64_artifact(self):
        audit,_=self.admission()
        name=str(Path(self.protocol['admission']['originalRunPath'])/'batches/batch-64/gae/update/policy.json')
        audit['artifactSha256'][name]='0'*64;self.update_audit(audit)
        with self.assertRaisesRegex(ValueError,'exact artifacts'):
            strong.validate_admission(self.protocol)

    def test_final_development_report_cannot_be_replaced(self):
        audit,report=self.admission();report['metrics']['winShareDifference']['mean']=.06
        self.protocol['admission']['finalReport']=self.write('changed-report.json',report)
        with self.assertRaisesRegex(ValueError,'exact artifacts'):
            strong.validate_admission(self.protocol)

    def test_cost_gate_requires_both_positive95_bounds(self):
        report={'metrics':self.metrics()};report['metrics']['rewardDifference']['lower95']=0
        self.assertFalse(strong.development_gate(report))

    def test_cost_gate_rejects_partial512_bank(self):
        report={'metrics':self.metrics()};report['metrics']['winShareDifference']['count']=511
        self.assertFalse(strong.development_gate(report))

    def test_cost_gate_rejects_nonfinite_metrics(self):
        report={'metrics':self.metrics()};report['metrics']['winShareDifference']['mean']=float('nan')
        self.assertFalse(strong.development_gate(report))

    def test_replay_verifier_binds_all_four_declared_roles(self):
        source=strong.arena_source(self.protocol,'heldout')
        names={'baseline':'baseline','policy':'policy','candidatePolicy':'candidate-policy',
               'incumbentPolicy':'incumbent-policy'}
        expected={role:dict(path=str(Path(path).resolve()),sha256=strong.sha(path),snapshot=f'frozen-purchase-{names[role]}.json')
                  for role,path in source['frozenPurchase'].items()}
        for role,row in expected.items():
            (self.root/row['snapshot']).write_bytes(Path(source['frozenPurchase'][role]).read_bytes())
        ecology.verify_purchase_roles(source,self.root,[dict(frozenPurchase=expected)])

    def test_replay_wrong_rival_actor_hash_fails(self):
        source=strong.arena_source(self.protocol,'heldout')
        with self.assertRaisesRegex(ValueError,'actor roles'):
            ecology.verify_purchase_roles(source,self.root,[dict(frozenPurchase={})])

    def test_rejected_confirmation_cannot_build_heldout_source(self):
        with patch.object(strong,'validate_inputs'),patch.object(old,'heldout_ready',side_effect=ValueError('rejected')), \
             patch.object(strong,'arena_source') as builder,patch.object(old.exp,'native') as native:
            with self.assertRaisesRegex(ValueError,'rejected'):
                strong.stage(self.protocol,self.root/'protocol',self.root/'run','heldout')
        builder.assert_not_called();native.assert_not_called()


if __name__=='__main__':unittest.main()
