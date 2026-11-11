package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Fixed historical parent and metadata-only branch requests; validation never opens a game. */
internal data class TokenOrderNeighborInput(
    val source: Path,
    val sourceSha256: String,
    val parentDirectory: Path,
    val parent: TokenOrderReproduction.Input,
    val requests: List<JsonNode>,
    val replayOrigins: List<Int>,
) {
    fun recorded(index: Int): JsonNode =
        PurchaseJson.read(parentDirectory.resolve("origin-$index.json"))

    fun missing(index: Int): List<Int> = requests[index].required("newIndices").map(JsonNode::asInt)

    companion object {
        const val KIND = "token-order-reference-neighbor-augmentation-1"

        fun read(source: Path, hash: String): TokenOrderNeighborInput {
            require(PurchaseJson.hash(source) == hash) { "Token augmentation source changed." }
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "kind",
                    "parentDirectory",
                    "parentFinishedSha256",
                    "parentCompletionAudit",
                    "parentRuntime",
                    "parentSource",
                    "branchPlan",
                    "replayOrigins",
                ),
            )
            require(
                node.required("schemaVersion").asInt() == 1 &&
                    node.required("kind").asText() == KIND
            ) {
                "Token augmentation requires its explicit versioned source."
            }
            fun pinned(entry: JsonNode): Path {
                PurchaseJson.fields(entry, setOf("path", "sha256"))
                val path = source.parent.resolve(entry.required("path").asText()).toRealPath()
                require(PurchaseJson.hash(path) == entry.required("sha256").asText()) {
                    "A frozen augmentation prerequisite changed: $path."
                }
                return path
            }
            val directory =
                source.parent.resolve(node.required("parentDirectory").asText()).toRealPath()
            require(
                PurchaseJson.hash(directory.resolve("finished.json")) ==
                    node.required("parentFinishedSha256").asText()
            ) {
                "The completed fixed-T parent changed."
            }
            val audit = PurchaseJson.read(pinned(node.required("parentCompletionAudit")))
            require(
                audit.required("kind").asText() == "token-fixed-reference-completion-audit" &&
                    audit.required("prospectiveOrigins").asInt() == 640 &&
                    audit.required("collectedOrigins").asInt() == 498
            ) {
                "Only the complete audited 37m fixed-T bank is eligible for this augmentation."
            }
            val runtime = TokenOrderParentRuntime.read(source, node.required("parentRuntime"))
            val relocation =
                TokenOrderParentSource.read(source, directory, node.required("parentSource"))
            val parent = TokenOrderReproduction.readParent(directory, runtime, relocation)
            require(
                parent.specification.continuationProtocol ==
                    TokenOrderContinuationProtocol.ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1 &&
                    parent.specification.trainingRoots == 512 &&
                    parent.specification.validationRoots == 128
            ) {
                "Augmentation cannot reuse ordinary-continuation labels or change the parent split."
            }
            val plan = PurchaseJson.read(pinned(node.required("branchPlan")))
            require(
                plan.required("kind").asText() ==
                    "reference-neighbor-augmentation-metadata-plan-1" &&
                    plan.required("parentFinishedSha256") ==
                        node.required("parentFinishedSha256") &&
                    plan.required("parentProtocolSha256") == audit.required("protocolSha256") &&
                    !plan.required("rewardFieldsUsed").asBoolean()
            ) {
                "Missing-branch requests must bind this parent and use public action metadata only."
            }
            val requests = plan.required("rows").toList()
            require(requests.size == 640) {
                "Every historical origin, including skips, must be retained."
            }
            for ((index, request) in requests.withIndex()) {
                val original = PurchaseJson.read(directory.resolve("origin-$index.json"))
                require(
                    request.required("index").asInt() == index &&
                        request.required("parentOriginSha256").asText() ==
                            parent.files["origin-$index.json"] &&
                        request.required("split") == original.required("split") &&
                        request.required("status") == original.required("status")
                ) {
                    "Augmentation requests cannot reorder or replace historical origins."
                }
                val missing = request.required("newIndices").map(JsonNode::asInt)
                require(missing.size <= 1 && missing.distinct() == missing) {
                    "This fixed augmentation may add at most one predeclared action per origin."
                }
                val actions = original["observation"]?.get("actions")
                val offeredFourteen =
                    actions?.indexOfFirst { it.required("command").asText() == "buy-tokens 14" }
                        ?: -1
                val oldProposals = original["proposedIndices"]?.map(JsonNode::asInt).orEmpty()
                val requiredMissing =
                    if (
                        original["baselineCommand"]?.asText() == "buy-tokens 16" &&
                            original["referenceIndex"]?.asInt()?.let {
                                actions?.get(it)?.get("command")?.asText()
                            } == "buy-tokens 15" &&
                            offeredFourteen >= 0 &&
                            offeredFourteen !in oldProposals
                    ) {
                        listOf(offeredFourteen)
                    } else {
                        emptyList()
                    }
                require(missing == requiredMissing) {
                    "Every eligible offered fourteen must be augmented without outcome-dependent filtering."
                }
                if (missing.isNotEmpty()) {
                    val actions = original.required("observation").required("actions")
                    require(
                        original.required("baselineCommand").asText() == "buy-tokens 16" &&
                            actions[original.required("referenceIndex").asInt()]
                                .required("command")
                                .asText() == "buy-tokens 15" &&
                            missing.single() in 0 until actions.size() &&
                            actions[missing.single()].required("command").asText() ==
                                "buy-tokens 14" &&
                            missing.single() !in
                                original.required("proposedIndices").map(JsonNode::asInt)
                    ) {
                        "A new label must be the originally offered fourteen missing from the fixed-T proposals."
                    }
                }
            }
            require(requests.sumOf { it.required("newIndices").size() } == 314) {
                "The frozen metadata plan requires exactly 314 new branches, or 2,512 tails."
            }
            val replay = node.required("replayOrigins").map(JsonNode::asInt)
            val expected =
                listOf("TRAINING", "VALIDATION").flatMap { split ->
                    requests
                        .filter {
                            it.required("split").asText() == split &&
                                !it.required("newIndices").isEmpty
                        }
                        .take(4)
                        .map { it.required("index").asInt() }
                }
            require(replay == expected && replay.size == 8) {
                "Replay requests must be the first four augmented origins of each original split."
            }
            return TokenOrderNeighborInput(source, hash, directory, parent, requests, replay)
        }
    }
}
