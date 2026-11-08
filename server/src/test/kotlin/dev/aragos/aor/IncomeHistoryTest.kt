package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class IncomeHistoryTest {
    private val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
    private val owner = players.first().id
    private val last = players.last().id

    @Test
    fun incomeNamesEachSourceUsingCashHeldBeforeCollection() {
        val initial = incomeBoundary()
        val action = Entry.Action(last, TurnCommand.Pass)
        val transition = initial.transition(action)

        val event =
            HistoryEvents.projectAll("20:0", initial, transition.state, action, owner, transition)
                .single { it.kind == HistoryEventKind.INCOME }

        assertThat(event.income)
            .contains(
                HistoryIncomeView(owner, baseIncome = 15, middleClassBonus = 10, interestProfit = 7)
            )
        assertThat(event.cashChanges).contains(HistoryCashChangeView(owner, 32))
        assertThat(transition.state.playerState(owner).cash).isEqualTo(39)
    }

    @Test
    fun incomeSourcesRemainEntryTimeFactsAfterLaterSpendingAndReplay() {
        val initial = incomeBoundary()
        val entries =
            listOf(
                Entry.Action(last, TurnCommand.Pass),
                GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(6), DieRoll(6), DieRoll(6))),
                GameOutcome.Roll(GameRollKind.SHORTAGE, listOf(DieRoll(6), DieRoll(6), DieRoll(6))),
                Entry.Action(owner, Command.BuyTokens(1)),
            )
        val original = events(initial, entries.take(1)).single()

        val replayed = events(initial, entries)

        assertThat(replayed.first()).isEqualTo(original)
        assertThat(
                json
                    .readTree(json.writeValueAsString(replayed.first()))["income"][0][
                        "interestProfit"]
                    .intValue()
            )
            .isEqualTo(7)
    }

    @Test
    fun playersInChaosHaveNoIncomeSources() {
        val base = incomeBoundary()
        val initial =
            base.copy(
                players =
                    base.players +
                        (owner to base.playerState(owner).copy(misery = MiseryStep.CHAOS))
            )
        val action = Entry.Action(last, TurnCommand.Pass)

        val event = events(initial, listOf(action)).single()

        assertThat(event.income.map(HistoryIncomeView::player)).doesNotContain(owner)
        assertThat(event.cashChanges.map(HistoryCashChangeView::player)).doesNotContain(owner)
    }

    private fun events(initial: GameState, entries: List<Entry>): List<HistoryEventView> {
        var state = initial
        return entries.flatMapIndexed { index, entry ->
            val before = state
            state = before.step(entry)
            HistoryEvents.projectAll("20:$index", before, state, entry, owner)
        }
    }

    private fun incomeBoundary(): GameState =
        GameState(
            header = GameHeader(players, owner),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = listOf("venice", "genoa", "barcelona")[index],
                                cash = 7,
                                advances =
                                    if (player.id == owner) {
                                        PlayerAdvances(
                                            listOf(
                                                    AdvanceId.MIDDLE_CLASS,
                                                    AdvanceId.INTEREST_PROFIT,
                                                )
                                                .map { AdvanceAcquisition(it, 1) }
                                        )
                                    } else PlayerAdvances(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = last,
                    cursor = 2,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
}
