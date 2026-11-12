"""Synthetic69 admission/role guards; no native games, model inference or fitting."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import automata_experiments as exp
import joint_chrono_qualification as executor
import joint_successor_qualification as successor


class SuccessorQualificationTests(unittest.TestCase):
    def nomination(self):
        metrics = {str(index):{key:dict(count=1024,mean=mean,lower95=-.01,upper95=.1)
                              for key in ('winShareDifference','rewardDifference')}
                   for index,mean in ((16,.04),(32,.02),(64,.01))}
        return dict(
            developmentAudit=dict(passed=True,status='completed',allOwnedProcessesExited=True,
                verifiedPairs=3072,verifiedHistoricalSideReplays=24,reachedCheckpoints=[16,32,64],
                commonIncumbentParityPassed=True,screenReportSha256=successor.DEVELOPMENT_REPORT,
                metrics=metrics,nominee=16),
            developmentReport=dict(completed=True,metrics=metrics,nominee=16,
                nomineeBundle=dict(sha256=successor.CANDIDATE)),
            nominationReview=dict(passed=True,terminalAuditSha256=successor.DEVELOPMENT_AUDIT,
                screenReportSha256=successor.DEVELOPMENT_REPORT,nominee=16,
                bundleSha256=successor.CANDIDATE,selectionRecomputedFromCompleteAuditedBank=True),
            screenRetention=dict(passed=True,terminalAuditSha256=successor.DEVELOPMENT_AUDIT,
                verifiedPairs=3072,verifiedHistoricalSides=24,verifiedArchiveMembers=3942,
                commonIncumbentParityPassed=True,nominee=16,metrics=metrics),
            learningReview=dict(passed=True,completedBatches=64,newWorlds=16384,
                historicalReproductions=64,acceptedEpochsPerHead=256))

    def validate_nomination(self, subjects):
        with patch.object(successor,'evidence_subject',side_effect=lambda _,key,digest:subjects[key]):
            successor.validate_nomination({})

    def roles(self):
        protocol=dict(jointRivalProfile=successor.PROFILE,
            rivalBundles={name:name for name in ('C64','G1','G2','G3')},
            confirmationLeague=list(successor.LEAGUE),ordinary='M8',expansion='E',prior='F0',
            candidateBundle='candidate',anchorBundle='G3')
        hashes=dict(M8=successor.joint.M8,E=successor.joint.E,F0=successor.ecology.F0,
                    candidate=successor.CANDIDATE,G3=successor.G3)
        models={'candidate':dict(card=dict(sha256=successor.CARD),purchase=dict(sha256=successor.PURCHASE)),
                'G3':dict(card=dict(sha256=successor.G3_CARD),purchase=dict(sha256=successor.G3_PURCHASE))}
        for name,digest in [('C64',successor.ecology.C),('G1',successor.G1),('G2',successor.joint.G2)]:
            models[name]=dict(card=None,purchase=dict(sha256=digest))
        return protocol,hashes,models

    def validate_roles(self, protocol, hashes, models):
        with patch.object(successor,'sha',side_effect=hashes.__getitem__), \
             patch.object(successor.joint,'bundle_subjects',side_effect=models.__getitem__), \
             patch.object(successor,'evidence_subject',return_value=dict(
                 passed=True,qualificationPassed=True,candidateBundleSha256=successor.G3)):
            successor.validate_roles(protocol)

    def test_complete_fixed_nomination_and_retention_are_accepted(self):
        self.validate_nomination(self.nomination())

    def test_missing_prescribed_checkpoint_rejects_nomination(self):
        subjects=self.nomination();subjects['developmentAudit']['reachedCheckpoints']=[16,32]
        with self.assertRaisesRegex(ValueError,'all three complete'):
            self.validate_nomination(subjects)

    def test_runner_up_cannot_replace_fixed_nominee(self):
        subjects=self.nomination();subjects['developmentReport']['nominee']=32
        with self.assertRaisesRegex(ValueError,'checkpoint16'):
            self.validate_nomination(subjects)

    def test_incomplete_lossless_retention_rejects_nomination(self):
        subjects=self.nomination();subjects['screenRetention']['verifiedPairs']=3071
        with self.assertRaisesRegex(ValueError,'lossless'):
            self.validate_nomination(subjects)

    def test_original_scientific_learning_completion_is_required(self):
        subjects=self.nomination();subjects['learningReview']['completedBatches']=63
        with self.assertRaisesRegex(ValueError,'completed64'):
            self.validate_nomination(subjects)

    def test_exact_learned_g3_and_actual_historical_null_cards_are_accepted(self):
        self.validate_roles(*self.roles())

    def test_historical_rivals_cannot_substitute_zero_residual_sampled_cards(self):
        protocol,hashes,models=self.roles();models['C64']['card']={'sha256':'zero-residual'}
        with self.assertRaisesRegex(ValueError,'Historical rival'):
            self.validate_roles(protocol,hashes,models)

    def test_changed_g3_purchase_head_rejects_strength_reference(self):
        protocol,hashes,models=self.roles();models['G3']['purchase']['sha256']='changed'
        with self.assertRaisesRegex(ValueError,'Both actual learned heads'):
            self.validate_roles(protocol,hashes,models)

    def test_null_card_cannot_replace_learned_g3_strength_reference(self):
        protocol,hashes,models=self.roles();models['G3']['card']=None
        with self.assertRaisesRegex(ValueError,'Both actual learned heads'):
            self.validate_roles(protocol,hashes,models)

    def test_league_order_cannot_be_changed_after_nomination(self):
        protocol,hashes,models=self.roles();protocol['confirmationLeague'].reverse()
        with self.assertRaisesRegex(ValueError,'fixed homogeneous-table'):
            self.validate_roles(protocol,hashes,models)

    def test_existing_candidate_identity_is_preserved_without_new_kind(self):
        self.assertEqual(executor.candidate_identity(),(executor.CANDIDATE,executor.CARD,executor.PURCHASE))

    def test_successor_candidate_identity_uses_only_exact_nominated_heads(self):
        self.assertEqual(executor.candidate_identity(dict(kind=successor.KIND)),
                         (successor.CANDIDATE,successor.CARD,successor.PURCHASE))

    def test_successor_kind_dispatches_to_its_strict_admission_owner(self):
        protocol=dict(kind=successor.KIND)
        with patch.object(successor,'validate_inputs') as validate:
            executor.validate_inputs(protocol)
        validate.assert_called_once_with(protocol)

    def test_failed_confirmation_keeps_successor_heldout_unopened(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            with patch.object(executor,'heldout_ready',side_effect=ValueError('not admitted')), \
                 patch.object(exp,'native') as native:
                with self.assertRaisesRegex(ValueError,'not admitted'):
                    executor.stage(dict(kind=successor.KIND),root/'protocol.json',root,'heldout')
            native.assert_not_called()
            self.assertFalse((root/'heldout').exists())


if __name__ == '__main__':
    unittest.main()
