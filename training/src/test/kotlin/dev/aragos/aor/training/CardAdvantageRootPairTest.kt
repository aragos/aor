package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.CardProposals
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageRootPairTest {
    @Test
    fun sampledRootKeepsFullSafeObservationAndTwoPairedCompleteTails() {
        val origin = sampled.origin
        val root = origin.groups.single()
        val observation =
            requireNotNull(origin.observations[root.observationSha256]) {
                "The root requires its complete safe observation."
            }
        val acquisition = requireNotNull(root.acquisition) { "Schema 2 records its acquisition." }

        assertThat(origin.schemaVersion).isEqualTo(2)
        assertThat(root.kind).isEqualTo("root")
        assertThat(root.proposalIndices).isEqualTo(CardProposals.indices(observation, root.botSeed))
        assertThat(root.returns.map { it.actionIndex }).isEqualTo(root.observedActionIndices)
        assertThat(root.observedActionIndices)
            .containsExactly(
                root.referenceIndex,
                acquisition.sampledActionIndex,
            )
        assertThat(origin.outcomes).hasSize(2)
        assertThat(origin.outcomes.values.map { it.chanceSeed }.distinct())
            .containsExactly(CardAdvantageExecutor.chanceSeed(origin.seed, 0))
        assertThat(root.botSeed)
            .isEqualTo(CardAdvantageExecutor.policySeed(origin.seed, root.number, origin.seat))
    }

    @Test
    fun denseTwoStreamRootsReuseEachPairedStreamAcrossAllProposals() {
        val origin = dense.origin
        val root = origin.groups.single()

        assertThat(root.observedActionIndices).isEqualTo(root.proposalIndices)
        assertThat(root.returns).hasSize(root.proposalIndices.size)
        assertThat(origin.outcomes).hasSize(root.proposalIndices.size * 2)
        root.returns.forEach { returned ->
            val streams =
                returned.outcomeIds.map { id ->
                    requireNotNull(origin.outcomes[id]) { "Every dense return has an outcome." }
                        .chanceSeed
                }
            assertThat(streams)
                .containsExactly(
                    CardAdvantageExecutor.chanceSeed(origin.seed, 0),
                    CardAdvantageExecutor.chanceSeed(origin.seed, 1),
                )
                .inOrder()
        }
    }

    @Test
    fun sampledAndDenseReferenceTailsPreserveTheSameActualContinuation() {
        fun reference(origin: CardAdvantageOrigin): CardAdvantageOutcome {
            val root = origin.groups.single()
            val id =
                root.returns.single { it.actionIndex == root.referenceIndex }.outcomeIds.first()
            return requireNotNull(origin.outcomes[id]) { "The reference tail must exist." }
        }

        assertThat(reference(sampled.origin)).isEqualTo(reference(dense.origin))
    }

    @Test
    fun aTrivialRootIsCollectedWithAnObservedReferenceAndNoInventedAlternative() {
        val origin = sampled.origin
        val original = origin.groups.single()
        val observation =
            requireNotNull(origin.observations[original.observationSha256]) {
                "The fixture requires its safe observation."
            }
        val pass = observation.actions.single { it.command == "pass" }
        val trivial = observation.copy(actions = listOf(pass), baselineIndex = 0)
        val hash = CardAdvantageExecutor.hash(trivial)
        val outcome = origin.outcomes.values.first()
        val group =
            original.copy(
                observationSha256 = hash,
                proposalIndices = listOf(0),
                referenceIndex = 0,
                continuationIndex = 0,
                observedActionIndices = listOf(0),
                acquisition = CardAdvantageAcquisition.select(listOf(0), 0, original.botSeed, true),
                returns = listOf(CardAdvantageReturns(0, listOf("root-0-0"))),
            )
        val document =
            PurchaseJson.mapper.valueToTree<JsonNode>(
                origin.copy(
                    observations = mapOf(hash to trivial),
                    groups = listOf(group),
                    outcomes = mapOf("root-0-0" to outcome),
                )
            )

        CardAdvantageArtifacts.validate(document, sampled.input, 0)
        assertThat(document.required("status").asText()).isEqualTo("collected")
        assertThat(document.required("outcomes").size()).isEqualTo(1)
    }

    @Test
    fun schemaTwoRejectsAReplacedAcquisitionSeed() {
        val altered = sampled.document.deepCopy<ObjectNode>()
        val group = altered.required("groups").single() as ObjectNode
        val acquisition = group.required("acquisition") as ObjectNode
        acquisition.put("seed", acquisition.required("seed").intValue() + 1)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, sampled.input, 0)
        }
    }

    @Test
    fun sparseObservedIndicesCannotClaimAnUnobservedAlternative() {
        val altered = sampled.document.deepCopy<ObjectNode>()
        val group = altered.required("groups").single() as ObjectNode
        (group.required("observedActionIndices") as ArrayNode).add(1024)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, sampled.input, 0)
        }
    }

    @Test
    fun rootPairValidatorRejectsChildren() {
        val altered = sampled.document.deepCopy<ObjectNode>()
        (altered.required("groups").single() as ObjectNode).put("kind", "child")

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, sampled.input, 0)
        }
    }

    @Test
    fun rootPairValidatorRejectsUnpairedChanceStreams() {
        val altered = sampled.document.deepCopy<ObjectNode>()
        val outcome = altered.required("outcomes").first() as ObjectNode
        outcome.put("chanceSeed", outcome.required("chanceSeed").longValue() + 1)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, sampled.input, 0)
        }
    }

    @Test
    fun schemaOneDoesNotAcquireNewWireFieldsOrManifestSettings() {
        CardAdvantageFixture().use { fixture ->
            val manifest = fixture.input.manifest()
            val legacy =
                sampled.origin.groups
                    .single()
                    .copy(
                        observedActionIndices = null,
                        acquisition = null,
                    )
            val document = PurchaseJson.mapper.valueToTree<JsonNode>(legacy)

            assertThat(manifest["schemaVersion"]).isEqualTo(1)
            assertThat(manifest["replicates"]).isEqualTo(4)
            assertThat(manifest["maxTailsPerOrigin"]).isEqualTo(52)
            assertThat(manifest.containsKey("collection")).isFalse()
            assertThat(document.has("observedActionIndices")).isFalse()
            assertThat(document.has("acquisition")).isFalse()
        }
    }

    private data class Collected(
        val input: CardAdvantageCollectionInput,
        val origin: CardAdvantageOrigin,
        val document: JsonNode,
    )

    companion object {
        private val sampled by lazy {
            collect(CardRootPairCollection("root-pairs-1", listOf("train"), 1))
        }
        private val dense by lazy {
            collect(CardRootPairCollection("root-pairs-1", emptyList(), 2))
        }

        private fun collect(plan: CardRootPairCollection): Collected {
            CardAdvantageFixture(continuation = true, preferPass = true).use { fixture ->
                fixture.artifacts.write(
                    "source.json",
                    fixture.source(listOf(fixture.request), continuation = true) +
                        mapOf("schemaVersion" to 2, "collection" to plan),
                )
                val input = fixture.input
                val origin = CardAdvantageCollector(input, Long.MAX_VALUE).collect(0)
                val document = PurchaseJson.mapper.valueToTree<JsonNode>(origin)
                CardAdvantageArtifacts.validate(document, input, 0)
                return Collected(input, origin, document)
            }
        }
    }
}
