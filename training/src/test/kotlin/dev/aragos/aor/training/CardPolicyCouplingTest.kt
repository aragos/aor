package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardPolicyCouplingTest {
    @Test
    fun legacyFirstFourOutcomesExactlyEqualUnchangedDefaultExecutor() {
        val root = requireNotNull(collected.origin.root) { "The fixture reaches a root." }
        val capture = requireNotNull(collected.capture.root) { "F reaches the fixture root." }
        val executor =
            CardAdvantageExecutor(
                collected.input.backboneInput,
                collected.input.origins.single(),
                Long.MAX_VALUE,
            )
        val initial = ExperimentSession(capture.state, 0L, recordEntries = true)
        for (arm in root.arms.filter { it.schedule == "L" }) {
            for (replicate in 0..3) {
                val branch =
                    initial.fork(CardAdvantageExecutor.chanceSeed(collected.origin.seed, replicate))
                branch.apply(
                    mapOf(capture.actor to root.observation.actions[arm.actionIndex].command)
                )
                val expected = executor.finish(branch, capture.state, root.number, replicate)

                assertThat(collected.origin.outcomes[arm.outcomeIds[replicate]]).isEqualTo(expected)
            }
        }
    }

    @Test
    fun allSchedulesPreserveRootAcquisitionAndPairEightAuthorityStreams() {
        val origin = collected.origin
        val root = requireNotNull(origin.root) { "The fixture reaches a root." }

        assertThat(root.arms.map { it.id }).isEqualTo(CardPolicyCouplingArm.IDS)
        assertThat(origin.outcomes).hasSize(48)
        assertThat(origin.policyTraces.keys).containsExactlyElementsIn(origin.outcomes.keys)
        root.arms.forEach { arm ->
            arm.outcomeIds.forEachIndexed { replicate, id ->
                val outcome = requireNotNull(origin.outcomes[id]) { "Each declared tail exists." }
                val trace =
                    requireNotNull(origin.policyTraces[id]) { "Each declared trace exists." }
                assertThat(outcome.chanceSeed)
                    .isEqualTo(CardPolicySeeds.chanceSeed(origin.seed, replicate))
                assertThat(trace).hasSize(outcome.gameDecisions - root.number)
                assertThat(trace.first().seed).isEqualTo(root.botSeed)
                assertThat(trace.first().forced).isTrue()
                assertThat(trace.drop(1).any { it.forced }).isFalse()
            }
        }
    }

    @Test
    fun semanticAddressesMatchAcrossActionArmsWhereTheirBucketsOverlap() {
        fun key(row: CardPolicySeedTrace) =
            listOf(row.turn, row.stage, row.decision, row.seat, row.occurrence)
        var matched = 0
        for (replicate in 0..7) {
            val left =
                requireNotNull(collected.origin.policyTraces["arm-s-f-$replicate"]) {
                        "F trace exists."
                    }
                    .filterNot { it.forced }
                    .associateBy(::key)
            val right =
                requireNotNull(collected.origin.policyTraces["arm-s-a-$replicate"]) {
                        "A trace exists."
                    }
                    .filterNot { it.forced }
                    .associateBy(::key)
            for (address in left.keys.intersect(right.keys)) {
                assertThat(left[address]?.seed).isEqualTo(right[address]?.seed)
                matched++
            }
        }
        assertThat(matched).isGreaterThan(0)
    }

    @Test
    fun cachedTraceCannotChangeOnePolicySeed() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val row = altered.required("policyTraces").required("arm-s-f-0")[1] as ObjectNode
        row.put("seed", row.required("seed").intValue() xor 1)

        assertFailsWith<IllegalArgumentException> {
            CardPolicyCouplingArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedTraceCannotOmitAPlayerCommand() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val rows = altered.required("policyTraces").required("arm-g-f-0") as ArrayNode
        rows.remove(1)

        assertFailsWith<IllegalArgumentException> {
            CardPolicyCouplingArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedAuthorityStreamCannotChangeForOneSchedule() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val outcome = altered.required("outcomes").required("arm-g-f-7") as ObjectNode
        outcome.put("chanceSeed", outcome.required("chanceSeed").longValue() + 1)

        assertFailsWith<IllegalArgumentException> {
            CardPolicyCouplingArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun trivialRootsAliasOnlyMatchingScheduleFArms() {
        val original = requireNotNull(collected.origin.root) { "The fixture reaches a root." }
        val pass = original.observation.actions.single { it.command == "pass" }
        val observation = original.observation.copy(actions = listOf(pass), baselineIndex = 0)
        val arms =
            CardPolicyCouplingArm.IDS.map { id ->
                CardPolicyCouplingArm(
                    id,
                    CardPolicyCouplingArm.schedule(id).name,
                    0,
                    CardPolicyCouplingArm.outcomeIds(id, null),
                )
            }
        val root =
            original.copy(
                observation = observation,
                proposalIndices = listOf(0),
                referenceIndex = 0,
                acquisition = CardAdvantageAcquisition.select(listOf(0), 0, original.botSeed, true),
                alternativeIndex = null,
                arms = arms,
            )
        val retained = arms.flatMap { it.outcomeIds }.toSet()
        val document =
            PurchaseJson.mapper.valueToTree<JsonNode>(
                collected.origin.copy(
                    root = root,
                    outcomes = collected.origin.outcomes.filterKeys { it in retained },
                    policyTraces = collected.origin.policyTraces.filterKeys { it in retained },
                )
            )

        CardPolicyCouplingArtifacts.validate(document, collected.input, 0)
        assertThat(retained).hasSize(24)
        assertThat(arms[0].outcomeIds).isEqualTo(arms[1].outcomeIds)
        assertThat(arms[2].outcomeIds).isEqualTo(arms[3].outcomeIds)
        assertThat(arms[4].outcomeIds).isEqualTo(arms[5].outcomeIds)
    }

    @Test
    fun sourceRejectsCandidateRivals() {
        val input = collected.input

        assertFailsWith<IllegalArgumentException> {
            input.copy(
                origins =
                    listOf(
                        input.origins
                            .single()
                            .copy(opponentUsesCandidate = listOf(false, true, false, false))
                    )
            )
        }
    }

    @Test
    fun couplingSourceCannotBeReadAsAnOldCollection() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)

            assertFailsWith<IllegalArgumentException> {
                CardAdvantageCollectionInput.read(input.source)
            }
        }
    }

    @Test
    fun manifestBindsAllThreeSchedulesAndUnchangedFControllers() {
        val manifest = collected.input.manifest()

        assertThat(manifest["kind"]).isEqualTo("card-policy-coupling")
        assertThat(manifest["continuation"]).isNull()
        assertThat(manifest["tailPolicy"]).isEqualTo("F")
        assertThat(manifest["schedules"]).isEqualTo(listOf("L", "G", "S"))
        assertThat(manifest["maxTailsPerOrigin"]).isEqualTo(48)
        assertThat(manifest["collection"]).isEqualTo(CardPolicyCouplingInput.plan())
    }

    @Test
    fun completedCouplingBatchResumePreservesBytesAndModificationTime() {
        CardAdvantageFixture().use { fixture ->
            val input = source(fixture)
            val origin = collected.origin.copy(signature = input.signature())
            val output = fixture.directory.resolve("out")
            Files.createDirectories(output)
            val path = output.resolve("origin-0.json.gz")
            CardTrajectoryFiles.save(path, PurchaseJson.mapper.valueToTree<JsonNode>(origin))
            val before = Files.readAllBytes(path)
            val modified = Files.getLastModifiedTime(path)

            CardAdvantageMain.run(
                listOf(
                    "--mode",
                    "card-policy-coupling",
                    "--source",
                    input.source.toString(),
                    "--output",
                    output.toString(),
                    "--indices",
                    "0",
                    "--hours",
                    ".05",
                )
            )

            assertThat(Files.readAllBytes(path)).isEqualTo(before)
            assertThat(Files.getLastModifiedTime(path)).isEqualTo(modified)
        }
    }

    private data class Collected(
        val input: CardPolicyCouplingInput,
        val origin: CardPolicyCouplingOrigin,
        val capture: CardAdvantageCapture,
        val document: JsonNode,
    )

    companion object {
        private val collected by lazy {
            CardAdvantageFixture().use { fixture ->
                val input = source(fixture)
                val origin = CardPolicyCouplingCollector(input, Long.MAX_VALUE).collect(0)
                val document = PurchaseJson.mapper.valueToTree<JsonNode>(origin)
                CardPolicyCouplingArtifacts.validate(document, input, 0)
                val executor =
                    CardAdvantageExecutor(
                        input.backboneInput,
                        input.origins.single(),
                        Long.MAX_VALUE,
                    )
                val capture =
                    CardAdvantageRootCapture(input.backboneInput, Long.MAX_VALUE)
                        .capture(0, executor)
                Collected(input, origin, capture, document)
            }
        }

        private fun source(fixture: CardAdvantageFixture): CardPolicyCouplingInput {
            fixture.artifacts.write(
                "source.json",
                (fixture.source(listOf(fixture.request)) - "continuation") +
                    mapOf(
                        "kind" to CardPolicyCouplingInput.KIND,
                        "collection" to CardPolicyCouplingInput.plan(),
                    ),
            )
            return CardPolicyCouplingInput.read(fixture.source)
        }
    }
}
