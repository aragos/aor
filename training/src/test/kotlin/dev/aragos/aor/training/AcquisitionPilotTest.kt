package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AcquisitionPilotTest {
    @Test
    fun requestsRotateSeatEpochAndBandWithoutRandomSelection() {
        val requests = (0 until 36).map(::AcquisitionRequest)

        assertThat(requests.map { Triple(it.seat, it.epoch, it.band) }.distinct()).hasSize(36)
        assertThat(requests[35].seat).isEqualTo(3)
        assertThat(requests[35].epoch).isEqualTo(3)
        assertThat(requests[35].band).isEqualTo(AcquisitionBand.LATE)
    }

    @Test
    fun thirdsUseExactIntegerBoundariesIncludingSmallPools() {
        assertThat((0..9).map { AcquisitionBand.from(9, it) })
            .containsExactly(
                AcquisitionBand.LATE,
                AcquisitionBand.LATE,
                AcquisitionBand.LATE,
                AcquisitionBand.LATE,
                AcquisitionBand.MIDDLE,
                AcquisitionBand.MIDDLE,
                AcquisitionBand.MIDDLE,
                AcquisitionBand.EARLY,
                AcquisitionBand.EARLY,
                AcquisitionBand.EARLY,
            )
            .inOrder()
        assertThat(AcquisitionBand.from(1, 1)).isEqualTo(AcquisitionBand.EARLY)
        assertThat(AcquisitionBand.from(0, 0)).isEqualTo(AcquisitionBand.LATE)
    }

    @Test
    fun cashDiscardIsNotAnObservedDecline() {
        val request = AcquisitionRequest(0)

        assertThat(
                request.eligible(
                    AcquisitionRoute.CASH,
                    1,
                    "buy-card",
                    listOf("buy-card", "discard hand-1", "pass"),
                    "discard hand-1",
                    null,
                    0,
                )
            )
            .isFalse()
    }

    @Test
    fun offeredCashPurchaseAndExplicitPassAreEligible() {
        assertThat(
                AcquisitionRequest(0)
                    .eligible(
                        AcquisitionRoute.CASH,
                        1,
                        "buy-card",
                        listOf("buy-card", "pass"),
                        "pass",
                        null,
                        0,
                    )
            )
            .isTrue()
    }

    @Test
    fun unavailableBuyDoesNotBecomeARoot() {
        assertThat(
                AcquisitionRequest(0)
                    .eligible(AcquisitionRoute.CASH, 1, "buy-card", listOf("pass"), "pass", null, 0)
            )
            .isFalse()
    }

    @Test
    fun expansionCaptureRequiresAssignedBand() {
        assertThat(
                AcquisitionRequest(12)
                    .eligible(
                        AcquisitionRoute.EXPANSION,
                        1,
                        "expand",
                        listOf("buy-expansion-card", "pass"),
                        "pass",
                        12,
                        10,
                    )
            )
            .isFalse()
    }

    @Test
    fun rootSelectionKeepsFirstDeclineAndItsOriginalPool() {
        ArenaPurchaseFixture().use { fixture ->
            val session = ExperimentSession.start(fixture.configuration, 51L)
            val actor = session.actors().first()
            val base = session.state.viewForPlayer(actor)
            val selection = AcquisitionRootSelection(AcquisitionRequest(12))
            val observation =
                session
                    .observation(actor)
                    .copy(
                        decision = "expand",
                        candidates =
                            listOf(
                                BotCandidate("buy-expansion-card", emptyMap()),
                                BotCandidate("pass", emptyMap()),
                            ),
                    )
            fun view(pool: Int) =
                base.copy(
                    stage = Stage.EXPANSION,
                    currentEpoch = 1,
                    turnNumber = 1,
                    players =
                        base.players.map { if (it.id == actor) it.copy(poolTokens = pool) else it },
                )
            selection.observe(10, 20, 20, view(12), observation, "pass")
            selection.observe(11, 21, 21, view(8), observation, "pass")
            selection.observe(12, 22, 22, view(7), observation, "pass")

            assertThat(selection.captured[AcquisitionRoute.EXPANSION]?.number).isEqualTo(11)
            assertThat(selection.captured[AcquisitionRoute.EXPANSION]?.initialExpansionPool)
                .isEqualTo(12)
        }
    }

    @Test
    fun noMatchingDecisionRemainsAnExplicitMissingRoute() {
        val selection = AcquisitionRootSelection(AcquisitionRequest(0))

        assertThat(selection.captured).isEmpty()
        assertThat(selection.coverage().keys)
            .containsExactly(AcquisitionRoute.CASH, AcquisitionRoute.EXPANSION)
    }

    @Test
    fun explicitHomogeneousEcologyKeepsOrdinaryCardsAndCashDecision() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val controllers = input.controllers(2)

            assertThat(controllers.keys).containsExactly(0, 1, 2, 3)
            for (controller in controllers.values) {
                assertThat(controller.controls("expand")).isTrue()
                assertThat(controller.controls("buy-tokens")).isTrue()
                assertThat(controller.controls("purchase-advances")).isTrue()
                assertThat(controller.controls("play-cards")).isFalse()
                assertThat(controller.controls("buy-card")).isFalse()
            }
        }
    }

    @Test
    fun purchaseOverrideIsRejectedInsteadOfChangingContinuation() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            (arena["frozenPurchase"] as ObjectNode).put("candidatePolicy", "policy.json")
            fixture.artifacts.write("arena.json", arena)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    AcquisitionPilotInput.read(fixture.directory.resolve("pilot.json"))
                }

            assertThat(failure).hasMessageThat().contains("shared sampled actor")
        }
    }

    @Test
    fun ordinaryRivalTokenRoutingIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            (arena["tokenOrder"]["opponentSelectors"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.textNode("ORDINARY"),
            )
            fixture.artifacts.write("arena.json", arena)

            assertFailsWith<IllegalArgumentException> {
                AcquisitionPilotInput.read(fixture.directory.resolve("pilot.json"))
            }
        }
    }

    @Test
    fun repeatedFutureStreamIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val source = PurchaseJson.read(fixture.directory.resolve("pilot.json")) as ObjectNode
            (source["origins"][1]["cash"] as ArrayNode).set(0, source["origins"][0]["cash"][0])
            fixture.artifacts.write("pilot.json", source)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    AcquisitionPilotInput.read(fixture.directory.resolve("pilot.json"))
                }

            assertThat(failure).hasMessageThat().contains("must be distinct")
        }
    }

    @Test
    fun seedArrayCannotRefillOrShrinkBank() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val source = PurchaseJson.read(fixture.directory.resolve("pilot.json")) as ObjectNode
            (source["origins"] as ArrayNode).remove(255)
            fixture.artifacts.write("pilot.json", source)

            assertFailsWith<IllegalArgumentException> {
                AcquisitionPilotInput.read(fixture.directory.resolve("pilot.json"))
            }
        }
    }

    @Test
    fun preparationCreatesNoOriginGamesAndBindsAllSnapshots() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val output = fixture.directory.resolve("prepared")

            AcquisitionPilotMain.main(
                arrayOf(
                    "--mode",
                    "prepare",
                    "--source",
                    input.source.toString(),
                    "--output",
                    output.toString(),
                )
            )

            assertThat(PurchaseJson.read(output.resolve("prepared.json"))["signature"].asText())
                .isEqualTo(input.signature)
            assertThat(Files.exists(output.resolve("origin-0.json"))).isFalse()
            assertThat(PurchaseJson.hash(output.resolve("frozen-purchase-policy.json")))
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("policy.json")))
        }
    }

    @Test
    fun sourceTamperCannotReplacePreviouslyLoadedSnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            fixture.artifacts.write("pilot.json", mapOf("tampered" to true))

            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.directory.resolve("snapshot")))
            }
        }
    }

    @Test
    fun omittedRivalProfileKeepsOriginalSignatureFormulaAndMetadata() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)

            assertThat(input.profileMetadata()).isEmpty()
            assertThat(input.rivalAcquisition).isNull()
            assertThat(input.signature)
                .isEqualTo(
                    trainingSignature(
                        "acquisition-data-pilot-1:${input.sourceSha256}:" +
                            "${input.arenaSha256}:${input.arena.identity()}"
                    )
                )
        }
    }

    @Test
    fun explicitRivalProfileBindsItsNameAndOriginSeed() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val path = fixture.directory.resolve("pilot.json")
            val source = PurchaseJson.read(path) as ObjectNode
            source.put("rivalAcquisition", "TURN_ROUTE_HALF_1")
            fixture.artifacts.write("pilot.json", source)
            val profiled = AcquisitionPilotInput.read(path)

            assertThat(profiled.rivalAcquisition)
                .isEqualTo(AcquisitionRivalProfile.TURN_ROUTE_HALF_1)
            assertThat(profiled.profileMetadata().keys).containsExactly("rivalAcquisition")
            assertThat(profiled.controllers(2, 2)[2]?.controls("buy-card")).isFalse()
            assertThat(profiled.controllers(2, 2)[1]?.controls("buy-card")).isTrue()
        }
    }

    @Test
    fun profiledControllerRequiresOriginalWorldSeed() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val path = fixture.directory.resolve("pilot.json")
            val source = PurchaseJson.read(path) as ObjectNode
            source.put("rivalAcquisition", "TURN_ROUTE_HALF_1")
            fixture.artifacts.write("pilot.json", source)

            assertFailsWith<IllegalArgumentException> {
                AcquisitionPilotInput.read(path).controllers(2)
            }
        }
    }

    @Test
    fun unknownRivalProfileCannotSilentlyChangeEcology() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val path = fixture.directory.resolve("pilot.json")
            val source = PurchaseJson.read(path) as ObjectNode
            source.put("rivalAcquisition", "ALWAYS_BUY")
            fixture.artifacts.write("pilot.json", source)

            assertFailsWith<IllegalArgumentException> { AcquisitionPilotInput.read(path) }
        }
    }

    @Test
    fun profileTamperCannotReplaceLoadedSourceSnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val source = PurchaseJson.read(input.source) as ObjectNode
            source.put("rivalAcquisition", "TURN_ROUTE_HALF_1")
            fixture.artifacts.write("pilot.json", source)

            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.directory.resolve("snapshot")))
            }
        }
    }

    private fun input(fixture: ArenaPurchaseFixture): AcquisitionPilotInput {
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
                "kind" to "acquisition-data-pilot-1",
                "arena" to "arena.json",
                "origins" to
                    (0 until 256).map { index ->
                        AcquisitionOriginSeeds(
                            index,
                            listOf(10000L + index * 4, 10001L + index * 4),
                            listOf(10002L + index * 4, 10003L + index * 4),
                        )
                    },
            ),
        )
        return AcquisitionPilotInput.read(fixture.directory.resolve("pilot.json"))
    }
}
