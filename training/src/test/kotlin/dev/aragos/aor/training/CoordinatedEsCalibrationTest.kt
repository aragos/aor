package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedEsCalibrationTest {
    @Test
    fun completeTypedHeadReceiptsAdmitTheirExactScale() {
        CoordinatedEsFixture().use { fixture ->
            val source = save(fixture, record(fixture))
            val calibration = CoordinatedEsCalibration.read(source, fixture.original)
            assertThat(calibration.selectors[CoordinatedEsHead.TOKEN]?.scale).isEqualTo(1.0)
        }
    }

    @Test
    fun globalPassedFlagCannotSubstituteForMissingHeadEvidence() {
        CoordinatedEsFixture().use { fixture ->
            val record = record(fixture)
            (record["heads"] as ObjectNode).putObject("TOKEN")
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsCalibration.read(save(fixture, record), fixture.original)
            }
        }
    }

    @Test
    fun topLevelScaleCannotContradictItsSealedHead() {
        CoordinatedEsFixture().use { fixture ->
            val record = record(fixture)
            (record["scales"] as ObjectNode).put("EXPANSION", 2.0)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsCalibration.read(save(fixture, record), fixture.original)
            }
        }
    }

    @Test
    fun prescribedMeanMustPassActualScoreMovementBounds() {
        CoordinatedEsFixture().use { fixture ->
            val record = record(fixture)
            (record["heads"]["TOKEN"]["movement"]["prescribedMean"] as ObjectNode).apply {
                put("changedWeight", 0)
                put("rate", 0.0)
            }
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsCalibration.read(save(fixture, record), fixture.original)
            }
        }
    }

    @Test
    fun differentPublicPanelCannotBeAdmittedAsTheSameCalibration() {
        CoordinatedEsFixture().use { fixture ->
            val record = record(fixture)
            (record["heads"]["TOKEN"]["subjects"] as ObjectNode).put(
                "publicPanelSha256",
                "f".repeat(64),
            )
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsCalibration.read(save(fixture, record), fixture.original)
            }
        }
    }

    private fun save(fixture: CoordinatedEsFixture, value: JsonNode): TokenPairedSubject {
        fixture.files.write("calibration-unit.json", value)
        return CoordinatedEsWire.source(fixture.files.directory.resolve("calibration-unit.json"))
    }

    private fun record(fixture: CoordinatedEsFixture): ObjectNode {
        val hashes =
            mapOf(
                "protocolSha256" to "a".repeat(64),
                "projectionSha256" to "b".repeat(64),
                "panelSha256" to "c".repeat(64),
                "probePopulationSha256" to "d".repeat(64),
                "directionsSha256" to "e".repeat(64),
                "prescribedMeanSha256" to "1".repeat(64),
            )
        val heads =
            listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN).associate { head ->
                val subjects =
                    hashes.filterKeys {
                        it in
                            setOf(
                                "protocolSha256",
                                "projectionSha256",
                                "directionsSha256",
                                "prescribedMeanSha256",
                            )
                    } +
                        mapOf(
                            "originalModelSha256" to fixture.original.subject(head).sha256,
                            "publicPanelSha256" to "c".repeat(64),
                        )
                head.name to
                    mapOf(
                        "kind" to "coordinated-es-signed-gap-calibration-1",
                        "complete" to true,
                        "head" to head.name,
                        "recipe" to CoordinatedEsCalibrationReceipt.recipe,
                        "panelSha256" to "2".repeat(64),
                        "subjects" to subjects,
                        "representedWorlds" to 128,
                        "missingWorlds" to 128,
                        "states" to 128,
                        "scale" to 1.0,
                        "terminalLabelsRead" to false,
                        "scaleAttempts" to 1,
                        "liveMovementIsGate" to false,
                        "distributions" to
                            listOf("mutants" to 8192, "prescribedMean" to 256).associate {
                                (role, total) ->
                                role to
                                    mapOf(
                                        "positiveWeight" to total / 2,
                                        "totalWeight" to total,
                                        "positiveMass" to .5,
                                        "quantile90" to 1.0 + 1.0 / 1048576,
                                    )
                            },
                        "movement" to
                            listOf("mutants" to 8192, "prescribedMean" to 256).associate {
                                (role, total) ->
                                role to
                                    mapOf(
                                        "changedWeight" to total / 8,
                                        "totalWeight" to total,
                                        "rate" to .125,
                                    )
                            },
                    )
            }
        return PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to "coordinated-es-calibration-1",
                "passed" to true,
                "complete" to true,
                "originalBundleSha256" to fixture.original.source.sha256,
                "scales" to mapOf("EXPANSION" to 1.0, "TOKEN" to 1.0),
                "heads" to heads,
                "semanticFitnessDecoded" to false,
                "calibrationAttempts" to 1,
            ) + hashes
        )
    }
}
