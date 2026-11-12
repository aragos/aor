package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.abs

/**
 * Separately admitted TRAIN/VALIDATION labels. Public support must already exist as a frozen file.
 * No first-action-only or unsupported-plan return is decoded; TEST has no accepted index or mode.
 */
object PurchaseMacroLabels {
    private val mapper =
        PurchaseJson.mapper.copy().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) {
            "Use a frozen label manifest and new detached JSONL output."
        }
        val manifest = PurchaseJson.read(Path.of(arguments[0]))
        PurchaseJson.fields(manifest, setOf("kind", "publicFacts", "origins"))
        require(manifest.required("kind").asText() == "purchase-macro-labels-1") {
            "Explicit macro label admission is required."
        }
        val rows = manifest.required("origins")
        require(
            rows.isArray &&
                rows.map { PurchaseJson.integer(it.required("index"), "origin index") } ==
                    (0 until 448).toList()
        ) {
            "Only complete original TRAIN/VALIDATION groups are admitted; TEST remains closed."
        }
        val publicPath = subject(manifest.required("publicFacts"))
        val publicHash = PurchaseJson.hash(publicPath)
        val public = Files.readAllLines(publicPath).map { mapper.readTree(it) }
        require(public.size == 448) {
            "Every original origin requires prior factual support, including missing roots."
        }
        var labels = 0
        Files.newBufferedWriter(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW).use { output
            ->
            for ((index, row) in rows.withIndex()) {
                PurchaseJson.fields(row, setOf("index", "split", "result"))
                val facts = public[index]
                val split = if (index < 384) "TRAIN" else "VALIDATION"
                require(
                    row.required("split").asText() == split &&
                        facts.required("split").asText() == split &&
                        PurchaseJson.integer(facts.required("originIndex"), "public origin") ==
                            index &&
                        facts.required("version").asText() == PurchaseMacroProjection.VERSION
                ) {
                    "Frozen public support changed its whole-origin identity."
                }
                val source = subject(row.required("result"))
                require(PurchaseJson.hash(source) == facts.required("sourceSha256").asText()) {
                    "Support and label sources must be the exact same original bytes."
                }
                val returns =
                    when (facts.required("status").asText()) {
                        "MISSING" -> emptyList()
                        "CAPTURED" -> {
                            require(facts.required("sampledPassVerified").asBoolean()) {
                                "Original G2 sampled root must be verified before labels."
                            }
                            val streams =
                                facts.required("continuationSeeds").map {
                                    require(it.isIntegralNumber && it.canConvertToLong()) {
                                        "Original future seeds require signed64 integers."
                                    }
                                    it.longValue()
                                }
                            readReturns(
                                    source,
                                    facts.required("facts").required("plans"),
                                    streams,
                                    facts.required("actor").asText(),
                                )
                                .also { labels += it.size * 2 }
                        }
                        else -> error("Unknown prior public support status.")
                    }
                output.write(
                    mapper.writeValueAsString(
                        mapOf(
                            "originIndex" to index,
                            "split" to split,
                            "sourceSha256" to PurchaseJson.hash(source),
                            "publicFactsSha256" to publicHash,
                            "labelKind" to "COMPLETE_PREFIX",
                            "plans" to returns,
                        )
                    )
                )
                output.newLine()
            }
        }
        println(
            mapper.writeValueAsString(
                mapOf(
                    "passed" to true,
                    "origins" to 448,
                    "pairedOutcomeRows" to labels,
                    "firstActionOutcomesDecoded" to 0,
                    "unsupportedOutcomesDecoded" to 0,
                    "testOriginsOpened" to 0,
                    "newGames" to 0,
                    "actualFits" to 0,
                )
            )
        )
    }

    private fun subject(node: JsonNode): Path {
        PurchaseJson.fields(node, setOf("path", "sha256"))
        val path = Path.of(PurchaseJson.text(node.required("path"), "input path"))
        require(
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path) &&
                PurchaseJson.hash(path) == node.required("sha256").asText()
        ) {
            "A prior frozen support or original result changed."
        }
        return path
    }

    internal fun readReturns(
        path: Path,
        plans: JsonNode,
        streams: List<Long>,
        actor: String,
    ): List<Map<String, Any>> {
        require(streams.size == 2 && streams.distinct().size == 2) {
            "Both original shared future streams are required."
        }
        val supported = plans.filter { it.required("support").asText() == "SUPPORTED" }
        val commands = supported.associate {
            PurchaseJson.strings(it.required("commands"), "macro commands") to
                PurchaseJson.integer(it.required("planIndex"), "macro index")
        }
        require(commands.size == supported.size && commands[listOf("pass")] == 0) {
            "Supported macros must be unique and retain PASS0."
        }
        val retained = linkedMapOf<Pair<Int, Long>, Map<String, Double>>()
        mapper.createParser(path.toFile()).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) {
                "A teacher result requires an object."
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                if (name != "root") parser.skipChildren()
                else {
                    require(parser.currentToken == JsonToken.START_OBJECT) {
                        "A supported root cannot be missing."
                    }
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        val field = parser.currentName()
                        parser.nextToken()
                        if (field != "tails") parser.skipChildren()
                        else {
                            require(parser.currentToken == JsonToken.START_ARRAY) {
                                "Teacher tails require an array."
                            }
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                readTail(parser, commands, streams, actor, retained)
                            }
                        }
                    }
                }
            }
            require(parser.nextToken() == null) { "No trailing teacher result is permitted." }
        }
        require(retained.size == supported.size * 2) {
            "Each supported full macro requires exactly two matching returns."
        }
        return supported.map { plan ->
            val index = PurchaseJson.integer(plan.required("planIndex"), "macro index")
            mapOf(
                "planIndex" to index,
                "commands" to PurchaseJson.strings(plan.required("commands"), "macro commands"),
                "continuationSeeds" to streams,
                "returns" to
                    streams.map { stream ->
                        requireNotNull(retained[index to stream]) {
                            "A supported macro lacks its original paired return."
                        }
                    },
            )
        }
    }

    private fun readTail(
        parser: JsonParser,
        supported: Map<List<String>, Int>,
        streams: List<Long>,
        actor: String,
        retained: MutableMap<Pair<Int, Long>, Map<String, Double>>,
    ) {
        require(parser.currentToken == JsonToken.START_OBJECT) {
            "A teacher tail requires an object."
        }
        var arm: String? = null
        var commands: List<String>? = null
        var stream: Long? = null
        var stop: String? = null
        var forced: List<String>? = null
        var outcome: JsonNode? = null
        var replay = false
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val field = parser.currentName()
            parser.nextToken()
            when (field) {
                "arm" -> arm = PurchaseJson.text(tree(parser), "tail arm")
                "commands" -> commands = PurchaseJson.strings(tree(parser), "tail commands")
                "continuationSeed" -> {
                    val seed = tree(parser)
                    require(seed.isIntegralNumber && seed.canConvertToLong()) {
                        "Tail seed requires a signed64 integer."
                    }
                    stream = seed.longValue()
                }
                "stop" -> stop = PurchaseJson.text(tree(parser), "prefix stop")
                "replayVerified" -> replay = tree(parser).asBoolean()
                "steps",
                "outcome" -> {
                    require(arm != null && commands != null) {
                        "Original wire order must identify the full prefix before effects or labels."
                    }
                    if (arm != "COMPLETE_PREFIX" || commands !in supported) parser.skipChildren()
                    else if (field == "steps") forced = forcedCommands(parser)
                    else outcome = tree(parser)
                }
                else -> parser.skipChildren()
            }
        }
        if (arm != "COMPLETE_PREFIX" || commands !in supported) return
        require(stop == "PREFIX_COMPLETE" && forced == commands && replay && stream in streams) {
            "The supported complete macro must actually execute exactly its commands and original stream."
        }
        val label =
            requireNotNull(outcome) { "A supported complete macro requires its retained outcome." }
        require(
            label.required("continuationSeed").isIntegralNumber &&
                label.required("continuationSeed").longValue() == stream
        ) {
            "Outcome stream differs from its complete prefix."
        }
        val values =
            listOf("reward", "winShare", "rankReward").associateWith {
                PurchaseJson.number(label.required(it), "terminal $it").also { value ->
                    require(value in 0.0..1.0) { "Terminal returns must be normalized." }
                }
            }
        require(
            abs(
                requireNotNull(values["reward"]) { "Reward is required." } -
                    .8 * requireNotNull(values["winShare"]) { "Win share is required." } -
                    .2 * requireNotNull(values["rankReward"]) { "Rank reward is required." }
            ) < 1e-12
        ) {
            "Original terminal reward arithmetic changed."
        }
        val own = label.required("scores").single { it.required("player").asText() == actor }
        require(own.required("misery").isNull == own.required("total").isNull) {
            "Chaos retains an explicit null score, never a fabricated numeric total."
        }
        val index = requireNotNull(supported[commands]) { "The supported macro index is required." }
        val key = index to requireNotNull(stream) { "A supported macro requires its stream." }
        require(key !in retained) { "A supported plan/stream label cannot be duplicated." }
        retained[key] = values + ("chaos" to if (own.required("misery").isNull) 1.0 else 0.0)
    }

    private fun forcedCommands(parser: JsonParser): List<String> {
        require(parser.currentToken == JsonToken.START_ARRAY) {
            "Original purchase steps require an array."
        }
        val result = mutableListOf<String>()
        var normalSeen = false
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken == JsonToken.START_OBJECT) { "A step requires an object." }
            var command: String? = null
            var forced: Boolean? = null
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val field = parser.currentName()
                parser.nextToken()
                when (field) {
                    "chosenCommand" -> command = PurchaseJson.text(tree(parser), "forced command")
                    "forced" -> {
                        require(parser.currentToken.isBoolean) {
                            "Step forced flag must be boolean."
                        }
                        forced = parser.booleanValue
                    }
                    else -> parser.skipChildren()
                }
            }
            if (requireNotNull(forced) { "Every original step declares whether it was forced." }) {
                require(!normalSeen) { "A forced prefix cannot resume after normal continuation." }
                result.add(requireNotNull(command) { "Forced commands must be retained." })
            } else normalSeen = true
        }
        return result
    }

    private fun tree(parser: JsonParser): JsonNode =
        requireNotNull(mapper.readTree<JsonNode>(parser)) { "A JSON field value is required." }
}
