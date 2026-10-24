package dev.aragos.aor

/**
 * Reusable immutable command-flow fixture with canonical replay lines.
 *
 * [play] parses commands and supplies deterministic authority outcomes until a player must act:
 * catalog-order shuffles, top-card draws, fixed dice, and the owner's first held card for trades.
 * [playAllCardRounds] and [complete] choose player commands with fixed priorities so completed
 * scenarios and their recorded histories are reproducible.
 */
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
        return play("${prompt.player}: ${nextCommand(prompt)}")
    }

    private fun nextCommand(prompt: Prompt.SinglePlayer): String {
        val choices = state.legalChoices(prompt.player).commands
        if (state.flow.decisions.isNotEmpty()) {
            return choices.firstOrNull()
                ?: error("A pending decision requires a legal command: $prompt")
        }
        return phaseCommand(prompt.player, choices)
    }

    private fun phaseCommand(actor: PlayerId, choices: List<String>): String =
        when (state.stage) {
            Stage.TURN_ORDER_TIE -> choices.first()
            Stage.DRAW_CARD -> "pass"
            Stage.BUY_CARD ->
                if ("buy-card" in choices && state.playerState(actor).cash >= 10) {
                    "buy-card"
                } else {
                    "pass"
                }
            Stage.PLAY_CARDS,
            Stage.FINAL_CARD_PLAY -> CardGame.playChoices(state, actor).firstOrNull() ?: "pass"
            Stage.PURCHASE -> purchaseCommand(state.playerState(actor))
            Stage.EXPANSION -> "pass"
            else -> error("Unhandled player phase ${state.stage}.")
        }

    private fun purchaseCommand(player: PlayerState): String =
        when (state.flow.purchaseStep) {
            PurchaseStep.SHIPPING -> "pass"
            PurchaseStep.ADVANCES -> {
                val advance = affordableAdvance(player)
                if (advance == null) "pass" else "buy-advance ${advance.letter}"
            }
            PurchaseStep.STABILIZATION -> stabilizationCommand(player)
            PurchaseStep.RELIEF -> "pass"
        }

    private fun affordableAdvance(player: PlayerState): AdvanceId? {
        val context = state.purchaseContext(player.player.id)
        return AdvanceId.entries.firstOrNull { advance ->
            try {
                AdvancePurchases.quote(player, advance, context).price <= player.cash
            } catch (_: RuleViolation) {
                false
            }
        }
    }

    private fun stabilizationCommand(player: PlayerState): String {
        val payment =
            if (player.cash.toLong() >= PlayerEconomy.stabilizationCost(player)) {
                "cash"
            } else {
                "misery"
            }
        return "stabilize $payment"
    }

    companion object {
        fun create(): Scenario {
            val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
            return Scenario(GameState(GameHeader(players, players.first().id)))
        }
    }
}
