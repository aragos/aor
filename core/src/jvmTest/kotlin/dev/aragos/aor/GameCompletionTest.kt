package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

/** Command-level acceptance: no board/cash/hand edits in the complete deck-exhaustion scenario. */
class GameCompletionTest {
    @Test
    fun ordinaryCommandsCompleteAnEpochAfterTheLastDraw() {
        val completed = completedGame()
        assertThat(completed.state.stage).isEqualTo(Stage.FINISHED)
        assertThat(completed.state.deck).isEmpty()
        assertThat(completed.state.viewForPlayer(PlayerId("p1")).scores.any(ScoreView::winner))
            .isTrue()
    }

    @Test
    fun completedGameReplaysToTheIdenticalState() {
        val completed = completedGame()
        assertThat(replay(completed.state.header, completed.lines)).isEqualTo(completed.state)
    }

    @Test
    fun fullGameExercisesExpansionCompetitionPurchasesAndCardEffects() {
        val completed = completedGame()
        assertThat(completed.lines.any { line -> line.contains(": place ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": attack ") }).isTrue()
        assertThat(completed.lines.any { line -> line.startsWith("!roll competition ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": buy-advance ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": play ") }).isTrue()
        assertThat(completed.lines.any { line -> line.startsWith("!roll war ") }).isTrue()
        assertThat(completed.lines.any { line -> line.contains(": civil-war-loss ") }).isTrue()
    }

    private fun completedGame(): Scenario {
        val opening =
            Scenario.create()
                .play(
                    "p1: start",
                    "p1: bid-capital 3",
                    "p2: bid-capital 2",
                    "p3: bid-capital 1",
                    "p1: choose-capital venice",
                    "p2: choose-capital genoa",
                    "p3: choose-capital barcelona",
                    "p1: buy-tokens 8",
                    "p2: buy-tokens 9",
                    "p3: buy-tokens 10",
                )
                .playAllCardRounds()
                .play(
                    "p1: pass",
                    "p1: buy-advance V",
                    "p1: pass",
                    "p1: stabilize cash",
                    "p1: pass",
                    "p2: upgrade-ships",
                    "p2: pass",
                    "p2: stabilize cash",
                    "p2: pass",
                    "p3: pass",
                    "p3: buy-advance I",
                    "p3: pass",
                    "p3: stabilize cash",
                    "p3: pass",
                    "p1: place 4 florence",
                    "p1: buy-expansion-card",
                    "p1: pass",
                    "p2: attack florence",
                    "p2: pass",
                    "p3: place 2 valencia",
                    "p3: place 2 montpellier",
                    "p3: pass",
                )
        return opening.complete()
    }
}

internal data class Scenario(val state: GameState, val lines: List<String> = emptyList()) {
    fun play(vararg commands: String): Scenario =
        commands.fold(this) { current, command ->
            current.accept(TextCodec.parse(command)).resolveOutcomes()
        }

    private fun accept(entry: Entry): Scenario =
        try {
            copy(state = state.step(entry), lines = lines + entry.toText())
        } catch (failure: Exception) {
            throw AssertionError(
                "Rejected ${entry.toText()} at ${state.stage}, ${state.prompts()}",
                failure,
            )
        }

    private fun resolveOutcomes(): Scenario {
        var current = this
        repeat(200) {
            val need =
                (current.state.prompts().singleOrNull() as? Prompt.System)?.need ?: return current
            val outcome =
                when (need) {
                    SystemNeed.Shuffle -> Entry.Shuffle(CardData.epochOne.map(CardDefinition::id))
                    is SystemNeed.Draw ->
                        GameOutcome.Draw(need.player, need.reference, current.state.deck.first())
                    is SystemNeed.GameRoll ->
                        GameOutcome.Roll(
                            need.kind,
                            when (need.kind) {
                                GameRollKind.CAPITAL_TIE ->
                                    need.players.mapIndexed { index, _ -> DieRoll(6 - index) }
                                GameRollKind.COMPETITION ->
                                    listOf(DieRoll(1), DieRoll(6), DieRoll(6))
                                GameRollKind.WAR -> listOf(DieRoll(6), DieRoll(1))
                                GameRollKind.SHORTAGE -> listOf(DieRoll(6), DieRoll(6), DieRoll(6))
                                GameRollKind.WINDMILL -> listOf(DieRoll(1))
                            },
                            players = need.players,
                        )
                    is SystemNeed.Trade ->
                        GameOutcome.Trade(
                            need.owner,
                            need.recipient,
                            current.state.playerState(need.owner).hand.first().reference,
                        )
                }
            current = current.accept(outcome)
        }
        error("Authority outcomes must reach a player decision.")
    }

    fun playAllCardRounds(): Scenario {
        var current = this
        repeat(200) {
            if (
                current.state.stage != Stage.PLAY_CARDS &&
                    current.state.stage != Stage.FINAL_CARD_PLAY
            ) {
                return current
            }
            current = current.nextAction()
        }
        error("Every card round must finish.")
    }

    fun complete(): Scenario {
        var current = this
        repeat(2000) {
            if (current.state.stage == Stage.FINISHED) return current
            current = current.nextAction()
        }
        error("A finite one-epoch deck must finish the game.")
    }

    private fun nextAction(): Scenario {
        val prompt = state.prompts().single()
        if (prompt is Prompt.MultiPlayer) {
            val actor = prompt.players.first()
            return play("$actor: buy-tokens ${state.playerIds.indexOf(actor)}")
        }
        check(prompt is Prompt.SinglePlayer) { "Automatic outcomes must have resolved." }
        val actor = prompt.player
        val player = state.playerState(actor)
        val choices = state.legalChoices(actor).commands
        val command =
            if (state.flow.decisions.isNotEmpty()) {
                choices.firstOrNull()
                    ?: error("A pending decision requires a legal command: $prompt")
            } else
                when (state.stage) {
                    Stage.TURN_ORDER_TIE -> choices.first()
                    Stage.DRAW_CARD -> "pass"
                    Stage.BUY_CARD ->
                        if ("buy-card" in choices && player.cash >= 10) "buy-card" else "pass"
                    Stage.PLAY_CARDS,
                    Stage.FINAL_CARD_PLAY ->
                        CardGame.playChoices(state, actor).firstOrNull() ?: "pass"
                    Stage.PURCHASE ->
                        when (state.flow.purchaseStep) {
                            PurchaseStep.SHIPPING -> "pass"
                            PurchaseStep.ADVANCES -> {
                                val advance =
                                    AdvanceId.entries.firstOrNull { advance ->
                                        try {
                                            AdvancePurchases.quote(
                                                    player,
                                                    advance,
                                                    state.purchaseContext(actor),
                                                )
                                                .price <= player.cash
                                        } catch (_: RuleViolation) {
                                            false
                                        }
                                    }
                                if (advance == null) "pass" else "buy-advance ${advance.letter}"
                            }
                            PurchaseStep.STABILIZATION ->
                                "stabilize ${if (player.cash.toLong() >= PlayerEconomy.stabilizationCost(player)) "cash" else "misery"}"
                            PurchaseStep.RELIEF -> "pass"
                        }
                    Stage.EXPANSION -> "pass"
                    else -> error("Unhandled player phase ${state.stage}.")
                }
        return play("$actor: $command")
    }

    companion object {
        fun create(): Scenario {
            val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
            return Scenario(GameState(GameHeader(players, players.first().id)))
        }
    }
}
