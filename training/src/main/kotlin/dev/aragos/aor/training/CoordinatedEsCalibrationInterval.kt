package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import kotlin.math.sqrt

/** Compact weighted-atom evidence; the pure auditor reconstructs atoms from retained gaps. */
internal data class CoordinatedEsCalibrationInterval(
    val lowerScale: Double,
    val upperScale: Double,
    val lowerInclusive: Boolean,
    val upperInclusive: Boolean,
) {
    init {
        require(
            lowerScale.isFinite() &&
                upperScale.isFinite() &&
                lowerScale >= MINIMUM_SCALE &&
                upperScale <= MAXIMUM_SCALE &&
                lowerScale < upperScale
        ) {
            "A clipped calibration interval must have positive finite width within [1/32,32]."
        }
    }

    fun validateRecord(node: JsonNode) {
        require(
            finite(node["lowerScale"], "lower scale") == lowerScale &&
                finite(node["upperScale"], "upper scale") == upperScale &&
                node["lowerInclusive"]?.isBoolean == true &&
                node["lowerInclusive"].booleanValue() == lowerInclusive &&
                node["upperInclusive"]?.isBoolean == true &&
                node["upperInclusive"].booleanValue() == upperInclusive
        ) {
            "Declared calibration bounds and endpoint inclusivity must match their weighted atoms."
        }
    }

    /** No endpoint adjustment or neighboring-scale retry is allowed. */
    fun midpoint(): Double =
        sqrt(lowerScale * upperScale).also { scale ->
            require(scale.isFinite() && scale > lowerScale && scale < upperScale) {
                "The sole geometric midpoint must be strictly inside the common Float64 interval."
            }
        }

    companion object {
        private const val MINIMUM_SCALE = 1.0 / 32
        private const val MAXIMUM_SCALE = 32.0
        private val BOUNDS = setOf("lowerScale", "upperScale", "lowerInclusive", "upperInclusive")

        fun read(node: JsonNode, distribution: JsonNode): CoordinatedEsCalibrationInterval {
            PurchaseJson.fields(
                node,
                BOUNDS +
                    setOf(
                        "minimumChangedWeight",
                        "maximumChangedWeight",
                        "lowerGap",
                        "lowerAboveWeight",
                        "lowerAtWeight",
                        "upperGap",
                        "upperAboveWeight",
                        "upperAtWeight",
                    ),
            )
            val total = PurchaseJson.integer(distribution["totalWeight"], "total weight")
            val positive = PurchaseJson.integer(distribution["positiveWeight"], "positive weight")
            val minimum = (total + 19) / 20
            val maximum = 2 * total / 5
            require(
                total > 0 &&
                    positive in 0..total &&
                    PurchaseJson.integer(node["minimumChangedWeight"], "minimum changed weight") ==
                        minimum &&
                    PurchaseJson.integer(node["maximumChangedWeight"], "maximum changed weight") ==
                        maximum
            ) {
                "The exact integer movement range must be ceil(total/20)..floor(2*total/5)."
            }
            val lowerGap = finite(node["lowerGap"], "lower signed-gap atom")
            val lowerAbove = PurchaseJson.integer(node["lowerAboveWeight"], "lower above weight")
            val lowerAt = PurchaseJson.integer(node["lowerAtWeight"], "lower atom weight")
            require(
                lowerGap > 0.0 &&
                    lowerGap >= finite(distribution["quantile90"], "signed quantile") &&
                    lowerAbove in 0 until minimum &&
                    lowerAt > 0 &&
                    lowerAbove + lowerAt in minimum..positive
            ) {
                "The lower positive atom must straddle the exact minimum changed mass."
            }
            val upperAbove = PurchaseJson.integer(node["upperAboveWeight"], "upper above weight")
            val upperAt = PurchaseJson.integer(node["upperAtWeight"], "upper atom weight")
            val upperNode = node.required("upperGap")
            val upperScale =
                if (upperNode.isNull) {
                    require(positive <= maximum && upperAbove == positive && upperAt == 0) {
                        "Only a distribution within the maximum positive mass has no finite upper gap."
                    }
                    MAXIMUM_SCALE
                } else {
                    val upperGap = finite(upperNode, "upper signed-gap atom")
                    require(
                        upperGap > 0.0 &&
                            positive > maximum &&
                            upperGap <= finite(distribution["quantile90"], "signed quantile") &&
                            upperGap <= lowerGap &&
                            upperAbove in 0..maximum &&
                            upperAt > 0 &&
                            upperAbove + upperAt in (maximum + 1)..positive
                    ) {
                        "The upper positive atom must straddle the exact maximum changed mass."
                    }
                    // Test the cap before reciprocal evaluation, including subnormal positive
                    // atoms.
                    if (upperGap <= MINIMUM_SCALE) MAXIMUM_SCALE else 1.0 / upperGap
                }
            val rawLower = 1.0 / lowerGap
            return CoordinatedEsCalibrationInterval(
                    lowerScale = maxOf(MINIMUM_SCALE, rawLower),
                    upperScale = upperScale,
                    lowerInclusive = rawLower < MINIMUM_SCALE,
                    upperInclusive = true,
                )
                .also { it.validateRecord(node) }
        }

        fun common(
            node: JsonNode,
            intervals: List<CoordinatedEsCalibrationInterval>,
        ): CoordinatedEsCalibrationInterval {
            require(intervals.size == 2) {
                "Mutants and the prescribed mean require two intervals."
            }
            PurchaseJson.fields(node, BOUNDS)
            val lower = intervals.maxOf(CoordinatedEsCalibrationInterval::lowerScale)
            val upper = intervals.minOf(CoordinatedEsCalibrationInterval::upperScale)
            return CoordinatedEsCalibrationInterval(
                    lowerScale = lower,
                    upperScale = upper,
                    lowerInclusive =
                        intervals.filter { it.lowerScale == lower }.all { it.lowerInclusive },
                    upperInclusive =
                        intervals.filter { it.upperScale == upper }.all { it.upperInclusive },
                )
                .also { it.validateRecord(node) }
        }

        private fun finite(node: JsonNode?, name: String): Double {
            require(node?.isNumber == true && node.doubleValue().isFinite()) {
                "$name must be a finite JSON number."
            }
            return node.doubleValue()
        }
    }
}
