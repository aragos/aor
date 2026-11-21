package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test

class CaravanTransitMigrationTest {
    @Test
    fun oldSaveActivatesAfterRecordedExpansionAndCanContinuePlaying() {
        val directory = Files.createTempDirectory("aor-caravan-upgrade-")
        try {
            val store = FileGameStore(directory)
            val driver = GameDriver(FixedRandom())
            val players = (1..3).map { Player(PlayerId("p$it"), "Player $it") }
            val initial =
                driver.create(
                    UUID.randomUUID().toString(),
                    GameHeader(players, players.first().id, darkCaravanFromTurn = null),
                    players.associate { player ->
                        player.id to player.id.value.last().toString().padStart(64, '0')
                    },
                    undoPolicy = UndoPolicy.LINEAR_GAME,
                )
            val expansion =
                generateSequence(initial) { game ->
                        val state = game.state()
                        val actor =
                            when (val prompt = state.prompts().single()) {
                                is Prompt.SinglePlayer -> prompt.player
                                is Prompt.MultiPlayer ->
                                    prompt.players.first { it !in game.pendingBids }
                                is Prompt.System ->
                                    error("The fixture driver resolves authority outcomes.")
                            }
                        val seat = state.playerIds.indexOf(actor)
                        val choices = state.legalChoices(actor).commands
                        val command =
                            when {
                                state.stage == Stage.BIDDING -> "bid-capital ${3 - seat}"
                                state.stage == Stage.TOKEN_PURCHASE -> "buy-tokens ${seat + 1}"
                                "stabilize cash" in choices -> "stabilize cash"
                                "pass" in choices -> "pass"
                                else -> choices.first()
                            }
                        driver.submit(game, actor, command)
                    }
                    .take(100)
                    .first { it.state().stage == Stage.EXPANSION }
            val firstExpander = (expansion.state().prompts().single() as Prompt.SinglePlayer).player
            val source = driver.submit(expansion, firstExpander, "pass")
            val raw = json.readTree(GameDocuments.write(source)) as ObjectNode
            (raw["header"] as ObjectNode).remove("darkCaravanFromTurn")
            val original = json.writeValueAsString(raw)
            Files.writeString(directory.resolve("${source.id}.json"), original)

            assertThat(CaravanTransitMigration.run(store)).containsExactly(source.id)
            val reopened = FileGameStore(directory)
            val upgraded = reopened.read(source.id)

            assertThat(upgraded.header.darkCaravanFromTurn).isEqualTo(2)
            assertThat(upgraded.effectiveEntries()).isEqualTo(source.effectiveEntries())
            assertThat(upgraded.state()).isEqualTo(source.state().copy(header = upgraded.header))
            val archive =
                requireNotNull(upgraded.revisions.last().storageArchive) {
                    "The Caravan migration requires an exact source archive."
                }
            assertThat(reopened.archivedDocument(source.id, archive)).isEqualTo(original)
            assertThat(CaravanTransitMigration.run(reopened)).isEmpty()
            val nextActor = (upgraded.state().prompts().single() as Prompt.SinglePlayer).player
            reopened.update(source.id) { driver.submit(it, nextActor, "pass") }
            assertThat(reopened.read(source.id).state().entryCount)
                .isGreaterThan(source.state().entryCount)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
