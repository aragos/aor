package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardFeatureDictionary
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Path

/** Exact card actor bytes; parsing and all numerical invariants belong to the shared AI model. */
internal data class CardActorInput(val path: Path, val sha256: String, val model: CardPolicyModel) {
    init {
        require(path.isAbsolute && sha256.matches(Regex("[0-9a-f]{64}"))) {
            "A card actor requires its absolute source path and exact SHA-256 fingerprint."
        }
    }
}

/**
 * One immutable collection: a declared arena seed bank, one setup, a rotating learner seat, and a
 * frozen HARD opponent population. Relative model paths resolve beside the source JSON. A null
 * [policy] collects exact baseline recommendations, without invented behavior likelihoods.
 * [opponentCardPolicy], when present, controls card decisions for every other seat using that
 * seat's existing HARD opponent model as its card prior. The frozen purchase actor handles shipping
 * and advances for every seat. Only the rotating controlled seat produces card records; absent/null
 * rival actors preserve baseline card choices.
 */
internal data class CardCollectionInput(
    val source: Path,
    val sourceSha256: String,
    val configuration: TrainingConfiguration,
    val seed: Int,
    val seeds: List<Int>,
    val baseline: PurchaseBaselineInput,
    val opponents: List<PurchaseBaselineInput>,
    val policyPath: Path?,
    val modelSha256: String?,
    val policy: CardPolicyModel?,
    val dictionary: CardFeatureDictionary,
    val frozenPurchasePolicy: PurchaseActorInput,
    val opponentCardPolicy: CardActorInput? = null,
) {
    init {
        require(
            listOfNotNull(policy, opponentCardPolicy?.model).all {
                it.stateFeatureNames == dictionary.stateFeatureNames &&
                    it.actionFeatureNames == dictionary.actionFeatureNames
            }
        ) {
            "Every card policy must use this configuration's complete encoder dictionaries."
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
                    "card-collect-1",
                    "gzip-json-v1",
                    frozenPurchasePolicy.sha256,
                    source.toString(),
                    sourceSha256,
                    baseline.sha256,
                    opponents.map { it.sha256 },
                    modelSha256,
                    CardObservationEncoder.ENCODER_VERSION,
                    CardPolicy.ACTOR_VERSION,
                    dictionary.stateFeatureNames,
                    dictionary.actionFeatureNames,
                )
                .plus(
                    listOfNotNull(
                        opponentCardPolicy?.let {
                            "opponent-card-policy:${it.sha256}"
                        }
                    )
                )
                .joinToString("\n")
        )

    companion object {
        fun read(source: Path): CardCollectionInput {
            val realSource = source.toRealPath()
            val sourceHash = PurchaseJson.hash(realSource)
            val node = PurchaseJson.read(realSource)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "configuration",
                    "games",
                    "seed",
                    "baseline",
                    "opponents",
                    "frozenPurchasePolicy",
                ),
                setOf("policy", "opponentCardPolicy"),
            )
            require(PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1) {
                "A card collection requires schemaVersion 1."
            }
            val configurationId = PurchaseJson.text(node.required("configuration"), "configuration")
            val configuration =
                requireNotNull(
                    TrainingMatrix.configurations.singleOrNull { it.id == configurationId }
                ) {
                    "Unsupported card collection configuration: $configurationId."
                }
            val games = PurchaseJson.integer(node.required("games"), "games")
            val seed = PurchaseJson.integer(node.required("seed"), "seed")
            require(games in 1..4096 && seed in 0..100_000_000) {
                "A card collection requires 1–4096 games and an arena seed offset in 0..100000000."
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
                "A card collection requires one through sixteen frozen opponents."
            }
            fun actor(name: String): CardActorInput? =
                node[name]
                    ?.takeUnless { it.isNull }
                    ?.let { value ->
                        val path = resolve(PurchaseJson.text(value, name))
                        val hash = PurchaseJson.hash(path)
                        val model = CardPolicyInput.read(path)
                        require(PurchaseJson.hash(path) == hash) {
                            "A card actor changed while it was being read: $name."
                        }
                        CardActorInput(path, hash, model)
                    }
            val policy = actor("policy")
            val opponentPolicy = actor("opponentCardPolicy")
            val purchasePath =
                resolve(
                    PurchaseJson.text(node.required("frozenPurchasePolicy"), "frozenPurchasePolicy")
                )
            val purchaseHash = PurchaseJson.hash(purchasePath)
            val purchase =
                PurchaseActorInput(
                    purchasePath,
                    purchaseHash,
                    PurchasePolicyInput.read(purchasePath),
                )
            require(PurchaseJson.hash(purchasePath) == purchaseHash) {
                "The frozen purchase actor changed while it was being read."
            }
            val purchaseDictionary =
                PurchaseObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            require(
                purchase.model.stateFeatureNames == purchaseDictionary.stateFeatureNames &&
                    purchase.model.actionFeatureNames == purchaseDictionary.actionFeatureNames
            ) {
                "The frozen purchase actor must retain the setup's complete purchase dictionary."
            }
            val dictionary =
                CardObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            require(PurchaseJson.hash(realSource) == sourceHash) {
                "A card source changed while it was being read."
            }
            return CardCollectionInput(
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
                purchase,
                opponentPolicy,
            )
        }
    }
}
