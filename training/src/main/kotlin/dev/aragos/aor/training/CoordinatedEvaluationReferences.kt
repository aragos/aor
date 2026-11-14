package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Exact83 provenance and fixed rival transformations shared by separately typed evaluations. */
internal object CoordinatedEvaluationReferences {
    fun bindInputs(
        record: JsonNode,
        host: QualificationHost,
        arena: TokenPairedSubject,
        admission: TokenPairedSubject,
        proof: TokenPairedSubject,
    ) {
        require(
            record["originArenaSha256"][host.name].asText() == arena.sha256 &&
                record["originAdmissionSha256"].asText() == admission.sha256 &&
                record["originProofSha256"][host.name].asText() == proof.sha256
        ) {
            "The host must retain its exact original83 arena and common final admission before model access."
        }
        val value = PurchaseJson.read(admission.path)
        require(
            value["finalBundle"]?.let { it["sha256"] }?.asText() ==
                record["candidateBundleSha256"].asText() &&
                value["originalBundle"]?.let { it["sha256"] }?.asText() ==
                    record["originalBundleSha256"].asText() &&
                value["calibration"]?.let { it["sha256"] }?.asText() ==
                    record["calibrationSha256"].asText()
        ) {
            "Only the prospectively nominated final bundle and calibration may be decoded."
        }
    }

    fun bind(record: JsonNode, admission: CoordinatedQualificationOrigin) {
        require(
            record["originAdmissionSha256"].asText() == admission.source.sha256 &&
                record["candidateBundleSha256"].asText() == admission.finalBundle.source.sha256 &&
                record["originalBundleSha256"].asText() == admission.scope.original.source.sha256 &&
                record["calibrationSha256"].asText() == admission.calibration.source.sha256
        ) {
            "Qualification must evaluate the exact admitted final mean and original E/T calibration."
        }
    }

    fun validateArena(
        record: JsonNode,
        arenaSource: TokenPairedSubject,
        arena: ArenaSpecification,
        admission: CoordinatedQualificationOrigin,
        mixed: Boolean,
        worlds: Int,
        nativeOffset: Int,
        purpose: ArenaPurpose,
    ) {
        require(
            arena.purpose == purpose &&
                arena.configurations.singleOrNull()?.id ==
                    CoordinatedQualificationProtocol.CONFIGURATION &&
                arena.pairsPerConfiguration == worlds &&
                arena.seed == nativeOffset &&
                arena.opponents.size == if (mixed) 4 else 1
        ) {
            "Qualification retains its complete global bank and fixed opponent-index curriculum."
        }
        val ordinary = TokenPairedEcology.ordinaryHash(arenaSource.path)
        require(ordinary == record["references"]["cardM8Sha256"].asText()) {
            "Every evaluated ordinary role must retain the original raw M8 bytes."
        }
        val expected =
            ExpansionOnPolicyStore.wire(
                TokenPairedEcology.portableBindings(admission.scope.policyBindings)
            ) as ObjectNode
        val expansion = expected["expansionImitation"] as ObjectNode
        val candidate = expansion.required("candidateModel")
        val models = expansion.putArray("opponentModels")
        if (mixed) {
            models.addNull()
            models.add(candidate)
            models.addNull()
            models.add(candidate)
        } else {
            models.add(candidate)
        }
        val tokens = (expected["tokenOrder"] as ObjectNode).putArray("opponentSelectors")
        val selectors =
            if (mixed) listOf("ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15")
            else listOf("FIXED_16_TO_15")
        selectors.forEach(tokens::add)
        val actual =
            ExpansionOnPolicyStore.wire(
                TokenPairedEcology.portableBindings(
                    ExpansionOnPolicyStore.wire(arena.overlayMetadata())
                )
            )
        require(
            actual == expected &&
                actual["jointPolicy"]["baseline"]["sha256"].asText() ==
                    record["references"]["purchaseF0Sha256"].asText() &&
                candidate["sha256"].asText() == record["references"]["expansionESha256"].asText()
        ) {
            "Only the declared four E/T rival combinations may differ from the admitted G3 ecology."
        }
    }
}
