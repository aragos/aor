package dev.aragos.aor.training

import dev.aragos.aor.GameState
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservations
import java.nio.file.Files
import java.nio.file.Path

/** Public states and executor canonical evidence have different files and independent hashes. */
internal class PurchasePlanPublicStore(private val input: PurchasePlanInput, val directory: Path) {
    private val artifacts = TrainingArtifacts(directory)

    init {
        Files.list(directory).use { paths ->
            require(paths.findAny().isEmpty) {
                "Plan collection requires a new empty output directory."
            }
        }
        input.pins.entries.forEachIndexed { index, (path, hash) ->
            artifacts.ensureFileSnapshot(path, hash, "input-$index.json")
        }
        artifacts.write("manifest.json", input.metadata())
    }

    fun save(result: PurchasePlanPublicResult) {
        val index = result.public.originIndex
        require(
            result.backbone.index == index &&
                artifacts.read("public-$index.json") == null &&
                artifacts.read("backbone-$index.json") == null
        ) {
            "Completed public origins cannot be overwritten or assigned a different backbone."
        }
        artifacts.write("backbone-$index.json", result.backbone)
        artifacts.write("public-$index.json", result.public)
        verify(index)
    }

    private fun verify(index: Int): PublicPurchasePlanOrigin {
        val public =
            PurchasePlanWire.origin(PurchaseJson.read(directory.resolve("public-$index.json")))
        val backbone = PurchaseJson.read(directory.resolve("backbone-$index.json"))
        require(
            public.originIndex == index &&
                backbone.required("index").intValue() == index &&
                backbone.required("gameSeed").intValue() == input.study.collection.seeds[index] &&
                backbone.required("seat").intValue() == input.study.collection.seat(index) &&
                backbone.required("assignedEpoch").intValue() == input.study.epoch(index)
        ) {
            "Public and canonical evidence must bind the exact assigned origin."
        }
        TrainingCanonicalHeader.read(backbone.required("header"))
        require(
            (public.status == PurchasePlanOriginStatus.MISSING) ==
                backbone.required("rootNumber").isNull
        ) {
            "Only a captured actual root may carry public plan evidence."
        }
        return public
    }

    fun finish() {
        input.unchanged()
        val counts = mutableMapOf<String, Int>()
        for (index in 0 until 256) {
            val public = verify(index)
            counts[public.status.name] = (counts[public.status.name] ?: 0) + 1
            for (plan in public.plans) {
                val key = plan.exclusion?.name ?: "SUPPORTED_PLANS"
                counts[key] = (counts[key] ?: 0) + 1
                if (plan.aliasOf != null) counts["ALIASES"] = (counts["ALIASES"] ?: 0) + 1
            }
        }
        val publicPins =
            (0 until 256).associate {
                it.toString() to PurchaseJson.hash(directory.resolve("public-$it.json"))
            }
        val names =
            listOf("manifest.json") +
                (0 until input.pins.size).map { "input-$it.json" } +
                (0 until 256).flatMap { listOf("public-$it.json", "backbone-$it.json") }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "purchase-plan-public-complete-1",
                "signature" to input.signature,
                "sourceSha256" to input.sourceSha256,
                "origins" to 256,
                "counts" to counts,
                "terminalTails" to 0,
                "publicSha256" to publicPins,
                "artifactSha256" to
                    names.associateWith { PurchaseJson.hash(directory.resolve(it)) },
            ),
        )
    }
}

/**
 * Only a completed public bank and a separately persisted fixed-predictor decision bank admit
 * continuation work. This boundary has no fit, choice regeneration or outcome-dependent fallback.
 */
internal class PurchasePlanTailAdmission(
    private val input: PurchasePlanInput,
    val publicDirectory: Path,
    val predictions: Path,
) {
    val finishedPath = publicDirectory.resolve("finished.json")
    val finished = PurchaseJson.read(finishedPath)
    private val selected = PurchaseJson.read(predictions)
    val pins: Map<Path, String>

    init {
        input.unchanged()
        require(
            PurchaseJson.read(publicDirectory.resolve("manifest.json")) ==
                PurchasePlanWire.wire(input.metadata())
        ) {
            "Public plans must retain the exact source, roles and runtime metadata."
        }
        require(
            finished.required("kind").asText() == "purchase-plan-public-complete-1" &&
                finished.required("signature").asText() == input.signature &&
                finished.required("origins").intValue() == 256 &&
                finished.required("terminalTails").intValue() == 0
        ) {
            "Tail admission requires all256 completed public origins and no prior terminal tails."
        }
        val artifactPins =
            finished.required("artifactSha256").properties().associate { (name, hash) ->
                require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
                    "Public artifact paths must be simple filenames."
                }
                publicDirectory.resolve(name) to hash.asText()
            }
        val expectedNames =
            listOf("manifest.json") +
                (0 until input.pins.size).map { "input-$it.json" } +
                (0 until 256).flatMap { listOf("public-$it.json", "backbone-$it.json") }
        require(
            artifactPins.keys == expectedNames.map { publicDirectory.resolve(it) }.toSet() &&
                finished.required("publicSha256").isObject &&
                finished.required("publicSha256").properties().map { it.key }.toSet() ==
                    (0 until 256).map(Int::toString).toSet()
        ) {
            "Completed evidence must bind every source snapshot, public origin and private backbone exactly once."
        }
        PurchaseJson.fields(
            selected,
            setOf(
                "kind",
                "signature",
                "publicFinishedSha256",
                "predictorDescriptorSha256",
                "publicSha256",
                "decisions",
                "allPredictionsComplete",
                "terminalTailsStarted",
            ),
        )
        require(
            selected.required("kind").asText() == "purchase-plan-predictions-1" &&
                selected.required("signature").asText() == input.signature &&
                selected.required("publicFinishedSha256").asText() ==
                    PurchaseJson.hash(finishedPath) &&
                selected.required("predictorDescriptorSha256").asText() ==
                    PurchaseJson.hash(input.predictorDescriptor) &&
                selected.required("publicSha256") == finished.required("publicSha256") &&
                selected.required("allPredictionsComplete").isBoolean &&
                selected.required("allPredictionsComplete").booleanValue() &&
                selected.required("terminalTailsStarted").isBoolean &&
                !selected.required("terminalTailsStarted").booleanValue() &&
                selected.required("decisions").isArray &&
                selected.required("decisions").size() == 256
        ) {
            "Every fixed prediction must be sealed against the complete public bank before any tail."
        }
        pins =
            artifactPins +
                mapOf(
                    finishedPath to PurchaseJson.hash(finishedPath),
                    predictions to PurchaseJson.hash(predictions),
                )
        unchanged()
        for (index in 0 until 256) validateDecision(index)
    }

    fun unchanged() {
        input.unchanged()
        require(
            pins.all { (path, hash) ->
                Files.isRegularFile(path) &&
                    !Files.isSymbolicLink(path) &&
                    PurchaseJson.hash(path) == hash
            }
        ) {
            "Public plans, canonical evidence or frozen predictions changed after admission."
        }
    }

    fun public(index: Int): PublicPurchasePlanOrigin {
        val path = publicDirectory.resolve("public-$index.json")
        require(
            PurchaseJson.hash(path) ==
                finished.required("publicSha256").required(index.toString()).asText()
        ) {
            "The exact public origin bytes must match the completed bank."
        }
        return PurchasePlanWire.origin(PurchaseJson.read(path))
    }

    fun root(index: Int, deadline: Long): Pair<GameState, Int> {
        val node = PurchaseJson.read(publicDirectory.resolve("backbone-$index.json"))
        val number = PurchaseJson.integer(node.required("rootNumber"), "rootNumber")
        val count = PurchaseJson.integer(node.required("rootEntryCount"), "rootEntryCount")
        val entries = PurchaseJson.strings(node.required("entries"), "canonical entries")
        require(number in 0 until 12000 && count in entries.indices) {
            "Captured root offsets must be actual canonical positions."
        }
        val state =
            entries.take(count).fold(
                GameState(TrainingCanonicalHeader.read(node.required("header")))
            ) { state, text ->
                checkTokenValueDeadline(deadline)
                state.step(TextCodec.parse(text))
            }
        val actor = state.playerIds[input.study.collection.seat(index)]
        require(
            PurchasePlanWire.wire(BotObservations.from(state, actor)) ==
                node.required("rootObservation") &&
                entries[count] == "${actor.value}: ${node.required("actualRootCommand").asText()}"
        ) {
            "The original canonical root and its actual normal command must reconstruct exactly."
        }
        PurchasePlanReference.verify(
            state,
            actor,
            public(index).plans.first(),
            entries,
            count,
            deadline,
        )
        return state to number
    }

    private fun validateDecision(index: Int) {
        val origin = public(index)
        val decision = selected.required("decisions")[index]
        PurchaseJson.fields(
            decision,
            setOf(
                "kind",
                "originIndex",
                "status",
                "selectedIndex",
                "fallbackReason",
                "predictorDescriptorSha256",
                "checkpointSha256",
                "dictionarySha256",
                "plans",
            ),
        )
        val descriptor = PurchaseJson.read(input.predictorDescriptor)
        require(
            decision.required("kind").asText() == "purchase-plan-ranking-decision-1" &&
                decision.required("originIndex").intValue() == index &&
                decision.required("status").asText() == origin.status.name &&
                decision.required("predictorDescriptorSha256").asText() ==
                    PurchaseJson.hash(input.predictorDescriptor) &&
                decision.required("checkpointSha256").asText() ==
                    descriptor.required("checkpointSha256").asText() &&
                decision.required("dictionarySha256").asText() ==
                    descriptor.required("dictionarySha256").asText()
        ) {
            "Each ranking decision must retain the same frozen predictor and exact public origin."
        }
        val values = PurchaseJson.array(decision.required("plans"), "ranked plans")
        require(values.size == origin.plans.size) {
            "Every logical plan requires a retained prediction or exclusion."
        }
        for ((plan, scored) in origin.plans.zip(values)) {
            PurchaseJson.fields(
                scored,
                setOf("index", "status", "exclusion", "aliasOf", "value", "valueSource"),
            )
            require(
                scored.required("index").intValue() == plan.index &&
                    scored.required("status").asText() == plan.status.name &&
                    scored.required("aliasOf") == PurchasePlanWire.wire(plan.aliasOf) &&
                    scored.required("exclusion") == PurchasePlanWire.wire(plan.exclusion)
            ) {
                "Prediction metadata must retain every public logical plan unchanged."
            }
            val value = scored.required("value")
            val expectedValue = plan.leaf?.exactValue
            if (
                origin.status != PurchasePlanOriginStatus.READY ||
                    plan.status != PurchasePlanStatus.SUPPORTED
            ) {
                require(value.isNull && scored.required("valueSource").isNull) {
                    "Unsupported roots/plans cannot acquire invented values."
                }
            } else {
                val numeric = PurchaseJson.number(value, "predicted value")
                require(
                    numeric in 0.0..1.0 && (expectedValue == null || numeric == expectedValue)
                ) {
                    "Predictions must preserve normalized values and exact terminal bypasses."
                }
                require(
                    scored.required("valueSource").asText() == (plan.leaf?.exactReason ?: "CRITIC")
                ) {
                    "The prediction must honestly distinguish exact bypass from critic inference."
                }
                plan.aliasOf?.let {
                    require(value == values[it].required("value")) {
                        "Identical complete plans must retain exactly identical predictor values."
                    }
                }
            }
        }
        val expected =
            when (origin.status) {
                PurchasePlanOriginStatus.MISSING -> null
                PurchasePlanOriginStatus.INCUMBENT_UNSUPPORTED -> 0
                PurchasePlanOriginStatus.READY -> {
                    var best = 0
                    for (plan in origin.plans.drop(1)) {
                        if (
                            plan.status == PurchasePlanStatus.SUPPORTED &&
                                values[plan.index].required("value").doubleValue() >
                                    values[best].required("value").doubleValue()
                        ) {
                            best = plan.index
                        }
                    }
                    best
                }
            }
        require(decision.required("selectedIndex") == PurchasePlanWire.wire(expected)) {
            "Selection must be a strict fixed-value improvement, with incumbent/canonical ties."
        }
        require(
            decision.required("fallbackReason") ==
                PurchasePlanWire.wire(
                    origin.status.takeUnless { it == PurchasePlanOriginStatus.READY }?.name
                )
        ) {
            "Only the declared missing/unsupported-incumbent states trigger normal fallback."
        }
    }
}
