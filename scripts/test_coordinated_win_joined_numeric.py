"""Small fixture assertions; the retained joined execution is a separately labeled proof."""
import math
import unittest

import coordinated_es_math as es
import coordinated_es_storage as storage
import coordinated_win_joined_fixture as fixture


class ConstructedOutcomes(unittest.TestCase):
    def test_all_invented_scores_match_every_recorded_terminal_metric(self):
        for index in range(8):
            for plus in (True,False):
                with self.subTest(index=index,plus=plus):
                    value=fixture.outcome(index,plus)
                    self.assertEqual(storage.terminal_metrics(value)['wins'],value['wins'])

    def test_win_share_and_old_rank_reward_favor_opposite_signs(self):
        def mean(field,plus):
            return math.fsum(fixture.outcome(index,plus)[field][index%4] for index in range(256))/256
        self.assertGreater(mean('wins',True),mean('wins',False))
        self.assertLess(mean('rewards',True),mean('rewards',False))

    def test_exact_win_gradient_differs_from_legacy_reward_gradient(self):
        directions=tuple(tuple(1. if i%2==0 else -1. for i in range(256)) for _ in range(16))
        def step(field):
            difference=math.fsum(fixture.outcome(i,True)[field][i%4]-fixture.outcome(i,False)[field][i%4]
                for i in range(256))/256
            return es.step_coordinates((.1,)*256,directions,(difference,)*16)
        wins,rewards=step('wins'),step('rewards')
        self.assertGreater(wins['gradient'][0],0.)
        self.assertLess(rewards['gradient'][0],0.)
        self.assertNotEqual(wins['coordinates'],rewards['coordinates'])

    def test_fixture_includes_split_winners_and_chaos(self):
        self.assertEqual(fixture.outcome(0,False)['wins'][0],1/3)
        self.assertIsNone(next(s for s in fixture.outcome(1,True)['scores'] if s['player']=='p2')['total'])


if __name__=='__main__':unittest.main()
