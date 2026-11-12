package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservations
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replays only explicitly pinned completed synthetic backbones. No controller, chance sampler,
 * alternative action or terminal score is evaluated. Outputs contain only public ownership, window
 * phase/turn, offered commands and the actual recorded choice; no hands or deck entries.
 */
object RenaissanceCoverageMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "Use INPUT_MANIFEST NEW_OUTPUT_DIRECTORY." }
        val manifestPath = Path.of(arguments[0]).toRealPath()
        val manifestHash = PurchaseJson.hash(manifestPath)
        val manifest = PurchaseJson.read(manifestPath)
        PurchaseJson.fields(manifest, setOf("kind", "completionPins", "origins"))
        require(manifest["kind"].asText() == "renaissance-synthetic-coverage-input-1") {
            "Coverage requires its explicit completed-synthetic manifest."
        }
        fun verifyPins() {
            manifest["completionPins"].properties().forEach { (path, hash) ->
                require(PurchaseJson.hash(Path.of(path)) == hash.asText()) {
                    "A completed synthetic dataset receipt changed."
                }
            }
            require(PurchaseJson.hash(manifestPath) == manifestHash) {
                "The coverage manifest changed."
            }
        }
        verifyPins()
        val sources = manifest["origins"]
        require(sources.isArray && sources.size() == 256) {
            "This coverage pass includes all 256 frozen family55 origins without selection."
        }
        val deadline = System.nanoTime() + 180_000_000_000L
        val results = sources.mapIndexed { index, source ->
            PurchaseJson.fields(source, setOf("index", "path", "sha256"))
            require(source["index"].intValue() == index) { "Origin order must remain exact." }
            replay(index, source, deadline)
        }
        verifyPins()
        val output = Path.of(arguments[1]).toAbsolutePath()
        Files.createDirectory(output)
        TrainingArtifacts(output)
            .write(
                "coverage.json",
                mapOf(
                    "kind" to "renaissance-synthetic-coverage-1",
                    "manifestSha256" to manifestHash,
                    "origins" to results,
                    "historicalCanonicalReplays" to results.size,
                    "newGames" to 0,
                    "newTails" to 0,
                    "policyCalls" to 0,
                    "fits" to 0,
                ),
            )
    }

    private fun replay(index: Int, source: JsonNode, deadline: Long): Map<String, Any> {
        val path = Path.of(source["path"].asText()).toRealPath()
        val expected = source["sha256"].asText()
        require(PurchaseJson.hash(path) == expected) { "A retained synthetic origin changed." }
        val document = PurchaseJson.read(path)
        require(
            document["kind"].asText() == "acquisition-origin-1" &&
                document["request"]["index"].intValue() == index
        ) {
            "Coverage origin identity must match its frozen completed source."
        }
        val backbone = document["backbone"]
        val header = header(backbone["header"])
        val entries = backbone["entries"].map(JsonNode::asText)
        require(
            backbone["replayVerified"].booleanValue() &&
                CardAdvantageExecutor.hash(entries) == backbone["canonicalSha256"].asText()
        ) {
            "The retained complete canonical backbone receipt must remain exact."
        }
        var state = GameState(header)
        val owners = mutableSetOf<Int>()
        val windows = mutableListOf<Map<String, Any>>()
        for ((number, text) in entries.withIndex()) {
            checkTokenValueDeadline(deadline)
            val entry = TextCodec.parse(text)
            val prompt = state.prompts().singleOrNull()
            if (
                entry is Entry.Action &&
                    prompt is Prompt.SinglePlayer &&
                    prompt.choice == "renaissance"
            ) {
                check(entry.actor == prompt.player) { "A recorded Renaissance actor changed." }
                val offered = state.legalChoices(entry.actor).commands
                val observation = BotObservations.from(state, entry.actor)
                check(
                    observation.candidates.map(BotCandidate::command).toSet() == offered.toSet()
                ) {
                    "The current ordinary actor must expose the exact owner swap/pass choices."
                }
                val chosen = entry.command.toText()
                check(chosen in offered) { "The recorded Renaissance action must be offered." }
                val seat = state.playerIds.indexOf(entry.actor)
                windows +=
                    mapOf(
                        "entry" to number,
                        "seat" to seat,
                        "controlled" to (seat == index % 4),
                        "turn" to requireNotNull(state.turnNumber) { "A swap requires a turn." },
                        "epoch" to state.currentEpoch.number,
                        "phase" to state.stage.name,
                        "offered" to offered,
                        "chosen" to chosen,
                    )
            }
            state = state.step(entry)
            state.playerIds.forEachIndexed { seat, player ->
                if (state.playerState(player).advances.owns(AdvanceId.RENAISSANCE)) {
                    owners += seat
                }
            }
        }
        check(state.stage == Stage.FINISHED) { "The retained backbone must replay to completion." }
        require(PurchaseJson.hash(path) == expected) { "An origin changed during replay." }
        return mapOf(
            "index" to index,
            "controlledSeat" to index % 4,
            "sourceSha256" to expected,
            "canonicalEntries" to entries.size,
            "everRenaissanceSeats" to owners.sorted(),
            "windows" to windows,
        )
    }

    private fun header(node: JsonNode): GameHeader {
        PurchaseJson.fields(
            node,
            setOf("players", "creatorId", "rulesVersion", "dataVersion", "drawRenaissanceFromTurn"),
        )
        val header =
            GameHeader(
                players =
                    node["players"].map { player ->
                        Player(PlayerId(player["id"]["value"].asText()), player["name"].asText())
                    },
                creatorId = PlayerId(node["creatorId"]["value"].asText()),
                rulesVersion = node["rulesVersion"].asText(),
                dataVersion = node["dataVersion"].asText(),
                drawRenaissanceFromTurn = node["drawRenaissanceFromTurn"].intValue(),
            )
        require(PurchaseJson.mapper.valueToTree<JsonNode>(header) == node) {
            "The canonical header must preserve every serialized identity and timing field."
        }
        return header
    }
}
