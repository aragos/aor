package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/** Replays an explicit operational upgrade under the current engine without fresh outcomes. */
internal fun replayUpgradedLog(header: JsonNode, entries: List<String>): GameState {
    val dataVersion = header.field("dataVersion").string()
    if (
        header.field("rulesVersion").string() != RULES_VERSION ||
            CatalogVersion.entries.none { it.wireValue == dataVersion }
    ) {
        throw ServiceFailure(422, "HISTORY_IDENTITY_UNVERIFIABLE")
    }
    val currentHeader =
        GameHeader(
            players =
                header.field("players").array().map { player ->
                    Player(PlayerId(player.field("id").string()), player.field("name").string())
                },
            creatorId = PlayerId(header.field("creatorId").string()),
            dataVersion = dataVersion,
        )
    try {
        if (entries.map(TextCodec::parse).map(Entry::toText) != entries) {
            throw ServiceFailure(422, "NONCANONICAL_LOG")
        }
        val state = replay(currentHeader, entries)
        requireCompletedTransaction(state)
        return state
    } catch (failure: ServiceFailure) {
        throw failure
    } catch (_: Exception) {
        throw ServiceFailure(422, "INVALID_GAME_LOG")
    }
}

/** Offline verification accepts one exported game or an export bundle; it writes nothing. */
fun main(args: Array<String>) {
    require(args.size == 1 && args[0].isNotBlank()) {
        "Provide an exported game or bundle JSON file."
    }
    val root = json.readTree(Files.readString(Path.of(args[0])))
    val logs = root["games"]?.array() ?: listOf(root)
    val results = logs.map { log ->
        val id = log.field("id").string()
        val result = runCatching {
            replayUpgradedLog(
                log.field("header"),
                log.field("entries").array().map(JsonNode::string),
            )
        }
        val description =
            result.fold(
                onSuccess = { "valid (${it.stage})" },
                onFailure = { "invalid (${(it as? ServiceFailure)?.code ?: "INVALID_GAME_LOG"})" },
            )
        println("$id: $description")
        result.isSuccess
    }
    check(results.all { it }) { "${results.count { !it }} of ${results.size} logs failed replay." }
    println("Verified ${results.size} game logs without changing storage.")
}
