package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Legacy TOKEN screen report contract, unchanged by complete C/P/E admission. */
internal object TokenPairedScreen {
    fun validateWorld(world: JsonNode, index: Int, science: String, seed: Int) =
        ThreeRoleScreen.validateWorld(
            world,
            index,
            science,
            seed,
            TokenPairedScreenInput.VERSION,
            TokenPairedScreenRole.entries.map { it.name },
        )

    fun semanticHash(value: JsonNode): String = ThreeRoleScreen.semanticHash(value)

    fun report(worlds: List<JsonNode>): Map<String, Any> =
        ThreeRoleScreen.report(worlds, "token-paired", "RAW_T", "RawT")
}
