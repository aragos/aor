"""One explicit smaller-scale hypothesis; no native worlds or adaptive search changes."""
import copy
import unittest
from unittest import mock

import evolution_coordinates as coordinate
import expansion_joint_models as joint
import expansion_target_models as models
import expansion_target_search as target
from test_expansion_joint_models import JointExpansionTest


class LocalStepTest(unittest.TestCase):
    def setUp(self):
        self.fixture = JointExpansionTest(methodName='runTest')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.protocol = dict(self.fixture.protocol,kind=joint.LOCAL_VERSION,sigmas=list(joint.LOCAL_SIGMAS),
                             maximumProposalAttempts=4096,populationSize=8)

    def test_version_owns_exact_tenth_scale_and_preserves_old_default(self):
        self.assertEqual((.25,.5,1.,2.),joint.mutation_sigmas(joint.VERSION))
        self.assertEqual(tuple(value/10 for value in coordinate.DEFAULT_SIGMAS),
                         joint.mutation_sigmas(joint.LOCAL_VERSION))

    def test_unknown_profile_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Unknown versioned'):
            joint.mutation_sigmas('invented')

    def test_zero_negative_nonfinite_or_mutable_schedules_are_rejected(self):
        for sigmas in ((),(0.,),(-.1,),(float('nan'),),(float('inf'),),[.1]):
            with self.subTest(sigmas=sigmas):
                with self.assertRaisesRegex(ValueError,'finite positive sigmas'):
                    coordinate.population(self.fixture.expansion,[],models.profile(self.protocol),101,
                        self.protocol['targetRulesVersion'],sigmas=sigmas)

    def test_same_rng_proposals_change_only_noise_scale_before_clipping(self):
        arguments=(self.fixture.expansion,[],models.profile(self.protocol),101,self.protocol['targetRulesVersion'])
        broad=coordinate.population(*arguments)
        local=coordinate.population(*arguments,sigmas=joint.LOCAL_SIGMAS)
        self.assertEqual(broad[0],local[0])
        for first,second in zip(broad[1:],local[1:]):
            self.assertEqual(first['gaussianNoise'],second['gaussianNoise'])
            self.assertEqual(first['parentIndices'],second['parentIndices'])
            self.assertEqual(first['attempt'],second['attempt'])
            self.assertEqual(first['center'],second['center'])
            self.assertAlmostEqual(first['sigma']/10,second['sigma'])
            for broad_value,local_value in zip(first['coordinates'],second['coordinates']):
                if abs(broad_value)<8:
                    self.assertAlmostEqual(broad_value/10,local_value)

    def test_executor_actually_uses_declared_small_sigmas_for_all_four_generations(self):
        output=self.fixture.root/'run'
        with mock.patch.object(target,'run_arena',side_effect=self.fixture.fake_arena) as native:
            target.evolve(self.protocol,output)
        self.assertEqual(32,native.call_count)
        for generation in range(4):
            proposals=target.read(output/f'generation-{generation}/proposals.json')['proposals']
            self.assertEqual(8,len(proposals))
            for row in proposals:
                if row['kind']=='mutation':
                    self.assertEqual(joint.LOCAL_SIGMAS[row['childIndex']%4],row['sigma'])
        self.assertEqual(joint.LOCAL_VERSION,target.read(output/'evolve-report.json')['kind'])

    def test_new_profile_cannot_omit_the_failed_search_binding(self):
        with self.assertRaisesRegex(ValueError,'explicit versioned owner'):
            joint.validate_local_step(self.protocol)

    def test_old_profile_cannot_silently_adopt_local_settings(self):
        with self.assertRaisesRegex(ValueError,'explicit versioned owner'):
            joint.validate_local_step(dict(self.fixture.protocol,localStep={}))

    def test_local_scale_keeps_qualified_military_anchor(self):
        protocol=copy.deepcopy(self.protocol)
        protocol['localStep']=dict(priorAudit={},failurePolicy=joint.FAILURE_POLICY)
        with self.assertRaisesRegex(ValueError,'qualified military anchor'):
            joint.validate_local_step(protocol)

    def test_failure_policy_cannot_authorize_more_weight_search(self):
        protocol=copy.deepcopy(self.protocol)
        protocol['jointExpansion']['anchor']='E+T+M8'
        protocol['localStep']=dict(priorAudit={},failurePolicy='TRY_ANOTHER_SCALE')
        with self.assertRaisesRegex(ValueError,'stops this search family'):
            joint.validate_local_step(protocol)

    def test_prior_audit_identity_is_fixed(self):
        protocol=copy.deepcopy(self.protocol)
        protocol['jointExpansion']['anchor']='E+T+M8'
        protocol['localStep']=dict(priorAudit=dict(path='/audit.json',sha256='changed'),
                                  failurePolicy=joint.FAILURE_POLICY)
        with mock.patch.object(joint,'sha',return_value='changed'):
            with self.assertRaisesRegex(ValueError,'exact completed broad-search audit'):
                joint.validate_local_step(protocol)


if __name__=='__main__':
    unittest.main()
