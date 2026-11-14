package dev.aragos.aor.training

/**
 * Typed admission supplies policies; the shared owner retains game, storage and replay mechanics.
 */
internal interface CoordinatedGenerationInput {
    val prefix: String
    val source: TokenPairedSubject
    val arenaSource: TokenPairedSubject
    val scopeSha256: String
    val originalBundleSha256: String
    val stage: CoordinatedEsStage
    val generation: Int
    val globalStart: Int
    val count: Int
    val bank: CoordinatedEsBank
    val indices: IntRange
    val arena: ArenaSpecification
    val roles: List<String>
    val specifications: Map<String, ArenaSpecification>
    val signature: String
    val scienceSha256: String
    val panelFiles: Boolean
    val additionalIdentity: Map<String, Any>
        get() = emptyMap()

    fun bundleSha256(role: String): String?

    fun canonical(role: String, index: Int): Boolean

    fun inputPins(): Map<String, String>

    fun metadata(): Map<String, Any?>
}
