package dev.aragos.aor.training

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

/** Synthetic parsing/routing checks; no collection, terminal games, or fits. */
class CancellationInputTest {
    @Test
    fun assignedEpochUsesOnlyProspectiveIndex() {
        assertThat((0 until 256).map(::CancellationRequest).groupingBy { it.epoch }.eachCount())
            .containsExactly(1, 86, 2, 85, 3, 85)
    }

    @Test
    fun assignedBranchingCancellationQualifiesRegardlessOfPlayerIdentity() {
        assertThat(
                CancellationRequest(1)
                    .captures(
                        2,
                        observation("pass", "cancel-adjustment cloth surplus")
                            .copy(playerId = "p4"),
                    )
            )
            .isTrue()
    }

    @Test
    fun passOnlyWindowDoesNotQualify() {
        assertThat(CancellationRequest(1).captures(2, observation("pass"))).isFalse()
    }

    @Test
    fun otherEpochDoesNotQualify() {
        assertThat(
                CancellationRequest(1)
                    .captures(3, observation("pass", "cancel-adjustment cloth surplus"))
            )
            .isFalse()
    }

    @Test
    fun unrelatedDecisionDoesNotQualify() {
        assertThat(
                CancellationRequest(1)
                    .captures(2, observation("pass", "buy-card").copy(decision = "buy-card"))
            )
            .isFalse()
    }

    @Test
    fun fixedOriginBoundaryRejectsRefill() {
        assertFailsWith<IllegalArgumentException> { CancellationRequest(256) }
    }

    @Test
    fun exactlyTwoFutureStreamsRequired() {
        assertFailsWith<IllegalArgumentException> { CancellationSeeds(1, listOf(2)) }
    }

    @Test
    fun homogeneousControllersKeepAllThreeQualifiedOverlaysOnEverySeat() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            for (seat in 0..3) {
                val controllers = input.controllers(seat)
                assertThat(controllers.keys).containsExactly(0, 1, 2, 3)
                assertThat(
                        controllers.values.all {
                            it.controls("purchase-advances") &&
                                it.controls("expand") &&
                                it.controls("buy-tokens")
                        }
                    )
                    .isTrue()
                assertThat(
                        controllers.values.any {
                            it.controls("cancel-adjustment") || it.controls("play-card")
                        }
                    )
                    .isFalse()
            }
        }
    }

    @Test
    fun candidateOnlyPurchaseActorCannotChangeActorAtRootEcology() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            (arena["frozenPurchase"] as ObjectNode).put("candidatePolicy", "policy.json")
            fixture.artifacts.write("arena.json", arena)
            assertFailsWith<IllegalArgumentException> {
                CancellationInput.read(fixture.directory.resolve("pilot.json"))
            }
        }
    }

    @Test
    fun mismatchedTokenRoleCannotChangeActorAtRootEcology() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            (arena["tokenOrder"] as ObjectNode).put("candidateSelector", "ORDINARY")
            fixture.artifacts.write("arena.json", arena)
            assertFailsWith<IllegalArgumentException> {
                CancellationInput.read(fixture.directory.resolve("pilot.json"))
            }
        }
    }

    @Test
    fun nonhomogeneousOrdinaryRoleIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            assertFailsWith<IllegalArgumentException> {
                CancellationInput.validateEcology(
                    input.arena.copy(
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
    fun unknownArenaScientificFieldCannotBeSilentlyIgnored() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            arena.put("rivalAcquisition", "TURN_ROUTE_HALF_1")
            fixture.artifacts.write("arena.json", arena)
            assertFailsWith<IllegalArgumentException> {
                CancellationInput.read(fixture.directory.resolve("pilot.json"))
            }
        }
    }

    @Test
    fun streamsCannotBeSharedBetweenIndependentOrigins() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val source = PurchaseJson.read(input.source) as ObjectNode
            (source["origins"][1] as ObjectNode).set<ObjectNode>(
                "future",
                source["origins"][0]["future"],
            )
            fixture.artifacts.write("pilot.json", source)
            assertFailsWith<IllegalArgumentException> { CancellationInput.read(input.source) }
        }
    }

    @Test
    fun futureStreamCannotReuseAnOriginWorldSeed() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val source = PurchaseJson.read(input.source) as ObjectNode
            (source["origins"][0] as ObjectNode).set<ObjectNode>(
                "future",
                PurchaseJson.mapper.valueToTree(listOf(0, 10001)),
            )
            fixture.artifacts.write("pilot.json", source)
            assertFailsWith<IllegalArgumentException> { CancellationInput.read(input.source) }
        }
    }

    @Test
    fun sourceMutationCannotBecomeASnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            Files.writeString(input.source, Files.readString(input.source) + "\n")
            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.directory.resolve("snapshot")))
            }
        }
    }

    @Test
    fun modelMutationCannotBecomeASnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val policy = fixture.directory.resolve("policy.json")
            Files.writeString(policy, Files.readString(policy) + "\n")
            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.directory.resolve("snapshot")))
            }
        }
    }

    @Test
    fun prepareWritesAllProspectiveRequestsWithoutRunningGames() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val output = fixture.directory.resolve("prepared")
            CancellationMain.main(
                arrayOf(
                    "--mode",
                    "prepare",
                    "--source",
                    input.source.toString(),
                    "--output",
                    output.toString(),
                )
            )
            val metadata = PurchaseJson.read(output.resolve("prepared.json"))
            assertThat(metadata["requests"].size()).isEqualTo(256)
            assertThat(metadata["maximumTerminalGames"].intValue()).isEqualTo(1792)
            assertThat(Files.exists(output.resolve("started.json"))).isFalse()
        }
    }

    @Test
    fun prepareCannotOverwriteAnExistingAttempt() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val output = fixture.directory.resolve("prepared")
            Files.createDirectory(output)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                CancellationMain.main(
                    arrayOf(
                        "--mode",
                        "prepare",
                        "--source",
                        input.source.toString(),
                        "--output",
                        output.toString(),
                    )
                )
            }
        }
    }

    private fun observation(vararg commands: String) =
        BotObservation(
            playerId = "p1",
            decision = "cancel-adjustment",
            context = emptyMap(),
            availableCash = 10,
            availableTokens = 3,
            candidates = commands.map { BotCandidate(it, emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun input(fixture: ArenaPurchaseFixture): CancellationInput {
        fixture.artifacts.model(BotModels.default, "expansion.json")
        val arena = PurchaseJson.read(fixture.source) as ObjectNode
        arena.set<ObjectNode>(
            "tokenOrder",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "candidateSelector" to "FIXED_16_TO_15",
                    "incumbentSelector" to "FIXED_16_TO_15",
                    "opponentSelectors" to listOf("FIXED_16_TO_15"),
                )
            ),
        )
        arena.set<ObjectNode>(
            "expansionImitation",
            PurchaseJson.mapper.valueToTree(
                mapOf(
                    "candidateModel" to "expansion.json",
                    "incumbentModel" to "expansion.json",
                    "opponentModels" to listOf("expansion.json"),
                )
            ),
        )
        fixture.artifacts.write("arena.json", arena)
        fixture.artifacts.write(
            "pilot.json",
            mapOf(
                "kind" to "cancellation-data-1",
                "arena" to "arena.json",
                "origins" to
                    (0 until 256).map {
                        CancellationSeeds(it, listOf(10000L + it * 2, 10001L + it * 2))
                    },
            ),
        )
        return CancellationInput.read(fixture.directory.resolve("pilot.json"))
    }
}
