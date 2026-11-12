package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RenaissancePhaseTest {
    @Test
    fun phaseAssignmentDoesNotConfoundControlledSeat() {
        val requests = (0 until 20).map(::RenaissancePhaseRequest)

        assertThat(requests.map { it.seat to it.targetPhase }.distinct()).hasSize(20)
        assertThat(requests.take(4).map { it.targetPhase }.distinct())
            .containsExactly(Stage.DRAW_CARD)
        assertThat(requests.takeLast(4).map { it.seat }).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun assignedWindowWithOnlyPassDoesNotBecomeARoot() {
        assertThat(RenaissancePhaseRequest(0).captures(Stage.DRAW_CARD, observation("pass")))
            .isFalse()
    }

    @Test
    fun branchingAssignedWindowBecomesARoot() {
        assertThat(
                RenaissancePhaseRequest(0).captures(Stage.DRAW_CARD, observation("pass", "swap p2"))
            )
            .isTrue()
    }

    @Test
    fun branchingOtherPhaseIsPassedEvenAfterAssignedPhaseInTurnOrder() {
        val request = RenaissancePhaseRequest(0)
        val observation = observation("pass", "swap p2")

        assertThat(request.captures(Stage.EXPANSION, observation)).isFalse()
        assertThat(request.prefixCommand(observation, "swap p2")).isEqualTo("pass")
    }

    @Test
    fun unrelatedOwnDecisionRetainsExactIncumbentCommand() {
        val observation = observation("pass", "buy-card").copy(decision = "buy-card")

        assertThat(RenaissancePhaseRequest(0).prefixCommand(observation, "buy-card"))
            .isEqualTo("buy-card")
    }

    @Test
    fun prefixPassDoesNotMutateNormalTailPolicy() {
        val observation = observation("pass", "swap p2")
        val ordinary = BotPolicy.choose(observation, BotDifficulty.HARD, 41, BotModels.default)

        RenaissancePhaseRequest(0).prefixCommand(observation, ordinary)

        assertThat(BotPolicy.choose(observation, BotDifficulty.HARD, 41, BotModels.default))
            .isEqualTo(ordinary)
    }

    @Test
    fun missingPassCannotSilentlyInventAnIllegalPrefixAction() {
        assertFailsWith<IllegalArgumentException> {
            RenaissancePhaseRequest(0).prefixCommand(observation("swap p2"), "swap p2")
        }
    }

    @Test
    fun fixedOriginBoundaryRejectsRefill() {
        assertFailsWith<IllegalArgumentException> { RenaissancePhaseRequest(512) }
    }

    @Test
    fun twoPairedStreamsAreRequired() {
        assertFailsWith<IllegalArgumentException> { RenaissancePhaseSeeds(1, listOf(2)) }
    }

    @Test
    fun ordinaryRoutingHasNoPersistentRenaissanceController() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)

            assertThat(input.controllers(2).keys).containsExactly(0, 1, 2, 3)
            assertThat(input.controllers(2).values.any { it.controls("renaissance") }).isFalse()
            assertThat(input.controllers(2).values.all { it.controls("purchase-advances") })
                .isTrue()
        }
    }

    @Test
    fun explicitRivalProfileCannotChangeTheFixedEcology() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val source = PurchaseJson.read(input.source) as ObjectNode
            source.put("rivalAcquisition", "TURN_ROUTE_HALF_1")
            fixture.artifacts.write("phase.json", source)

            assertFailsWith<IllegalArgumentException> { RenaissancePhaseInput.read(input.source) }
        }
    }

    @Test
    fun duplicateFutureStreamCannotCoupleIndependentWorlds() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            val source = PurchaseJson.read(input.source) as ObjectNode
            (source["origins"][1] as ObjectNode).set<ObjectNode>(
                "future",
                source["origins"][0]["future"],
            )
            fixture.artifacts.write("phase.json", source)

            assertFailsWith<IllegalArgumentException> { RenaissancePhaseInput.read(input.source) }
        }
    }

    @Test
    fun tamperedLoadedSourceCannotBecomeASnapshot() {
        ArenaPurchaseFixture().use { fixture ->
            val input = input(fixture)
            fixture.artifacts.write("phase.json", mapOf("changed" to true))

            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.directory.resolve("snapshot")))
            }
        }
    }

    @Test
    fun candidateOnlyPurchaseActorIsRejected() {
        ArenaPurchaseFixture().use { fixture ->
            input(fixture)
            val arena = PurchaseJson.read(fixture.source) as ObjectNode
            (arena["frozenPurchase"] as ObjectNode).put("candidatePolicy", "policy.json")
            fixture.artifacts.write("arena.json", arena)

            assertFailsWith<IllegalArgumentException> {
                RenaissancePhaseInput.read(fixture.directory.resolve("phase.json"))
            }
        }
    }

    private fun observation(vararg commands: String): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "renaissance",
            context = emptyMap(),
            availableCash = 10,
            availableTokens = 3,
            candidates = commands.map { BotCandidate(it, emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun input(fixture: ArenaPurchaseFixture): RenaissancePhaseInput {
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
            "phase.json",
            mapOf(
                "kind" to "renaissance-phase-data-1",
                "arena" to "arena.json",
                "origins" to
                    (0 until 512).map {
                        RenaissancePhaseSeeds(it, listOf(10000L + it * 2, 10001L + it * 2))
                    },
            ),
        )
        return RenaissancePhaseInput.read(fixture.directory.resolve("phase.json"))
    }
}
