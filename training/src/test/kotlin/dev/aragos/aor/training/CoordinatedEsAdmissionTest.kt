package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented compact receipt chains only; no final model is opened or materialized. */
class CoordinatedEsAdmissionTest {
    @Test
    fun completeSixteenMeanChainResolvesEveryOriginalSubject(): Unit =
        Fixture().use { fixture ->
            val pins = fixture.validate()
            assertThat(pins).hasSize(33)
            assertThat(pins[fixture.final.path.toString()]).isEqualTo(fixture.final.sha256)
        }

    @Test
    fun skippedGenerationCannotQualifyEarlierMean(): Unit =
        Fixture().use { fixture ->
            val node = PurchaseJson.read(fixture.audit.path) as ObjectNode
            node.put("completedGenerations", 15)
            fixture.write("audit.json", node)
            assertFailsWith<IllegalArgumentException> { fixture.validate() }
        }

    @Test
    fun resealedWrongOutputCenterDoesNotMatchActualUpdate(): Unit =
        Fixture().use { fixture ->
            val center = PurchaseJson.read(fixture.directory.resolve("center-7.json")) as ObjectNode
            center.putArray("coordinates").also { array -> repeat(256) { array.add(.75) } }
            val changed = fixture.write("center-7.json", center)
            val audit = PurchaseJson.read(fixture.audit.path) as ObjectNode
            val row = audit["generations"][6] as ObjectNode
            row.put("outputCenterSha256", changed.sha256)
            row.set<ObjectNode>(
                "outputCenter",
                PurchaseJson.mapper.valueToTree(CoordinatedEsWire.subject(changed)),
            )
            fixture.write("audit.json", audit)
            assertFailsWith<IllegalArgumentException> { fixture.validate() }
        }

    @Test
    fun partialPopulationCountCannotCompleteGeneration(): Unit =
        Fixture().use { fixture ->
            val node = PurchaseJson.read(fixture.audit.path) as ObjectNode
            (node["generations"][3] as ObjectNode).put("mutants", 8191)
            fixture.write("audit.json", node)
            assertFailsWith<IllegalArgumentException> { fixture.validate() }
        }

    private class Fixture : AutoCloseable {
        val directory: Path = Files.createTempDirectory("coordinated-es-admission-unit")
        val scope =
            mapOf(
                "scopeSha256" to "a".repeat(64),
                "originalBundleSha256" to "b".repeat(64),
                "projectionSha256" to "c".repeat(64),
            )
        val final: TokenPairedSubject
        val audit: TokenPairedSubject

        init {
            fun center(generation: Int) =
                mapOf(
                    "kind" to "coordinated-es-center-1",
                    "scope" to scope,
                    "generation" to generation,
                    "coordinates" to List(256) { generation * .1 },
                )
            var previous = CpePairedDigest.json(center(0))
            val rows =
                (1..16).map { generation ->
                    val current = write("center-$generation.json", center(generation))
                    val update =
                        write(
                            "update-$generation.json",
                            mapOf(
                                "kind" to "coordinated-es-update-1",
                                "complete" to true,
                                "protocolSha256" to "d".repeat(64),
                                "previousCenterSha256" to previous,
                                "populationSha256" to "e".repeat(64),
                                "center" to center(generation),
                            ),
                        )
                    mapOf(
                            "generation" to generation,
                            "inputCenterSha256" to previous,
                            "outputCenterSha256" to current.sha256,
                            "outputCenter" to CoordinatedEsWire.subject(current),
                            "update" to CoordinatedEsWire.subject(update),
                            "populationSha256" to "e".repeat(64),
                            "controls" to 256,
                            "mutants" to 8192,
                            "reproductions" to 2,
                        )
                        .also { previous = current.sha256 }
                }
            final = CoordinatedEsWire.source(directory.resolve("center-16.json"))
            audit =
                write(
                    "audit.json",
                    mapOf(
                        "kind" to "coordinated-es-generation-audit-1",
                        "complete" to true,
                        "passed" to true,
                        "protocolSha256" to "d".repeat(64),
                        "scopeSha256" to scope["scopeSha256"],
                        "originalBundleSha256" to scope["originalBundleSha256"],
                        "projectionSha256" to scope["projectionSha256"],
                        "ecologySha256" to "f".repeat(64),
                        "calibrationSha256" to "1".repeat(64),
                        "completedGenerations" to 16,
                        "finalCenterSha256" to final.sha256,
                        "generations" to rows,
                    ),
                )
        }

        fun write(name: String, value: Any): TokenPairedSubject {
            TrainingArtifacts(directory).write(name, value)
            return CoordinatedEsWire.source(directory.resolve(name))
        }

        fun validate() =
            CoordinatedEsAdmission.validateGenerations(
                CoordinatedEsWire.source(audit.path),
                "d".repeat(64),
                scope,
                "f".repeat(64),
                "1".repeat(64),
                final,
            )

        override fun close() {
            directory.toFile().deleteRecursively()
        }
    }
}
