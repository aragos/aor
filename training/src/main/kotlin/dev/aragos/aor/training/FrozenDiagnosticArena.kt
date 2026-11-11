package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Shared role contract for homogeneous diagnostics using frozen E, fixed T and Purchase. */
internal object FrozenDiagnosticArena {
    fun validate(arena: ArenaSpecification, playerCount: Int) {
        require(
            arena.configurations.size == 1 &&
                arena.configurations.single().playerCount == playerCount &&
                arena.candidate == arena.incumbent &&
                arena.opponents == listOf(arena.candidate) &&
                arena.frozenPurchase != null &&
                arena.expansionImitation != null &&
                arena.tokenOrder != null
        ) {
            "Recorded diagnostics require a single homogeneous frozen ordinary/overlay environment."
        }
        val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
        val expansion = metadata.required("expansionImitation")
        val expansionHash = expansion.required("candidateModel").required("sha256")
        require(
            expansion.required("incumbentModel").required("sha256") == expansionHash &&
                expansion.required("opponentModels").size() == 1 &&
                expansion.required("opponentModels")[0]?.get("sha256") == expansionHash
        ) {
            "Every recorded diagnostic seat must use the identical expansion overlay."
        }
        val tokens = metadata.required("tokenOrder")
        require(
            tokens["candidateSelector"]?.asText() == "FIXED_16_TO_15" &&
                tokens["incumbentSelector"]?.asText() == "FIXED_16_TO_15" &&
                tokens["opponentSelectors"]?.map { it.asText() } == listOf("FIXED_16_TO_15")
        ) {
            "Every recorded diagnostic seat must use the frozen fixed token control."
        }
        arena.frozenPurchase.controllers(playerCount, 0, side = null)
    }
}
