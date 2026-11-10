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

/** Exact bytes and decoded actor for an optional learned purchase policy. */
internal data class PurchaseActorInput(
    val path: Path,
    val sha256: String,
    val model: PurchasePolicyModel,
) {
    init {
        require(path.isAbsolute) { "A purchase actor requires its resolved absolute source path." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) {
            "A purchase actor requires its exact SHA-256 fingerprint."
        }
    }
}

/**
 * One immutable collection: a declared arena seed bank, one setup, a rotating learner seat, and a
 * frozen HARD opponent population. Relative model paths resolve beside the source JSON. A null
 * [policy] collects exact baseline recommendations, without invented behavior likelihoods.
 * [opponentPurchasePolicy], when present, controls purchase decisions for every other seat using
 * that seat's existing HARD opponent model as its prior and nonpurchase policy. An explicit
 * [sharedPurchaseBaseline] instead supplies every seat's purchase prior, including baseline-only
 * rivals; ordinary decisions keep their original models. Only the rotating controlled seat produces
 * training records. Omitting the shared baseline preserves legacy signatures and artifact fields.
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
    val opponentPurchasePolicy: PurchaseActorInput? = null,
    val sharedPurchaseBaseline: PurchaseBaselineInput? = null,
) {
    init {
        require(
            listOfNotNull(policy, opponentPurchasePolicy?.model).all {
                it.stateFeatureNames == dictionary.stateFeatureNames &&
                    it.actionFeatureNames == dictionary.actionFeatureNames
            }
        ) {
            "Every purchase policy must use this configuration's complete encoder dictionaries."
        }
    }

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
                .plus(
                    listOfNotNull(
                        opponentPurchasePolicy?.let {
                            "opponent-purchase-policy:${it.sha256}"
                        },
                        sharedPurchaseBaseline?.let { "shared-purchase-baseline:${it.sha256}" },
                    )
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
                setOf("policy", "opponentPurchasePolicy", "sharedPurchaseBaseline"),
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
            fun actor(name: String): PurchaseActorInput? =
                node[name]
                    ?.takeUnless { it.isNull }
                    ?.let { value ->
                        val path = resolve(PurchaseJson.text(value, name))
                        val hash = PurchaseJson.hash(path)
                        val model = PurchasePolicyInput.read(path)
                        require(PurchaseJson.hash(path) == hash) {
                            "A purchase actor changed while it was being read: $name."
                        }
                        PurchaseActorInput(path, hash, model)
                    }
            val policy = actor("policy")
            val opponentPolicy = actor("opponentPurchasePolicy")
            val sharedBaseline =
                node["sharedPurchaseBaseline"]?.let {
                    baseline(PurchaseJson.text(it, "sharedPurchaseBaseline"))
                }
            val dictionary =
                PurchaseObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            require(PurchaseJson.hash(realSource) == sourceHash) {
                "A purchase source changed while it was being read."
            }
            return PurchaseCollectionInput(
                realSource,
                sourceHash,
                configuration,
                seed,
                seeds,
                incumbent,
                opponents,
                policy?.path,
                policy?.sha256,
                policy?.model,
                dictionary,
                opponentPolicy,
                sharedBaseline,
            )
        }
    }
}
