"""Joint host proof binds all12 coordinates to actual ordinary/expansion snapshots."""
import json
import unittest
from unittest import mock

import expansion_target_models as models
import target_portability as proof
import test_target_portability as legacy


class JointPortabilityTest(unittest.TestCase):
    def setUp(self):
        self.fixture = legacy.TargetPortabilityTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root,self.template,self.receipt = self.fixture.root,self.fixture.template,self.fixture.receipt
        self.settings = dict(anchor='E+T',expansionNormalization=dict(features=[1.]*8,temperature=24.))
        self.template['jointExpansion'] = self.settings
        self.receipt.update(kind='expansion-joint-weighted-portability-1',coordinates=proof.JOINT_COORDINATES)
        baseline = dict(schemaVersion=1,rulesVersion='old',dataVersion='data',weights=dict(tokens=1.,cards=17.))
        expansion = dict(baseline,rulesVersion='new',weights=dict(baseline['weights'],tokens=2.))
        candidate = models.coordinate.export_model(proof.JOINT_COORDINATES,expansion,models.profile(self.template),'new')
        ordinary,expanded = models.joint.export_models(candidate,baseline,expansion,self.settings)
        self.receipt['rawWeights'] = {name:expanded['weights'][name] for name in models.joint.FEATURES}
        self.template['frozenPurchaseSha256'] = {}
        for host,prefix,workers in (('mac','mac',1),('desktop','desktop-recovery',10)):
            directory = self.root/prefix/'run'
            snapshots = {'candidate.json':ordinary,'incumbent.json':baseline,
                         'expansion-imitation-candidate.json':expanded,'expansion-imitation-incumbent.json':expansion}
            snapshots.update({f'opponent-{i}.json':baseline for i in range(4)})
            snapshots.update({f'expansion-imitation-opponent-{i}.json':expansion for i in (1,3)})
            for name,value in snapshots.items():
                proof.target.document(directory/name,value)
            def descriptor(name):
                return dict(path=str(directory/name),snapshot=name,sha256=proof.sha(directory/name))
            binding = models.bindings(dict(baseline='/ordinary',expansionModel='/expansion'),'/candidate','/candidate-expansion','mixed')
            proof.target.document(directory/'source.json',binding)
            native = proof.read(directory/'run.json')
            native.update(workers=workers,tokenOrder=binding['tokenOrder'],
                ordinaryModelSources={role:descriptor(role+'.json') for role in ('candidate','incumbent',*(f'opponent-{i}' for i in range(4)))},
                expansionImitation=dict(candidateModel=descriptor('expansion-imitation-candidate.json'),
                    incumbentModel=descriptor('expansion-imitation-incumbent.json'),
                    opponentModels=[None,descriptor('expansion-imitation-opponent-1.json'),None,descriptor('expansion-imitation-opponent-3.json')]),
                frozenPurchase={})
            for role in ('baseline','policy'):
                name=f'frozen-purchase-{role}.json'
                proof.target.document(directory/name,dict(role=role))
                native['frozenPurchase'][role]=descriptor(name)
                self.template['frozenPurchaseSha256'][role]=proof.sha(directory/name)
            (directory/'run.json').write_text(json.dumps(native))
            self.receipt['hosts'][host].update(workers=workers,nativeRunSha256=proof.sha(directory/'run.json'))
        anchor = tuple(proof.sha(self.root/'mac/run'/name) for name in ('incumbent.json','expansion-imitation-incumbent.json'))
        patch = mock.patch.dict(models.joint.ANCHORS,{'E+T':anchor})
        patch.start();self.addCleanup(patch.stop)
        self.fixture.refresh()

    def verify(self):
        return proof.verify(self.root,self.fixture.digest,self.template)

    def test_all_twelve_coordinates_and_ten_desktop_workers_pass(self):
        self.assertTrue(self.verify()['passed'])

    def test_expansion_only_candidate_drift_rejected_even_with_fresh_artifact_hashes(self):
        path=self.root/'mac/run/expansion-imitation-candidate.json'
        candidate=proof.read(path);candidate['weights']['tokens']+=1.
        path.write_text(json.dumps(candidate));self.fixture.refresh()
        with self.assertRaisesRegex(ValueError,'exactly all12'):
            self.verify()

    def test_changed_rival_phase_role_rejected(self):
        path=self.root/'mac/run/source.json'
        source=proof.read(path);source['expansionImitation']['opponentModels'][0]='/expansion'
        path.write_text(json.dumps(source));self.fixture.refresh()
        with self.assertRaisesRegex(ValueError,'mixed rival roles'):
            self.verify()

    def test_joint_proof_cannot_claim_legacy_six_desktop_workers(self):
        self.receipt['hosts']['desktop']['workers']=6;self.fixture.refresh()
        with self.assertRaisesRegex(ValueError,'worker executions'):
            self.verify()

    def test_joint_native_snapshot_must_be_pinned(self):
        self.receipt['artifactHashes'].pop('mac/run/candidate.json')
        (self.root/'receipt.json').write_text(json.dumps(self.receipt))
        self.fixture.digest=proof.sha(self.root/'receipt.json')
        with self.assertRaisesRegex(ValueError,'Every joint native snapshot'):
            self.verify()

    def test_missing_ordinary_rival_role_is_rejected(self):
        path=self.root/'mac/run/run.json'
        native=proof.read(path);native['ordinaryModelSources'].pop('opponent-3')
        path.write_text(json.dumps(native))
        self.receipt['hosts']['mac']['nativeRunSha256']=proof.sha(path)
        self.fixture.refresh()
        with self.assertRaisesRegex(ValueError,'every ordinary and shared Purchase role'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
