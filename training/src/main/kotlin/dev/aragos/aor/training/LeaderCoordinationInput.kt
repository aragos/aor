package dev.aragos.aor.training

import java.nio.file.Path

/** Explicit disjoint origin/tail streams; retained-root index follows ascending world index. */
internal data class LeaderCoordinationInput(
    val sourceSha256: String,
    val arena: ArenaSpecification,
    val originChance: List<Int>,
    val originPolicy: List<Int>,
    val tailChance: List<Int>,
    val tailPolicy: List<Int>,
) {
    init {
        require(originChance.size == 128 && originPolicy.size == 128) {
            "Coordination scans exactly 128 declared origin streams."
        }
        require(tailChance.size == 512 && tailPolicy.size == 512) {
            "Coordination reserves 32 roots times 16 paired future streams."
        }
        val streams = originChance + originPolicy + tailChance + tailPolicy
        require(streams.distinct().size == 1280) { "All four stream banks must be disjoint." }
        FrozenDiagnosticArena.validate(arena, playerCount = 4)
    }

    fun receipt(): Map<String, Any> =
        mapOf(
            "kind" to "leader-purchase-coordination-1",
            "sourceSha256" to sourceSha256,
            "scanWorlds" to 128,
            "maximumRoots" to 32,
            "minimumRoots" to 16,
            "replicates" to 16,
            "maximumTails" to 1024,
            "workers" to 3,
            "maxSeconds" to 3600,
            "selectionPerformed" to false,
            "rootOrder" to
                "first eligible baseline-selected leader per rotating seat/world; first32 world indices",
            "targetOrder" to
                "actual marginal saving descending; current quote ascending; printed letter ascending",
            "originChance" to originChance,
            "originPolicy" to originPolicy,
            "tailChance" to tailChance,
            "tailPolicy" to tailPolicy,
            "arenaIdentity" to arena.identity(),
            "overlays" to arena.overlayMetadata(),
        )

    companion object {
        fun read(path: Path): LeaderCoordinationInput {
            val node = PurchaseJson.read(path)
            val names = setOf("originChance", "originPolicy", "tailChance", "tailPolicy")
            PurchaseJson.fields(node, names + setOf("schemaVersion", "arena", "arenaSha256"))
            require(node["schemaVersion"]?.asInt() == 1) { "Coordination source requires schema1." }
            val arena = path.parent.resolve(PurchaseJson.text(node.required("arena"), "arena"))
            require(PurchaseJson.hash(arena) == node.required("arenaSha256").asText()) {
                "Coordination arena differs from its frozen bytes."
            }
            fun seeds(name: String): List<Int> =
                PurchaseJson.array(node.required(name), name).map { PurchaseJson.integer(it, name) }
            return LeaderCoordinationInput(
                PurchaseJson.hash(path),
                ArenaSpecification.read(arena),
                seeds("originChance"),
                seeds("originPolicy"),
                seeds("tailChance"),
                seeds("tailPolicy"),
            )
        }
    }
}
