"""Constructed metadata only; no RNG draws, models, corpus, native process or network."""
import copy
import unittest

import token_anchor_distribution as distribution
import token_anchored_plan as owner
from token_anchored_recipe import RECIPE


def record():
    rounds = []
    for number in (1, 2):
        start = 100*number
        rounds.append(dict(round=number, trainCount=16, validationCount=4,
            origins=[dict(gameSeed=1000000+start+index, proposalSeed=2000000+start+index,
                future=[3000000+2*(start+index), 3000001+2*(start+index)]) for index in range(20)],
            shuffleSeeds=[4000000+start+index for index in range(20)],
            replayIndices=[0, 19], opponentStyle='QUALIFIED'))
    result = dict(kind=owner.KIND, schemaVersion=1, methodSha256='1'*64,
        anchorDescriptorSha256='2'*64, originalActorSha256='3'*64, dictionarySha256='4'*64,
        nativeJarSha256='5'*64, probabilityContract=copy.deepcopy(distribution.RECORD),
        numericalRecipe=copy.deepcopy(RECIPE), proposalBudget=12, rounds=rounds,
        evaluation=dict(ordinary=dict(seeds=list(range(5000000, 5000032)),
            replayIndices=[0, 10, 21, 31], opponentStyle='QUALIFIED'),
            adaptive=dict(seeds=list(range(6000000, 6000032)), replayIndices=[0, 10, 21, 31],
                opponentStyle='REVEALED_UNDERCUT', adaptiveOpponent=dict(kind='public-token-history-counter-1',
                    minimumObservations=2, lookbackTurns=8, targetedRivalCount=1))),
        selection=copy.deepcopy(owner.SELECTION), chunkSize=8, maximumScienceSeconds=3600,
        auditSeconds=600, globalDeadlineEpoch=2000000000., automaticRetry=False, automaticSuccessor=False,
        ownership=dict(desktopChunksPerCycle=4, cycle=5), hosts={name: dict(workers=workers,
            heapMiB=6144, maximumRssBytes=12*1024**3, minimumFreeBytes=16*1024**3,
            maximumDataBytes=4*1024**3) for name, workers in (('DESKTOP', 16), ('MAC', 10))})
    result['maximumTerminalExecutions'] = owner.declared_budget(result)['maximum']
    return result


class PlanTests(unittest.TestCase):
    def test_full_declared_budget_counts12tails_and_all_reproductions(self):
        plan = owner.Plan.from_record(record())
        self.assertEqual(plan.record['maximumTerminalExecutions'], 1148)

    def test_later_rounds_are_generated_from_finite_declaration_not_study_registry(self):
        plan = owner.Plan.from_record(record())
        profile = dict(arena=dict(path='/invented/arena', sha256='a'*64),
            anchor=dict(path='/invented/anchor', sha256='b'*64),
            originalActor=dict(path='/invented/zero', sha256='3'*64))
        source = plan.collection_source(2, 2, profile, dict(path='/invented/final-round1', sha256='c'*64))
        self.assertEqual(source['globalStart'], 16); self.assertEqual(source['count'], 4)
        self.assertEqual(source['round'], 2); self.assertEqual(source['proposalBudget'], 12)
        self.assertEqual(source['origins'], plan.record['rounds'][1]['origins'])

    def test_stream_reuse_between_fit_and_holdout_is_rejected(self):
        value = record(); value['evaluation']['ordinary']['seeds'][0] = value['rounds'][0]['shuffleSeeds'][0]
        with self.assertRaisesRegex(ValueError, 'mutually disjoint'):
            owner.Plan.from_record(value)

    def test_neighbor_budget_cannot_change_after_registration(self):
        value = record(); value['proposalBudget'] = 8
        with self.assertRaisesRegex(ValueError, '12-arm'):
            owner.Plan.from_record(value)

    def test_repeated_source_round_is_rejected(self):
        value = record(); value['rounds'][1]['round'] = 1
        with self.assertRaisesRegex(ValueError, 'finite round sequence'):
            owner.Plan.from_record(value)

    def test_intermediate_or_diagnostic_selection_is_forbidden(self):
        value = record(); value['selection']['validationSelection'] = True
        with self.assertRaisesRegex(ValueError, 'sole final'):
            owner.Plan.from_record(value)

    def test_periodic_host_ownership_is_fixed(self):
        plan = owner.Plan.from_record(record())
        self.assertEqual([plan.owner(index) for index in range(10)], ['DESKTOP']*4+['MAC']+['DESKTOP']*4+['MAC'])

    def test_half_ordinary_mixed_league_has_one_canonical_four_field_config(self):
        value = record(); config = dict(kind='public-token-history-counter-1', minimumObservations=2,
            lookbackTurns=8, targetedRivalCount=1)
        for row in value['rounds']:
            row.update(opponentStyle='QUALIFIED_REVEALED_BALANCED', adaptiveOpponent=copy.deepcopy(config))
        plan = owner.Plan.from_record(value)
        self.assertEqual(plan.record['rounds'][0]['adaptiveOpponent'], config)
        self.assertNotIn('adaptiveOrdinaryFraction', plan.record['rounds'][0]['adaptiveOpponent'])

    def test_empty_random_remainder_is_not_an_unrecorded_proposal_option(self):
        value = record(); value['numericalRecipe']['maximumMeanOriginalKl'] = .02
        with self.assertRaisesRegex(ValueError, 'exact warm policy'):
            owner.Plan.from_record(value)


if __name__ == '__main__':
    unittest.main()
