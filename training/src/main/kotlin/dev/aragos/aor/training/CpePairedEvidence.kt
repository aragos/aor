package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/** Pure retained-evidence checks; no actor forward, fitting or hidden-world reconstruction. */
internal class CpePairedEvidence(private val input: CpePairedInput) {
    private val dictionary =
        requireNotNull(input.dictionaries[input.head]) { "Head dictionary is required." }
    private val stateNames = dictionary.stateFeatureNames.toSet()
    private val actionNames = dictionary.actionFeatureNames.toSet()

    fun validate(origin: JsonNode, index: Int, reproduction: Boolean = false) {
        require(index in input.range.indices) { "C/P/E evidence must belong to its source shard." }
        require(
            origin["kind"]?.asText() == "cpe-paired-origin-1" &&
                origin["signature"]?.asText() == input.signature &&
                origin["bundleScopeSha256"]?.asText() == input.bundleScopeSha256 &&
                origin["scopeSha256"]?.asText() == input.scopeSha256 &&
                origin["scienceSha256"]?.asText() == input.scienceSha256 &&
                integer(origin, "iteration") == input.iteration &&
                origin["request"] == wire(input.request(index)) &&
                origin["streams"] == wire(input.origins[index])
        ) {
            "C/P/E evidence must retain its complete source, head, request and stream identities."
        }
        val prefix = origin.required("prefix")
        val backbone = origin.required("backbone")
        for (canonical in listOf(prefix, backbone)) {
            require(
                canonical["replayVerified"]?.booleanValue() == true &&
                    canonical["canonicalSha256"]?.asText() ==
                        CardAdvantageExecutor.hash(
                            PurchaseJson.strings(canonical["entries"], "entries")
                        )
            ) {
                "Canonical history hashes must bind the verified exact entries."
            }
        }
        require(
            backbone["terminal"]?.booleanValue() == true &&
                prefix["header"] == backbone["header"] &&
                backbone["entries"].take(prefix["entries"].size()) == prefix["entries"].toList()
        ) {
            "Every C/P/E origin requires one complete backbone and its exact selected prefix."
        }
        val decisions =
            terminal(origin["backboneTerminal"], index, input.origins[index].gameSeed.toLong())
        require(decisions == integer(backbone, "playerDecisions")) {
            "Backbone decision counts must match the terminal."
        }
        val anchors = PurchaseJson.array(origin["anchors"], "anchors")
        require(input.iteration == 1 && index < 512 || anchors.isEmpty()) {
            "Only first-iteration TRAIN backbones supply fixed anchors."
        }
        val numbers = anchors.map { integer(it, "number") }
        require(numbers == numbers.sorted().distinct() && numbers.all { it in 0 until decisions }) {
            "Anchor identities must be distinct chronological decisions."
        }
        anchors.forEach { anchor ->
            require(
                integer(anchor, "iteration") == input.iteration &&
                    integer(anchor, "globalIndex") == index &&
                    anchor["head"]?.asText() == input.head.name
            ) {
                "Anchors must retain the original head/world identity."
            }
            observation(anchor["observation"])
            probabilities(anchor["originalProbabilities"], anchor["observation"]["actions"].size())
        }
        val observed = integer(origin, "observedWindows")
        val branching = integer(origin, "branchingWindows")
        require(branching in 0..observed && observed <= decisions) {
            "Public window counts must fit the completed backbone."
        }
        val root = origin.required("publicRoot")
        val arms = PurchaseJson.array(origin["arms"], "arms")
        if (branching == 0) {
            require(
                origin["status"]?.asText() == CpePairedStatus.MISSING.name &&
                    root.isNull &&
                    arms.isEmpty() &&
                    prefix == backbone &&
                    origin["selectedOrdinal"].isNull &&
                    origin["missingReason"]?.asText() == "NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH"
            ) {
                "Missing roots retain their complete backbone without invented alternatives."
            }
            return
        }
        val random = Random(input.origins[index].reservoirSeed)
        val expectedOrdinal =
            (1..branching).fold(0) { selected, ordinal ->
                if (random.nextInt(ordinal) == 0) ordinal else selected
            }
        require(
            origin["status"]?.asText() == CpePairedStatus.READY.name &&
                !root.isNull &&
                origin["missingReason"].isNull &&
                integer(origin, "selectedOrdinal") == expectedOrdinal
        ) {
            "The selected root must match the public branching reservoir draw."
        }
        val count = observation(root["observation"])
        require(count > 1) { "A C/P/E root must branch." }
        val reference = integer(root, "referenceIndex")
        val proposal =
            CpePairedProposals.sample(count, reference, input.origins[index].proposalSeed)
        require(
            reference == integer(root, "sampledCommandIndex") &&
                root["proposedIndices"].map(JsonNode::intValue) == proposal.indices &&
                PurchaseJson.number(root["inclusionProbability"], "inclusion") ==
                    proposal.inclusionProbability
        ) {
            "The delta reference is the actual sample and proposals have exact uniform inclusion."
        }
        val probabilities = probabilities(root["currentProbabilities"], count)
        probabilities(root["originalProbabilities"], count)
        val number = integer(root, "number")
        val batch = integer(root, "batchStartNumber")
        require(
            number in 0 until decisions &&
                batch >= 0 &&
                number - batch in 0..3 &&
                batch == integer(prefix, "playerDecisions")
        ) {
            "Root numbers must preserve the original absolute pre-batch offset."
        }
        val botSeed =
            simulationPolicySeed(input.origins[index].gameSeed, batch, input.request(index).seat)
        val actorSeed =
            when (input.head) {
                CpeHead.CARD -> CardPolicy.actorSeed(botSeed)
                CpeHead.PURCHASE -> PurchasePolicy.actorSeed(botSeed)
                CpeHead.EXPANSION -> TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, botSeed)
            }
        require(
            integer(root, "botSeed") == botSeed &&
                integer(root, "actorSeed") == actorSeed &&
                abs(
                    exp(PurchaseJson.number(root["behaviorLogProbability"], "log probability")) -
                        probabilities[reference]
                ) < 1e-12 &&
                PurchaseJson.number(root["behaviorValue"], "value") in 0.0..1.0
        ) {
            "Actor seed and recorded likelihood must belong to this actual current-policy sample."
        }
        require(
            if (reproduction) arms.isEmpty()
            else arms.map { integer(it, "actionIndex") } == proposal.indices
        ) {
            "Only a declared reproduction may omit the complete paired arm matrix."
        }
        arms.forEach { arm ->
            val action = integer(arm, "actionIndex")
            require(
                arm["command"] == root["observation"]["actions"][action]["command"] &&
                    PurchaseJson.number(arm["inclusionProbability"], "inclusion") ==
                        if (action == reference) 1.0 else proposal.inclusionProbability
            ) {
                "Each arm must preserve its offered command and sampling inclusion."
            }
            val outcomes = PurchaseJson.array(arm["outcomes"], "outcomes")
            require(outcomes.size == 2) {
                "Every proposed action requires exactly two paired futures."
            }
            outcomes.forEachIndexed { replicate, outcome ->
                require(
                    integer(outcome, "replicate") == replicate &&
                        outcome["replayVerified"]?.booleanValue() == true &&
                        outcome["canonicalSha256"]?.asText() ==
                            CardAdvantageExecutor.hash(
                                PurchaseJson.strings(
                                    outcome["canonicalEntries"],
                                    "canonical entries",
                                )
                            )
                ) {
                    "Each paired suffix must retain its exact replicate and replay proof."
                }
                terminal(outcome["terminal"], index, input.origins[index].future[replicate])
            }
        }
    }

    private fun observation(node: JsonNode): Int {
        PurchaseJson.fields(
            node,
            setOf("encoderVersion", "stateFeatures", "actions", "baselineIndex"),
        )
        require(node["encoderVersion"]?.asText() == dictionary.encoderVersion) {
            "A public row must use its assigned head encoder."
        }
        fun features(value: JsonNode, names: Set<String>) {
            require(
                value.isObject &&
                    value.properties().all { (key, feature) ->
                        key in names && PurchaseJson.number(feature, key) in -16.0..16.0
                    }
            ) {
                "Public sparse features must belong to the complete frozen dictionary."
            }
        }
        features(node["stateFeatures"], stateNames)
        val actions = PurchaseJson.array(node["actions"], "actions")
        val maximum = if (input.head == CpeHead.PURCHASE) 128 else 1024
        require(actions.size in 1..maximum && integer(node, "baselineIndex") in actions.indices) {
            "The original prior index must belong to the full bounded offer."
        }
        val commands = actions.map { action ->
            PurchaseJson.fields(action, setOf("command", "features"))
            features(action["features"], actionNames)
            PurchaseJson.text(action["command"], "command")
        }
        require(commands == commands.sorted().distinct()) {
            "Public commands must retain canonical distinct order."
        }
        return actions.size
    }

    private fun terminal(value: JsonNode, index: Int, seed: Long): Int {
        PurchaseJson.fields(
            value,
            setOf("reward", "winShare", "rankReward", "scores", "decisions", "continuationSeed"),
        )
        require(
            value["continuationSeed"].isIntegralNumber &&
                value["continuationSeed"].canConvertToLong() &&
                value["continuationSeed"].longValue() == seed
        ) {
            "Terminal outcomes must retain their exact declared future stream."
        }
        val adapted = value.deepCopy<ObjectNode>()
        adapted.set<JsonNode>("gameDecisions", adapted.required("decisions"))
        adapted.remove(listOf("decisions", "continuationSeed"))
        return PurchaseTrajectoryValidation.terminal(adapted, input.request(index).seat, 4)
    }

    private fun probabilities(node: JsonNode, count: Int): List<Double> =
        PurchaseJson.numbers(node, "probabilities").also { values ->
            require(
                values.size == count &&
                    values.all { it > 0.0 && it <= 1.0 } &&
                    abs(values.sum() - 1.0) < 1e-9
            ) {
                "Each public probability vector must retain complete finite support."
            }
        }

    private fun integer(node: JsonNode, field: String): Int =
        PurchaseJson.integer(node.required(field), field)

    private fun wire(value: Any): JsonNode =
        PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))
}
