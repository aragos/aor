package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Four frozen synthetic histories only; no simulation, fitting, policy selection or label export.
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 3) { "Use PROTOCOL PROTOCOL_SHA256 NEW_OUTPUT." }
    val protocolPath = Path.of(arguments[0]).toRealPath()
    require(PurchaseJson.hash(protocolPath) == arguments[1]) {
        "Frozen extraction protocol changed."
    }
    val protocol = PurchaseJson.read(protocolPath)
    require(
        protocol.required("kind").asText() == "purchase-privileged-four-prefixes-1" &&
            protocol.required("schemaVersion").asInt() == 1 &&
            protocol.required("maximumHistories").asInt() == 4 &&
            protocol.required("maximumSeconds").asInt() == 600 &&
            listOf("policyCalls", "newGames", "fits").all { protocol.required(it).asInt() == 0 }
    ) {
        "Extract only the four approved retained prefixes under the fixed ten-minute ceiling."
    }
    val histories = protocol.required("histories").toList()
    val expected =
        listOf(
            Triple(1, "mc", 0),
            Triple(3, "mc", 85),
            Triple(5, "gae", 170),
            Triple(7, "gae", 255),
        )
    require(
        histories.map {
            Triple(
                it.required("batch").asInt(),
                it.required("arm").asText(),
                it.required("index").asInt(),
            )
        } == expected
    ) {
        "The four prospectively frozen original 44 histories cannot be substituted."
    }
    val pins =
        listOf("sourceFreezes", "originalInputs").flatMap { field ->
            protocol.required(field).properties().map { Path.of(it.key) to it.value.asText() }
        } +
            histories.flatMap { row ->
                listOf("trajectory", "replay", "actorModel").map { field ->
                    Path.of(row.required(field).asText()) to row.required(field + "Sha256").asText()
                }
            }
    PurchasePrivilegedFiles.verify(pins)
    val output = Path.of(arguments[2]).toAbsolutePath().normalize()
    require(!Files.exists(output)) {
        "Prefix extraction permits one new output, never replacement."
    }
    Files.createDirectories(output)
    val deadline = System.nanoTime() + 600_000_000_000L
    val coverage = mutableListOf<Map<String, Any>>()
    var rowNumber = 0
    Files.newBufferedWriter(output.resolve("features.jsonl"), StandardOpenOption.CREATE_NEW).use {
        features ->
        Files.newBufferedWriter(output.resolve("provenance.jsonl"), StandardOpenOption.CREATE_NEW)
            .use { provenance ->
                for ((origin, request) in histories.withIndex()) {
                    val game = PurchaseJson.read(Path.of(request.required("trajectory").asText()))
                    val replay = PurchaseJson.read(Path.of(request.required("replay").asText()))
                    require(
                        replay.required("replayVerified").asBoolean() &&
                            replay.required("trajectorySha256").asText() ==
                                request.required("trajectorySha256").asText() &&
                            replay.required("signature") == game.required("signature") &&
                            replay.required("index") == game.required("index") &&
                            game.required("index") == request.required("index") &&
                            game.required("modelSha256").asText() ==
                                request.required("actorModelSha256").asText()
                    ) {
                        "Original replay, trajectory and actor identities must match exactly."
                    }
                    val header = PurchasePrivilegedFiles.header(replay.required("header"))
                    val actor = header.players[game.required("controlledSeat").asInt()].id
                    val captures =
                        PurchasePrivilegedPrefix.extract(
                            GameState(header),
                            actor,
                            PurchaseJson.strings(replay.required("entries"), "canonical entries"),
                            game.required("decisions").toList(),
                            deadline,
                        )
                    for (capture in captures) {
                        features.appendLine(
                            PurchaseJson.mapper.writeValueAsString(
                                mapOf(
                                    "row" to rowNumber,
                                    "features" to capture.features,
                                )
                            )
                        )
                        provenance.appendLine(
                            PurchaseJson.mapper.writeValueAsString(
                                mapOf(
                                    "row" to rowNumber++,
                                    "origin" to origin,
                                    "sourceTrajectorySha256" to
                                        request.required("trajectorySha256").asText(),
                                    "sourceReplaySha256" to
                                        request.required("replaySha256").asText(),
                                    "decisionNumber" to capture.number,
                                    "entryIndex" to capture.entryIndex,
                                    "turn" to capture.turn,
                                    "decision" to capture.decision,
                                    "exactActorStateActionsAndRecordedBaseline" to true,
                                )
                            )
                        )
                    }
                    coverage.add(
                        mapOf(
                            "origin" to origin,
                            "purchases" to captures.size,
                            "phases" to captures.groupingBy { it.decision }.eachCount(),
                            "rowsWithOpponentCards" to
                                captures.count { it.features.opponentHandCounts.isNotEmpty() },
                            "lastRetainedPrefixTransitions" to captures.last().entryIndex,
                            "fullRecordedTransitions" to replay.required("entries").size(),
                        )
                    )
                }
            }
    }
    PurchasePrivilegedFiles.verify(pins)
    require(PurchaseJson.hash(protocolPath) == arguments[1]) {
        "Protocol changed during extraction."
    }
    PurchasePrivilegedFiles.write(
        output.resolve("completion.json"),
        mapOf(
            "kind" to "purchase-privileged-four-prefixes-complete-1",
            "passed" to true,
            "histories" to 4,
            "rows" to rowNumber,
            "coverage" to coverage,
            "protocolSha256" to arguments[1],
            "inputPinsVerifiedBeforeAndAfter" to pins.size,
            "featuresSha256" to PurchaseJson.hash(output.resolve("features.jsonl")),
            "provenanceSha256" to PurchaseJson.hash(output.resolve("provenance.jsonl")),
            "newGames" to 0,
            "policyChoiceCalls" to 0,
            "criticFits" to 0,
            "exportedLabels" to 0,
            "baselineParity" to
                "Original legal baseline command rebound; no policy recommendation recomputed.",
            "limitations" to
                listOf(
                    "Original action/chance seed coupling remains.",
                    "44 contains32 independent worlds across64 paired-arm histories.",
                    "Extraction establishes no prediction or policy-strength result.",
                ),
        ),
    )
}

/** File boundaries own pin verification; neither engine state nor feature rows contain paths. */
internal object PurchasePrivilegedFiles {
    fun verify(pins: List<Pair<Path, String>>) {
        for ((path, hash) in pins) {
            require(PurchaseJson.hash(path) == hash) { "Frozen synthetic source changed: $path." }
        }
    }

    fun write(path: Path, value: Any) {
        Files.writeString(
            path,
            PurchaseJson.mapper.writeValueAsString(value) + "\n",
            StandardOpenOption.CREATE_NEW,
        )
    }

    fun header(node: JsonNode): GameHeader =
        GameHeader(
            node.required("players").map {
                Player(PlayerId(it.required("id").asText()), it.required("name").asText())
            },
            PlayerId(node.required("creatorId").asText()),
            node.required("rulesVersion").asText(),
            node.required("dataVersion").asText(),
            node.required("drawRenaissanceFromTurn").asInt(),
        )
}
