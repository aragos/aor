package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Explicit offline prefix-only export. The manifest admits original TRAIN/VALIDATION files; no
 * outcome tree or post-root canonical command is decoded. No simulator or fitting entry is used.
 */
object PurchaseMacroExport {
    private const val SPLIT = "a5e75cca16b3190a1d32f8a792df305de95f778cf0849e8496116c4f9b3450fd"
    private const val F0 = "c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0"
    private const val G2 = "09e29e5b25b76bdad3c2e082e210322d16851bfd8a978ce123b6a8b0fbf8b2af"
    private val mapper =
        PurchaseJson.mapper.copy().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "Use frozen macro manifest and new public JSONL output." }
        val manifest = PurchaseJson.read(Path.of(arguments[0]))
        PurchaseJson.fields(manifest, setOf("kind", "splitSha256", "prior", "policy", "origins"))
        require(
            manifest.required("kind").asText() == "purchase-macro-prefix-export-1" &&
                manifest.required("splitSha256").asText() == SPLIT
        ) {
            "Only the original59 prospective split is supported."
        }
        val priorPath = subject(manifest.required("prior"))
        val policyPath = subject(manifest.required("policy"))
        require(PurchaseJson.hash(priorPath) == F0 && PurchaseJson.hash(policyPath) == G2) {
            "Root verification retains the exact original F0/G2 sampled controller."
        }
        val priorNode = PurchaseJson.read(priorPath)
        val prior =
            BotModels.fromSerializedWeights(
                priorNode.required("weights").properties().associate {
                    it.key to PurchaseJson.number(it.value, "prior weight")
                },
                PurchaseJson.integer(priorNode.required("schemaVersion"), "prior schema"),
            )
        val policy = PurchasePolicyInput.read(policyPath)
        val rows = manifest.required("origins")
        require(
            rows.isArray &&
                rows.map { PurchaseJson.integer(it.required("index"), "origin index") } ==
                    (0 until 448).toList()
        ) {
            "Retain exactly384 TRAIN and64 VALIDATION origins; TEST is closed."
        }
        var captured = 0
        Files.newBufferedWriter(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW).use { output
            ->
            for (row in rows) {
                PurchaseJson.fields(row, setOf("index", "split", "result", "backbone"))
                val index = PurchaseJson.integer(row.required("index"), "origin index")
                val split = if (index < 384) "TRAIN" else "VALIDATION"
                require(row.required("split").asText() == split) {
                    "Origin grouping differs from the frozen split."
                }
                val resultPath = subject(row.required("result"))
                val result = rootFields(resultPath)
                require(PurchaseJson.integer(result.required("index"), "retained index") == index) {
                    "Original origin identity changed."
                }
                val root = result.required("root")
                val base =
                    mapOf(
                        "originIndex" to index,
                        "split" to split,
                        "sourceSha256" to PurchaseJson.hash(resultPath),
                        "version" to PurchaseMacroProjection.VERSION,
                    )
                val record =
                    if (root.isNull) {
                        require(result.required("missingRoot").asBoolean()) {
                            "A missing root must remain explicitly missing."
                        }
                        base +
                            mapOf(
                                "status" to "MISSING",
                                "exclusions" to result.required("exclusions"),
                            )
                    } else {
                        require(!result.required("missingRoot").asBoolean()) {
                            "A captured root cannot be marked missing."
                        }
                        val backbonePath = subject(row.required("backbone"))
                        require(
                            PurchaseJson.hash(backbonePath) ==
                                result.required("backboneEvidence").required("sha256").asText()
                        ) {
                            "Canonical prefix must belong to the exact original teacher result."
                        }
                        val prefix = prefix(backbonePath)
                        val number = PurchaseJson.integer(root.required("number"), "root decision")
                        require(prefix.number == number) {
                            "Original absolute player decision changed."
                        }
                        val state =
                            prefix.entries.fold(GameState(header(prefix.header))) { value, entry ->
                                value.step(TextCodec.parse(entry))
                            }
                        val origin = result.required("origin")
                        val seat = PurchaseJson.integer(origin.required("seat"), "actor seat")
                        require(seat in 0..3) {
                            "The retained actor must be a registered four-player seat."
                        }
                        val actor = state.playerIds[seat]
                        require(prefix.next == "${actor.value}: pass") {
                            "The original canonical next command must be PASS."
                        }
                        val observation = BotObservations.from(state, actor)
                        val projection =
                            PurchaseObservationEncoder.project(
                                state.viewForPlayer(actor),
                                actor,
                                observation,
                            )
                        require(
                            json(observation) == root.required("observation") &&
                                json(projection) == root.required("projection")
                        ) {
                            "Original actor-visible root projections differ from the exact canonical prefix."
                        }
                        val seed =
                            simulationPolicySeed(
                                PurchaseJson.integer(origin.required("gameSeed"), "game seed"),
                                number,
                                seat,
                            )
                        val baseline =
                            BotPolicy.choose(observation, BotDifficulty.HARD, seed, prior)
                        val purchase = projection.bindBaseline(baseline)
                        val choice =
                            PurchasePolicy.choose(purchase, policy, PurchasePolicy.actorSeed(seed))
                        require(purchase.actions[choice.index].command == "pass") {
                            "Exact original sampled G2/F0 must reproduce PASS."
                        }
                        val facts = PurchaseMacroProjection.from(state, actor, observation)
                        require(json(facts.proposals) == root.required("proposals")) {
                            "Prospective plan support/order/truncation changed."
                        }
                        captured++
                        base +
                            mapOf(
                                "status" to "CAPTURED",
                                "facts" to facts,
                                "backboneSha256" to PurchaseJson.hash(backbonePath),
                                "prefixEntries" to prefix.entries.size,
                                "rootNumber" to number,
                                "actor" to actor.value,
                                "continuationSeeds" to origin.required("continuationSeeds"),
                                "sampledPassVerified" to true,
                            )
                    }
                output.write(PurchaseJson.mapper.writeValueAsString(record))
                output.newLine()
            }
        }
        println(
            PurchaseJson.mapper.writeValueAsString(
                mapOf(
                    "passed" to true,
                    "origins" to 448,
                    "captured" to captured,
                    "missing" to 448 - captured,
                    "tailTreesDecoded" to 0,
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
            "A frozen regular input changed."
        }
        return path
    }

    /** Unlike a complete result decode, this skips every tail including intermediate effects. */
    internal fun rootFields(path: Path): JsonNode {
        val result = mapper.createObjectNode()
        mapper.createParser(path.toFile()).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) {
                "A teacher result must be an object."
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                when (name) {
                    "index",
                    "origin",
                    "missingRoot",
                    "exclusions",
                    "backboneEvidence" -> result.set<JsonNode>(name, tree(parser))
                    "root" -> {
                        if (parser.currentToken == JsonToken.VALUE_NULL) result.putNull(name)
                        else {
                            require(parser.currentToken == JsonToken.START_OBJECT) {
                                "A root must be an object or null."
                            }
                            val root = mapper.createObjectNode()
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                val field = parser.currentName()
                                parser.nextToken()
                                if (
                                    field in
                                        setOf("number", "observation", "projection", "proposals")
                                ) {
                                    root.set<JsonNode>(field, tree(parser))
                                } else {
                                    parser.skipChildren()
                                }
                            }
                            result.set<JsonNode>(name, root)
                        }
                    }
                    else -> parser.skipChildren()
                }
            }
            require(parser.nextToken() == null) { "No trailing teacher result data is permitted." }
        }
        return result
    }

    internal data class Prefix(
        val header: JsonNode,
        val entries: List<String>,
        val next: String,
        val number: Int,
    )

    /** Two streaming passes locate the root boundary before decoding any canonical entry text. */
    internal fun prefix(path: Path): Prefix {
        val meta = mapper.createObjectNode()
        mapper.createParser(path.toFile()).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) {
                "Canonical evidence requires an object."
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                if (name in setOf("header", "rootPlayerDecision", "rootEntryCount"))
                    meta.set<JsonNode>(name, tree(parser))
                else parser.skipChildren()
            }
        }
        val count = PurchaseJson.integer(meta.required("rootEntryCount"), "root entry count")
        val number = PurchaseJson.integer(meta.required("rootPlayerDecision"), "root decision")
        require(count in 1..1_000_000 && number in 0 until 12000) {
            "Canonical root bounds must be finite."
        }
        val entries = mutableListOf<String>()
        var next: String? = null
        mapper.createParser(path.toFile()).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) {
                "Canonical evidence requires an object."
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                if (name != "entries") parser.skipChildren()
                else {
                    require(parser.currentToken == JsonToken.START_ARRAY) {
                        "Canonical entries require an array."
                    }
                    var index = 0
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        if (index <= count) {
                            require(parser.currentToken == JsonToken.VALUE_STRING) {
                                "Canonical prefix entries must be text."
                            }
                            if (index == count) next = parser.text else entries.add(parser.text)
                        } else parser.skipChildren()
                        index++
                    }
                }
            }
            require(parser.nextToken() == null) { "No trailing canonical evidence is permitted." }
        }
        require(entries.size == count) { "The exact canonical prefix was truncated." }
        val start = entries.indexOfFirst { it == "p1: start" }
        require(
            start >= 0 &&
                entries.drop(start + 1).count { it.matches(Regex("p[1-4]: .*")) } == number
        ) {
            "Player-decision offsets must include the entire original prefix."
        }
        return Prefix(
            meta.required("header"),
            entries,
            requireNotNull(next) { "The retained next PASS is required." },
            number,
        )
    }

    private fun header(node: JsonNode): GameHeader {
        val header =
            GameHeader(
                players =
                    node.required("players").map {
                        Player(
                            PlayerId(it.required("id").required("value").asText()),
                            it.required("name").asText(),
                        )
                    },
                creatorId = PlayerId(node.required("creatorId").required("value").asText()),
                rulesVersion = node.required("rulesVersion").asText(),
                dataVersion = node.required("dataVersion").asText(),
                drawRenaissanceFromTurn =
                    PurchaseJson.integer(
                        node.required("drawRenaissanceFromTurn"),
                        "Renaissance activation turn",
                    ),
            )
        require(json(header) == node) { "Canonical header fields must be preserved exactly." }
        return header
    }

    private fun tree(parser: JsonParser): JsonNode =
        requireNotNull(mapper.readTree<JsonNode>(parser)) { "Expected a JSON field value." }

    private fun json(value: Any): JsonNode =
        mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))
}
