package dev.aragos.aor.training

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import java.io.InputStream

/** Streaming public-only projection. Terminal/actions-taken/logp/value fields are never decoded. */
internal object CardCalibrationPublic {
    private val valueReader =
        PurchaseJson.mapper
            .readerFor(JsonNode::class.java)
            .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun rows(
        input: InputStream,
        index: Int,
        hash: String,
        signature: String,
    ): List<Map<String, Any>> {
        require(index in 0..63 && Regex("[0-9a-f]{64}").matches(hash)) {
            "Calibration admits only the preselected64 original TRAIN worlds with exact hashes."
        }
        val result = mutableListOf<Map<String, Any>>()
        val seen = mutableSetOf<String>()
        PurchaseJson.mapper.factory.createParser(input).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) {
                "A complete original game object is required."
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(
                    parser.currentToken == JsonToken.FIELD_NAME && seen.add(parser.currentName)
                ) {
                    "Original game keys must be unique."
                }
                val field = parser.currentName
                parser.nextToken()
                when (field) {
                    "index" ->
                        require(
                            parser.currentToken == JsonToken.VALUE_NUMBER_INT &&
                                parser.intValue == index
                        ) {
                            "The original world index changed."
                        }
                    "signature" ->
                        require(
                            parser.currentToken == JsonToken.VALUE_STRING &&
                                parser.text == signature
                        ) {
                            "The completed original collection signature changed."
                        }
                    "cardDecisions",
                    "purchaseDecisions" -> {
                        val phase = if (field == "cardDecisions") "card" else "purchase"
                        result += phase(parser, phase, index, hash)
                    }
                    else -> parser.skipChildren()
                }
            }
            require(
                parser.nextToken() == null &&
                    seen.containsAll(
                        listOf("index", "signature", "cardDecisions", "purchaseDecisions")
                    )
            ) {
                "Complete original game identities and both phase arrays are required."
            }
        }
        return result
    }

    private fun phase(
        parser: JsonParser,
        phase: String,
        world: Int,
        hash: String,
    ): List<Map<String, Any>> {
        require(parser.currentToken == JsonToken.START_ARRAY) {
            "A phase requires its original ordered records."
        }
        val rows = mutableListOf<Map<String, Any>>()
        var previous = -1
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken == JsonToken.START_OBJECT) {
                "An original phase record must be an object."
            }
            if (rows.size == 32) {
                parser.skipChildren()
                continue
            }
            var number: Int? = null
            var observation: JsonNode? = null
            val fields = mutableSetOf<String>()
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(
                    parser.currentToken == JsonToken.FIELD_NAME && fields.add(parser.currentName)
                ) {
                    "Original record fields must be unique."
                }
                val field = parser.currentName
                parser.nextToken()
                when (field) {
                    "number" -> {
                        require(parser.currentToken == JsonToken.VALUE_NUMBER_INT) {
                            "An absolute PLAYER number must be integral."
                        }
                        number = parser.intValue
                    }
                    "observation" -> observation = valueReader.readValue(parser)
                    else -> parser.skipChildren()
                }
            }
            val absolute = requireNotNull(number) { "The original decision number is required." }
            require(absolute in 0..12000 && absolute > previous) {
                "Original phase records must be in strict absolute order."
            }
            previous = absolute
            val own =
                requireNotNull(observation) { "The original own/public observation is required." }
            PurchaseJson.fields(
                own,
                setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"),
            )
            val actions = own.required("actions")
            require(actions.isArray && !actions.isEmpty) {
                "A decision needs its actual offered actions."
            }
            for (action in actions) {
                PurchaseJson.fields(action, setOf("command", "features"))
            }
            if (actions.size() > 1) {
                rows +=
                    mapOf(
                        "world" to world,
                        "phase" to phase,
                        "number" to absolute,
                        "gameSha256" to hash,
                        "observation" to own,
                    )
            }
        }
        return rows
    }
}
