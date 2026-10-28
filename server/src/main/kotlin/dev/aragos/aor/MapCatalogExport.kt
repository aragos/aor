package dev.aragos.aor

import java.io.File

/** Exports the resolved six-player map for offline geometry generation and validation. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Supply a map name (standard/european) and output JSON path." }
    val map =
        requireNotNull(MapRule.entries.singleOrNull { it.commandValue == args[0] }) {
            "Unknown map: ${args[0]}."
        }
    val rules = RuleOptions(map = map)
    GameCatalog.requireResolvedFor(6, rules)
    File(args[1]).writeText(json.writeValueAsString(PublicCatalog.forPlayers(6, rules)) + "\n")
}
