package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.WindmillValueEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Explicit offline public-prefix export. Skips every continuation tree and admits only original
 * TRAIN/VALIDATION indices before opening an origin. No simulation, policy choice or label decode.
 */
object WindmillValueExport {
    private val mapper =
        PurchaseJson.mapper.copy().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @JvmStatic
    fun main(arguments: Array<String>) {
        if (arguments.size == 2 && arguments[0] == "--dictionary") {
            writeNew(
                Path.of(arguments[1]),
                mapOf(
                    "version" to WindmillValueEncoder.VERSION,
                    "featureNames" to WindmillValueEncoder.featureNames,
                ),
            )
            return
        }
        require(arguments.size == 2) { "Use a frozen public manifest and new JSONL output." }
        val manifest = PurchaseJson.read(Path.of(arguments[0]))
        PurchaseJson.fields(manifest, setOf("kind", "origins"))
        require(manifest.required("kind").asText() == "windmill-value-prefix-export-1") {
            "The fixed Windmill public export contract is required."
        }
        val origins = manifest.required("origins").toList()
        val expected = (1..8).flatMap { chunk -> (0 until 224).map { chunk to it } }
        require(origins.map { identity(it) } == expected) {
            "Retain all eight chunks'192 TRAIN and32 VALIDATION requests; TEST is closed."
        }
        Files.newBufferedWriter(Path.of(arguments[1]), StandardOpenOption.CREATE_NEW).use { output
            ->
            for (subject in origins) {
                val record = project(subject)
                output.write(PurchaseJson.mapper.writeValueAsString(record))
                output.newLine()
            }
        }
    }

    internal fun identity(subject: JsonNode): Pair<Int, Int> {
        val chunk = PurchaseJson.integer(subject.required("chunk"), "chunk")
        val index = PurchaseJson.integer(subject.required("index"), "origin index")
        require(chunk in 1..8 && index in 0 until 224) {
            "Only fixed TRAIN/VALIDATION origins may be opened; TEST is forbidden."
        }
        require(subject.required("split").asText() == if (index < 192) "TRAIN" else "VALIDATION") {
            "The original whole-origin split must remain unchanged."
        }
        return chunk to index
    }

    internal fun project(subject: JsonNode): Map<String, Any?> {
        val (chunk, index) = identity(subject)
        PurchaseJson.fields(
            subject,
            setOf(
                "chunk",
                "index",
                "split",
                "path",
                "sha256",
                "status",
                "signature",
                "gameSeed",
                "chanceSeeds",
            ),
        )
        val path = Path.of(PurchaseJson.text(subject.required("path"), "origin path"))
        val digest = PurchaseJson.hash(path)
        require(digest == subject.required("sha256").asText()) { "Original origin bytes changed." }
        val captured = subject.required("status").asText() == "COLLECTED"
        require(captured || subject.required("status").asText() == "MISSING") {
            "Every original request must remain explicitly captured or missing."
        }
        val root = publicFields(path, captured)
        for (field in listOf("status", "signature", "gameSeed", "chanceSeeds")) {
            require(root[field] == subject[field]) { "Original Windmill $field binding changed." }
        }
        val request = root.required("request")
        require(PurchaseJson.integer(request.required("index"), "request index") == index) {
            "The native origin request index must match its frozen member."
        }
        val base =
            mapOf(
                "version" to WindmillValueEncoder.VERSION,
                "chunk" to chunk,
                "index" to index,
                "globalIndex" to (chunk - 1) * 256 + index,
                "split" to subject.required("split").asText(),
                "sourceSha256" to digest,
                "signature" to root.required("signature").asText(),
                "chanceSeeds" to root.required("chanceSeeds"),
                "status" to root.required("status").asText(),
            )
        if (!captured) return base + mapOf("features" to null, "incumbentCommand" to null)
        val prefix = root.required("prefix")
        val entries = PurchaseJson.strings(prefix.required("entries"), "canonical prefix")
        require(
            prefix.required("replayVerified").asBoolean() &&
                prefix.required("canonicalSha256").asText() == CardAdvantageExecutor.hash(entries)
        ) {
            "The exact previously verified canonical prefix is required."
        }
        val initial = GameState(TrainingCanonicalHeader.read(prefix.required("header")))
        val state = entries.fold(initial) { current, entry -> current.step(TextCodec.parse(entry)) }
        val provenance = root.required("provenance")
        val actor = PlayerId(PurchaseJson.text(provenance.required("actor"), "actor"))
        val seat = PurchaseJson.integer(provenance.required("seat"), "controlled seat")
        require(
            seat in state.playerIds.indices &&
                state.playerIds[seat] == actor &&
                request.required("seat") == provenance.required("seat") &&
                prefix.required("playerDecisions") ==
                    provenance.required("absolutePlayerDecision") &&
                entries.size ==
                    PurchaseJson.integer(provenance.required("prefixEntryCount"), "prefix size") &&
                !prefix.required("terminal").asBoolean()
        ) {
            "The retained actor, absolute decision and prefix boundary must agree exactly."
        }
        val view = state.viewForDecision(actor)
        val observation = BotObservations.from(state, actor)
        require(
            observation.decision == "windmill" &&
                observation.candidates.map { it.command }.toSet() == setOf("windmill", "pass")
        ) {
            "The original actor must still have exactly ATTEMPT and PASS."
        }
        require(json(WindmillActorInput(view, observation)) == root.required("actorInput")) {
            "Reconstructed public view and ordinary observation must match the original exactly."
        }
        val incumbent = provenance.required("incumbentCommand").asText()
        require(
            incumbent in setOf("windmill", "pass") &&
                provenance.required("exactPrefixAndIncumbentVerified").asBoolean()
        ) {
            "The original normal choice is retained only as an evaluation reference."
        }
        return base +
            mapOf(
                "featureNames" to WindmillValueEncoder.featureNames,
                "features" to WindmillValueEncoder.from(view, actor),
                "incumbentCommand" to incumbent,
                "prefixEntries" to entries.size,
                "actorInputSha256" to CardAdvantageExecutor.hash(root.required("actorInput")),
            )
    }

    internal fun publicFields(path: Path, captured: Boolean): JsonNode {
        val fields =
            setOf("status", "signature", "request", "gameSeed", "chanceSeeds") +
                if (captured) setOf("prefix", "provenance", "actorInput") else emptySet()
        val result = mapper.createObjectNode()
        mapper.createParser(path.toFile()).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) { "An origin must be an object." }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val name = parser.currentName()
                parser.nextToken()
                if (name in fields) result.set<JsonNode>(name, mapper.readTree(parser))
                else parser.skipChildren()
            }
            require(parser.nextToken() == null) { "No trailing origin document is permitted." }
        }
        return result
    }

    private fun json(value: Any): JsonNode =
        mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

    private fun writeNew(path: Path, value: Any) {
        Files.write(
            path,
            PurchaseJson.mapper.writeValueAsBytes(value),
            StandardOpenOption.CREATE_NEW,
        )
    }
}
