package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Intrinsic eligibility/subject checks; the pure numerical owner recomputes full gap evidence. */
internal object CoordinatedEsCalibrationReceipt {
    val recipe: Map<String, Any> =
        mapOf(
            "kind" to "coordinated-es-signed-gap-calibration-1",
            "margin" to 1.0,
            "quantileNumerator" to 9,
            "quantileDenominator" to 10,
            "minimumWorlds" to 128,
            "minimumPositiveMass" to .1,
            "minimumQuantile" to 1e-8,
            "marginExcess" to 1.0 / 1048576,
            "minimumScale" to 1.0 / 32,
            "maximumScale" to 32.0,
            "minimumMovement" to .05,
            "maximumMovement" to .40,
        )

    fun validate(node: JsonNode, name: String, original: CoordinatedEsBundle) {
        val head = node["heads"].required(name)
        PurchaseJson.fields(
            head,
            setOf(
                "kind",
                "complete",
                "head",
                "recipe",
                "panelSha256",
                "subjects",
                "representedWorlds",
                "missingWorlds",
                "states",
                "distributions",
                "scale",
                "movement",
                "terminalLabelsRead",
                "scaleAttempts",
                "liveMovementIsGate",
            ),
        )
        require(
            head["kind"]?.asText() == "coordinated-es-signed-gap-calibration-1" &&
                head["complete"]?.booleanValue() == true &&
                head["head"]?.asText() == name &&
                head["recipe"] == PurchaseJson.mapper.valueToTree<JsonNode>(recipe) &&
                head["terminalLabelsRead"]?.isBoolean == true &&
                !head["terminalLabelsRead"].booleanValue() &&
                head["scaleAttempts"]?.asInt() == 1 &&
                head["liveMovementIsGate"]?.isBoolean == true &&
                !head["liveMovementIsGate"].booleanValue()
        ) {
            "Each executable head requires its complete fixed one-pass outcome-blind calibration receipt."
        }
        CoordinatedEsWire.sha(head["panelSha256"], "head signed-gap panel")
        val subjects = head.required("subjects")
        PurchaseJson.fields(
            subjects,
            setOf(
                "protocolSha256",
                "originalModelSha256",
                "projectionSha256",
                "directionsSha256",
                "prescribedMeanSha256",
                "publicPanelSha256",
            ),
        )
        require(
            listOf("protocolSha256", "projectionSha256", "directionsSha256", "prescribedMeanSha256")
                .all { subjects[it] == node[it] } &&
                subjects["publicPanelSha256"] == node["panelSha256"] &&
                subjects["originalModelSha256"]?.asText() ==
                    original.subject(CoordinatedEsHead.valueOf(name)).sha256
        ) {
            "Each head must bind the same protocol, projection, original model, public panel and prescribed probe."
        }
        val worlds = PurchaseJson.integer(head["representedWorlds"], "represented worlds")
        val states = PurchaseJson.integer(head["states"], "represented states")
        require(
            worlds in 128..256 &&
                PurchaseJson.integer(head["missingWorlds"], "missing worlds") == 256 - worlds &&
                states in worlds..2 * worlds
        ) {
            "The fixed public panel must pass its represented-world floor without refills."
        }
        PurchaseJson.fields(head.required("distributions"), setOf("mutants", "prescribedMean"))
        PurchaseJson.fields(head.required("movement"), setOf("mutants", "prescribedMean"))
        val quantiles =
            listOf("mutants", "prescribedMean").map { role ->
                val total = worlds * if (role == "mutants") 64 else 2
                val distribution = head["distributions"].required(role)
                PurchaseJson.fields(
                    distribution,
                    setOf("positiveWeight", "totalWeight", "positiveMass", "quantile90"),
                )
                val positive =
                    PurchaseJson.integer(
                        distribution["positiveWeight"],
                        "positive signed-gap weight",
                    )
                val quantile = finite(distribution["quantile90"], "signed quantile")
                require(
                    PurchaseJson.integer(distribution["totalWeight"], "distribution weight") ==
                        total &&
                        positive in 0..total &&
                        positive * 10 >= total &&
                        finite(distribution["positiveMass"], "positive mass") ==
                            positive.toDouble() / total &&
                        quantile > 1e-8
                ) {
                    "Signed equal-world gaps must pass the fixed positive-mass and percentile floors."
                }
                val movement = head["movement"].required(role)
                PurchaseJson.fields(movement, setOf("changedWeight", "totalWeight", "rate"))
                val changed =
                    PurchaseJson.integer(movement["changedWeight"], "changed score weight")
                val rate = finite(movement["rate"], "movement rate")
                require(
                    PurchaseJson.integer(movement["totalWeight"], "movement weight") == total &&
                        changed in 0..total &&
                        rate == changed.toDouble() / total &&
                        rate >= .05 &&
                        rate <= .40
                ) {
                    "Both actual mutant scores and the prescribed mean must pass the fixed movement bounds."
                }
                quantile
            }
        val scale = finite(head["scale"], "head scale")
        require(
            scale == finite(node["scales"][name], "executable scale") &&
                scale == (1.0 + 1.0 / 1048576) / quantiles.min()
        ) {
            "The executable head scale must equal its sole closed-form signed-gap calibration."
        }
    }

    private fun finite(node: JsonNode?, name: String): Double {
        require(node?.isNumber == true && node.doubleValue().isFinite()) {
            "$name must be a finite JSON number."
        }
        return node.doubleValue()
    }
}
