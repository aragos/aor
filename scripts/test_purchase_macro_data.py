"""UNIT adapter records only; no retained scientific corpus is opened."""
import unittest

import purchase_macro_data as data

HASH = 'a' * 64
PUBLIC = 'b' * 64


def fixture():
    commands = [['pass'], ['buy-advance N', 'pass']]
    plans = [dict(planIndex=i, commands=c, support='SUPPORTED', naturalFinish=False,
                  features=[float(i)] + [0.] * 168) for i, c in enumerate(commands)]
    facts = dict(version=data.VERSION, featureNames=list(data.FEATURE_NAMES), plans=plans,
                 proposals=dict(plans=[dict(commands=c) for c in commands]))
    public = dict(originIndex=0, split='TRAIN', sourceSha256=HASH, version=data.VERSION,
                  status='CAPTURED', sampledPassVerified=True, continuationSeeds=[-10, -11], facts=facts)
    outcome = dict(reward=.9, winShare=1., rankReward=.5, chaos=0.)
    labels = dict(originIndex=0, split='TRAIN', sourceSha256=HASH, publicFactsSha256=PUBLIC,
                  labelKind='COMPLETE_PREFIX', plans=[dict(planIndex=i, commands=c,
                    continuationSeeds=[-10, -11], returns=[outcome.copy(), outcome.copy()])
                    for i, c in enumerate(commands)])
    return public, labels


class MacroDataTest(unittest.TestCase):
    def test_matching_complete_plan_and_both_original_streams_are_retained(self):
        public, labels = fixture()
        result = data.origin(public, labels, PUBLIC)
        self.assertTrue(result.eligible)
        self.assertEqual(result.plans[1].commands, ('buy-advance N', 'pass'))
        self.assertEqual(len(result.plans[1].returns), 2)

    def test_first_action_only_labels_are_rejected_before_returns(self):
        public, labels = fixture();labels['labelKind'] = 'FIRST_ACTION_ONLY';labels['plans'] = object()
        with self.assertRaisesRegex(ValueError, 'Detached labels'):
            data.origin(public, labels, PUBLIC)

    def test_test_index_is_rejected_before_label_access(self):
        public, _ = fixture();public['originIndex'] = 448
        with self.assertRaisesRegex(ValueError, 'TRAIN/VALIDATION'):
            data.origin(public, object(), PUBLIC)

    def test_changed_second_command_cannot_supply_a_full_plan_return(self):
        public, labels = fixture();labels['plans'][1]['commands'] = ['buy-advance N']
        with self.assertRaisesRegex(ValueError, 'identical complete plan'):
            data.origin(public, labels, PUBLIC)

    def test_unsupported_plan_remains_missing_not_zero_effect(self):
        public, labels = fixture();plan = public['facts']['plans'][1]
        plan.update(support='INTERVENING_CHOICE', features=None)
        labels['plans'] = labels['plans'][:1]
        result = data.origin(public, labels, PUBLIC)
        self.assertFalse(result.eligible)
        self.assertEqual(result.missingReason, 'NO_SUPPORTED_NONPASS')
        self.assertIsNone(result.plans[1].returns)

    def test_missing_root_retains_no_imputed_label(self):
        public, labels = fixture();public['status'] = 'MISSING';labels['plans'] = []
        result = data.origin(public, labels, PUBLIC)
        self.assertEqual(result.missingReason, 'NO_ROOT')
        self.assertEqual(result.plans, ())

    def test_public_support_hash_must_match_before_consuming_labels(self):
        public, labels = fixture();labels['publicFactsSha256'] = 'c' * 64
        with self.assertRaisesRegex(ValueError, 'exact prior public support'):
            data.origin(public, labels, PUBLIC)


if __name__ == '__main__':
    unittest.main()
