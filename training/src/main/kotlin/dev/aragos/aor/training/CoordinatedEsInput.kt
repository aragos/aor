package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt

/** Exact global-index bank; shard boundaries never reset seeds, seats or prescribed replays. */
internal data class CoordinatedEsBank(val nativeOffset: Int, val count: Int, val seeds: List<Int>) {
    init {
        require(
            count in setOf(256, 2048) &&
                seeds == List(count) { StrategyArena.seed(nativeOffset, it) }
        ) {
            "A coordinated bank must retain every seed from the fixed native global-index formula."
        }
    }

    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val sha256: String
        get() =
            CpePairedDigest.json(
                mapOf("nativeOffset" to nativeOffset, "count" to count, "seeds" to seeds)
            )

    companion object {
        fun read(node: JsonNode, expected: Int): CoordinatedEsBank {
            PurchaseJson.fields(node, setOf("nativeOffset", "count", "seeds"))
            val bank =
                CoordinatedEsBank(
                    PurchaseJson.integer(node["nativeOffset"], "native offset"),
                    PurchaseJson.integer(node["count"], "bank count"),
                    PurchaseJson.array(node["seeds"], "bank seeds").map {
                        PurchaseJson.integer(it, "game seed")
                    },
                )
            require(bank.count == expected) { "The stage must use its declared complete bank." }
            return bank
        }
    }
}

/**
 * Boundary helpers operate on small immutable receipts, never on fitness arrays or hidden games.
 */
internal object CoordinatedEsWire {
    fun source(path: Path): TokenPairedSubject {
        val declared = path.toAbsolutePath().normalize()
        val actual = declared.toRealPath()
        return TokenPairedSubject(actual, PurchaseJson.hash(actual), declared.toString())
    }

    fun subject(value: TokenPairedSubject): Map<String, String> =
        mapOf(
            "path" to value.path.toString(),
            "sha256" to value.sha256,
            "originalPath" to value.originalPath,
        )

    fun sha(node: JsonNode?, name: String): String {
        val value = node?.takeIf(JsonNode::isTextual)?.asText()
        require(value?.matches(Regex("[0-9a-f]{64}")) == true) {
            "$name requires a lowercase raw SHA-256."
        }
        return requireNotNull(value) { "$name is required." }
    }

    fun scope(scope: CoordinatedEsScope, projection: TokenPairedSubject): Map<String, String> =
        mapOf(
            "scopeSha256" to scope.sha256,
            "originalBundleSha256" to scope.original.source.sha256,
            "projectionSha256" to projection.sha256,
        )

    fun protocol(subject: TokenPairedSubject): JsonNode {
        subject.verify()
        val bytes = Files.readAllBytes(subject.path)
        require(
            bytes.size > 1 &&
                bytes.last() == 10.toByte() &&
                bytes[bytes.lastIndex - 1] != 10.toByte()
        ) {
            "The shared protocol requires exactly one framing LF."
        }
        val node = PurchaseJson.read(subject.path)
        require(
            node["kind"]?.asText() == "coordinated-es-campaign-1" &&
                node["scienceSeconds"]?.asInt() == 86400 &&
                node["auditSeconds"]?.asInt() == 900 &&
                node["budget"]?.get("maximumTerminalExecutions")?.asInt() == 139306 &&
                node["gameLimits"]?.get("maximumSeconds")?.asInt() == 300 &&
                node["gameLimits"]?.get("maximumDecisions")?.asInt() == 12000 &&
                node["automaticRetry"]?.isBoolean == true &&
                !node["automaticRetry"].booleanValue() &&
                node["automaticSuccessor"]?.isBoolean == true &&
                !node["automaticSuccessor"].booleanValue()
        ) {
            "Only the fixed coordinated24-hour, bounded-game, once-only protocol is admitted."
        }
        return node
    }

    fun original(node: JsonNode, scope: CoordinatedEsScope, projection: TokenPairedSubject) {
        val original = node.required("original")
        require(
            original["bundleSha256"]?.asText() == scope.original.source.sha256 &&
                original["scopeSha256"]?.asText() == scope.sha256 &&
                original["ecologySha256"]?.asText() == scope.ecologySha256 &&
                original["projectionSha256"]?.asText() == projection.sha256
        ) {
            "The protocol must bind the actual complete original scope, ecology and projection."
        }
        CoordinatedEsHead.entries.forEach { head ->
            val subject =
                requireNotNull(scope.original.subjects[head]) { "Original head required." }
            val dictionary =
                requireNotNull(scope.dictionaries[head]) { "Original dictionary required." }
            require(
                original["heads"]?.get(head.name)?.get("actorSha256")?.asText() == subject.sha256 &&
                    original["heads"]?.get(head.name)?.get("dictionarySha256")?.asText() ==
                        dictionary.sha256()
            ) {
                "Every original actor and complete dictionary must match the shared protocol."
            }
        }
        val record = PurchaseJson.read(projection.path)
        require(
            record["kind"]?.asText() == "coordinated-es-projection-1" &&
                record["scopeSha256"]?.asText() == scope.sha256 &&
                record["originalBundleSha256"]?.asText() == scope.original.source.sha256
        ) {
            "Projection must bind the actual original role and scope before execution."
        }
        projection.verify()
    }

    fun center(node: JsonNode, expectedScope: Map<String, String>, generation: Int) {
        PurchaseJson.fields(node, setOf("kind", "scope", "generation", "coordinates"))
        require(
            node["kind"]?.asText() == "coordinated-es-center-1" &&
                node["scope"] == PurchaseJson.mapper.valueToTree<JsonNode>(expectedScope) &&
                PurchaseJson.integer(node["generation"], "center generation") == generation
        ) {
            "The center must be the exact immediate generation in this complete original scope."
        }
        val coordinates = PurchaseJson.array(node["coordinates"], "center coordinates")
        require(
            coordinates.size == 256 &&
                coordinates.all { it.isNumber && it.doubleValue().isFinite() } &&
                rootMeanSquare(coordinates.map(JsonNode::doubleValue)) <= 4.0 + 2e-15 &&
                (generation != 0 || coordinates.all { it.doubleValue() == 0.0 })
        ) {
            "The fixed256-coordinate center must be finite, initially zero and within RMS4."
        }
    }

    /** Scaled compensated summation preserves the numeric owner's projected-radius boundary. */
    private fun rootMeanSquare(values: List<Double>): Double {
        val scale = values.maxOf(::abs)
        if (scale == 0.0) return 0.0
        var sum = 0.0
        var correction = 0.0
        values.forEach { value ->
            val normalized = value / scale
            val square = normalized * normalized
            val next = sum + square
            correction += if (sum >= square) (sum - next) + square else (square - next) + sum
            sum = next
        }
        return scale * sqrt((sum + correction) / values.size)
    }

    fun historical(
        subject: TokenPairedSubject,
        protocol: TokenPairedSubject,
        scope: CoordinatedEsScope,
    ) {
        subject.verify()
        val node = PurchaseJson.read(subject.path)
        require(
            node["kind"]?.asText() == "coordinated-es-historical-admission-1" &&
                node["complete"]?.booleanValue() == true &&
                node["passed"]?.booleanValue() == true &&
                node["protocolSha256"]?.asText() == protocol.sha256 &&
                node["scopeSha256"]?.asText() == scope.sha256 &&
                node["ecologySha256"]?.asText() == scope.ecologySha256 &&
                node["originalBundleSha256"]?.asText() == scope.original.source.sha256 &&
                node["scientificTerminalExecutions"]?.asInt() == 2 &&
                node["zeroFailures"]?.booleanValue() == true
        ) {
            "Both exact historical zero-role controls must pass before any fresh game."
        }
        val controls = PurchaseJson.array(node["controls"], "historical controls")
        require(
            controls.size == 2 &&
                controls.map { it["globalIndex"]?.asInt() } == listOf(0, 4095) &&
                controls.all {
                    it["index"]?.isIntegralNumber == true &&
                        it["index"].canConvertToInt() &&
                        it["index"].intValue() >= 0 &&
                        it["side"]?.asText() == "candidate" &&
                        it["canonicalVerified"]?.booleanValue() == true
                }
        ) {
            "Historical admission must retain the two predetermined family60 candidate indices."
        }
    }
}

internal enum class CoordinatedEsStage {
    CONTROLS,
    POPULATION,
}

/**
 * One exact32-world shard; source paths are provenance, scientific identity excludes host/range.
 */
internal class CoordinatedEsInput
internal constructor(
    val source: TokenPairedSubject,
    val subjects: Map<String, TokenPairedSubject?>,
    val scope: CoordinatedEsScope,
    val stage: CoordinatedEsStage,
    val generation: Int,
    val globalStart: Int,
    val count: Int,
    val bank: CoordinatedEsBank,
    val calibration: CoordinatedEsCalibration?,
    val population: CoordinatedEsPopulation?,
) {
    init {
        require(
            generation in 1..16 &&
                count == 32 &&
                globalStart in 0..224 &&
                globalStart % 32 == 0 &&
                bank.count == 256
        ) {
            "The native shard retains its fixed generation, bank and global32-world range."
        }
        require(
            subjects.keys == SUBJECTS &&
                (population != null) == (stage == CoordinatedEsStage.POPULATION)
        ) {
            "A native shard must retain every named subject slot and its exact stage population."
        }
    }

    val indices = globalStart until globalStart + count
    val protocol = required("protocol")
    val arena = scope.arena.copy(pairsPerConfiguration = bank.count, seed = bank.nativeOffset)
    val roles = population?.members?.keys?.toList() ?: listOf("RAW_G3")
    val scienceIdentity =
        mapOf(
            "kind" to VERSION,
            "stage" to stage,
            "generation" to generation,
            "scopeSha256" to scope.sha256,
            "ecologySha256" to scope.ecologySha256,
            "bank" to bank,
            "subjects" to subjects.filterKeys { it != "arena" }.mapValues { it.value?.sha256 },
        )
    val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    val signature = trainingSignature("$VERSION:${source.sha256}:${scienceSha256}")
    val specifications: Map<String, ArenaSpecification> =
        population?.members?.mapValues { (_, bundle) ->
            arena.copy(
                coordinatedEs =
                    ArenaCoordinatedPolicy.calibrated(
                        bundle,
                        scope.original,
                        scope.baseline,
                        requireNotNull(calibration) { "Population calibration required." },
                    )
            )
        } ?: mapOf("RAW_G3" to arena)

    fun required(name: String): TokenPairedSubject =
        requireNotNull(subjects[name]) { "$name subject is required." }

    fun canonical(role: String, index: Int): Boolean =
        stage == CoordinatedEsStage.POPULATION &&
            (index == 0 && role == "PLUS-${(generation - 1).toString().padStart(2, '0')}" ||
                index == 255 && role == "MINUS-${(generation - 1).toString().padStart(2, '0')}")

    fun inputPins(): Map<String, String> = buildMap {
        putAll(scope.inputPins())
        put(source.path.toString(), source.sha256)
        subjects.values.filterNotNull().forEach {
            it.verify()
            put(it.path.toString(), it.sha256)
        }
        population?.members?.values?.forEach { bundle ->
            bundle.verify()
            put(bundle.source.path.toString(), bundle.source.sha256)
            bundle.subjects.values.forEach { put(it.path.toString(), it.sha256) }
        }
    }

    fun metadata(): Map<String, Any?> =
        mapOf(
            "kind" to "coordinated-es-prepared-1",
            "signature" to signature,
            "scienceSha256" to scienceSha256,
            "scopeSha256" to scope.sha256,
            "ecologySha256" to scope.ecologySha256,
            "stage" to stage,
            "generation" to generation,
            "globalStart" to globalStart,
            "count" to count,
            "bank" to bank,
            "dictionaries" to
                scope.dictionaries.entries.associate { it.key.name to it.value.wire() },
            "policyBindings" to scope.policyBindings,
            "subjects" to subjects.mapValues { it.value?.let(CoordinatedEsWire::subject) },
            "source" to source.metadata("source.json"),
            "arena" to scope.arenaSource.metadata("arena.json"),
            "originalBundle" to CoordinatedEsWire.subject(scope.original.source),
            "inputPins" to inputPins(),
        )

    companion object {
        const val VERSION = "coordinated-es-generation-1"
        val SUBJECTS =
            setOf(
                "protocol",
                "arena",
                "originalBundle",
                "projection",
                "center",
                "directions",
                "population",
                "calibration",
                "previousUpdate",
                "historical",
            )

        fun read(path: Path): CoordinatedEsInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                SUBJECTS + setOf("kind", "stage", "generation", "globalStart", "count", "bank"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "An explicit coordinated generation source is required."
            }
            val stage =
                CoordinatedEsStage.entries.singleOrNull { it.name == node["stage"]?.asText() }
                    ?: error("Only explicit CONTROLS or POPULATION stages are supported.")
            val generation = PurchaseJson.integer(node["generation"], "generation")
            val start = PurchaseJson.integer(node["globalStart"], "global start")
            val count = PurchaseJson.integer(node["count"], "shard count")
            require(generation in 1..16 && count == 32 && start in 0..224 && start % 32 == 0) {
                "Generation shards must retain their fixed32 global indices within1..16."
            }
            val subjects = SUBJECTS.associateWith { name ->
                node[name].takeUnless(JsonNode::isNull)?.let {
                    TokenPairedSubject.read(it, source.path.parent)
                }
            }
            fun required(name: String) =
                requireNotNull(subjects[name]) { "$name is required for this stage." }
            require(
                (subjects["population"] != null) == (stage == CoordinatedEsStage.POPULATION) &&
                    (subjects["calibration"] == null) ==
                        (generation == 1 && stage == CoordinatedEsStage.CONTROLS) &&
                    (subjects["previousUpdate"] == null) == (generation == 1)
            ) {
                "Only G1 controls omit calibration; only G1 omits previous update; controls omit population."
            }
            val protocol = required("protocol")
            val plan = CoordinatedEsWire.protocol(protocol)
            val scope = CoordinatedEsScope.read(required("arena"), required("originalBundle"))
            val projection = required("projection")
            CoordinatedEsWire.original(plan, scope, projection)
            CoordinatedEsWire.historical(required("historical"), protocol, scope)
            val bank = CoordinatedEsBank.read(node["bank"], 256)
            val generations = PurchaseJson.array(plan["generations"], "protocol generations")
            require(
                generations.size == 16 &&
                    generations[generation - 1]["generation"]?.asInt() == generation &&
                    generations[generation - 1]["worldBank"] == node["bank"]
            ) {
                "A shard cannot substitute or reset its complete protocol world bank."
            }
            val expectedScope = CoordinatedEsWire.scope(scope, projection)
            val center = PurchaseJson.read(required("center").path)
            CoordinatedEsWire.center(center, expectedScope, generation - 1)
            val directions = PurchaseJson.read(required("directions").path)
            PurchaseJson.fields(directions, setOf("kind", "scope", "generation", "vectors"))
            val vectors = PurchaseJson.array(directions["vectors"], "directions")
            require(
                directions["kind"]?.asText() == "coordinated-es-directions-1" &&
                    directions["scope"] == center["scope"] &&
                    directions["generation"]?.asInt() == generation &&
                    vectors.size == 16 &&
                    vectors.all { row ->
                        row.isArray &&
                            row.size() == 256 &&
                            row.all { it.isNumber && it.doubleValue().isFinite() }
                    }
            ) {
                "All16 finite signed256-coordinate directions must bind the immediate center scope."
            }
            subjects["previousUpdate"]?.let { previous ->
                val update = PurchaseJson.read(previous.path)
                require(
                    update["kind"]?.asText() == "coordinated-es-update-1" &&
                        update["complete"]?.booleanValue() == true &&
                        update["protocolSha256"]?.asText() == protocol.sha256 &&
                        update["center"] == center
                ) {
                    "The next generation must consume the exact complete previous mean update."
                }
            }
            val calibration =
                subjects["calibration"]?.let { CoordinatedEsCalibration.read(it, scope.original) }
            calibration?.let {
                require(
                    it.record["protocolSha256"]?.asText() == protocol.sha256 &&
                        it.record["projectionSha256"]?.asText() == projection.sha256
                ) {
                    "The executable calibration must bind this exact protocol and projection."
                }
            }
            val population =
                subjects["population"]?.let {
                    CoordinatedEsPopulation.read(
                        it,
                        scope,
                        protocol,
                        projection,
                        required("center"),
                        required("directions"),
                        requireNotNull(calibration) { "Population calibration." },
                        generation,
                        bank,
                    )
                }
            source.verify()
            return CoordinatedEsInput(
                source,
                subjects,
                scope,
                stage,
                generation,
                start,
                count,
                bank,
                calibration,
                population,
            )
        }
    }
}
