package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.PurchaseAction
import dev.aragos.aor.ai.PurchaseProjection
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Synthetic source/parser/storage checks never invoke the teacher collector. */
class PurchaseSequenceInputTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("purchase-sequence-unit-")
        val artifacts = TrainingArtifacts(directory)
        val source = directory.resolve("teacher.json")
        val output = directory.resolve("unused-output")
        val origins =
            List(32) { index ->
                mapOf(
                    "gameSeed" to index,
                    "seat" to index % 4,
                    "opponentIndices" to List(4) { 0 },
                    "continuationSeeds" to listOf(1000L + index * 2, 1001L + index * 2),
                )
            }
        val document: Map<String, Any> =
            mapOf(
                "schemaVersion" to 1,
                "protocol" to PurchaseSequenceProtocol.VERSION,
                "arena" to "arena.json",
                "origins" to origins,
            )

        init {
            artifacts.write(
                "arena.json",
                mapOf(
                    "schemaVersion" to 1,
                    "purpose" to "SCREENING",
                    "configurations" to
                        listOf(TrainingMatrix.stratified.first { it.playerCount == 4 }.id),
                    "candidate" to null,
                    "incumbent" to null,
                    "opponents" to listOf(null),
                    "pairsPerConfiguration" to 32,
                    "seed" to 0,
                ),
            )
            artifacts.write("teacher.json", document)
        }

        override fun close() {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun inputRetainsAllExplicitWorldsAndSharedStreams() {
        Fixture().use { fixture ->
            val input = PurchaseSequenceInput.read(fixture.source)
            assertThat(input.study.origins).hasSize(32)
            assertThat(input.study.origins.last().continuationSeeds)
                .containsExactly(1062L, 1063L)
                .inOrder()
            assertThat(input.manifest()["maximumSimulatedTerminals"]).isEqualTo(1056)
        }
    }

    @Test
    fun inputRejectsExtraScientificFields() {
        Fixture().use { fixture ->
            fixture.artifacts.write(
                "teacher.json",
                fixture.document + ("refillMissingRoots" to true),
            )
            assertFailsWith<IllegalArgumentException> { PurchaseSequenceInput.read(fixture.source) }
        }
    }

    @Test
    fun inputRejectsAStreamSharedAcrossIndependentOrigins() {
        Fixture().use { fixture ->
            val changed =
                fixture.origins.mapIndexed { index, origin ->
                    if (index == 1) {
                        origin + ("continuationSeeds" to listOf(1000L, 1003L))
                    } else {
                        origin
                    }
                }
            fixture.artifacts.write("teacher.json", fixture.document + ("origins" to changed))
            assertFailsWith<IllegalArgumentException> { PurchaseSequenceInput.read(fixture.source) }
        }
    }

    @Test
    fun inputRejectsFractionalContinuationSeeds() {
        Fixture().use { fixture ->
            val changed =
                fixture.origins.mapIndexed { index, origin ->
                    if (index == 0) {
                        origin + ("continuationSeeds" to listOf(1000.5, 1001.0))
                    } else {
                        origin
                    }
                }
            fixture.artifacts.write("teacher.json", fixture.document + ("origins" to changed))
            assertFailsWith<IllegalArgumentException> { PurchaseSequenceInput.read(fixture.source) }
        }
    }

    @Test
    fun inputRejectsSourceMutationAfterParsing() {
        Fixture().use { fixture ->
            val input = PurchaseSequenceInput.read(fixture.source)
            Files.writeString(fixture.source, Files.readString(fixture.source) + "\n")
            assertFailsWith<IllegalArgumentException> { input.verifySources() }
        }
    }

    @Test
    fun snapshotsRetainExactTeacherAndArenaBytes() {
        Fixture().use { fixture ->
            val input = PurchaseSequenceInput.read(fixture.source)
            input.snapshot(fixture.directory.resolve("snapshots"))
            assertThat(
                    PurchaseJson.hash(fixture.directory.resolve("snapshots/teacher-source.json"))
                )
                .isEqualTo(input.sourceSha256)
            assertThat(PurchaseJson.hash(fixture.directory.resolve("snapshots/arena-source.json")))
                .isEqualTo(input.arenaSha256)
        }
    }

    @Test
    fun describeParsesWithoutCreatingOutputOrExecutingOrigins() {
        Fixture().use { fixture ->
            val original = System.out
            val bytes = ByteArrayOutputStream()
            try {
                System.setOut(PrintStream(bytes))
                PurchaseSequenceMain.run(
                    listOf(
                        "--mode",
                        "purchase-sequence",
                        "--source",
                        fixture.source.toString(),
                        "--output",
                        fixture.output.toString(),
                        "--workers",
                        "1",
                        "--describe",
                        "true",
                    )
                )
            } finally {
                System.setOut(original)
            }
            val node = PurchaseJson.mapper.readTree(bytes.toByteArray())
            assertThat(node.required("origins").size()).isEqualTo(32)
            assertThat(Files.exists(fixture.output)).isFalse()
        }
    }

    @Test
    fun storeRefusesAnExistingDirectoryInsteadOfResuming() {
        Fixture().use { fixture ->
            Files.createDirectory(fixture.output)
            assertFailsWith<IllegalArgumentException> {
                PurchaseSequenceStore(PurchaseSequenceInput.read(fixture.source), fixture.output)
            }
        }
    }

    @Test
    fun incompleteStoreCannotPublishFinishedReceipt() {
        Fixture().use { fixture ->
            val store =
                PurchaseSequenceStore(PurchaseSequenceInput.read(fixture.source), fixture.output)
            assertFailsWith<java.nio.file.NoSuchFileException> { store.finish(Long.MAX_VALUE) }
            assertThat(Files.exists(fixture.output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun negativeIntRangeLongStreamsSurviveSaveAndFinishedReadback() {
        Fixture().use { fixture ->
            val negative =
                fixture.origins.map {
                    it +
                        ("continuationSeeds" to
                            (it["continuationSeeds"] as List<*>).map { seed -> -(seed as Long) })
                }
            fixture.artifacts.write("teacher.json", fixture.document + ("origins" to negative))
            val input = PurchaseSequenceInput.read(fixture.source)
            val store = PurchaseSequenceStore(input, fixture.output)
            input.study.origins.indices.forEach { store.save(syntheticResult(store, input, it)) }
            store.finish(Long.MAX_VALUE)
            val finished = PurchaseJson.read(fixture.output.resolve("finished.json"))
            assertThat(finished.required("origins").intValue()).isEqualTo(32)
            assertThat(finished.required("simulatedTails").intValue()).isEqualTo(128)
        }
    }

    @Test
    fun finderMetadataIsCountedButExcludedFromScientificInventory() {
        Fixture().use { fixture ->
            val input = PurchaseSequenceInput.read(fixture.source)
            val store = PurchaseSequenceStore(input, fixture.output)
            input.study.origins.indices.forEach { store.save(syntheticResult(store, input, it)) }
            Files.writeString(fixture.output.resolve(".DS_Store"), "UNIT incidental metadata")
            store.finish(Long.MAX_VALUE)
            val finished = PurchaseJson.read(fixture.output.resolve("finished.json"))
            assertThat(finished.required("incidentalFinderFilesExcluded").intValue()).isEqualTo(1)
            assertThat(finished.required("artifactSha256").has(".DS_Store")).isFalse()
        }
    }

    /** UNIT-only mock terminal documents exercise storage, not game or policy correctness. */
    private fun syntheticResult(
        store: PurchaseSequenceStore,
        input: PurchaseSequenceInput,
        index: Int,
    ): PurchaseSequenceResult {
        val origin = input.study.origins[index]
        val owner = store.originArtifacts(index)
        val canonical = emptyList<String>()
        val hash = CardAdvantageExecutor.hash(canonical)
        owner.write("backbone.json", mapOf("entries" to canonical, "unitSynthetic" to true))
        val backbone =
            PurchaseSequenceEvidenceRef(
                "backbone.json",
                PurchaseJson.hash(owner.directory.resolve("backbone.json")),
            )
        val commands = listOf("pass")
        val tails =
            PurchaseSequenceArm.entries.flatMap { arm ->
                origin.continuationSeeds.mapIndexed { replica, stream ->
                    val name = "tail-${arm.name}-$replica.json"
                    owner.write(
                        name,
                        mapOf(
                            "backbone" to backbone,
                            "arm" to arm,
                            "commands" to commands,
                            "continuationSeed" to stream,
                            "entries" to canonical,
                            "unitSynthetic" to true,
                        ),
                    )
                    PurchaseSequenceTail(
                        arm,
                        commands,
                        stream,
                        PurchaseSequenceStop.PREFIX_COMPLETE,
                        emptyList(),
                        TokenValueOutcome(
                            0.0,
                            0.0,
                            0.0,
                            List(4) { PilotReplayScore("p${it + 1}", 0, 0, 0, 0, false) },
                            0,
                            stream,
                        ),
                        hash,
                        true,
                        PurchaseSequenceEvidenceRef(
                            name,
                            PurchaseJson.hash(owner.directory.resolve(name)),
                        ),
                    )
                }
            }
        val observation =
            BotObservation(
                playerId = "p1",
                decision = "purchase-advances",
                context = emptyMap(),
                availableCash = 0,
                availableTokens = 0,
                candidates = listOf(BotCandidate("pass", emptyMap())),
                rulesVersion = RULES_VERSION,
                dataVersion = DATA_VERSION,
            )
        val projection =
            PurchaseProjection(
                stateFeatures = emptyMap(),
                actions = listOf(PurchaseAction("pass", emptyMap())),
            )
        val plans =
            PurchaseSequenceProposals(
                listOf(
                    PurchaseSequencePlan(
                        commands,
                        PlanBoundary.INCOMPLETE,
                        "purchase-stabilization",
                    )
                ),
                0,
                emptyList(),
                0,
                emptyList(),
                0,
                PurchaseCompletionCoverage(0, 0),
            )
        val root = PurchaseSequenceRoot(0, observation, projection, plans, tails)
        return PurchaseSequenceResult(
            index = index,
            origin = origin,
            environmentSha256 = CardAdvantageExecutor.hash(listOf(input.study.arena.identity())),
            backboneCanonicalSha256 = hash,
            backboneDecisions = 0,
            backboneEvidence = backbone,
            exclusions = emptyMap(),
            missingRoot = false,
            root = root,
        )
    }

    @Test
    fun optionParserRejectsHiddenResumeOrNonfiniteBudget() {
        Fixture().use { fixture ->
            val args =
                listOf(
                    "--mode",
                    "purchase-sequence",
                    "--source",
                    fixture.source.toString(),
                    "--describe",
                    "true",
                    "--workers",
                    "1",
                )
            assertFailsWith<IllegalArgumentException> {
                PurchaseSequenceOptions.parse(args + listOf("--resume", "true"))
            }
            assertFailsWith<IllegalArgumentException> {
                PurchaseSequenceOptions.parse(args + listOf("--hours", "NaN"))
            }
        }
    }
}
