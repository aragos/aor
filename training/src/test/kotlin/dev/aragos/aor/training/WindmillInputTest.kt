package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Prospective schedules and metadata-only preparation; no collection or fitting. */
class WindmillInputTest {
    @Test
    fun seatAndEpochComeOnlyFromProspectiveIndex() {
        assertThat((0 until 12).map { WindmillRequest(it).let { row -> row.seat to row.epoch } })
            .containsExactlyElementsIn(
                (0..3).flatMap { seat -> (1..3).map { epoch -> seat to epoch } }
            )
    }

    @Test
    fun exactlyAssignedControlledWindowQualifies() {
        assertThat(WindmillRequest(7).captures(2, 3, observation())).isTrue()
    }

    @Test
    fun otherSeatCannotBecomeRoot() {
        assertThat(WindmillRequest(7).captures(2, 0, observation())).isFalse()
    }

    @Test
    fun otherEpochCannotBecomeRoot() {
        assertThat(WindmillRequest(7).captures(1, 3, observation())).isFalse()
    }

    @Test
    fun passOnlyOrWrongDecisionCannotBecomeRoot() {
        assertThat(WindmillRequest(0).captures(1, 0, observation("pass"))).isFalse()
    }

    @Test
    fun noOriginRefillAllowed() {
        assertFailsWith<IllegalArgumentException> { WindmillRequest(256) }
    }

    @Test
    fun exactlyFourFutureStreamsRequired() {
        assertFailsWith<IllegalArgumentException> { WindmillSeeds(1, listOf(2, 3)) }
    }

    @Test
    fun completeJointExpansionAndTokenControllersOnEverySeat() {
        WindmillFixture().use { fixture ->
            for (seat in 0..3) {
                val controllers = fixture.input.controllers(seat)
                assertThat(controllers.keys).containsExactly(0, 1, 2, 3)
                assertThat(
                        controllers.values.all {
                            it.controls("play-cards") &&
                                it.controls("purchase-advances") &&
                                it.controls("expand") &&
                                it.controls("buy-tokens")
                        }
                    )
                    .isTrue()
            }
        }
    }

    @Test
    fun candidateOnlyJointActorCannotChangeHomogeneousEcology() {
        WindmillFixture().use { fixture ->
            fixture.base.writeBundle("null-card.json", cardName = null)
            fixture.edit {
                (this["jointPolicy"] as ObjectNode).put("sharedBundle", "null-card.json")
            }
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun differentBundleIdentityRejectedEvenWithSameHeads() {
        WindmillFixture().use { fixture ->
            val copy = fixture.base.artifacts.directory.resolve("other.json")
            Files.writeString(
                copy,
                Files.readString(fixture.base.artifacts.directory.resolve("bundle.json")) + "\n",
            )
            fixture.edit {
                (this["jointPolicy"] as ObjectNode).put("incumbentBundle", "other.json")
            }
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun ordinaryRoleMismatchRejected() {
        WindmillFixture().use { fixture ->
            val parsed = fixture.input
            assertFailsWith<IllegalArgumentException> {
                WindmillInput.validateEcology(
                    parsed.arena.copy(
                        incumbent =
                            BotModels.default.copy(
                                weights = BotModels.default.weights + ("cash" to 99.0)
                            )
                    )
                )
            }
        }
    }

    @Test
    fun tokenRoleMismatchRejected() {
        WindmillFixture().use { fixture ->
            fixture.edit { (this["tokenOrder"] as ObjectNode).put("candidateSelector", "ORDINARY") }
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun unrelatedProfileFieldRejected() {
        WindmillFixture().use { fixture ->
            fixture.edit { put("rivalAcquisition", "TURN_ROUTE_HALF_1") }
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun independentOriginsCannotReuseAStream() {
        WindmillFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["origins"][1] as ObjectNode).set<JsonNode>(
                "future",
                source["origins"][0]["future"],
            )
            fixture.base.artifacts.write("pilot.json", source)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun changedHeadCannotBeSnapshotted() {
        WindmillFixture().use { fixture ->
            val input = fixture.input
            val file = fixture.base.artifacts.directory.resolve("card-policy.json")
            Files.writeString(file, Files.readString(file) + "\n")
            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.base.output))
            }
        }
    }

    @Test
    fun preparationRetainsAllOriginsWithoutGames() {
        WindmillFixture().use { fixture ->
            WindmillMain.main(
                arrayOf(
                    "--mode",
                    "prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    fixture.base.output.toString(),
                )
            )
            val prepared = PurchaseJson.read(fixture.base.output.resolve("prepared.json"))
            assertThat(prepared["requests"].size()).isEqualTo(256)
            assertThat(prepared["maximumTerminalGames"].intValue()).isEqualTo(2304)
            assertThat(prepared["maximumTailGames"].intValue()).isEqualTo(2048)
            assertThat(Files.exists(fixture.base.output.resolve("started.json"))).isFalse()
        }
    }

    @Test
    fun prepareCannotOverwriteAnAttempt() {
        WindmillFixture().use { fixture ->
            Files.createDirectories(fixture.base.output)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                WindmillMain.main(
                    arrayOf(
                        "--mode",
                        "prepare",
                        "--source",
                        fixture.source.toString(),
                        "--output",
                        fixture.base.output.toString(),
                    )
                )
            }
        }
    }

    private fun observation(vararg commands: String = arrayOf("windmill", "pass")) =
        BotObservation(
            playerId = "p1",
            decision = "windmill",
            context = emptyMap(),
            availableCash = 10,
            availableTokens = 3,
            candidates = commands.map { BotCandidate(it, emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}

internal class WindmillFixture : AutoCloseable {
    val base =
        JointCollectionFixture(
            configuration =
                TrainingMatrix.configurations.single {
                    it.id == "players-4/standard/no-crusades/deck-true/art-true"
                }
        )
    val source = base.artifacts.directory.resolve("pilot.json")

    init {
        base.artifacts.model(BotModels.default, "expansion.json")
        base.artifacts.write(
            "arena.json",
            mapOf(
                "schemaVersion" to 1,
                "purpose" to "SCREENING",
                "configurations" to listOf(base.configuration.id),
                "candidate" to "baseline.json",
                "incumbent" to "baseline.json",
                "opponents" to listOf("baseline.json"),
                "pairsPerConfiguration" to 8,
                "seed" to 71,
                "jointPolicy" to
                    mapOf(
                        "baseline" to "baseline.json",
                        "sharedBundle" to "bundle.json",
                        "candidateBundle" to "bundle.json",
                        "incumbentBundle" to "bundle.json",
                    ),
                "tokenOrder" to
                    mapOf(
                        "candidateSelector" to "FIXED_16_TO_15",
                        "incumbentSelector" to "FIXED_16_TO_15",
                        "opponentSelectors" to listOf("FIXED_16_TO_15"),
                    ),
                "expansionImitation" to
                    mapOf(
                        "candidateModel" to "expansion.json",
                        "incumbentModel" to "expansion.json",
                        "opponentModels" to listOf("expansion.json"),
                    ),
            ),
        )
        base.artifacts.write(
            "pilot.json",
            mapOf(
                "kind" to "windmill-data-1",
                "arena" to "arena.json",
                "origins" to
                    (0 until 256).map { index ->
                        WindmillSeeds(
                            index,
                            (0..3).map { 10000L + 4 * index + it },
                        )
                    },
            ),
        )
    }

    val input: WindmillInput
        get() = WindmillInput.read(source)

    fun edit(change: ObjectNode.() -> Unit) {
        val file = base.artifacts.directory.resolve("arena.json")
        val node = PurchaseJson.read(file) as ObjectNode
        node.change()
        base.artifacts.write("arena.json", node)
    }

    override fun close() = base.close()
}
