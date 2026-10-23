package dev.aragos.aor

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule

/** Encodes core values as JSON strings, numbers, and object keys. */
private val gameValueModule =
    SimpleModule().apply {
        addSerializer(
            PlayerId::class.java,
            object : JsonSerializer<PlayerId>() {
                override fun serialize(
                    value: PlayerId,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    output.writeString(value.value)
                }
            },
        )
        addKeySerializer(
            PlayerId::class.java,
            object : JsonSerializer<PlayerId>() {
                override fun serialize(
                    value: PlayerId,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    output.writeFieldName(value.value)
                }
            },
        )
        addSerializer(
            DieRoll::class.java,
            object : JsonSerializer<DieRoll>() {
                override fun serialize(
                    value: DieRoll,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    output.writeNumber(value.value)
                }
            },
        )
        addSerializer(
            CardKind::class.java,
            object : JsonSerializer<CardKind>() {
                override fun serialize(
                    value: CardKind,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    output.writeString(value.name.lowercase())
                }
            },
        )
        addSerializer(
            MiseryStep::class.java,
            object : JsonSerializer<MiseryStep>() {
                override fun serialize(
                    value: MiseryStep,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    val points = value.value
                    if (points == null) {
                        output.writeNull()
                    } else {
                        output.writeNumber(points)
                    }
                }
            },
        )
        addSerializer(
            ShippingCapability::class.java,
            object : JsonSerializer<ShippingCapability>() {
                override fun serialize(
                    value: ShippingCapability,
                    output: JsonGenerator,
                    provider: SerializerProvider,
                ) {
                    output.writeNumber(value.level)
                }
            },
        )
    }

internal val json = ObjectMapper().registerModule(gameValueModule)

internal fun JsonNode.field(name: String): JsonNode =
    get(name) ?: throw ServiceFailure(400, "INVALID_REQUEST")

internal fun JsonNode.string(): String =
    if (isTextual) textValue() else throw ServiceFailure(400, "INVALID_REQUEST")

internal fun JsonNode.integer(): Int =
    if (isIntegralNumber && canConvertToInt()) {
        intValue()
    } else {
        throw ServiceFailure(400, "INVALID_REQUEST")
    }

internal fun JsonNode.array(): List<JsonNode> =
    if (isArray) toList() else throw ServiceFailure(400, "INVALID_REQUEST")

internal fun JsonNode.fieldsOnly(vararg names: String) {
    if (!isObject || fieldNames().asSequence().any { name -> name !in names }) {
        throw ServiceFailure(400, "INVALID_REQUEST")
    }
}
