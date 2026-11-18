"""Pure contract and paired-screen tests; no game executor or optimizer is called."""
import copy
import unittest

import purchase_baseline_pilot as pilot
import purchase_ecology_data as data
import automata_experiments as exp
import automata_purchase_continuation as legacy


class MatchedPilotTest(unittest.TestCase):
    def protocol(self):
        return dict(configuration=exp.CONFIGURATION,baseline='/ordinary',prior='/prior',
            purchasePolicy='/c64',expansionModel='/expansion',opponents=['/ordinary']*4,
            experimentalRulesTransfer=legacy.RULES_TRANSFER,
            banks=dict(training=dict(nativeOffset=42000000),screen=dict(nativeOffset=42000001)))

    def pairs(self):
        return [dict(index=i,configuration=exp.CONFIGURATION,seed=-900+i,seat=i%4,
            opponentIndices=[0,1,2,3],
            candidate=dict(wins=[0.,0.,0.,0.],rewards=[.2,.2,.2,.2],scores=[1,2,3,4],ranks=[4,3,2,1],
                           decisions=1,elapsedMillis=1),
            incumbent=dict(wins=[0.,0.,0.,0.],rewards=[.1,.1,.1,.1],scores=[1,2,3,4],ranks=[4,3,2,1],
                           decisions=1,elapsedMillis=1)) for i in range(512)]

    def test_controlled_collection_and_rivals_have_explicit_same_roles(self):
        source=pilot.collection_source(self.protocol())
        self.assertEqual(source['games'],256)
        self.assertEqual(source['policy'],source['opponentPurchasePolicy'])
        self.assertEqual(source['tokenOrder'],dict(candidateSelector=data.FIXED,
            opponentSelectors=['ORDINARY','ORDINARY',data.FIXED,data.FIXED]))
        self.assertEqual(source['expansionImitation']['opponentModels'],[None,'/expansion',None,'/expansion'])
        self.assertEqual(source['sharedPurchaseBaseline'],'/prior')

    def test_screens_change_only_controlled_purchase_actor(self):
        first=pilot.arena_source(self.protocol(),'/recorded')
        second=pilot.arena_source(self.protocol(),'/external')
        first['frozenPurchase']['candidatePolicy']='/external'
        self.assertEqual(first,second)
        self.assertEqual(second['tokenOrder']['incumbentSelector'],data.FIXED)
        self.assertEqual(second['expansionImitation']['incumbentModel'],'/expansion')

    def test_primary_contrast_uses_external_minus_recorded_same_world(self):
        first=self.pairs();second=copy.deepcopy(first)
        for row in second:
            row['candidate']['wins'][row['seat']]=1.
            row['candidate']['rewards'][row['seat']]=.5
        result=pilot.compare(first,second)
        self.assertEqual(result['winShareDifference']['mean'],1.)
        self.assertAlmostEqual(result['rewardDifference']['mean'],.3)
        self.assertEqual(result['rewardDifference']['count'],512)

    def test_common_incumbent_mismatch_prevents_paired_contrast(self):
        first=self.pairs();second=copy.deepcopy(first)
        second[9]['incumbent']['scores'][0]=99
        with self.assertRaisesRegex(ValueError,'same qualified incumbent'):
            pilot.compare(first,second)

    def test_partial_screens_cannot_be_reported(self):
        with self.assertRaisesRegex(ValueError,'complete common512'):
            pilot.compare(self.pairs()[:-1],self.pairs())


if __name__=='__main__':unittest.main()
