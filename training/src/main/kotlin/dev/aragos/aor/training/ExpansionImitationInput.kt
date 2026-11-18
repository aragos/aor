package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.Commodity
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotConfiguration
import dev.aragos.aor.ai.BotExpansionPlanning
import dev.aragos.aor.ai.BotExpansionStep
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPlanningCommodity
import java.nio.file.Files
import java.nio.file.Path

/** Sanitized actor observations only; source game hashes are provenance, not file references. */
internal class ExpansionImitationInput(
    val source: Path,
    val sourceSha256: String,
    val observations: Path,
    val observationsSha256: String,
    val baselinePath: Path,
    val baselineSha256: String,
    val baseline: BotModel,
    val gameId: String,
    val gameSourceSha256: String,
    val rows: List<JsonNode>,
    val transfer: ExperimentalRulesTransfer?,
) {
    val expansionRows = rows.filter {
        it["decision"].asText() == "expand" && it["observation"]["candidates"].size() > 1
    }

    init {
        require(
            expansionRows.size ==
                ExpansionImitationProtocol.TRAIN +
                    ExpansionImitationProtocol.VALIDATION +
                    ExpansionImitationProtocol.AUDIT
        ) {
            "The frozen pilot requires exactly 50 nontrivial chronological expansion decisions."
        }
    }

    fun example(row: JsonNode): ExpansionImitationExample {
        val observation = observation(row.required("observation"))
        val example =
            ExpansionImitationExample(
                integer(row, "entry"),
                observation,
                text(row, "selectedCommand"),
            )
        require(
            integer(row, "baselineSeed") == ExpansionImitationProtocol.POLICY_SEED &&
                ExpansionImitationOptimizer.commands(listOf(example), baseline).single() ==
                    text(row, "ordinaryBaselineCommand")
        ) {
            "Sanitized observation must reproduce its frozen F1 HARD diagnostic baseline."
        }
        return example
    }

    companion object {
        fun read(source: Path): ExpansionImitationInput {
            val sourceHash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "observations",
                    "observationsSha256",
                    "baseline",
                    "baselineSha256",
                    "gameId",
                    "gameSourceSha256",
                ),
                setOf("experimentalRulesTransfer"),
            )
            require(integer(node, "schemaVersion") == 1) {
                "Imitation source requires schemaVersion 1."
            }
            fun path(name: String): Path =
                source.toAbsolutePath().parent.resolve(text(node, name)).normalize().toRealPath()
            val observations = path("observations")
            val baselinePath = path("baseline")
            require(Files.size(observations) in 1..16_000_000) {
                "Sanitized imitation observations must fit within 16 MB."
            }
            val observationsHash = text(node, "observationsSha256")
            val baselineHash = text(node, "baselineSha256")
            require(
                PurchaseJson.hash(observations) == observationsHash &&
                    PurchaseJson.hash(baselinePath) == baselineHash
            ) {
                "Imitation inputs must match their frozen byte hashes."
            }
            val transfer = ExperimentalRulesTransfer.read(node["experimentalRulesTransfer"])
            val baseline = TrainingArtifacts.readModel(baselinePath, transfer)
            val rows = Files.readAllLines(observations).map(PurchaseJson.mapper::readTree)
            require(rows.size == 186) {
                "The frozen human imitation source requires 186 gameplay observations."
            }
            val gameId = text(node, "gameId")
            val gameHash = text(node, "gameSourceSha256")
            require(gameHash.matches(Regex("[0-9a-f]{64}"))) {
                "Human game provenance requires a SHA-256 hash."
            }
            rows.forEach { row ->
                PurchaseJson.fields(
                    row,
                    setOf(
                        "gameId",
                        "sourceSha256",
                        "entry",
                        "turn",
                        "epoch",
                        "decision",
                        "selectedCommand",
                        "baselineSeed",
                        "ordinaryBaselineCommand",
                        "observation",
                    ),
                )
                require(
                    text(row, "gameId") == gameId &&
                        text(row, "sourceSha256") == gameHash &&
                        text(row, "decision") == text(row.required("observation"), "decision")
                ) {
                    "Every sanitized row must bind the same human game and its actor decision."
                }
            }
            require(
                rows.map { text(it.required("observation"), "playerId") }.distinct().size == 1
            ) {
                "Human imitation observations must all belong to the same deciding actor."
            }
            require(
                rows.zipWithNext().all { (first, second) ->
                    integer(first, "entry") < integer(second, "entry")
                }
            ) {
                "Human decisions must retain strictly increasing source entry order."
            }
            require(
                PurchaseJson.hash(observations) == observationsHash &&
                    PurchaseJson.hash(baselinePath) == baselineHash &&
                    PurchaseJson.hash(source) == sourceHash
            ) {
                "Imitation input bytes changed while reading."
            }
            return ExpansionImitationInput(
                source,
                sourceHash,
                observations,
                observationsHash,
                baselinePath,
                baselineHash,
                baseline,
                gameId,
                gameHash,
                rows,
                transfer,
            )
        }

        /**
         * Only expansion inputs are reconstructed; no authority state or other decision labels
         * enter fitting.
         */
        internal fun observation(node: JsonNode): BotObservation {
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "playerId",
                    "decision",
                    "context",
                    "availableCash",
                    "availableTokens",
                    "candidates",
                    "rulesVersion",
                    "dataVersion",
                    "configuration",
                    "expansionPlanning",
                    "reserveShortfallBasis",
                    "advancePlanning",
                    "leaderPlanning",
                ),
            )
            require(
                text(node, "decision") == "expand" &&
                    node["advancePlanning"].isNull &&
                    node["leaderPlanning"].isNull &&
                    text(node, "rulesVersion") == RULES_VERSION &&
                    text(node, "dataVersion") == DATA_VERSION
            ) {
                "Imitation observations require current-rules expansion with no unrelated planning payload."
            }
            val configuration = node.required("configuration")
            PurchaseJson.fields(
                configuration,
                setOf(
                    "playerCount",
                    "map",
                    "initialCard",
                    "deckModification",
                    "immediateMasterArtDiscard",
                    "drawThreeKeepTwo",
                ),
            )
            val setup =
                BotConfiguration(
                    integer(configuration, "playerCount"),
                    MapRule.valueOf(text(configuration, "map")),
                    InitialCardRule.valueOf(text(configuration, "initialCard")),
                    boolean(configuration, "deckModification"),
                    boolean(configuration, "immediateMasterArtDiscard"),
                    boolean(configuration, "drawThreeKeepTwo"),
                )
            require(
                setup ==
                    BotConfiguration(4, MapRule.STANDARD, InitialCardRule.NO_CRUSADES, true, true)
            ) {
                "Imitation is scoped to the frozen four-player standard configuration."
            }
            val candidates =
                PurchaseJson.array(node.required("candidates"), "candidates").map { candidate ->
                    PurchaseJson.fields(
                        candidate,
                        setOf(
                            "command",
                            "features",
                            "cashCost",
                            "tokenCost",
                            "group",
                            "family",
                            "playerTarget",
                        ),
                    )
                    require(candidate["playerTarget"].isNull) {
                        "Expansion candidates cannot carry private player targets."
                    }
                    BotCandidate(
                        text(candidate, "command"),
                        numbers(candidate.required("features")),
                        integer(candidate, "cashCost"),
                        integer(candidate, "tokenCost"),
                        candidate["group"].asText(),
                        text(candidate, "family"),
                    )
                }
            return BotObservation(
                integer(node, "schemaVersion"),
                text(node, "playerId"),
                "expand",
                numbers(node.required("context")),
                integer(node, "availableCash"),
                integer(node, "availableTokens"),
                candidates,
                RULES_VERSION,
                DATA_VERSION,
                setup,
                node["expansionPlanning"].takeUnless(JsonNode::isNull)?.let(::planning),
                node["reserveShortfallBasis"].takeUnless(JsonNode::isNull)?.let(::numbers),
            )
        }

        private fun planning(node: JsonNode): BotExpansionPlanning {
            PurchaseJson.fields(
                node,
                setOf(
                    "markerStock",
                    "industry",
                    "commodities",
                    "heldCards",
                    "steps",
                    "overseasSlots",
                ),
            )
            val commodities =
                PurchaseJson.array(node.required("commodities"), "planning commodities").map { value
                    ->
                    PurchaseJson.fields(
                        value,
                        setOf(
                            "commodity",
                            "markets",
                            "baseValue",
                            "adjustmentOffset",
                            "outsideCards",
                        ),
                    )
                    BotPlanningCommodity(
                        Commodity.valueOf(text(value, "commodity")),
                        integer(value, "markets"),
                        integer(value, "baseValue"),
                        integer(value, "adjustmentOffset"),
                        integer(value, "outsideCards"),
                    )
                }
            val held =
                PurchaseJson.array(node.required("heldCards"), "held cards").map {
                    PurchaseJson.strings(it, "held commodity choices")
                        .map(Commodity::valueOf)
                        .toSet()
                }
            val steps = node.required("steps")
            require(steps.isObject) { "Expansion planning steps must be keyed by offered command." }
            return BotExpansionPlanning(
                integer(node, "markerStock"),
                boolean(node, "industry"),
                commodities,
                held,
                steps.properties().associate { (command, value) ->
                    PurchaseJson.fields(
                        value,
                        setOf("markerCost", "commodities", "newOverseasCost"),
                    )
                    command to
                        BotExpansionStep(
                            integer(value, "markerCost"),
                            PurchaseJson.strings(value.required("commodities"), "step commodities")
                                .map(Commodity::valueOf)
                                .toSet(),
                            integer(value, "newOverseasCost"),
                        )
                },
                integer(node, "overseasSlots"),
            )
        }

        private fun numbers(node: JsonNode): Map<String, Double> {
            require(node.isObject) { "Imitation numerical features must be a JSON object." }
            return node.properties().associate { (key, value) ->
                key to PurchaseJson.number(value, key)
            }
        }

        private fun integer(node: JsonNode, key: String): Int =
            PurchaseJson.integer(node.required(key), key)

        private fun text(node: JsonNode, key: String): String =
            PurchaseJson.text(node.required(key), key)

        private fun boolean(node: JsonNode, key: String): Boolean {
            val value = node.required(key)
            require(value.isBoolean) { "Imitation $key must be a JSON boolean." }
            return value.booleanValue()
        }
    }
}
