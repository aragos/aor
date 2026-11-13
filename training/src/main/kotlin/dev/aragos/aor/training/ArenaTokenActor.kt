package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Path

/** Explicit evaluated-seat TOKEN_2 actors; every rival and all other phases retain G3/E/T. */
class ArenaTokenActor private constructor(private val roles: Map<ArenaSide, TerritoryActorInput>) {
    internal fun actor(side: ArenaSide): TerritoryActorInput? = roles[side]

    internal fun validate(arena: ArenaSpecification) {
        require(
            arena.jointPolicy != null &&
                arena.tokenOrder != null &&
                arena.expansionImitation != null &&
                arena.expansionActor == null
        ) {
            "TOKEN_2 evaluation requires the complete frozen G3/E/T reference and no expansion actor."
        }
        for (configuration in arena.configurations) {
            val dictionary =
                TokenObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                    TerritoryEncoder.TOKEN_2,
                )
            require(
                roles.values.all {
                    it.model.schemaVersion == 3 &&
                        it.model.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                        it.model.stateFeatureNames == dictionary.stateFeatureNames &&
                        it.model.actionFeatureNames == dictionary.actionFeatureNames
                }
            ) {
                "Each token actor requires schema 3 and the exact full public TOKEN_2 dictionary."
            }
        }
    }

    internal fun controllers(
        original: Map<Int, SimulationController>,
        seat: Int,
        side: ArenaSide?,
    ): Map<Int, SimulationController> {
        require(side != null) { "TOKEN_2 evaluation requires an explicit evaluated role." }
        val actor = roles[side] ?: return original
        val reference =
            requireNotNull(original[seat]) {
                "Complete reference composition must precede the token actor."
            }
        return original + (seat to TokenPairedController(reference, actor.model, actor.model))
    }

    internal fun identity(): String =
        "$VERSION:" + roles.entries.joinToString(":") { (side, actor) -> "$side=${actor.sha256}" }

    internal fun binding(): Map<String, Any> =
        mapOf(
            "version" to VERSION,
            "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
            "actorVersion" to TokenPairedInput.ACTOR_VERSION,
            "reference" to "complete-joint-token-expansion-composition",
            "roles" to
                roles.entries.associate { (side, actor) ->
                    side.name to
                        mapOf(
                            "path" to actor.path.toString(),
                            "sha256" to actor.sha256,
                            "snapshot" to filename(side),
                            "schemaVersion" to 3,
                            "stateFeatureNames" to actor.model.stateFeatureNames,
                            "actionFeatureNames" to actor.model.actionFeatureNames,
                        )
                },
        )

    internal fun metadata(): Map<String, Any> = mapOf("tokenActor" to binding())

    internal fun snapshot(artifacts: TrainingArtifacts) {
        roles.forEach { (side, actor) ->
            artifacts.ensureFileSnapshot(actor.path, actor.sha256, filename(side))
        }
    }

    companion object {
        const val VERSION = "token-actor-arena-2"

        internal fun from(
            final: TerritoryActorInput,
            original: TerritoryActorInput,
        ): ArenaTokenActor =
            ArenaTokenActor(mapOf(ArenaSide.CANDIDATE to final, ArenaSide.INCUMBENT to original))

        internal fun read(source: Path, node: JsonNode): ArenaTokenActor {
            PurchaseJson.fields(node, setOf("version"), setOf("candidate", "incumbent"))
            require(node["version"]?.asText() == VERSION) {
                "Declare the explicit TOKEN_2 arena version."
            }
            val roles =
                ArenaSide.entries
                    .mapNotNull { side ->
                        node[side.name.lowercase()]?.let { value ->
                            val subject =
                                TokenPairedSubject.read(value, source.toAbsolutePath().parent)
                            val model = TerritoryPolicyInput.read(subject.path)
                            subject.verify()
                            side to TerritoryActorInput(subject.path, subject.sha256, model)
                        }
                    }
                    .toMap()
            require(roles.isNotEmpty()) { "Declare at least one token actor role." }
            return ArenaTokenActor(roles)
        }

        private fun filename(side: ArenaSide) = "token-actor-${side.name.lowercase()}.json"
    }
}
