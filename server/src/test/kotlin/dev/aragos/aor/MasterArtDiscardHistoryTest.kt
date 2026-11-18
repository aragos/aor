package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test

class MasterArtDiscardHistoryTest {
    private val owner = PlayerId("p1")
    private val observer = PlayerId("p2")

    @Test
    fun ordinaryDiscardReplaysPubliclyAndContinuesAfterRestart() {
        verifySavedDiscard(immediate = false)
    }

    @Test
    fun immediateDiscardReplaysPubliclyAndContinuesAfterRestart() {
        verifySavedDiscard(immediate = true)
    }

    private fun verifySavedDiscard(immediate: Boolean) {
        val driver = GameDriver(FixedRandom())
        val ready = readyToDiscard(driver, immediate)
        val command =
            ready.state().legalChoices(owner).commands.first { it.startsWith("discard-card ") }
        val card =
            ready.state().playerState(owner).hand.single {
                it.reference == command.substringAfter(' ')
            }
        val original = driver.submit(ready, owner, command)
        val directory = Files.createTempDirectory("aor-master-art-history-")
        try {
            FileGameStore(directory).create(original)
            val reopened = FileGameStore(directory)

            val restored = reopened.read(original.id)
            val projections =
                listOf(owner, observer, null).map { HistoryProjection.forPlayer(restored, it) }
            val next = restored.state().prompts().single() as Prompt.SinglePlayer
            val continued = driver.submit(restored, next.player, "pass")
            reopened.update(original.id) { continued }

            assertThat(restored).isEqualTo(original)
            assertThat(restored.state()).isEqualTo(original.state())
            for (projection in projections) {
                assertThat(projection.lines).contains("$owner: $command [${card.definition.id}]")
                assertThat(projection.events.last { it.kind == HistoryEventKind.DISCARD_CARD }.card)
                    .isEqualTo(card.definition.id)
                assertThat(projection.state.publicView().unplayedCards.map(CardDefinition::id))
                    .doesNotContain(card.definition.id)
            }
            assertThat(continued.effectiveEntries().take(original.effectiveEntries().size))
                .isEqualTo(original.effectiveEntries())
            assertThat(reopened.read(original.id).state()).isEqualTo(continued.state())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Reach Master Art through recorded commands under a previously supported catalog. */
    private fun readyToDiscard(driver: GameDriver, immediate: Boolean): StoredGame {
        val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
        var game =
            driver.create(
                id = UUID.randomUUID().toString(),
                header =
                    GameHeader(players, owner, dataVersion = CatalogVersion.CATALOG_13.wireValue),
                credentials =
                    players
                        .mapIndexed { index, player ->
                            player.id to index.toString().padStart(64, '0')
                        }
                        .toMap(),
                rules = RuleOptions(immediateMasterArtDiscard = immediate),
            )
        repeat(600) {
            val state = game.state()
            val actor =
                when (val prompt = state.prompts().single()) {
                    is Prompt.SinglePlayer -> prompt.player
                    is Prompt.MultiPlayer -> prompt.players.first { it !in game.pendingBids }
                    is Prompt.System -> error("The driver must resolve authority outcomes.")
                }
            val choices = state.legalChoices(actor).commands
            if (actor == owner && choices.any { it.startsWith("discard-card ") }) return game
            val player = state.playerState(actor)
            val placement =
                state
                    .viewForPlayer(actor)
                    .expansionOffers
                    .filter {
                        it.primaryAction == ExpansionAction.PLACE &&
                            it.establishesMarker &&
                            it.blockers.isEmpty()
                    }
                    .minByOrNull { it.requiredTokens }
            val command =
                when {
                    state.stage == Stage.BIDDING -> "bid-capital ${if (actor == owner) 3 else 0}"
                    state.stage == Stage.TOKEN_PURCHASE ->
                        "buy-tokens ${if (actor == owner) minOf(6, player.cash, player.stockTokens) else 0}"
                    actor == owner &&
                        choices.any {
                            it in listOf("buy-advance N", "buy-advance O", "buy-advance P")
                        } ->
                        listOf("buy-advance N", "buy-advance O", "buy-advance P").first {
                            it in choices
                        }
                    actor == owner && placement != null ->
                        "place ${placement.requiredTokens} ${placement.destination}"
                    "stabilize cash" in choices -> "stabilize cash"
                    "stabilize misery" in choices -> "stabilize misery"
                    "pass" in choices -> "pass"
                    else -> choices.first()
                }
            game = driver.submit(game, actor, command)
        }
        error("The fixture must reach a Master Art discard opportunity.")
    }
}
