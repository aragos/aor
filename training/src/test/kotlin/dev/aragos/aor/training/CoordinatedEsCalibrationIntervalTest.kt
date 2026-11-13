package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedEsCalibrationIntervalTest {
    @Test
    fun exactWeightedAtomsProduceOpenLowerAndClosedUpper() {
        val interval = CoordinatedEsCalibrationInterval.read(record(), distribution())
        assertThat(interval).isEqualTo(CoordinatedEsCalibrationInterval(.5, 2.0, false, true))
        assertThat(interval.midpoint()).isEqualTo(1.0)
    }

    @Test
    fun lowerAtomMustReachMinimumChangedWeight() {
        val value = record().put("lowerAtWeight", 19)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.read(value, distribution())
        }
    }

    @Test
    fun lowerAtomCannotAlreadyHaveMinimumWeightStrictlyAboveIt() {
        val value = record().put("lowerAboveWeight", 50)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.read(value, distribution())
        }
    }

    @Test
    fun upperAtomMustExceedMaximumChangedWeight() {
        val value = record().put("upperAtWeight", 0)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.read(value, distribution())
        }
    }

    @Test
    fun upperAtomAllowsExactlyMaximumWeightStrictlyAboveIt() {
        val interval = CoordinatedEsCalibrationInterval.read(record(), distribution())
        assertThat(interval.upperInclusive).isTrue()
    }

    @Test
    fun nullUpperGapRequiresTotalPositiveMassWithinMaximum() {
        val value =
            record()
                .putNull("upperGap")
                .put("upperAboveWeight", 500)
                .put("upperAtWeight", 0)
                .put("upperScale", 32.0)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.read(value, distribution())
        }
    }

    @Test
    fun distributionWithoutUpperCrossingUsesFixedScaleCap() {
        val value =
            record()
                .putNull("upperGap")
                .put("upperAboveWeight", 200)
                .put("upperAtWeight", 0)
                .put("upperScale", 32.0)
        val evidence = distribution().put("positiveWeight", 200).put("positiveMass", .2)
        val interval = CoordinatedEsCalibrationInterval.read(value, evidence)
        assertThat(interval.upperScale).isEqualTo(32.0)
    }

    @Test
    fun tinyPositiveUpperGapNeverRequiresOverflowingReciprocal() {
        val value = record().put("upperGap", Double.MIN_VALUE).put("upperScale", 32.0)
        val interval = CoordinatedEsCalibrationInterval.read(value, distribution())
        assertThat(interval.upperScale).isEqualTo(32.0)
    }

    @Test
    fun scaleFloorStrictlyAboveRawLowerIsInclusive() {
        val value =
            record().put("lowerGap", 64.0).put("lowerScale", 1.0 / 32).put("lowerInclusive", true)
        val interval = CoordinatedEsCalibrationInterval.read(value, distribution())
        assertThat(interval.lowerInclusive).isTrue()
    }

    @Test
    fun scaleFloorEqualToRawLowerRemainsOpen() {
        val value = record().put("lowerGap", 32.0).put("lowerScale", 1.0 / 32)
        val interval = CoordinatedEsCalibrationInterval.read(value, distribution())
        assertThat(interval.lowerInclusive).isFalse()
    }

    @Test
    fun forgedClosedLowerEndpointIsRejected() {
        val value = record().put("lowerInclusive", true)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.read(value, distribution())
        }
    }

    @Test
    fun commonIntersectionUsesBothDistributions() {
        val first = CoordinatedEsCalibrationInterval(.5, 2.0, false, true)
        val second = CoordinatedEsCalibrationInterval(1.0, 4.0, false, true)
        val expected = bounds(1.0, 2.0)
        val common = CoordinatedEsCalibrationInterval.common(expected, listOf(first, second))
        assertThat(common.midpoint()).isEqualTo(sqrt(2.0))
    }

    @Test
    fun disjointIntervalsAreRejected() {
        val first = CoordinatedEsCalibrationInterval(.5, 1.0, false, true)
        val second = CoordinatedEsCalibrationInterval(2.0, 4.0, false, true)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.common(bounds(2.0, 1.0), listOf(first, second))
        }
    }

    @Test
    fun singletonIntersectionIsRejectedEvenWhenClosed() {
        val first = CoordinatedEsCalibrationInterval(.5, 1.0, true, true)
        val second = CoordinatedEsCalibrationInterval(1.0, 4.0, true, true)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationInterval.common(bounds(1.0, 1.0), listOf(first, second))
        }
    }

    @Test
    fun midpointRoundedToEndpointCannotBeRepaired() {
        val interval = CoordinatedEsCalibrationInterval(1.0, Math.nextUp(1.0), false, true)
        assertFailsWith<IllegalArgumentException> { interval.midpoint() }
    }

    @Test
    fun eachExplicitReceiptVersionRequiresItsOwnProtocolRecipe() {
        CoordinatedEsCalibrationVersion.entries.forEach { version ->
            val protocol = node(mapOf("calibration" to mapOf("kind" to version.recipeKind)))
            version.validateProtocol(protocol)
        }
    }

    @Test
    fun versionTwoCannotUseVersionOneProtocol() {
        val protocol =
            node(
                mapOf(
                    "calibration" to mapOf("kind" to CoordinatedEsCalibrationVersion.V1.recipeKind)
                )
            )
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationVersion.V2.validateProtocol(protocol)
        }
    }

    @Test
    fun versionOneCannotUseVersionTwoProtocol() {
        val protocol =
            node(
                mapOf(
                    "calibration" to mapOf("kind" to CoordinatedEsCalibrationVersion.V2.recipeKind)
                )
            )
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationVersion.V1.validateProtocol(protocol)
        }
    }

    @Test
    fun unknownReceiptVersionCannotChooseLegacyDefault() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsCalibrationVersion.read(
                node(mapOf("kind" to "coordinated-es-calibration-3"))
            )
        }
    }

    private fun distribution(): ObjectNode =
        node(
            mapOf(
                "totalWeight" to 1000,
                "positiveWeight" to 500,
                "positiveMass" to .5,
                "quantile90" to 1.0,
            )
        )

    private fun record(): ObjectNode =
        node(
            mapOf(
                "minimumChangedWeight" to 50,
                "maximumChangedWeight" to 400,
                "lowerGap" to 2.0,
                "lowerAboveWeight" to 30,
                "lowerAtWeight" to 20,
                "upperGap" to .5,
                "upperAboveWeight" to 400,
                "upperAtWeight" to 100,
                "lowerScale" to .5,
                "upperScale" to 2.0,
                "lowerInclusive" to false,
                "upperInclusive" to true,
            )
        )

    private fun bounds(lower: Double, upper: Double): JsonNode =
        node(
            mapOf(
                "lowerScale" to lower,
                "upperScale" to upper,
                "lowerInclusive" to false,
                "upperInclusive" to true,
            )
        )

    private fun node(value: Any): ObjectNode = PurchaseJson.mapper.valueToTree(value)
}
