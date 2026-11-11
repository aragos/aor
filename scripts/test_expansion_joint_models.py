"""Joint mutations preserve phase roles, exact anchors and complete bundle identity."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import expansion_joint_models as joint
import expansion_target_models as models
import expansion_target_search as target
import test_expansion_target_search as fixtures


class JointExpansionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.baseline = dict(schemaVersion=1,rulesVersion='ah1996-three-epoch-4',dataVersion='data',
            weights=dict(tokens=1.,income=4.,cards=17.,**{'card.military':8.}))
        self.expansion = dict(self.baseline,rulesVersion='ah1996-three-epoch-5',
            weights=dict(self.baseline['weights'],tokens=-2.,income=9.))
        for name,model in (('ordinary',self.baseline),('expansion',self.expansion)):
            target.document(self.root/(name+'.json'),model)
        self.settings = dict(anchor='E+T',expansionNormalization=dict(features=[3.,1.,1.,2.,2.5,1.5,1.,1.],temperature=24.),
            normalizationSource=dict(path='normalization.json',sha256=joint.NORMALIZATION_SHA),militaryQualification=None)
        self.protocol = dict(kind=joint.VERSION,jointExpansion=self.settings,
            baseline=str(self.root/'ordinary.json'),expansionModel=str(self.root/'expansion.json'),
            targetRulesVersion='ah1996-three-epoch-5',sourceRulesVersion='ah1996-three-epoch-4',
            experimentalRulesTransfer=dict(sourceRulesVersion='ah1996-three-epoch-4',targetRulesVersion='ah1996-three-epoch-5'),
            proposalSeeds=[101,102,103,104],frozenPurchase=dict(baseline='F0',policy='C'),
            worldBanks={name:dict(nativeOffset=34000000+i,count=count)
                        for i,(name,count) in enumerate(target.COUNTS.items())})

    def bundle(self,values=None,name='model'):
        values = values if values is not None else [1.,-1.,2.,-2.]*3
        expanded = models.coordinate.export_model(values,self.expansion,models.profile(self.protocol),
                                                  self.protocol['targetRulesVersion'])
        return models.export_bundle(self.root/name,expanded,self.baseline,self.expansion,self.settings)

    def read_pair(self,path):
        bundle = models.verify_bundle(path,self.protocol)
        return tuple(target.read(bundle[role]['path']) for role in ('ordinary','expansion'))

    def test_original_expansion_and_target_scales_are_both_preserved(self):
        profile = models.profile(self.protocol)
        self.assertEqual(profile.features,joint.FEATURES)
        self.assertEqual(profile.scales[:8],tuple(self.settings['expansionNormalization']['features']))
        ordinary,expanded = self.read_pair(self.bundle())
        values = [1.,-1.,2.,-2.]*3
        for index,name in enumerate(target.evolution.FEATURES):
            self.assertEqual(ordinary['weights'].get(name,0.),self.baseline['weights'].get(name,0.))
            self.assertAlmostEqual(expanded['weights'][name]-self.expansion['weights'].get(name,0.),
                                   24.*values[index]/profile.scales[index])
        for index,name in enumerate(joint.TARGET_FEATURES):
            self.assertEqual(ordinary['weights'][name],8.*values[8+index])
            self.assertEqual(ordinary['weights'][name],expanded['weights'][name])

    def test_zero_anchor_preserves_both_weight_vectors_and_frozen_military(self):
        ordinary,expanded = self.read_pair(self.bundle([0.]*12))
        for actual,expected in ((ordinary,self.baseline),(expanded,self.expansion)):
            self.assertEqual(target.evolution.model_key(actual),target.evolution.model_key(expected))
            self.assertEqual(actual['weights']['card.military'],8.)
        self.assertEqual(target.bundle_key(self.root/'model/bundle.json',self.protocol),target.anchor_key(self.protocol))

    def test_ordinary_and_expansion_may_differ_only_in_original_eight_features(self):
        ordinary,expanded = self.read_pair(self.bundle())
        changed = {name for name in set(ordinary['weights'])|set(expanded['weights'])
                   if ordinary['weights'].get(name,0.) != expanded['weights'].get(name,0.)}
        self.assertTrue(changed <= set(target.evolution.FEATURES))
        self.assertEqual(ordinary['weights']['cards'],expanded['weights']['cards'])

    def test_ordinary_original_feature_mutation_is_rejected(self):
        ordinary,expanded = self.read_pair(self.bundle())
        ordinary['weights']['income'] += 1.
        with self.assertRaisesRegex(ValueError,'declared coordinate'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion,self.settings)

    def test_unrelated_expansion_feature_mutation_is_rejected(self):
        ordinary,expanded = self.read_pair(self.bundle())
        expanded['weights']['card.military'] += 1.
        with self.assertRaisesRegex(ValueError,'declared coordinate'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion,self.settings)

    def test_mismatched_target_role_is_rejected(self):
        ordinary,expanded = self.read_pair(self.bundle())
        expanded['weights'][joint.TARGET_FEATURES[0]] += 1.
        with self.assertRaisesRegex(ValueError,'identical target'):
            models.verify_models(ordinary,expanded,self.baseline,self.expansion,self.settings)

    def test_expansion_only_changes_are_distinct_archive_bundles(self):
        first = self.bundle([0.]*12,'anchor')
        second = self.bundle([1.]+[0.]*11,'changed')
        self.assertEqual(target.evolution.model_key(self.read_pair(first)[0]),
                         target.evolution.model_key(self.read_pair(second)[0]))
        self.assertNotEqual(target.bundle_key(first,self.protocol),target.bundle_key(second,self.protocol))
        self.assertEqual(target.archive_append([first],[second],self.protocol),[first,second])

    def test_joint_sources_retain_strong_mixed_roles_and_homogeneous_current_anchor(self):
        path = self.bundle()
        mixed = target.source(self.protocol,path,'generation-0')
        heldout = target.source(self.protocol,path,'heldout')
        self.assertEqual(mixed['opponents'],[self.protocol['baseline']]*4)
        self.assertEqual(mixed['expansionImitation']['opponentModels'],
                         [None,self.protocol['expansionModel'],None,self.protocol['expansionModel']])
        self.assertEqual(heldout['opponents'],[self.protocol['baseline']])
        self.assertEqual(heldout['expansionImitation']['opponentModels'],[self.protocol['expansionModel']])
        self.assertEqual(heldout['tokenOrder']['opponentSelectors'],['FIXED_16_TO_15'])
        self.assertEqual(mixed['frozenPurchase'],self.protocol['frozenPurchase'])

    def fake_arena(self,protocol,directory,bundle,bank):
        index = int(directory.name.rsplit('-',1)[-1])
        game = dict(wins=[0.,1.,0.,0.],rewards=[.1,.4,.2,.3],ranks=[4.,1.,3.,2.],scores=[1,4,2,3],decisions=100)
        pair = dict(index=0,seed=100,seat=0,configuration='test',opponentIndices=[0,1,2,3],candidate=game,incumbent=game)
        return fixtures.metrics(reward=index/100,count=target.COUNTS[bank]),[pair],directory/'source.json'

    def test_existing_executor_runs_fixed_joint_generations_and_archive_selection(self):
        with mock.patch.object(target,'run_arena',side_effect=self.fake_arena) as native:
            target.evolve(self.protocol,self.root/'run')
            target.select_archive(self.protocol,self.root/'run')
        self.assertEqual(native.call_count,32+9)
        archive = target.read(self.root/'run/archive.json')
        self.assertEqual(len(archive['models']),9)
        self.assertEqual(target.read(self.root/'run/evolve-report.json')['kind'],joint.VERSION)
        for generation in range(4):
            proposal = target.read(self.root/f'run/generation-{generation}/proposals.json')
            self.assertEqual(proposal['profile']['featureNames'],list(joint.FEATURES))
            self.assertEqual(len(proposal['proposals']),8)
            self.assertTrue(all(len(row['coordinates']) == 12 for row in proposal['proposals']))

    def test_joint_anchor_gameplay_mismatch_fails_before_other_candidates(self):
        def mismatched(*arguments):
            metrics,pairs,path = self.fake_arena(*arguments)
            pairs[0]['candidate'] = dict(pairs[0]['candidate'],decisions=101)
            return metrics,pairs,path
        with mock.patch.object(target,'run_arena',side_effect=mismatched) as native:
            with self.assertRaisesRegex(ValueError,'anchor failed full gameplay parity'):
                target.evolve(self.protocol,self.root/'run')
        self.assertEqual(native.call_count,1)

    def test_frozen_normalization_source_rejects_changed_bytes(self):
        with mock.patch.object(joint,'sha',side_effect=[*joint.ANCHORS['E+T'],'changed']):
            with self.assertRaisesRegex(ValueError,'training-only normalization source'):
                joint.validate_anchor(self.protocol)

    def test_unqualified_military_anchor_is_rejected(self):
        self.settings['anchor'] = 'E+T+M8'
        self.settings['militaryQualification'] = None
        source = dict(featureNames=list(target.evolution.FEATURES),
                      normalization=self.settings['expansionNormalization'],trainEntries=list(range(30)))
        with mock.patch.object(joint,'sha',side_effect=[*joint.ANCHORS['E+T+M8'],joint.NORMALIZATION_SHA]), \
             mock.patch.object(joint,'read',return_value=source):
            with self.assertRaisesRegex(ValueError,'reviewed terminal audit'):
                joint.validate_anchor(self.protocol)


if __name__ == '__main__':
    unittest.main()
