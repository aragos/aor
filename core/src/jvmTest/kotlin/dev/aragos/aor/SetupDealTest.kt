package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class SetupDealTest {
    @Test
    fun initialDealRestrictionsApplyToEverySeat() {
        for (rule in InitialCardRule.entries) {
            val state = deal(rule)
            val cards = state.players.values.flatMap(PlayerState::hand)

            assertThat(state.stage).isEqualTo(Stage.BIDDING)
            assertThat(state.players.values.map { it.hand.size }).containsExactly(1, 1, 1)
            assertThat(cards.all { rule.accepts(it.definition) }).isTrue()
        }
    }

    @Test
    fun excludedEventsRemainInTheDeckForLaterDraws() {
        val state = deal(InitialCardRule.NO_EVENTS)
        val printedEvents =
            CardData.forEpoch(Epoch.ONE)
                .filter { it.kind == CardKind.EVENT }
                .map(CardDefinition::id)

        assertThat(state.deck).containsAtLeastElementsIn(printedEvents)
    }

    @Test
    fun noCrusadesStillAllowsOtherEvents() {
        val state = deal(InitialCardRule.NO_CRUSADES)

        assertThat(state.deck).contains("the-crusades")
        assertThat(
                state.players.values.flatMap(PlayerState::hand).any {
                    it.definition.kind == CardKind.EVENT
                }
            )
            .isTrue()
    }

    @Test
    fun restrictedDealReplaysWithoutNewRandomness() {
        val dealt =
            Scenario.create().play("p1: set-option initial-card commodity-only", "p1: start")

        assertThat(replay(dealt.state.header, dealt.lines)).isEqualTo(dealt.state)
    }

    private fun deal(rule: InitialCardRule): GameState {
        val state = Scenario.create().state.copy(rules = RuleOptions(initialCard = rule))
        val started = state.step(Entry.Action(PlayerId("p1"), Command.Start))
        val cards =
            checkNotNull(started.pendingShuffle()) {
                    "Starting setup requires its initial shuffle."
                }
                .cards
        val eventFirst = cards.sortedBy {
            if (CardData.definition(it).kind == CardKind.EVENT) 0 else 1
        }
        var dealt = started.step(Entry.Shuffle(eventFirst))
        while (dealt.stage == Stage.INITIAL_DRAW) {
            val need =
                checkNotNull(dealt.pendingDraw()) { "Every initial seat needs an eligible card." }
            dealt = dealt.step(GameOutcome.Draw(need.player, need.reference, need.card))
        }
        return dealt
    }
}
