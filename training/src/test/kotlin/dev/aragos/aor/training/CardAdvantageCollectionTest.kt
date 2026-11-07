package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.CardProposals
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageCollectionTest {
    @Test
    fun actualOriginEvaluatesEveryRootAndChildProposalWithExactContinuationReuse() {
        val origin = collected.origin
        val root = origin.groups.single { it.kind == "root" }
        val children = origin.groups.filter { it.kind == "child" }

        assertThat(origin.status).isEqualTo("collected")
        assertThat(children).isNotEmpty()
        assertThat(origin.groups.any { it.continuationIndex != it.referenceIndex }).isTrue()
        assertThat(origin.outcomes.size).isAtMost(52)
        assertThat(origin.groups.size).isAtMost(13)
        origin.groups.forEach { group ->
            val observation =
                requireNotNull(origin.observations[group.observationSha256]) {
                    "A complete fixture requires every safe observation."
                }
            assertThat(group.proposalIndices)
                .isEqualTo(CardProposals.indices(observation, group.botSeed))
            assertThat(group.returns.map { it.actionIndex }).isEqualTo(group.proposalIndices)
            assertThat(group.referenceIndex).isEqualTo(observation.baselineIndex)
            assertThat(group.botSeed)
                .isEqualTo(CardAdvantageExecutor.policySeed(origin.seed, group.number, origin.seat))
        }
        children.forEach { child ->
            val reused =
                child.returns
                    .single { it.actionIndex == child.continuationIndex }
                    .outcomeIds
                    .single()
            val rootIds =
                root.returns.single { it.actionIndex == child.parentActionIndex }.outcomeIds
            assertThat(rootIds[requireNotNull(child.replicate) { "A child has its stream index." }])
                .isEqualTo(reused)
        }
        root.returns.forEach { row ->
            assertThat(row.outcomeIds).hasSize(4)
            row.outcomeIds.forEachIndexed { replicate, id ->
                assertThat(
                        requireNotNull(origin.outcomes[id]) { "A target needs its outcome." }
                            .chanceSeed
                    )
                    .isEqualTo(CardAdvantageExecutor.chanceSeed(origin.seed, replicate))
            }
        }
    }

    @Test
    fun compressedOriginRoundTripsAndRejectsOverwriteWithoutChangingBytes() {
        val directory = Files.createTempDirectory("advantage-roundtrip-")
        try {
            val path = directory.resolve("origin-0.json.gz")
            CardTrajectoryFiles.save(path, collected.document)
            val bytes = Files.readAllBytes(path)
            val restored =
                requireNotNull(CardTrajectoryFiles.readIfPresent(path)) {
                    "A saved whole origin must load."
                }
            CardAdvantageArtifacts.validate(restored, collected.input, 0)
            CardTrajectoryFiles.save(path, restored)
            val altered = restored.deepCopy<ObjectNode>().put("seed", 72)

            assertFailsWith<IllegalArgumentException> { CardTrajectoryFiles.save(path, altered) }
            assertThat(Files.readAllBytes(path)).isEqualTo(bytes)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedOriginCannotMoveItsRootIntoAnotherEpoch() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val root = altered.required("groups").first() as ObjectNode
        root.put("epoch", 2)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageArtifacts.validate(altered, collected.input, 0)
            }
        assertThat(failure).hasMessageThat().contains("requested epoch")
    }

    @Test
    fun cachedOriginCannotDuplicateAChildToIncreaseItsTrainingWeight() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val groups = altered.required("groups") as ArrayNode
        val child = groups[1].deepCopy<ObjectNode>().put("id", "different-id-same-child")
        groups.set(2, child)

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageArtifacts.validate(altered, collected.input, 0)
            }
        assertThat(failure).hasMessageThat().contains("only one child")
    }

    @Test
    fun cachedOriginRejectsReplacedWorldIdentity() {
        val altered = collected.document.deepCopy<ObjectNode>().put("seed", 72)
        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedOriginRejectsAnUnpairedFutureStream() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val outcome = altered.required("outcomes").first() as ObjectNode
        outcome.put("chanceSeed", outcome.required("chanceSeed").longValue() + 10)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedOriginRejectsChangedProposalOrReference() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val root = altered.required("groups").first() as ObjectNode
        root.put("referenceIndex", -1)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedOriginRejectsChangedPublicTerminalReward() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val outcome = altered.required("outcomes").first() as ObjectNode
        outcome.put("reward", outcome.required("reward").doubleValue() + .123)

        assertFailsWith<IllegalArgumentException> {
            CardAdvantageArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun expiredCollectionCannotStartABackboneOrPublishPartialTargets() {
        CardAdvantageFixture().use { fixture ->
            assertFailsWith<CancellationException> {
                CardAdvantageCollector(fixture.input, System.nanoTime() - 1).collect(0)
            }
            assertThat(Files.exists(fixture.directory.resolve("origin-0.json.gz"))).isFalse()
        }
    }

    private data class Collected(
        val input: CardAdvantageCollectionInput,
        val origin: CardAdvantageOrigin,
        val document: JsonNode,
    )

    companion object {
        private val collected by lazy {
            CardAdvantageFixture(continuation = true, preferPass = true).use { fixture ->
                val input = fixture.input
                val origin = CardAdvantageCollector(input, Long.MAX_VALUE).collect(0)
                val document = PurchaseJson.mapper.valueToTree<JsonNode>(origin)
                CardAdvantageArtifacts.validate(document, input, 0)
                Collected(input, origin, document)
            }
        }
    }
}
