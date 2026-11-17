package dev.aragos.aor.ai

import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.ExpansionOfferView
import dev.aragos.aor.GameView
import dev.aragos.aor.MarkerView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerView
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvinceView
import dev.aragos.aor.RebuiltMarkerBonus
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ScoreView
import dev.aragos.aor.Stage
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicLeaderAttackPolicyTest {
    private val actor = PlayerId("p1")
    private val leader = PlayerId("p2")
    private val other = PlayerId("p3")
    private val commands = listOf("attack basel", "attack madrid", "attack paris", "pass")
    private val observation =
        TerritoryObservation(
            TerritoryEncoder.EXPANSION_2.version,
            emptyMap(),
            commands.map { TerritoryAction(it, emptyMap()) },
            baselineIndex = 3,
        )
    private val deterministic =
        IncumbentResidualChoice(
            List(4) { 0.0 },
            listOf(.5, .75, .9, 1.0),
            index = 3,
            command = "pass",
        )
    private val config = PublicLeaderAttackConfig(beta = 1.0, minimumWinProbability = .5)

    private fun offer(province: String, probability: Double?) =
        ExpansionOfferView(
            destination = ProvinceId(province),
            primaryAction = ExpansionAction.ATTACK,
            requiredTokens = 2,
            availablePool = 10,
            resultingPool = 8,
            establishesMarker = true,
            uncertain = true,
            blockers = emptyList(),
            partialPlacements = emptyList(),
            shipping = null,
            incomeBefore = 0,
            incomeAfter = 10,
            reachable = true,
            competitionWinProbability = probability,
        )

    private fun view(ownScore: Int = 40): GameView {
        val cash = mapOf(actor to ownScore, leader to 100, other to 80)
        return GameView(
            stage = Stage.EXPANSION,
            currentEpoch = 1,
            rules = RuleOptions(),
            ruleOptions = emptyList(),
            rebuiltMarkerBonus = RebuiltMarkerBonus.EXCLUDE,
            players =
                cash.map { (id, amount) ->
                    PlayerView(
                        id,
                        id.value,
                        amount,
                        amount,
                        capital = null,
                        handCount = 0,
                        boardMarkers = 0,
                    )
                },
            hand = emptyList(),
            capitalOrder = cash.keys.toList(),
            revealedBids = emptyMap(),
            turnNumber = 3,
            turnOrder = cash.keys.toList(),
            orderBoxes = emptyMap(),
            revealedTokenPurchases = emptyMap(),
            provisionalTieOrder = emptyList(),
            board =
                listOf(
                    ProvinceView(ProvinceId("basel"), MarkerView(leader, PieceSide.COLOURED)),
                    ProvinceView(ProvinceId("madrid"), MarkerView(other, PieceSide.COLOURED)),
                    ProvinceView(ProvinceId("paris"), MarkerView(leader, PieceSide.COLOURED)),
                ),
            scores = cash.map { (id, amount) -> ScoreView(id, 0, amount, 0, amount, false) },
            expansionOffers = listOf(offer("basel", .8), offer("madrid", 1.0), offer("paris", .3)),
        )
    }

    @Test
    fun eligibleLegalAttackReceivesProbabilityWeightedBonus() {
        val result =
            PublicLeaderAttackPolicy.choose(view(), actor, observation, deterministic, config)
        assertEquals("attack basel", result.command)
        assertEquals(listOf(1.3, .75, .9, 1.0), result.scores)
    }

    @Test
    fun playerAlreadyInFrontReceivesNoAttackBonus() {
        assertEquals(
            deterministic,
            PublicLeaderAttackPolicy.choose(view(120), actor, observation, deterministic, config),
        )
    }

    @Test
    fun playerTiedForFirstReceivesNoAttackBonus() {
        assertEquals(
            deterministic,
            PublicLeaderAttackPolicy.choose(view(100), actor, observation, deterministic, config),
        )
    }

    @Test
    fun everyTiedLeadingRivalIsEligibleWhileTheActorIsStrictlyBehind() {
        val tied =
            view().let { original ->
                original.copy(
                    scores =
                        original.scores.map { score ->
                            if (score.player == other) score.copy(cash = 100, total = 100)
                            else score
                        }
                )
            }
        val selected =
            PublicLeaderAttackPolicy.choose(tied, actor, observation, deterministic, config)
        assertEquals(listOf(1.3, 1.75, .9, 1.0), selected.scores)
        assertEquals("attack madrid", selected.command)
    }

    @Test
    fun attackingLowerScoringRivalReceivesNoBonus() {
        val offered =
            observation.copy(
                actions = listOf(observation.actions[1], observation.actions[3]),
                baselineIndex = 1,
            )
        val original =
            deterministic.copy(
                residualDifferences = listOf(0.0, 0.0),
                scores = listOf(.75, 1.0),
                index = 1,
            )
        assertEquals(
            original,
            PublicLeaderAttackPolicy.choose(view(), actor, offered, original, config),
        )
    }

    @Test
    fun probabilityThresholdCanExcludeAnOtherwiseEligibleAttack() {
        assertEquals(
            deterministic,
            PublicLeaderAttackPolicy.choose(
                view(),
                actor,
                observation,
                deterministic,
                config.copy(minimumWinProbability = .9),
            ),
        )
    }

    @Test
    fun missingAuthoritativeProbabilityNeverBecomesAnInventedEstimate() {
        val noProbability =
            view()
                .copy(
                    expansionOffers =
                        listOf(
                            offer("basel", null),
                            offer("madrid", 1.0),
                            offer("paris", null),
                        )
                )
        assertEquals(
            deterministic,
            PublicLeaderAttackPolicy.choose(
                noProbability,
                actor,
                observation,
                deterministic,
                config,
            ),
        )
    }

    @Test
    fun partialPlacementNeverBorrowsThePrimaryFullAttackProbability() {
        val offered =
            TerritoryObservation(
                TerritoryEncoder.EXPANSION_2.version,
                emptyMap(),
                listOf("attack basel", "pass", "place 1 basel").map {
                    TerritoryAction(it, emptyMap())
                },
                baselineIndex = 1,
            )
        val original = IncumbentResidualChoice(List(3) { 0.0 }, listOf(.5, 1.0, .9), 1, "pass")
        val selected = PublicLeaderAttackPolicy.choose(view(), actor, offered, original, config)
        assertEquals(listOf(1.3, 1.0, .9), selected.scores)
    }

    @Test
    fun nonAttackPrimaryQuoteDoesNotSupplyAnAttackProbability() {
        val mismatched =
            view()
                .copy(
                    expansionOffers =
                        listOf(offer("basel", .8).copy(primaryAction = ExpansionAction.PLACE))
                )
        assertEquals(
            deterministic,
            PublicLeaderAttackPolicy.choose(mismatched, actor, observation, deterministic, config),
        )
    }

    @Test
    fun exactAdjustedTieRetainsOriginalReferenceCommand() {
        val certain = view().copy(expansionOffers = listOf(offer("basel", 1.0)))
        assertEquals(
            "pass",
            PublicLeaderAttackPolicy.choose(
                    certain,
                    actor,
                    observation,
                    deterministic,
                    config.copy(beta = .5),
                )
                .command,
        )
    }
}
