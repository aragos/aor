package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Path

/** Explicit schema-2 replacement-seat actors; rivals always retain their original E controller. */
class ArenaExpansionActor
private constructor(private val roles: Map<ArenaSide, TerritoryActorInput>) {
    internal fun actor(side: ArenaSide): TerritoryActorInput? = roles[side]

    internal fun validate(arena: ArenaSpecification) {
        require(
            arena.jointPolicy != null &&
                arena.expansionImitation != null &&
                arena.tokenOrder != null
        ) {
            "Expansion actors require the complete joint/E/T reference composition."
        }
        for (configuration in arena.configurations) {
            val dictionary =
                ExpansionObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                    TerritoryEncoder.EXPANSION_2,
                )
            for ((side, input) in roles) {
                require(
                    input.model.schemaVersion == 2 &&
                        input.model.encoderVersion == TerritoryEncoder.EXPANSION_2.version &&
                        input.model.stateFeatureNames == dictionary.stateFeatureNames &&
                        input.model.actionFeatureNames == dictionary.actionFeatureNames
                ) {
                    "The $side expansion actor requires schema 2 and the exact complete dictionary."
                }
            }
        }
        val expansion =
            PurchaseJson.mapper
                .valueToTree<JsonNode>(arena.expansionImitation.metadata())["expansionImitation"]
        require(roles.keys.all { expansion["${it.name.lowercase()}Model"] != null }) {
            "Each expansion actor requires its own explicit E reference role."
        }
    }

    internal fun controllers(
        original: Map<Int, SimulationController>,
        controlledSeat: Int,
        side: ArenaSide?,
    ): Map<Int, SimulationController> {
        require(side != null) { "Schema-2 expansion evaluation requires an explicit side." }
        val actor = roles[side] ?: return original
        val reference =
            requireNotNull(original[controlledSeat]) {
                "The complete ordinary E/T/G3 controller must precede the expansion actor."
            }
        return original + (controlledSeat to ExpansionOnPolicyController(reference, actor.model))
    }

    internal fun identity(): String =
        "$VERSION:" +
            roles.entries.joinToString(":") { (side, input) ->
                "$side=${input.sha256}"
            }

    internal fun binding(): Map<String, Any> =
        mapOf(
            "version" to VERSION,
            "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
            "actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION,
            "reference" to "complete-joint-token-expansion-composition",
            "roles" to
                roles
                    .mapValues { (side, input) ->
                        mapOf(
                            "path" to input.path.toString(),
                            "sha256" to input.sha256,
                            "snapshot" to filename(side),
                            "schemaVersion" to 2,
                            "stateFeatureNames" to input.model.stateFeatureNames,
                            "actionFeatureNames" to input.model.actionFeatureNames,
                        )
                    }
                    .mapKeys { it.key.name },
        )

    internal fun metadata(): Map<String, Any> = mapOf("expansionActor" to binding())

    internal fun snapshot(artifacts: TrainingArtifacts) {
        roles.forEach { (side, input) ->
            artifacts.ensureFileSnapshot(input.path, input.sha256, filename(side))
        }
    }

    companion object {
        const val VERSION = "expansion-actor-arena-2"

        private fun filename(side: ArenaSide) = "expansion-actor-${side.name.lowercase()}.json"

        internal fun read(source: Path, node: JsonNode): ArenaExpansionActor {
            PurchaseJson.fields(node, setOf("version"), setOf("candidate", "incumbent"))
            require(node["version"]?.asText() == VERSION) {
                "An explicit expansion actor version is required."
            }
            val roles =
                ArenaSide.entries
                    .mapNotNull { side ->
                        node[side.name.lowercase()]?.let { subject ->
                            PurchaseJson.fields(subject, setOf("path", "sha256"))
                            val path =
                                source
                                    .toAbsolutePath()
                                    .parent
                                    .resolve(PurchaseJson.text(subject["path"], "actor path"))
                                    .toRealPath()
                            val hash = PurchaseJson.text(subject["sha256"], "actor hash")
                            require(
                                hash.matches(Regex("[0-9a-f]{64}")) &&
                                    PurchaseJson.hash(path) == hash
                            ) {
                                "The explicit expansion actor bytes changed."
                            }
                            val model = TerritoryPolicyInput.read(path)
                            require(PurchaseJson.hash(path) == hash) {
                                "Expansion actor changed during decode."
                            }
                            side to TerritoryActorInput(path, hash, model)
                        }
                    }
                    .toMap()
            require(roles.isNotEmpty()) {
                "Declare at least one candidate/incumbent expansion actor."
            }
            return ArenaExpansionActor(roles)
        }
    }
}
