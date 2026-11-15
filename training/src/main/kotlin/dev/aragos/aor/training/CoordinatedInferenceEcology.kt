package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** The nominated reference subjects define G3; qualification fixes all-seat support and rivals. */
internal object CoordinatedInferenceEcology {
    fun read(
        source: TokenPairedSubject,
        asset: CoordinatedInferenceAsset,
        bank: CoordinatedQualificationBank,
        name: String,
    ): ArenaSpecification {
        source.verify()
        val arena = ArenaSpecification.read(source.path)
        require(
            arena.purpose == ArenaPurpose.CONFIRMATION &&
                arena.configurations.singleOrNull()?.id ==
                    CoordinatedQualificationProtocol.CONFIGURATION &&
                arena.pairsPerConfiguration == 4096 &&
                arena.seed == bank.nativeOffset &&
                arena.opponents.size == (if (name == "mixed") 4 else 1) &&
                arena.expansionActor == null &&
                arena.tokenActor == null &&
                arena.cpeBundle == null &&
                arena.coordinatedEs == null
        ) {
            "Qualification requires the exact independent bank, fixed configuration and declared rival count."
        }
        fun reference(name: String) =
            requireNotNull(asset.references[name]) { "Missing $name reference." }.sha256
        require(TokenPairedEcology.ordinaryHash(source.path) == reference("cardM8")) {
            "Every ordinary role must retain the exact nominated M8 reference."
        }
        val metadata = ExpansionOnPolicyStore.wire(arena.overlayMetadata())
        PurchaseJson.fields(
            metadata,
            setOf("rulesVersion", "dataVersion", "jointPolicy", "expansionImitation", "tokenOrder"),
        )
        require(
            metadata["rulesVersion"] == asset.record["rulesVersion"] &&
                metadata["dataVersion"] == asset.record["dataVersion"]
        ) {
            "Evaluation and inference must retain the same rules and public catalog."
        }
        asset.original.validateOriginal(arena, asset.baseline)
        val expansion = metadata.required("expansionImitation")
        PurchaseJson.fields(
            expansion,
            setOf(
                "version",
                "decision",
                "featureNames",
                "candidateModel",
                "incumbentModel",
                "opponentModels",
            ),
        )
        require(
            expansion["version"]?.asText() == ExpansionImitationProtocol.VERSION &&
                expansion["decision"]?.asText() == "expand" &&
                expansion["featureNames"]?.map(JsonNode::asText) ==
                    ExpansionImitationProtocol.features &&
                listOf("candidateModel", "incumbentModel").all {
                    expansion[it]?.let { model -> model["sha256"] }?.asText() ==
                        reference("expansionE")
                }
        ) {
            "Evaluated E references must retain the exact complete eight-coefficient model."
        }
        val expectedExpansion =
            if (name == "mixed")
                listOf(null, reference("expansionE"), null, reference("expansionE"))
            else listOf(reference("expansionE"))
        require(
            expansion["opponentModels"]?.map { if (it.isNull) null else it["sha256"]?.asText() } ==
                expectedExpansion
        ) {
            "Only the fixed mixed or homogeneous expansion rival assignments are permitted."
        }
        val token = metadata.required("tokenOrder")
        PurchaseJson.fields(
            token,
            setOf("candidateSelector", "incumbentSelector", "opponentSelectors"),
        )
        val selectors =
            if (name == "mixed") listOf("ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15")
            else listOf("FIXED_16_TO_15")
        require(
            token["candidateSelector"]?.asText() == "FIXED_16_TO_15" &&
                token["incumbentSelector"]?.asText() == "FIXED_16_TO_15" &&
                token["opponentSelectors"]?.map(JsonNode::asText) == selectors
        ) {
            "Only the exact declared fixed-token rival composition is permitted."
        }
        return arena
    }
}
