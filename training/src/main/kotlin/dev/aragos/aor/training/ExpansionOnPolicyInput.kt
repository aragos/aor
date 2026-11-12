package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Path

/** One new expansion actor, an unchanged complete reference ecology, and a prospective bank. */
internal class ExpansionOnPolicyInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSourceSha256: String,
    val arena: ArenaSpecification,
    val actor: TerritoryActorInput,
    val seed: Int,
    val seeds: List<Int>,
    val replayIndices: List<Int>,
) {
    val configuration = arena.configurations.single()
    val dictionary =
        ExpansionObservationEncoder.dictionary(
            PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
            TerritoryEncoder.EXPANSION_2,
        )
    val policyBindings: JsonNode = PurchaseJson.mapper.valueToTree(arena.overlayMetadata())
    val frozenCardPurchaseBundleSha256: String =
        policyBindings["jointPolicy"]["candidateBundle"]["sha256"].asText()
    val referenceExpansionModelSha256: String =
        policyBindings["expansionImitation"]["candidateModel"]["sha256"].asText()
    val signature =
        trainingSignature(
            "$VERSION:$sourceSha256:$arenaSourceSha256:${arena.identity()}:${actor.sha256}"
        )

    init {
        require(
            actor.model.schemaVersion == 2 &&
                actor.model.encoderVersion == TerritoryEncoder.EXPANSION_2.version &&
                actor.model.stateFeatureNames == dictionary.stateFeatureNames &&
                actor.model.actionFeatureNames == dictionary.actionFeatureNames
        ) {
            "Expansion collection requires schema 2 and the complete expansion-v2 vocabulary."
        }
    }

    fun seat(index: Int): Int = index % configuration.playerCount

    fun controllers(
        index: Int,
        record: ((TerritoryDecisionRecord) -> Unit)? = null,
    ): Map<Int, SimulationController> {
        val seat = seat(index)
        val normal = arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
        val reference =
            requireNotNull(normal[seat]) { "The complete E/T/G3 reference is required." }
        return normal + (seat to ExpansionOnPolicyController(reference, actor.model, record))
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(source, sourceSha256, "source.json")
        artifacts.ensureFileSnapshot(arenaSource, arenaSourceSha256, "arena.json")
        artifacts.ensureFileSnapshot(actor.path, actor.sha256, "actor.json")
        val ecology = TrainingArtifacts(artifacts.directory.resolve("ecology"))
        arena.snapshotModels(ecology)
        arena.snapshotOverlays(ecology)
    }

    companion object {
        const val VERSION = "expansion-on-policy-collect-1"
        const val ACTOR_VERSION = "aor-expansion-policy-2"

        fun read(path: Path): ExpansionOnPolicyInput {
            val source = path.toRealPath()
            val sourceSha = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf("kind", "arena", "actor", "games", "seed", "replayIndices"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "An explicit expansion-on-policy-collect-1 source is required."
            }
            val arenaSource =
                source.parent.resolve(PurchaseJson.text(node["arena"], "arena")).toRealPath()
            val arenaSha = PurchaseJson.hash(arenaSource)
            PurchaseJson.fields(
                PurchaseJson.read(arenaSource),
                setOf(
                    "schemaVersion",
                    "purpose",
                    "configurations",
                    "candidate",
                    "incumbent",
                    "opponents",
                    "pairsPerConfiguration",
                    "seed",
                    "jointPolicy",
                    "tokenOrder",
                    "expansionImitation",
                ),
            )
            val arena = ArenaSpecification.read(arenaSource)
            ExpansionPairedInput.validateEcology(arena)
            val subject = node.required("actor")
            PurchaseJson.fields(subject, setOf("path", "sha256"))
            val actorPath =
                source.parent.resolve(PurchaseJson.text(subject["path"], "actor path")).toRealPath()
            val actorSha = PurchaseJson.text(subject["sha256"], "actor SHA")
            require(
                actorSha.matches(Regex("[0-9a-f]{64}")) && PurchaseJson.hash(actorPath) == actorSha
            ) {
                "The explicit expansion behavior model hash changed."
            }
            val actor =
                TerritoryActorInput(actorPath, actorSha, TerritoryPolicyInput.read(actorPath))
            val games = PurchaseJson.integer(node["games"], "games")
            val seed = PurchaseJson.integer(node["seed"], "seed")
            require(games in 1..4096 && seed in 0..100_000_000) {
                "Expansion collection requires a bounded prospective arena bank."
            }
            val replayIndices =
                PurchaseJson.array(node["replayIndices"], "replayIndices").map {
                    PurchaseJson.integer(it, "replay index")
                }
            require(
                replayIndices == replayIndices.distinct().sorted() &&
                    replayIndices.size <= 16 &&
                    replayIndices.all { it in 0 until games }
            ) {
                "Declare at most 16 distinct prospective complete canonical histories."
            }
            require(
                PurchaseJson.hash(source) == sourceSha &&
                    PurchaseJson.hash(arenaSource) == arenaSha &&
                    PurchaseJson.hash(actorPath) == actorSha
            ) {
                "Expansion source or actor changed while decoding."
            }
            return ExpansionOnPolicyInput(
                source,
                sourceSha,
                arenaSource,
                arenaSha,
                arena,
                actor,
                seed,
                List(games) { StrategyArena.seed(seed, it) },
                replayIndices,
            )
        }
    }
}
