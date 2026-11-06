package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.PurchaseFeatureDictionary
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel
import java.nio.file.Path

/** Raw file identity and decoded baseline retain explicit zeros and qualified-profile metadata. */
internal data class PurchaseBaselineInput(val path: Path, val sha256: String, val model: BotModel)

/**
 * One immutable collection: a declared arena seed bank, one setup, a rotating learner seat, and a
 * frozen HARD opponent population. Relative model paths resolve beside the source JSON. A null
 * [policy] collects exact baseline recommendations, without invented behavior likelihoods.
 */
internal data class PurchaseCollectionInput(
    val source: Path,
    val sourceSha256: String,
    val configuration: TrainingConfiguration,
    val seed: Int,
    val seeds: List<Int>,
    val baseline: PurchaseBaselineInput,
    val opponents: List<PurchaseBaselineInput>,
    val policyPath: Path?,
    val modelSha256: String?,
    val policy: PurchasePolicyModel?,
    val dictionary: PurchaseFeatureDictionary,
) {
    val mode: String
        get() = if (policy == null) "baseline-imitation" else "on-policy"

    fun seat(index: Int): Int = index % configuration.playerCount

    fun opponentIndices(index: Int): List<Int> =
        List(configuration.playerCount) { player ->
            (index / configuration.playerCount + player) % opponents.size
        }

    fun signature(): String =
        trainingSignature(
            listOf(
                    "purchase-collect-1",
                    source.toString(),
                    sourceSha256,
                    baseline.sha256,
                    opponents.map { it.sha256 },
                    modelSha256,
                    PurchaseObservationEncoder.ENCODER_VERSION,
                    PurchasePolicy.ACTOR_VERSION,
                    dictionary.stateFeatureNames,
                    dictionary.actionFeatureNames,
                )
                .joinToString("\n")
        )

    companion object {
        fun read(source: Path): PurchaseCollectionInput {
            val realSource = source.toRealPath()
            val sourceHash = PurchaseJson.hash(realSource)
            val node = PurchaseJson.read(realSource)
            PurchaseJson.fields(
                node,
                setOf("schemaVersion", "configuration", "games", "seed", "baseline", "opponents"),
                setOf("policy"),
            )
            require(PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1) {
                "A purchase collection requires schemaVersion 1."
            }
            val configurationId = PurchaseJson.text(node.required("configuration"), "configuration")
            val configuration =
                requireNotNull(
                    TrainingMatrix.configurations.singleOrNull { it.id == configurationId }
                ) {
                    "Unsupported purchase collection configuration: $configurationId."
                }
            val games = PurchaseJson.integer(node.required("games"), "games")
            val seed = PurchaseJson.integer(node.required("seed"), "seed")
            require(games in 1..4096 && seed in 0..100_000_000) {
                "A purchase collection requires 1–4096 games and an arena seed offset in 0..100000000."
            }
            val seeds = List(games) { StrategyArena.seed(seed, it) }
            fun resolve(text: String): Path =
                realSource.parent.resolve(text).normalize().toRealPath()
            fun baseline(text: String): PurchaseBaselineInput {
                val path = resolve(text)
                val hash = PurchaseJson.hash(path)
                val model = TrainingArtifacts.readModel(path)
                require(PurchaseJson.hash(path) == hash) {
                    "A baseline input changed while it was being read."
                }
                return PurchaseBaselineInput(path, hash, model)
            }
            val incumbent = baseline(PurchaseJson.text(node.required("baseline"), "baseline"))
            val opponents =
                PurchaseJson.strings(node.required("opponents"), "opponents").map(::baseline)
            require(opponents.size in 1..16) {
                "A purchase collection requires one through sixteen frozen opponents."
            }
            val policyPath =
                node["policy"]
                    ?.takeUnless { it.isNull }
                    ?.let {
                        resolve(PurchaseJson.text(it, "policy"))
                    }
            val modelHash = policyPath?.let(PurchaseJson::hash)
            val policy = policyPath?.let(PurchasePolicyInput::read)
            val dictionary =
                PurchaseObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            require(
                policy == null ||
                    policy.stateFeatureNames == dictionary.stateFeatureNames &&
                        policy.actionFeatureNames == dictionary.actionFeatureNames
            ) {
                "The purchase policy must use this configuration's complete encoder dictionaries."
            }
            require(
                PurchaseJson.hash(realSource) == sourceHash &&
                    policyPath?.let(PurchaseJson::hash) == modelHash
            ) {
                "A purchase source or policy changed while it was being read."
            }
            return PurchaseCollectionInput(
                realSource,
                sourceHash,
                configuration,
                seed,
                seeds,
                incumbent,
                opponents,
                policyPath,
                modelHash,
                policy,
                dictionary,
            )
        }
    }
}
