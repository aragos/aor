package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PilotDiagnosticInputTest {
    private val mapper = ObjectMapper()

    @Test
    fun defaultsKeepTheDiagnosticBoundedAndIndependentOfResourceSettings() = withDirectory { root ->
        val source = Files.createDirectory(root.resolve("pilot"))

        val options = PilotDiagnosticOptions.parse(arguments(source, root.resolve("diagnostic")))

        assertThat(options.source).isEqualTo(source.toRealPath())
        assertThat(options.output).isEqualTo(root.toRealPath().resolve("diagnostic"))
        assertThat(options.hours).isEqualTo(2.0)
        assertThat(options.seed).isEqualTo(20261107)
        assertThat(options.samples).isEqualTo(32)
        assertThat(options.workers).isEqualTo(maximumWorkers())
        assertThat(options.copy(workers = 1, hours = 1.0).identity()).isEqualTo(options.identity())
        assertThat(options.copy(seed = 7).identity()).isNotEqualTo(options.identity())
        assertThat(options.copy(samples = 64).identity()).isNotEqualTo(options.identity())
        assertThat(Files.exists(options.output)).isFalse()
    }

    @Test
    fun malformedAndOutOfRangeOptionsAreRejected() = withDirectory { root ->
        val source = Files.createDirectory(root.resolve("pilot"))
        val base = arguments(source, root.resolve("diagnostic"))
        val invalid =
            listOf(
                listOf("--samples"),
                listOf("--samples", "2", "--samples", "3"),
                listOf("--model", "replacement.json"),
                listOf("--samples", "1.5"),
                listOf("--samples", "0"),
                listOf("--samples", "257"),
                listOf("--seed", "-1"),
                listOf("--seed", "100000001"),
                listOf("--hours", "NaN"),
                listOf("--hours", "0"),
                listOf("--hours", "73"),
                listOf("--workers", "0"),
                listOf("--workers", (maximumWorkers() + 1).toString()),
            )

        for (extra in invalid) {
            assertFailsWith<IllegalArgumentException> { PilotDiagnosticOptions.parse(base + extra) }
        }
    }

    @Test
    fun sourceAndOutputAreRequiredWithoutCreatingMissingSources() = withDirectory { root ->
        val missing = root.resolve("missing")

        assertFailsWith<IllegalArgumentException> {
            PilotDiagnosticOptions.parse(listOf("--mode", "pilot-diagnose"))
        }
        assertFailsWith<IllegalArgumentException> {
            PilotDiagnosticOptions.parse(arguments(missing, root.resolve("diagnostic")))
        }

        assertThat(Files.exists(missing)).isFalse()
    }

    @Test
    fun overlappingDirectoriesAreRejectedIncludingSymbolicLinks() = withDirectory { root ->
        val source = Files.createDirectory(root.resolve("pilot"))
        val link = Files.createSymbolicLink(root.resolve("pilot-link"), source)

        for (output in
            listOf(source, source.resolve("diagnostic"), root, link.resolve("diagnostic"))) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PilotDiagnosticOptions.parse(arguments(source, output))
                }
            assertThat(failure).hasMessageThat().contains("must not overlap")
        }
    }

    @Test
    fun inputLoadsACompleteSnapshotWithoutChangingItsFiles() = withDirectory { root ->
        val source = fixture(root)
        val before = snapshot(source)

        val input = PilotDiagnosticInput(source)

        assertThat(input.seed).isEqualTo(17)
        assertThat(input.rows.map { it.required("index").intValue() })
            .containsExactly(0, 1)
            .inOrder()
        assertThat(input.validation.keys).containsExactly(0)
        assertThat(input.sourceHash).hasLength(64)
        assertThat(snapshot(source)).isEqualTo(before)
    }

    @Test
    fun incompleteSourceCannotBeDiagnosed() = withDirectory { root ->
        val source = fixture(root)
        update(source, "state.json") { it.put("phase", "validate") }

        val failure = assertFailsWith<IllegalArgumentException> { PilotDiagnosticInput(source) }

        assertThat(failure).hasMessageThat().contains("completed automata pilot")
    }

    @Test
    fun mismatchedFeatureOrderCannotReuseTheFrozenNetwork() = withDirectory { root ->
        val source = fixture(root)
        update(source, "student.json") { node ->
            node.set<JsonNode>(
                "featureNames",
                mapper.valueToTree(CommodityExperiment.featureNames.reversed()),
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> { PilotDiagnosticInput(source) }

        assertThat(failure).hasMessageThat().contains("feature names")
    }

    @Test
    fun missingHeldOutUnitIsRejectedEvenWhenCheckpointClaimsCompletion() = withDirectory { root ->
        val source = fixture(root)
        Files.delete(source.resolve("validation-0.json"))

        val failure = assertFailsWith<IllegalArgumentException> { PilotDiagnosticInput(source) }

        assertThat(failure).hasMessageThat().contains("every position and held-out validation unit")
    }

    @Test
    fun changedValidationChoiceCannotReplaceTheFrozenStudentsDecision() = withDirectory { root ->
        val source = fixture(root)
        update(source, "validation-0.json") { it.put("strategy", "HOLD_EXPAND") }

        val failure = assertFailsWith<IllegalArgumentException> { PilotDiagnosticInput(source) }

        assertThat(failure).hasMessageThat().contains("frozen student's selected strategy")
    }

    @Test
    fun sourceFingerprintIncludesExactValidationBytes() = withDirectory { root ->
        val source = fixture(root)
        val before = PilotDiagnosticInput(source).sourceHash
        val validation = source.resolve("validation-0.json")
        Files.writeString(validation, Files.readString(validation) + "\n")

        val after = PilotDiagnosticInput(source).sourceHash

        assertThat(after).isNotEqualTo(before)
    }

    private fun arguments(source: Path, output: Path): List<String> =
        listOf(
            "--mode",
            "pilot-diagnose",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
        )

    private fun maximumWorkers(): Int {
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        return ((cpus * 0.8).toInt() - 2).coerceAtLeast(1)
    }

    private fun fixture(root: Path): Path {
        val source = Files.createDirectory(root.resolve("pilot"))
        fun write(name: String, value: Any) {
            mapper.writeValue(source.resolve(name).toFile(), value)
        }
        val common =
            mapOf(
                "curriculumVersion" to CommodityExperiment.VERSION,
                "featureNames" to CommodityExperiment.featureNames,
                "strategies" to CommodityExperimentStrategy.entries.map { it.name },
            )
        val student =
            PilotStudent(
                inputWeights = List(24) { List(CommodityExperiment.featureNames.size) { 0.0 } },
                hiddenBias = List(24) { 0.0 },
                outputWeights = List(CommodityExperimentStrategy.entries.size) { List(24) { 0.0 } },
                outputBias = List(CommodityExperimentStrategy.entries.size) { 0.0 },
            )
        write(
            "run.json",
            common +
                mapOf(
                    "kind" to "automata-pilot",
                    "signature" to "fixture",
                    "rulesVersion" to RULES_VERSION,
                    "dataVersion" to DATA_VERSION,
                    "configuration" to CommodityExperiment.configuration.id,
                    "settings" to "17:1:2:2:1",
                ),
        )
        write(
            "state.json",
            mapOf("kind" to "automata-pilot", "signature" to "fixture", "phase" to "complete"),
        )
        write("teacher.json", common + mapOf("positions" to 2, "samplesPerStrategy" to 2))
        write(
            "student.json",
            common +
                mapOf(
                    "schemaVersion" to 2,
                    "minimumPredictedAdvantage" to PilotStudent.DEFAULT_MINIMUM_ADVANTAGE,
                    "network" to student,
                    "trainingPositions" to 1,
                    "validationPositions" to 1,
                ),
        )
        write(
            "baseline.json",
            mapOf(
                "schemaVersion" to 1,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "weights" to emptyMap<String, Double>(),
            ),
        )
        for (index in 0..1) {
            write(
                "position-$index.json",
                mapOf(
                    "index" to index,
                    "seed" to 17 + index * 8191,
                    "tag" to "opportunity",
                    "split" to if (index == 0) "validation" else "train",
                    "features" to List(CommodityExperiment.featureNames.size) { 0.0 },
                    "baselineRewards" to listOf(0.4, 0.6),
                    "strategies" to
                        CommodityExperimentStrategy.entries.map { strategy ->
                            mapOf(
                                "strategy" to strategy.name,
                                "rewards" to listOf(0.4, 0.6),
                                "reward" to mapOf("mean" to 0.5),
                            )
                        },
                ),
            )
        }
        write(
            "validation-0.json",
            mapOf(
                "scenarioSeed" to 17,
                "tag" to "opportunity",
                "baselineRewards" to listOf(0.4, 0.6),
                "studentRewards" to listOf(0.4, 0.6),
                "strategy" to "KEEP_BASELINE",
            ),
        )
        return source
    }

    private fun update(source: Path, name: String, change: (ObjectNode) -> Unit) {
        val path = source.resolve(name)
        val node = mapper.readTree(path.toFile()) as ObjectNode
        change(node)
        mapper.writeValue(path.toFile(), node)
    }

    private fun snapshot(source: Path): Map<String, String> =
        Files.list(source).use { paths ->
            paths.toList().associate { it.fileName.toString() to Files.readString(it) }
        }

    private fun withDirectory(action: (Path) -> Unit) {
        val directory = Files.createTempDirectory("aor-diagnostic-input-")
        try {
            action(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
