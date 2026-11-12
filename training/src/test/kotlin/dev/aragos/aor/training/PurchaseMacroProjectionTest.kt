package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import java.nio.file.Path
import kotlin.test.Test

/** UNIT: only short deterministic purchase previews, no sampled game or return labels. */
class PurchaseMacroProjectionTest {
    private val actor = PlayerId("p1")
    private val people = (1..4).map { Player(PlayerId("p$it"), "Synthetic $it") }
    private val acquisitions =
        listOf(
            AdvanceAcquisition(AdvanceId.THE_HEAVENS, 2),
            AdvanceAcquisition(AdvanceId.PATRONAGE, 1),
            AdvanceAcquisition(AdvanceId.CARAVAN, 1),
            AdvanceAcquisition(AdvanceId.OVERLAND_EAST, 1),
            AdvanceAcquisition(AdvanceId.URBAN_ASCENDANCY, 1),
        )

    private fun state(
        cash: Int = 90,
        misery: MiseryStep = MiseryStep.HUNDRED,
        advances: List<AdvanceAcquisition> = acquisitions,
        handCount: Int = 1,
    ): GameState =
        GameState(
            header = GameHeader(people, actor),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(people.map(Player::id)),
            deck = CardData.epochOne.map { it.id },
            flow =
                TurnFlow(
                    actor = actor,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
            players =
                people
                    .mapIndexed { index, person ->
                        person.id to
                            PlayerState(
                                player = person,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = cash,
                                misery = misery,
                                advances =
                                    if (person.id == actor) PlayerAdvances(advances)
                                    else PlayerAdvances(),
                                hand =
                                    CardData.epochOne.take(handCount).mapIndexed { cardIndex, card
                                        ->
                                        HandCard("hand-$index-$cardIndex", card)
                                    },
                            )
                    }
                    .toMap(),
        )

    private fun project(state: GameState): PurchaseMacroFacts =
        PurchaseMacroProjection.from(state, actor, BotObservations.from(state, actor))

    private fun values(
        facts: PurchaseMacroFacts,
        plan: PurchaseMacroPlanFacts,
    ): Map<String, Double> =
        facts.featureNames
            .zip(requireNotNull(plan.features) { "The synthetic plan must be supported." })
            .toMap()

    @Test
    fun dictionaryMatchesTheProspectivelyFrozenPythonSchema() {
        val expected =
            ObjectMapper()
                .readTree(Path.of("../scripts/purchase_macro_features.json").toFile())
                .required("featureNames")
                .map { it.asText() }
        assertThat(PurchaseMacroProjection.featureNames)
            .containsExactlyElementsIn(expected)
            .inOrder()
        assertThat(expected).hasSize(169)
    }

    @Test
    fun passIsExactlyZeroInEveryCoordinate() {
        val facts = project(state())
        val pass = facts.plans.first()
        assertThat(pass.commands).containsExactly("pass")
        assertThat(pass.support).isEqualTo(PurchaseMacroSupport.SUPPORTED)
        assertThat(pass.features).containsExactlyElementsIn(List(169) { 0.0 })
    }

    @Test
    fun completionUsesAuthoritativeCashMrAndTierConsequences() {
        val initial = state()
        val facts = project(initial)
        val completion = facts.plans.single { it.commands == listOf("buy-advance N", "pass") }
        val actual =
            ActivityPlanning.preview(
                initial,
                actor,
                completion.commands.map(PurchaseSequencePlans::action),
            )
        val before = requireNotNull(initial.players[actor]) { "The synthetic owner must exist." }
        val after = actual.effects.last().after
        val features = values(facts, completion)
        assertThat(features["delta.cash"]).isEqualTo((after.cash - before.cash).toDouble())
        assertThat(features["delta.tiers"]).isEqualTo(after.advances.tierCount.toDouble())
        assertThat(features["delta.unusedMR"])
            .isEqualTo((actual.game.reliefRatingAvailable - 5).toDouble())
    }

    @Test
    fun conditionalCashReliefQuotesAccountForStabilizationFirst() {
        val facts =
            project(state(cash = 50, misery = MiseryStep.HUNDRED_TWENTY_FIVE, handCount = 4))
        val completion = facts.plans.single { it.commands == listOf("buy-advance N", "pass") }
        val features = values(facts, completion)
        assertThat(features["delta.maxAffordableReliefSpaces"]).isEqualTo(1.0)
        assertThat(features["delta.conditional.cash.maxAffordableReliefSpaces"]).isEqualTo(0.0)
    }

    @Test
    fun naturallyTerminalChaosPurchaseRemainsASupportedHarmfulPlan() {
        val initial =
            state(
                misery = MiseryStep.THOUSAND,
                advances =
                    acquisitions.filter { it.advance != AdvanceId.PATRONAGE } +
                        AdvanceAcquisition(AdvanceId.WRITTEN_RECORD, 1),
            )
        val facts = project(initial)
        val harmful = facts.plans.single { it.commands == listOf("buy-advance E") }
        assertThat(harmful.support).isEqualTo(PurchaseMacroSupport.SUPPORTED)
        assertThat(harmful.naturalFinish).isTrue()
        assertThat(values(facts, harmful)["delta.chaos"]).isEqualTo(1.0)
    }

    @Test
    fun interveningMasterArtChoiceIsAnExplicitUnsupportedPlan() {
        val owned =
            AdvanceId.entries
                .filter {
                    AdvanceData.definition(it).category != AdvanceCategory.COMMUNICATION
                }
                .map { AdvanceAcquisition(it, if (it == AdvanceId.THE_HEAVENS) 2 else 1) } +
                listOf(
                    AdvanceAcquisition(AdvanceId.WRITTEN_RECORD, 1),
                    AdvanceAcquisition(AdvanceId.PRINTED_WORD, 1),
                )
        // Keep exactly one missing category (Science), leaving only three affordable scientific
        // offers before Master Art in the fixed four-first-action proposal budget.
        val withoutScience =
            owned
                .filter { AdvanceData.definition(it.advance).category != AdvanceCategory.SCIENCE }
                .map { it.copy(turnNumber = if (it.advance == AdvanceId.CARAVAN) 2 else 1) }
        val initial =
            state(cash = 100, advances = withoutScience)
                .copy(rules = RuleOptions(immediateMasterArtDiscard = true))
        val facts = project(initial)
        val interrupted = facts.plans.single { it.commands == listOf("buy-advance P") }
        assertThat(interrupted.support).isEqualTo(PurchaseMacroSupport.INTERVENING_CHOICE)
        assertThat(interrupted.features).isNull()
    }

    @Test
    fun hiddenRivalHandsAndDeckPermutationCannotChangePublicPlanFeatures() {
        val original = state()
        val changed =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == actor) player
                        else
                            player.copy(
                                hand =
                                    listOf(HandCard("other-${id.value}", CardData.epochOne.last()))
                            )
                    },
            )
        assertThat(project(changed)).isEqualTo(project(original))
    }

    @Test
    fun projectionPreservesTheOriginalTeacherProposalOrderingAndCaps() {
        val initial = state(cash = 200)
        val expected =
            PurchaseSequencePlans.propose(initial, actor, BotObservations.from(initial, actor))
        val actual = project(initial)
        assertThat(actual.proposals).isEqualTo(expected)
        assertThat(actual.plans.map { it.commands })
            .containsExactlyElementsIn(expected.plans.map { it.commands })
            .inOrder()
    }

    @Test
    fun conditionalQuotesDoNotConsumeActualCashOrRating() {
        val initial = state()
        val before = initial.viewForPlayer(actor)
        project(initial)
        assertThat(initial.viewForPlayer(actor)).isEqualTo(before)
    }
}
