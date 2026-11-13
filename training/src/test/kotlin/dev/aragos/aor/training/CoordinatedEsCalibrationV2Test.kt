package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Actual pure-Python output from invented gap witnesses; no scientific model or panel input. */
class CoordinatedEsCalibrationV2Test {
    @Test
    fun numericalReceiptAdmitsItsExactInteriorScale() {
        CoordinatedEsFixture().use { fixture ->
            val calibration =
                CoordinatedEsCalibration.read(save(fixture, record(fixture)), fixture.original)
            assertThat(calibration.version).isEqualTo(CoordinatedEsCalibrationVersion.V2)
            assertThat(calibration.selectors[CoordinatedEsHead.TOKEN]?.scale).isEqualTo(sqrt(32.0))
        }
    }

    @Test
    fun mixedVersionHeadCannotBorrowVersionTwoOuterSeal() {
        reject { value ->
            (value["heads"]["TOKEN"] as ObjectNode).put(
                "kind",
                CoordinatedEsCalibrationVersion.V1.recipeKind,
            )
        }
    }

    @Test
    fun versionTwoCannotBeRelabeledAsLegacyOuterSeal() {
        reject { value -> value.put("kind", CoordinatedEsCalibrationVersion.V1.receiptKind) }
    }

    @Test
    fun recipeCannotWidenMovementBounds() {
        reject { value ->
            (value["heads"]["TOKEN"]["recipe"] as ObjectNode).put("maximumMovement", .41)
        }
    }

    @Test
    fun emptyIntervalCannotBeHiddenBehindPassingMovement() {
        reject { value ->
            (value["heads"]["TOKEN"]["commonInterval"] as ObjectNode).put("upperScale", 1.0)
        }
    }

    @Test
    fun passingMovementCannotSubstituteNeighboringScale() {
        reject { value ->
            (value["heads"]["TOKEN"] as ObjectNode).put("scale", 6.0)
            (value["scales"] as ObjectNode).put("TOKEN", 6.0)
        }
    }

    @Test
    fun changedWeightOutsideFixedBoundsCannotPass() {
        reject { value ->
            (value["heads"]["TOKEN"]["movement"]["prescribedMean"] as ObjectNode).apply {
                put("changedWeight", 104)
                put("rate", 104.0 / 256)
            }
        }
    }

    @Test
    fun versionTwoStillRequiresOriginalPositiveMassFloor() {
        reject { value ->
            (value["heads"]["TOKEN"]["distributions"]["prescribedMean"] as ObjectNode).apply {
                put("positiveWeight", 25)
                put("positiveMass", 25.0 / 256)
            }
        }
    }

    @Test
    fun versionTwoStillRequiresSignedQuantileFloor() {
        reject { value ->
            (value["heads"]["TOKEN"]["distributions"]["prescribedMean"] as ObjectNode).put(
                "quantile90",
                1e-8,
            )
        }
    }

    @Test
    fun intervalThresholdCannotRoundMinimumMovementDown() {
        reject { value ->
            (value["heads"]["TOKEN"]["intervals"]["prescribedMean"] as ObjectNode).put(
                "minimumChangedWeight",
                12,
            )
        }
    }

    @Test
    fun versionTwoPreservesOutcomeBlindOneAttemptBoundary() {
        reject { value -> (value["heads"]["TOKEN"] as ObjectNode).put("scaleAttempts", 2) }
    }

    private fun reject(change: (ObjectNode) -> Unit) {
        CoordinatedEsFixture().use { fixture ->
            val value = record(fixture).also(change)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsCalibration.read(save(fixture, value), fixture.original)
            }
        }
    }

    private fun save(fixture: CoordinatedEsFixture, value: JsonNode): TokenPairedSubject {
        fixture.files.write("calibration-v2-unit.json", value)
        return CoordinatedEsWire.source(fixture.files.directory.resolve("calibration-v2-unit.json"))
    }

    private fun record(fixture: CoordinatedEsFixture): ObjectNode {
        val stream =
            requireNotNull(
                javaClass.getResourceAsStream("/coordinated-es-calibration-v2-head.json")
            ) {
                "The retained constructed numerical receipt must be present."
            }
        val head = stream.use { PurchaseJson.mapper.readTree(it) as ObjectNode }
        val subjects = head["subjects"]
        val heads =
            listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN).associate { phase ->
                phase.name to
                    head.deepCopy().also { value ->
                        value.put("head", phase.name)
                        (value["subjects"] as ObjectNode).put(
                            "originalModelSha256",
                            fixture.original.subject(phase).sha256,
                        )
                    }
            }
        return PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to CoordinatedEsCalibrationVersion.V2.receiptKind,
                "passed" to true,
                "complete" to true,
                "originalBundleSha256" to fixture.original.source.sha256,
                "protocolSha256" to subjects["protocolSha256"],
                "projectionSha256" to subjects["projectionSha256"],
                "panelSha256" to subjects["publicPanelSha256"],
                "probePopulationSha256" to "1".repeat(64),
                "directionsSha256" to subjects["directionsSha256"],
                "prescribedMeanSha256" to subjects["prescribedMeanSha256"],
                "scales" to mapOf("EXPANSION" to head["scale"], "TOKEN" to head["scale"]),
                "heads" to heads,
                "semanticFitnessDecoded" to false,
                "calibrationAttempts" to 1,
            )
        )
    }
}
