package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test

class SharedAdjustmentServiceTest {
    @Test
    fun sharedMarketRollOffersOneCancellationAndChargesTenDollarsOnce() {
        val directory = Files.createTempDirectory("aor-shared-adjustment-")
        try {
            val service = createGameService(directory, SharedSurplusRandom)
            val created = service.create(listOf("One", "Two", "Three"))
            val tokens = created.links.map { it.path.substringAfter("token=") }
            fun view(seat: Int): GameSnapshot = service.get(created.gameId, tokens[seat])
            fun cancellationWindow(): GameSnapshot {
                repeat(80) {
                    val state = view(0)
                    if (
                        state.game.stage == Stage.DRAW_CARD &&
                            state.legalChoices.commands.contains("cancel-adjustment gold surplus")
                    ) {
                        return state
                    }
                    val actor =
                        checkNotNull(state.prompt?.players?.firstOrNull()) {
                            "The fixture must reach another player decision."
                        }
                    val seat = created.links.indexOfFirst { it.id == actor }
                    val current = view(seat)
                    val commands = current.legalChoices.commands
                    val command =
                        when (current.game.stage) {
                            Stage.BIDDING -> "bid-capital ${3 - seat}"
                            Stage.CAPITAL_CHOICE ->
                                "choose-capital ${listOf("venice", "genoa", "barcelona")[seat]}"
                            Stage.TOKEN_PURCHASE -> "buy-tokens ${seat + 1}"
                            else ->
                                when {
                                    "pass" in commands -> "pass"
                                    "stabilize cash" in commands -> "stabilize cash"
                                    else ->
                                        error(
                                            "The fixture requires a supported phase action: $commands"
                                        )
                                }
                        }
                    service.submit(created.gameId, tokens[seat], current.revision, command)
                }
                error("The fixture must reach a shared adjustment cancellation window.")
            }
            val before = cancellationWindow()

            val after =
                service.submit(
                    created.gameId,
                    tokens[0],
                    before.revision,
                    "cancel-adjustment gold surplus",
                )
            val recovered =
                createGameService(directory, SharedSurplusRandom).get(created.gameId, tokens[0])

            assertThat(before.legalChoices.commands.filter { it.startsWith("cancel-adjustment") })
                .containsExactly("cancel-adjustment gold surplus")
            assertThat(after.game.players.first().cash)
                .isEqualTo(before.game.players.first().cash - 10)
            assertThat(after.game.adjustments[Commodity.GOLD])
                .containsExactly(CommodityAdjustment.SURPLUS)
            assertThat(after.game.adjustments[Commodity.IVORY])
                .containsExactly(CommodityAdjustment.SURPLUS)
            assertThat(after.history.last()).isEqualTo("p1: cancel-adjustment gold surplus")
            assertThat(recovered).isEqualTo(after)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private object SharedSurplusRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = cards

    override fun roll(count: Int): List<DieRoll> =
        if (count == 3) {
            listOf(DieRoll(6), DieRoll(6), DieRoll(6))
        } else {
            (1..count).map { DieRoll(7 - it) }
        }
}
