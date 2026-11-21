package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test

class ChaosOrderMigrationTest {
    @Test
    fun oldSaveRetainsItsRevealedTieAndContinuesAfterReload() {
        val directory = Files.createTempDirectory("aor-chaos-order-upgrade-")
        try {
            val store = FileGameStore(directory)
            val driver = GameDriver(FixedRandom())
            val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
            val initial =
                driver.create(
                    UUID.randomUUID().toString(),
                    GameHeader(players, players.first().id, automaticChaosOrderFromTurn = null),
                    players.associate { it.id to it.id.value.last().toString().padStart(64, '0') },
                    undoPolicy = UndoPolicy.LINEAR_GAME,
                )
            val bids =
                players.withIndex().fold(initial) { game, (index, player) ->
                    driver.submit(game, player.id, "bid-capital ${3 - index}")
                }
            val capitals =
                players.fold(bids) { game, player ->
                    driver.submit(
                        game,
                        player.id,
                        game.state().legalChoices(player.id).commands.first(),
                    )
                }
            val source =
                players.fold(capitals) { game, player ->
                    driver.submit(game, player.id, "buy-tokens 0")
                }
            val raw = json.readTree(GameDocuments.write(source)) as ObjectNode
            (raw["header"] as ObjectNode).remove("automaticChaosOrderFromTurn")
            val original = json.writeValueAsString(raw)
            Files.writeString(directory.resolve("${source.id}.json"), original)

            assertThat(ChaosOrderMigration.run(store)).containsExactly(source.id)
            val reopened = FileGameStore(directory)
            val upgraded = reopened.read(source.id)

            assertThat(upgraded.header.automaticChaosOrderFromTurn).isEqualTo(2)
            assertThat(upgraded.effectiveEntries()).isEqualTo(source.effectiveEntries())
            assertThat(upgraded.state()).isEqualTo(source.state().copy(header = upgraded.header))
            val archive =
                checkNotNull(upgraded.revisions.last().storageArchive) {
                    "The Chaos ordering migration requires an exact source archive."
                }
            assertThat(reopened.archivedDocument(source.id, archive)).isEqualTo(original)
            assertThat(ChaosOrderMigration.run(reopened)).isEmpty()
            reopened.update(source.id) {
                driver.submit(it, PlayerId("p2"), "choose-turn-position 1")
            }
            assertThat(reopened.read(source.id).state().prompts())
                .containsExactly(Prompt.SinglePlayer(PlayerId("p3"), "choose-turn-position"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
