package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameHeader
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.replay
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Standalone read-only extraction CLI; it cannot generate games, choose policies or score a critic.
 */
fun main(arguments: Array<String>) {
    require(arguments.size == 4) {
        "Use PROTOCOL PROTOCOL_SHA256 OUTPUT MODE (prepare or extract)."
    }
    val protocol = Path.of(arguments[0]).toAbsolutePath().normalize()
    require(PurchaseJson.hash(protocol) == arguments[1]) { "Boundary protocol pin changed." }
    val source = PurchaseJson.read(protocol)
    val keys =
        setOf(
            "schemaVersion",
            "kind",
            "sourceDirectory",
            "sourceManifestSha256",
            "completionAuditSha256",
            "rootsSha256",
            "completedManifestSha256",
            "dictionary",
            "dictionarySha256",
            "analysisPlan",
            "analysisPlanSha256",
        )
    PurchaseJson.fields(source, keys)
    require(
        source.required("schemaVersion").asInt() == 1 &&
            source.required("kind").asText() == "retained-purchase-boundary-1"
    ) {
        "Boundary extraction requires its fixed schema and kind."
    }
    val directory = Path.of(source.required("sourceDirectory").asText()).toRealPath()
    fun pinned(path: Path, field: String): JsonNode {
        require(PurchaseJson.hash(path) == source.required(field).asText()) {
            "Boundary source changed: $path."
        }
        return PurchaseJson.read(path)
    }
    val manifest = pinned(directory.resolve("manifest.json"), "sourceManifestSha256")
    for ((name, hash) in manifest.required("fileSha256").properties()) {
        require(PurchaseJson.hash(Path.of(name)) == hash.asText()) {
            "Original 40m frozen input changed: $name."
        }
    }
    val audit = pinned(directory.resolve("completion-audit.json"), "completionAuditSha256")
    require(
        audit.required("status").asText() == "complete" &&
            audit.required("originReplays").asInt() == 128 &&
            audit.required("canonicalTailReplays").asInt() == 1024
    ) {
        "Only the complete audited 40m bank may be extracted."
    }
    val roots = pinned(directory.resolve("run/roots.json"), "rootsSha256").required("roots")
    val completed = directory.resolve("run/completed.jsonl")
    require(PurchaseJson.hash(completed) == source.required("completedManifestSha256").asText()) {
        "Completed tail inventory changed."
    }
    val hashes =
        Files.readAllLines(completed).map(PurchaseJson.mapper::readTree).associate {
            it.required("file").asText() to it.required("sha256").asText()
        }
    require(roots.size() == 32 && hashes.size == 512) {
        "Boundary extraction requires all 32 roots and 512 paired-tail files."
    }
    val dictionaryPath = protocol.parent.resolve(source.required("dictionary").asText()).normalize()
    val dictionary = pinned(dictionaryPath, "dictionarySha256")
    require(
        dictionary.required("encoderVersion").asText() == PurchaseObservationEncoder.ENCODER_VERSION
    ) {
        "The critic dictionary must use the existing purchase encoder."
    }
    val names =
        PurchaseJson.strings(dictionary.required("stateFeatureNames"), "stateFeatureNames").toSet()
    require(names.size == 3556) { "The frozen critic has exactly 3,556 state features." }
    pinned(protocol.parent.resolve(source.required("analysisPlan").asText()), "analysisPlanSha256")
    val mode = arguments[3]
    require(mode in setOf("prepare", "extract")) { "Boundary mode must be prepare or extract." }
    val output = Path.of(arguments[2]).toAbsolutePath().normalize()
    RecordedPrefixMain.createOutput(output)
    val receipt =
        mapOf(
            "protocolSha256" to PurchaseJson.hash(protocol),
            "sourceManifestSha256" to source.required("sourceManifestSha256").asText(),
            "completionAuditSha256" to source.required("completionAuditSha256").asText(),
            "dictionarySha256" to source.required("dictionarySha256").asText(),
            "rows" to 1024,
            "stateFeatureCount" to names.size,
            "policyCalls" to 0,
            "generatedGames" to 0,
            "criticScoresComputed" to 0,
        )
    RecordedPrefixMain.write(output.resolve("preparation.json"), receipt)
    if (mode == "prepare") return
    var rows = 0
    var fallbacks = 0
    var transitions = 0L
    var fullTransitions = 0L
    val counts = mutableListOf<Map<String, Any>>()
    GZIPOutputStream(
            Files.newOutputStream(
                output.resolve("features.jsonl.gz"),
                StandardOpenOption.CREATE_NEW,
            )
        )
        .bufferedWriter()
        .use { features ->
            GZIPOutputStream(
                    Files.newOutputStream(
                        output.resolve("labels.jsonl.gz"),
                        StandardOpenOption.CREATE_NEW,
                    )
                )
                .bufferedWriter()
                .use { labels ->
                    for ((rootIndex, request) in roots.withIndex()) {
                        require(request.required("rootIndex").asInt() == rootIndex) {
                            "Frozen root order changed."
                        }
                        val header = request.required("header")
                        val players =
                            header.required("players").map {
                                Player(
                                    PlayerId(it.required("id").asText()),
                                    it.required("name").asText(),
                                )
                            }
                        val gameHeader =
                            GameHeader(
                                players,
                                PlayerId(header.required("creatorId").asText()),
                                header.required("rulesVersion").asText(),
                                header.required("dataVersion").asText(),
                                header.required("drawRenaissanceFromTurn").asInt(),
                            )
                        val state =
                            replay(
                                gameHeader,
                                PurchaseJson.strings(request.required("entries"), "entries"),
                            )
                        val actor = PlayerId(request.required("actor").asText())
                        for (replicate in 0..15) {
                            val name = "root-$rootIndex-replicate-$replicate.json.gz"
                            val path = directory.resolve("run/$name")
                            val hash =
                                requireNotNull(hashes[name]) {
                                    "Missing predeclared paired tail: $name."
                                }
                            require(PurchaseJson.hash(path) == hash) {
                                "Retained paired-tail bytes changed."
                            }
                            val tails =
                                GZIPInputStream(Files.newInputStream(path))
                                    .use(PurchaseJson.mapper::readTree)
                            require(tails.size() == 2) {
                                "Both fixed policies must remain present."
                            }
                            for ((action, tail) in tails.withIndex()) {
                                require(
                                    tail.required("rootIndex").asInt() == rootIndex &&
                                        tail.required("action").asInt() == action &&
                                        tail.required("replicate").asInt() == replicate &&
                                        tail.required("world").asInt() ==
                                            request.required("world").asInt()
                                ) {
                                    "Retained tail identity changed."
                                }
                                val entries =
                                    PurchaseJson.strings(tail.required("entries"), "entries")
                                val capture = PurchaseBoundary.capture(state, actor, entries)
                                val projection = capture.projection
                                require(
                                    projection?.stateFeatures.orEmpty().all { (key, value) ->
                                        key in names && value.isFinite() && value in -16.0..16.0
                                    }
                                ) {
                                    "Boundary features must match the frozen dictionary and encoder units."
                                }
                                val identity =
                                    mapOf(
                                        "rootIndex" to rootIndex,
                                        "world" to request.required("world").asInt(),
                                        "action" to action,
                                        "replicate" to replicate,
                                        "sourceTailSha256" to hash,
                                    )
                                features.appendLine(
                                    PurchaseJson.mapper.writeValueAsString(
                                        identity +
                                            mapOf(
                                                "kind" to
                                                    if (projection == null) "terminal"
                                                    else "purchase",
                                                "encoderVersion" to
                                                    PurchaseObservationEncoder.ENCODER_VERSION,
                                                "boundaryTurn" to projection?.turn,
                                                "boundaryDecision" to projection?.decision,
                                                "stateFeatures" to projection?.stateFeatures,
                                            )
                                    )
                                )
                                val terminal = tail.required("terminal")
                                labels.appendLine(
                                    PurchaseJson.mapper.writeValueAsString(
                                        identity +
                                            mapOf(
                                                "reward" to terminal.required("reward").asDouble(),
                                                "winShare" to
                                                    terminal.required("winShare").asDouble(),
                                                "rankReward" to
                                                    terminal.required("rankReward").asDouble(),
                                            )
                                    )
                                )
                                counts.add(
                                    identity.filterKeys { it != "sourceTailSha256" } +
                                        mapOf(
                                            "prefixTransitions" to capture.prefixTransitions,
                                            "prefixPlayerActions" to capture.prefixPlayerActions,
                                            "fullSuffixTransitions" to entries.size,
                                            "fullSuffixPlayerActions" to
                                                terminal.required("decisions").asInt(),
                                            "terminalFallback" to (projection == null),
                                        )
                                )
                                rows++
                                if (projection == null) fallbacks++
                                transitions += capture.prefixTransitions
                                fullTransitions += entries.size
                            }
                        }
                    }
                }
        }
    require(rows == 1024) { "Boundary extraction must retain every predeclared tail." }
    RecordedPrefixMain.write(output.resolve("prefix-counts.json"), mapOf("rows" to counts))
    RecordedPrefixMain.write(
        output.resolve("complete.json"),
        receipt +
            mapOf(
                "rows" to rows,
                "terminalFallbacks" to fallbacks,
                "retainedPrefixTransitions" to transitions,
                "fullSuffixTransitions" to fullTransitions,
                "featuresSha256" to PurchaseJson.hash(output.resolve("features.jsonl.gz")),
                "labelsSha256" to PurchaseJson.hash(output.resolve("labels.jsonl.gz")),
                "prefixCountsSha256" to PurchaseJson.hash(output.resolve("prefix-counts.json")),
                "runtimeSpeedupClaimed" to false,
            ),
    )
}
