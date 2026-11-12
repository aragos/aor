import copy
import unittest
import numpy as np
import cancellation_conditional_value as c


def fixture():
    command = 'cancel-adjustment cloth surplus'
    offer = dict(command=command, cost=5, cashAfter=35, commodities=['CLOTH'])
    payouts = [dict(commodity='CLOTH', adjustments=['SURPLUS'], owners=[])]
    owners = [dict(player={'value': p}, marketCount=2, industryBonus=0, boxBefore=1,
                   boxAfter=2, payoutBefore=5, payoutAfter=20) for p in ['p1', 'p2']]
    context = dict(actor={'value': 'p1'}, cash=40, offers=[offer], payouts=payouts)
    quote = dict(command=command, adjustment='SURPLUS', cost=5, cashBefore=40, cashAfter=35,
                 commodities=[dict(commodity='CLOTH', before=['SURPLUS'], after=[], owners=owners)])
    public = dict(context=context, quotes=[{'command': 'pass'}, quote])
    view = dict(currentEpoch=2, turnNumber=5, deckRemaining=11, endingTriggered=False,
                players=[dict(id={'value': 'p1'}, cash=40, handCount=1, boardMarkers=7)],
                hand=[dict(reference='hand-1', definition={'id': 'cloth'})],
                turnOrder=[{'value': 'p'+str(n)} for n in range(1, 5)],
                adjustmentOffers=[offer], commodityPayouts=payouts)
    actor = dict(view=view, observation=dict(decision='cancel-adjustment',
                 candidates=[{'command': 'pass'}, {'command': command}]))
    return actor, public, command, {'cloth': ['CLOTH']}


def world(index, commands=('a',), split='TRAIN'):
    baseline = dict(reward=.2, winShare=0., rankReward=1., chaos=0.)
    cancel = dict(reward=.4, winShare=.5, rankReward=0., chaos=0.)
    return dict(index=index, split=split, features={cmd: np.asarray([float(i)]) for i, cmd in enumerate(commands)},
                labels={'pass': [baseline]*2} | {cmd: [cancel]*2 for cmd in commands},
                kinds={cmd: 'SURPLUS' for cmd in commands}, epoch=1, sourceSha256='x',
                incumbent=commands[0], predictions={cmd: .1 for cmd in commands}, selected=commands[0])


class FeaturesTest(unittest.TestCase):
    def test_exact_dictionary_and_relative_owner_values(self):
        row = dict(zip(c.FEATURE_NAMES, c.extract(*fixture())))
        self.assertEqual(len(row), 58)
        self.assertEqual([row[x+'.payoutDeltaSum'] for x in ['own', 'rival1', 'rival2']], [15, 15, 0])
        self.assertEqual(row['ownPhysicalMatchingCardCountTimesOwnPayoutDeltaSum'], 15)

    def test_shared_card_counted_once(self):
        actor, public, command, catalog = fixture()
        quote = public['quotes'][1]
        quote['commodities'] = [dict(quote['commodities'][0], commodity=k) for k in ('GOLD', 'IVORY')]
        catalog['cloth'] = ['GOLD', 'IVORY']
        row = dict(zip(c.FEATURE_NAMES, c.extract(actor, public, command, catalog)))
        self.assertEqual([row[k] for k in ['ownPhysicalMatchingCardCount', 'sharedGoldIvory',
                                          'affectedCommodityCount', 'cost']], [1, 1, 2, 5])

    def test_repeated_kind_keeps_exact_after_quote(self):
        actor, public, command, catalog = fixture()
        change = public['quotes'][1]['commodities'][0]
        change.update(before=['SURPLUS', 'SURPLUS'], after=['SURPLUS'])
        for owner in change['owners']:
            owner['payoutAfter'] = owner['payoutBefore']
        row = dict(zip(c.FEATURE_NAMES, c.extract(actor, public, command, catalog)))
        self.assertEqual([row[k] for k in ['requestedKindCountSum', 'remainingRequestedKindCountSum',
                                          'unchangedHeadCount', 'own.payoutDeltaSum']], [2, 1, 1, 0])

    def test_non_head_marker_position(self):
        actor, public, command, catalog = fixture()
        public['quotes'][1]['commodities'][0].update(before=['SHORTAGE', 'SURPLUS'], after=['SHORTAGE'])
        row = dict(zip(c.FEATURE_NAMES, c.extract(actor, public, command, catalog)))
        self.assertEqual(row['requestedKindFirstIndexSum'], 1)
        self.assertEqual(row['requestedKindHeadCount'], 0)

    def test_future_metadata_cannot_change_features(self):
        actor, public, command, catalog = fixture()
        expected = c.extract(actor, public, command, catalog)
        actor.update(seed=987, futureCard='gold', terminalReward=1)
        public.update(index=999, split='OTHER', sourceSha256='arbitrary')
        np.testing.assert_array_equal(c.extract(actor, public, command, catalog), expected)

    def test_unknown_card_rejected(self):
        actor, public, command, _ = fixture()
        with self.assertRaisesRegex(ValueError, 'authoritative static'):
            c.extract(actor, public, command, {})

    def test_substituted_view_rejected(self):
        actor, public, command, catalog = fixture()
        actor = copy.deepcopy(actor)
        actor['view']['adjustmentOffers'][0]['cost'] += 1
        with self.assertRaisesRegex(ValueError, 'Original public context'):
            c.extract(actor, public, command, catalog)


class SelectionTest(unittest.TestCase):
    def test_zero_tie_is_pass(self): self.assertEqual(c.choose({'a': 0}), 'pass')
    def test_negative_is_pass(self): self.assertEqual(c.choose({'a': -.1, 'b': -.2}), 'pass')
    def test_positive_tie_is_canonical(self): self.assertEqual(c.choose({'b': .1, 'a': .1}), 'a')
    def test_highest_positive(self): self.assertEqual(c.choose({'a': .1, 'b': .2}), 'b')
    def test_nonfinite_rejected(self):
        with self.assertRaisesRegex(ValueError, 'finite'): c.choose({'a': float('nan')})
    def test_test_rejected(self):
        with self.assertRaisesRegex(ValueError, 'TEST'): c.split_for(224)


class NumericalTest(unittest.TestCase):
    def test_equal_origin_weights(self):
        _, targets, weights, _ = c.training_matrix([world(0), world(1, ('a', 'b'))])
        np.testing.assert_array_equal(weights, [1, .5, .5])
        np.testing.assert_allclose(targets, [.2, .2, .2])

    def test_validation_cannot_fit(self):
        with self.assertRaisesRegex(ValueError, 'Only TRAIN'):
            c.training_matrix([world(192, split='VALIDATION')])

    def test_weighted_scaling_unpenalized_intercept(self):
        fitted = c.ridge.fit_ridge([[0, 2], [4, 2]], [3, 3], [1, 3])
        self.assertEqual(fitted['mean'], [3, 2])
        self.assertEqual(fitted['standardDeviation'][1], 0)
        np.testing.assert_allclose(c.ridge.predict(fitted, [[999, 888]]), [3])

    def test_exact_fixed_ridge_solution(self):
        fitted = c.ridge.fit_ridge([[-1], [1]], [-1, 1], [1, 1])
        self.assertAlmostEqual(fitted['coefficients'][0], 1/11)
        self.assertEqual(fitted['lambda'], 10)

    def test_kind_mean_uses_train_weights(self):
        self.assertEqual(c.kind_means([1, 0, 0], [1, .5, .5], ['SURPLUS']*3),
                         {'SURPLUS': .5, 'SHORTAGE': .5})

    def test_report_groups_origins_and_keeps_both_references(self):
        first, second = world(192, split='VALIDATION'), world(193, ('a', 'b'), 'VALIDATION')
        second['selected'] = 'pass'
        summary, retained = c.report([first, second], {'SURPLUS': 0})
        self.assertAlmostEqual(summary['selectionEffects']['pass']['reward']['mean'], .1)
        self.assertAlmostEqual(summary['selectionEffects']['incumbent']['reward']['mean'], -.1)
        self.assertEqual(summary['selectionEffects']['pass']['reward']['origins'], 2)
        self.assertEqual(len(retained[1]['actions']), 2)

    def test_validation_returns_cannot_change_selection(self):
        value = world(192, split='VALIDATION')
        expected = c.choose(value['predictions'])
        value['labels'] = {'unoffered-lucky-action': [{'reward': 1000}]}
        self.assertEqual(c.choose(value['predictions']), expected)


class LabelsTest(unittest.TestCase):
    def origin(self):
        terminal = dict(reward=.2, winShare=0, rankReward=1,
                        scores=[dict(player='p1', misery=20, total=100)])
        return dict(provenance={'actor': 'p1'}, chanceSeeds=[123, 456],
                    actorInput={'observation': {'candidates': [{'command': c} for c in ['pass', 'a']]}},
                    arms=[dict(actionIndex=i, command=c,
                               outcomes=[dict(replicate=r, terminal=dict(terminal, continuationSeed=s),
                                              canonicalEntries=['private-not-input'], replayVerified=True)
                                         for r, s in enumerate([123, 456])])
                          for i, c in enumerate(['pass', 'a'])])

    def test_all_paired_actions_kept(self):
        actual = c.label_actions(self.origin())
        self.assertEqual(len(actual), 2)
        self.assertTrue(all(len(rows) == 2 for rows in actual.values()))

    def test_wrong_seed_rejected(self):
        origin = self.origin()
        origin['arms'][1]['outcomes'][1]['terminal']['continuationSeed'] = 999
        with self.assertRaisesRegex(ValueError, 'shared chance'): c.label_actions(origin)

    def test_suffix_strings_not_labels(self):
        origin = self.origin()
        expected = c.label_actions(origin)
        origin['arms'][0]['outcomes'][0]['canonicalEntries'] = ['different future']
        self.assertEqual(c.label_actions(origin), expected)

    def test_chaos_uses_actual_actor_receipt(self):
        origin = self.origin()
        origin['arms'][0]['outcomes'][0]['terminal']['scores'][0] = dict(player='p1', misery=None, total=None)
        self.assertEqual(c.label_actions(origin)['pass'][0]['chaos'], 1)


if __name__ == '__main__': unittest.main()
