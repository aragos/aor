"""Fixed contract checks use constructed metadata, never game or model inputs."""
import unittest

import prepare_local_bid_seeds as owner


def method():
    return dict(kind='local-bid-mixture-prospective-method-1', study=95, localOnly=True,
        remoteUseAuthorized=False, noNewFits=True, weightsImmutable=True,
        globalDeadlineEpoch=1795020399.0, maximumTerminalExecutions=2648,
        banks=[dict(name=name, worlds=count,
            replayIndices=[0, 21, 42, 63] if name == 'development' else [0, 85, 170, 255])
            for name, count in owner.COUNTS.items()],
        caseConfigs=[dict(id=f'n{index:02d}', tokenNeighborMixture=dict(
            kind='local-token-neighbor-mixture-1', departureProbability=epsilon,
            radius=radius, admission=admission))
            for index, (admission, radius, epsilon) in enumerate(
                (admission, radius, epsilon)
                for admission in ('OFFERED_ONLY', 'QUOTED_LEGAL_NEIGHBORS')
                for radius in (1, 2) for epsilon in (0.25, 0.5))])


class LocalBidReservationContractTest(unittest.TestCase):
    def test_approved_fixed_recipe_is_valid(self):
        self.assertIsNone(owner.validate_method(method()))

    def test_extra_worlds_are_rejected_before_reservation(self):
        changed = method()
        changed['banks'][0]['worlds'] += 1
        with self.assertRaisesRegex(ValueError, '832 new worlds'):
            owner.validate_method(changed)

    def test_extra_reproduction_worlds_are_rejected(self):
        changed = method()
        changed['banks'][1]['replayIndices'].append(256)
        with self.assertRaisesRegex(ValueError, 'fixed approved world positions'):
            owner.validate_method(changed)

    def test_reordering_case_selection_is_rejected(self):
        changed = method()
        changed['caseConfigs'] = list(reversed(changed['caseConfigs']))
        with self.assertRaisesRegex(ValueError, 'exact order'):
            owner.validate_method(changed)

    def test_window_extension_is_rejected(self):
        changed = method()
        changed['globalDeadlineEpoch'] += 1
        with self.assertRaisesRegex(ValueError, 'approved local-only window'):
            owner.validate_method(changed)

    def test_mutable_weights_are_rejected(self):
        changed = method()
        changed['weightsImmutable'] = False
        with self.assertRaisesRegex(ValueError, 'immutable no-fit policy'):
            owner.validate_method(changed)


if __name__ == '__main__':
    unittest.main()
