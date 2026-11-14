package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Complete-bank paired99 inference, preserving the existing independent-world interval owner. */
internal object CoordinatedQualificationReport {
    const val VERSION = "coordinated-qualification-screen-report-1"

    fun report(worlds: List<JsonNode>): Map<String, Any> {
        require(worlds.map { it["index"]?.intValue() } == (0..4095).toList()) {
            "Qualification effects open only after all4096 original paired worlds complete."
        }
        val contrasts =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                val interval =
                    pilotInterval(
                        worlds.map { world ->
                            val seat = world["seat"].intValue()
                            fun value(role: String): Double =
                                if (metric == "chaos") {
                                    if (world["roles"][role]["scores"][seat]["total"].isNull) 1.0
                                    else 0.0
                                } else world["roles"][role][metric][seat].doubleValue()
                            value("CANDIDATE") - value("CONTROL")
                        }
                    )
                val error =
                    (requireNotNull(interval.upper95) { "A complete bank has an interval." } -
                        interval.mean) / 1.96
                val margin = CoordinatedQualificationProtocol.MULTIPLIER * error
                mapOf(
                    "count" to interval.count,
                    "mean" to interval.mean,
                    "standardError" to error,
                    "lower99" to interval.mean - margin,
                    "upper99" to interval.mean + margin,
                )
            }
        fun passes(metric: String): Boolean =
            requireNotNull(
                    requireNotNull(contrasts[metric]) { "Required qualification metric." }[
                        "lower99"]
                ) {
                    "Required paired lower99 bound."
                }
                .toDouble() > 0.0
        return mapOf(
            "kind" to VERSION,
            "worlds" to 4096,
            "confidence" to .99,
            "intervalMultiplier" to CoordinatedQualificationProtocol.MULTIPLIER,
            "candidateMinusControl" to contrasts,
            "gates" to
                mapOf(
                    "wins" to passes("wins"),
                    "rewards" to passes("rewards"),
                    "passed" to (passes("wins") && passes("rewards")),
                ),
            "qualification" to false,
            "activated" to false,
        )
    }

    fun requirePassed(report: JsonNode) {
        require(
            report["kind"]?.asText() == VERSION &&
                report["worlds"]?.asInt() == 4096 &&
                report["confidence"]?.doubleValue() == .99 &&
                report["intervalMultiplier"]?.doubleValue() ==
                    CoordinatedQualificationProtocol.MULTIPLIER &&
                report["qualification"]?.isBoolean == true &&
                !report["qualification"].booleanValue() &&
                report["activated"]?.isBoolean == true &&
                !report["activated"].booleanValue()
        ) {
            "A complete mixed99 report is required before independent homogeneous evaluation."
        }
        for (metric in listOf("wins", "rewards")) {
            val interval = report["candidateMinusControl"]?.let { it[metric] }
            require(
                interval != null &&
                    interval["count"]?.asInt() == 4096 &&
                    interval["mean"]?.isNumber == true &&
                    interval["standardError"]?.isNumber == true &&
                    interval["lower99"]?.isNumber == true &&
                    interval["upper99"]?.isNumber == true
            ) {
                "Each gate needs its original full paired99 interval."
            }
            val mean = interval["mean"].doubleValue()
            val error = interval["standardError"].doubleValue()
            val lower = mean - CoordinatedQualificationProtocol.MULTIPLIER * error
            require(
                mean.isFinite() &&
                    error.isFinite() &&
                    error >= 0.0 &&
                    lower == interval["lower99"].doubleValue() &&
                    lower > 0.0 &&
                    mean + CoordinatedQualificationProtocol.MULTIPLIER * error ==
                        interval["upper99"].doubleValue() &&
                    report["gates"]?.let { it[metric] }?.booleanValue() == true &&
                    report["gates"]?.let { it["passed"] }?.booleanValue() == true
            ) {
                "Both declared99 gates must be strictly positive and agree with their interval evidence."
            }
        }
    }
}
