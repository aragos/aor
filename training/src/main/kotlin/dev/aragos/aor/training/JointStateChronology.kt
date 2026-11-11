package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder

/** Recorded phase names prefix existing state dictionaries; they do not introduce action inputs. */
internal enum class JointStatePhase(val prefix: String, val records: String) {
    CARD("card", "cardDecisions"),
    PURCHASE("purchase", "purchaseDecisions"),
}

/** Provenance stays outside [stateFeatures]; terminal labels are a separately bound artifact. */
internal data class JointStateRow(
    val number: Int,
    val turn: Int,
    val phase: JointStatePhase,
    val sourceRow: Int,
    val stateFeatures: Map<String, Double>,
)

/** Pure projection of recorded actor states. No policy calls, action fields or future views. */
internal class JointStateChronology(private val dictionary: Map<JointStatePhase, List<String>>) {
    val stateFeatureNames: List<String> =
        dictionary.flatMap { (phase, names) -> names.map { "${phase.prefix}.$it" } }.sorted()

    init {
        require(dictionary.keys == JointStatePhase.entries.toSet()) {
            "Joint state projection requires both complete existing phase dictionaries."
        }
        require(dictionary.values.all { it == it.distinct().sorted() }) {
            "Each phase dictionary must retain its unique sorted encoder vocabulary."
        }
    }

    fun metadata(): Map<String, Any> =
        mapOf(
            "version" to JointEcology.VERSION,
            "stateFeatureNames" to stateFeatureNames,
            "units" to "unchanged-existing-phase-encoder-units",
            "missingValue" to 0.0,
        )

    fun project(game: JsonNode): List<JointStateRow> {
        val rows =
            JointStatePhase.entries
                .flatMap { phase ->
                    val names =
                        requireNotNull(dictionary[phase]) { "The phase dictionary is required." }
                            .toSet()
                    PurchaseJson.array(game.required(phase.records), phase.records).mapIndexed {
                        index,
                        row ->
                        val features = row.required("observation").required("stateFeatures")
                        require(
                            features.isObject && features.properties().all { it.key in names }
                        ) {
                            "Joint state inputs must belong to the existing phase encoder dictionary."
                        }
                        JointStateRow(
                            PurchaseJson.integer(
                                row.required("number"),
                                "absolute decision number",
                            ),
                            PurchaseJson.integer(row.required("turn"), "actual public turn"),
                            phase,
                            index,
                            features.properties().associate { (name, value) ->
                                "${phase.prefix}.$name" to PurchaseJson.number(value, name)
                            },
                        )
                    }
                }
                .sortedBy(JointStateRow::number)
        require(
            rows.map(JointStateRow::number).distinct().size == rows.size &&
                rows.all { it.number >= 0 } &&
                rows.zipWithNext().all { (a, b) -> a.turn <= b.turn }
        ) {
            "Joint chronology requires unique absolute decisions and nondecreasing actual turns."
        }
        return rows
    }

    companion object {
        fun from(input: JointCollectionInput): JointStateChronology = from(input.configuration)

        fun from(configuration: TrainingConfiguration): JointStateChronology {
            val catalog = PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            return JointStateChronology(
                mapOf(
                    JointStatePhase.CARD to
                        CardObservationEncoder.dictionary(catalog).stateFeatureNames,
                    JointStatePhase.PURCHASE to
                        PurchaseObservationEncoder.dictionary(catalog).stateFeatureNames,
                )
            )
        }
    }
}
