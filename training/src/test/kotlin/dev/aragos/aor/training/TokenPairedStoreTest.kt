package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented wire records only; these tests never start or finish a simulated game. */
class TokenPairedStoreTest {
    @Test
    fun preparationPublishesGlobalDictionaryAndExactSnapshotsWithoutGames() {
        TokenPairedFixture().use { fixture ->
            val output = fixture.files.directory.resolve("prepared")
            TokenPairedMain.main(
                arrayOf(
                    "--mode",
                    "token-paired-prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.toString(),
                )
            )
            val manifest = PurchaseJson.read(output.resolve("prepared.json"))
            assertThat(manifest["requests"].size()).isEqualTo(64)
            assertThat(manifest["range"].fieldNames().asSequence().toSet())
                .containsExactly("globalStart", "count")
            assertThat(manifest["origins"].size()).isEqualTo(640)
            assertThat(manifest["ecologySha256"].asText()).isEqualTo(fixture.input.ecologySha256)
            assertThat(Files.exists(output.resolve("started.json"))).isFalse()
            assertThat(
                    Files.list(output).use {
                        it.anyMatch { path -> path.fileName.toString().startsWith("origin-") }
                    }
                )
                .isFalse()
        }
    }

    @Test
    fun singletonProposalIsRetainedWithoutInventedTails() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val origin = origin(input, singleton = true)
            val store = TokenPairedStore(input, fixture.files.directory.resolve("singleton"))
            store.validate(origin, 0)
            assertThat(origin["publicRoot"]["proposedIndices"].map(JsonNode::intValue))
                .containsExactly(0)
            assertThat(origin["arms"].isEmpty).isTrue()
        }
    }

    @Test
    fun publicSidecarCannotContainOutcomeOrAuthorityTrees() {
        TokenPairedFixture().use { fixture ->
            val original = origin(fixture.input, singleton = true)
            original.put("privateAuthority", "FORBIDDEN_PRIVATE_TREE")
            original.set<JsonNode>(
                "arms",
                PurchaseJson.mapper.valueToTree(
                    listOf(mapOf("secretOutcome" to "FORBIDDEN_OUTCOME"))
                ),
            )
            val public = TokenPairedStore.publicSidecar(original, "a".repeat(64))
            assertThat(public.fieldNames().asSequence().toSet())
                .containsExactly(
                    "kind",
                    "originSha256",
                    "signature",
                    "scopeSha256",
                    "scienceSha256",
                    "iteration",
                    "request",
                    "streams",
                    "status",
                    "missingReason",
                    "observedWindows",
                    "publicRoot",
                    "anchors",
                )
            assertThat(public.toString()).doesNotContain("FORBIDDEN")
            assertThat(public.has("backbone")).isFalse()
        }
    }

    @Test
    fun missingOriginRetainsItsNormalBackboneWithoutCounterfactualLabels() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            TokenPairedStore(input, fixture.files.directory.resolve("missing"))
                .validate(origin(input, singleton = false), 0)
        }
    }

    @Test
    fun canonicalMutationIsRejectedBeforePublication() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val original = origin(input, singleton = false)
            (original["backbone"] as ObjectNode).put("canonicalSha256", "b".repeat(64))
            assertFailsWith<IllegalArgumentException> {
                TokenPairedStore(input, fixture.files.directory.resolve("tampered"))
                    .validate(original, 0)
            }
        }
    }

    @Test
    fun globalRequestCannotBeResetAtShardBoundary() {
        TokenPairedFixture().use { fixture ->
            fixture.source(576)
            val input = fixture.input
            assertFailsWith<IllegalArgumentException> {
                TokenPairedStore(input, fixture.files.directory.resolve("wrong-index"))
                    .validate(origin(input, singleton = false), 0)
            }
        }
    }

    @Test
    fun reproductionChecksPublicProbabilitiesAndBackboneNotJustTerminalScores() {
        TokenPairedFixture().use { fixture ->
            val expected = origin(fixture.input, singleton = true)
            val actual = expected.deepCopy()
            (actual["publicRoot"] as ObjectNode).put("number", 3)
            assertFailsWith<IllegalArgumentException> {
                TokenPairedMain.verifyReproduction(expected, actual)
            }
        }
    }

    @Test
    fun ordinarySingletonWindowsConsumeTheProspectiveOrdinal() {
        val tracker = TokenPairedWindow(TokenPairedRequest(12))
        assertThat(tracker.observe(1, "buy-tokens")).isFalse()
        assertThat(tracker.observe(1, "choose-turn-position")).isFalse()
        assertThat(tracker.observe(1, "buy-tokens")).isTrue()
        assertThat(tracker.observe(1, "buy-tokens")).isFalse()
    }

    private fun origin(input: TokenPairedInput, singleton: Boolean): ObjectNode {
        val canonical =
            mapOf(
                "header" to mapOf("constructed" to true),
                "entries" to emptyList<String>(),
                "canonicalSha256" to CardAdvantageExecutor.hash(emptyList<String>()),
                "playerDecisions" to 0,
                "terminal" to true,
                "replayVerified" to true,
            )
        val observation =
            mapOf(
                "encoderVersion" to "aor-token-observation-2",
                "stateFeatures" to emptyMap<String, Double>(),
                "actions" to
                    listOf(
                        mapOf("command" to "buy-tokens 0", "features" to emptyMap<String, Double>())
                    ),
                "baselineIndex" to 0,
            )
        val root =
            mapOf(
                "number" to 0,
                "batchStartNumber" to 0,
                "observation" to observation,
                "referenceIndex" to 0,
                "sampledCommandIndex" to 0,
                "proposedIndices" to listOf(0),
                "inclusionProbability" to 0.0,
                "currentProbabilities" to listOf(1.0),
                "originalProbabilities" to listOf(1.0),
            )
        val value =
            mapOf(
                "kind" to "token-paired-origin-1",
                "signature" to input.signature,
                "scopeSha256" to input.scopeSha256,
                "scienceSha256" to input.scienceSha256,
                "iteration" to input.iteration,
                "request" to TokenPairedRequest(0),
                "streams" to input.origins[0],
                "status" to if (singleton) "SINGLETON" else "MISSING",
                "missingReason" to if (singleton) null else "TARGET_WINDOW_NOT_REACHED",
                "observedWindows" to 0,
                "publicRoot" to if (singleton) root else null,
                "anchors" to emptyList<Any>(),
                "prefix" to canonical,
                "backbone" to canonical,
                "backboneTerminal" to mapOf("continuationSeed" to 0L, "decisions" to 0),
                "arms" to emptyList<Any>(),
            )
        return PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))
            as ObjectNode
    }
}
