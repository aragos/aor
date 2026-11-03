package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Atomic local checkpoints; training never connects to production game storage. */
class TrainingArtifacts(val directory: Path) {
    private val json = ObjectMapper()

    init {
        Files.createDirectories(directory)
    }

    fun read(name: String): com.fasterxml.jackson.databind.JsonNode? {
        require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "Training artifact names must be simple file names."
        }
        val path = directory.resolve(name)
        return if (Files.exists(path)) json.readTree(path.toFile()) else null
    }

    fun write(name: String, value: Any) {
        require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "Training artifact names must be simple file names."
        }
        val temporary = Files.createTempFile(directory, ".training-", ".tmp")
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value)
            Files.move(
                temporary,
                directory.resolve(name),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun model(model: BotModel, name: String = "model.json") {
        write(
            name,
            mapOf(
                "schemaVersion" to model.schemaVersion,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "weights" to model.weights.toSortedMap(),
            ),
        )
    }

    fun append(name: String, value: Any) {
        require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "Training artifact names must be simple file names."
        }
        Files.writeString(
            directory.resolve(name),
            json.writeValueAsString(value) + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    companion object {
        fun readModel(path: Path?): BotModel {
            if (path == null) return BotModels.default
            val root = ObjectMapper().readTree(path.toFile())
            require(root["schemaVersion"]?.intValue() == 1) {
                "The model must declare observation/model schema version 1."
            }
            require(root["rulesVersion"]?.asText()?.let { it == RULES_VERSION } != false) {
                "The model was generated for a different rules version."
            }
            require(root["dataVersion"]?.asText()?.let { it == DATA_VERSION } != false) {
                "The model was generated for a different data version."
            }
            val weights = requireNotNull(root["weights"]) { "The model requires feature weights." }
            require(weights.isObject) { "Model weights must be an object." }
            return BotModel(
                weights =
                    weights.properties().associate { (feature, weight) ->
                        require(weight.isNumber) { "Weight $feature must be numerical." }
                        feature to weight.doubleValue()
                    }
            )
        }
    }
}

/** Coverage remains explicit: a variant sweep does not imply every rare event occurred. */
class CoverageLedger {
    private data class ConfigurationCoverage(
        var games: Int = 0,
        var probes: Int = 0,
        val seeds: MutableSet<Int> = mutableSetOf(),
        val epochs: MutableSet<Int> = mutableSetOf(),
        val capitals: MutableSet<String> = mutableSetOf(),
        val decisions: MutableMap<String, Int> = mutableMapOf(),
    )

    private val configurations = mutableMapOf<String, ConfigurationCoverage>()
    private val features = mutableSetOf<String>()
    private val cards = mutableSetOf<String>()
    private val families = mutableMapOf<String, Int>()

    fun add(game: SimulatedGame) {
        val record = configurations.getOrPut(game.configuration) { ConfigurationCoverage() }
        record.games++
        record.seeds.add(game.seed)
        record.epochs.addAll(game.coverage.epochs)
        record.capitals.addAll(game.coverage.capitals)
        mergeCounts(record.decisions, game.coverage.decisions)
        mergeCounts(families, game.coverage.families)
        features.addAll(game.coverage.features)
        cards.addAll(game.coverage.playedCards)
    }

    fun contains(configuration: String, seed: Int): Boolean =
        configuration.let { configurations[it]?.seeds?.contains(seed) } == true

    fun probe(configuration: String, observation: dev.aragos.aor.ai.BotObservation) {
        val record = configurations.getOrPut(configuration) { ConfigurationCoverage() }
        record.probes++
        record.decisions[observation.decision] = (record.decisions[observation.decision] ?: 0) + 1
        observation.candidates.flatMapTo(features) { it.features.keys }
    }

    val featureNames: Set<String>
        get() = features.toSortedSet()

    val missingDecisionTypes: Set<String>
        get() = requiredDecisions - configurations.values.flatMap { it.decisions.keys }.toSet()

    val missingCards: Set<String>
        get() = dev.aragos.aor.CardData.all.map { it.id }.toSet() - cards

    fun report(): Map<String, Any> {
        val observed = configurations.values.flatMap { it.decisions.keys }.toSet()
        val completedConfigurations = configurations.filterValues { it.games > 0 }.keys
        return mapOf(
            "generatedAt" to Instant.now().toString(),
            "rulesVersion" to RULES_VERSION,
            "dataVersion" to DATA_VERSION,
            "games" to configurations.values.sumOf { it.games },
            "directedProbes" to configurations.values.sumOf { it.probes },
            "requiredConfigurations" to TrainingMatrix.configurations.size,
            "observedConfigurations" to completedConfigurations.size,
            "probedConfigurations" to configurations.filterValues { it.probes > 0 }.size,
            "missingConfigurations" to
                TrainingMatrix.configurations
                    .map { it.id }
                    .filterNot(completedConfigurations::contains),
            "observedDecisions" to observed.sorted(),
            "missingDecisionTypes" to missingDecisionTypes.sorted(),
            "missingCards" to missingCards.sorted(),
            "playedCards" to cards.sorted(),
            "featureNames" to features.sorted(),
            "families" to families.toSortedMap(),
            "configurations" to
                configurations.toSortedMap().mapValues { (_, record) ->
                    mapOf(
                        "games" to record.games,
                        "probes" to record.probes,
                        "seeds" to record.seeds.sorted(),
                        "epochs" to record.epochs.sorted(),
                        "capitals" to record.capitals.sorted(),
                        "decisions" to record.decisions.toSortedMap(),
                    )
                },
        )
    }

    fun restore(root: com.fasterxml.jackson.databind.JsonNode) {
        require(configurations.isEmpty()) { "Coverage can only be restored into an empty ledger." }
        require(
            root["rulesVersion"]?.asText() == RULES_VERSION &&
                root["dataVersion"]?.asText() == DATA_VERSION
        ) {
            "Saved coverage belongs to a different engine version."
        }
        for ((id, node) in root["configurations"].properties()) {
            configurations[id] =
                ConfigurationCoverage(
                    games = node["games"].intValue(),
                    probes = node["probes"]?.intValue() ?: 0,
                    seeds = node["seeds"].map { it.intValue() }.toMutableSet(),
                    epochs = node["epochs"].map { it.intValue() }.toMutableSet(),
                    capitals = node["capitals"].map { it.asText() }.toMutableSet(),
                    decisions =
                        node["decisions"]
                            .properties()
                            .associate { (key, value) ->
                                key to value.intValue()
                            }
                            .toMutableMap(),
                )
        }
        features.addAll(root["featureNames"]?.map { it.asText() }.orEmpty())
        cards.addAll(root["playedCards"].map { it.asText() })
        families.putAll(
            root["families"].properties().associate { (key, value) -> key to value.intValue() }
        )
    }

    companion object {
        val requiredDecisions: Set<String> =
            setOf(
                "bid-capital",
                "choose-capital",
                "buy-tokens",
                "choose-turn-position",
                "cancel-adjustment",
                "buy-card",
                "play-cards",
                "purchase-shipping",
                "purchase-advances",
                "purchase-stabilization",
                "purchase-relief",
                "expand",
                "renaissance",
                "civil-war-loss",
                "replace-event-token",
                "prepare-war",
                "place-event-marker",
                "war-surrender",
                "cathedral",
                "windmill",
                "offer-card",
                "immediate-master-art-discard",
            )

        private fun mergeCounts(target: MutableMap<String, Int>, source: Map<String, Int>) {
            source.forEach { (key, value) -> target[key] = (target[key] ?: 0) + value }
        }
    }
}
