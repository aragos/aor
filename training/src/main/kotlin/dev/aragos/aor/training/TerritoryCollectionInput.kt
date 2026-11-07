package dev.aragos.aor.training

import java.nio.file.Path

/** One fixed territory behavior bundle and explicit frozen rivals over a prospective seed bank. */
internal data class TerritoryCollectionInput(
    val source: Path,
    val sourceSha256: String,
    val configuration: TrainingConfiguration,
    val seed: Int,
    val seeds: List<Int>,
    val baseline: PurchaseBaselineInput,
    val frozen: JointActorBundle,
    val bundle: TerritoryActorBundle,
    val opponents: List<TerritoryActorBundle>,
) {
    init {
        require(bundle.token != null && bundle.expansion != null) {
            "The territory learning seat requires both behavior actors."
        }
        require(opponents.size in 1..16) {
            "Territory collection requires one through sixteen frozen rival bundles."
        }
        require(
            (opponents + bundle).all {
                it.configuration == configuration &&
                    it.baselineSha256 == baseline.sha256 &&
                    it.frozen.sha256 == frozen.sha256
            }
        ) {
            "Every territory role must preserve the same setup, F prior and frozen continuation."
        }
        require(
            seeds.isNotEmpty() &&
                seeds.size <= 4096 &&
                seed in 0..100_000_000 &&
                seeds == List(seeds.size) { StrategyArena.seed(seed, it) }
        ) {
            "A territory collection requires a bounded, exact arena seed bank."
        }
    }

    fun seat(index: Int): Int = index % configuration.playerCount

    fun opponentIndices(index: Int): List<Int> =
        List(configuration.playerCount) { player ->
            (index / configuration.playerCount + player) % opponents.size
        }

    fun signature(): String =
        trainingSignature(
            listOf(
                    "territory-collect-1",
                    CardTrajectoryFiles.ENCODING,
                    source,
                    sourceSha256,
                    baseline.sha256,
                    frozen.metadata(),
                    bundle.metadata(),
                    opponents.map(TerritoryActorBundle::metadata),
                )
                .joinToString("\n")
        )

    fun files(): List<Path> =
        listOf(source, baseline.path) +
            bundle.files() +
            opponents.flatMap(TerritoryActorBundle::files)

    companion object {
        fun read(source: Path): TerritoryCollectionInput {
            val file = source.toRealPath()
            val hash = PurchaseJson.hash(file)
            val node = PurchaseJson.read(file)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "kind",
                    "configuration",
                    "games",
                    "seed",
                    "baseline",
                    "frozenCardPurchaseBundle",
                    "bundle",
                    "opponents",
                ),
            )
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    PurchaseJson.text(node.required("kind"), "kind") == "territory-collect"
            ) {
                "Territory collection requires explicit kind territory-collect and schemaVersion 1."
            }
            val configurationId = PurchaseJson.text(node.required("configuration"), "configuration")
            val configuration =
                requireNotNull(
                    TrainingMatrix.configurations.singleOrNull { it.id == configurationId }
                ) {
                    "Unsupported territory collection configuration: $configurationId."
                }
            val games = PurchaseJson.integer(node.required("games"), "games")
            val seed = PurchaseJson.integer(node.required("seed"), "seed")
            require(games in 1..4096 && seed in 0..100_000_000) {
                "Territory games/seed exceed the declared collection bounds."
            }
            fun resolve(text: String): Path = file.parent.resolve(text).normalize().toRealPath()
            val baselinePath = resolve(PurchaseJson.text(node.required("baseline"), "baseline"))
            val baselineHash = PurchaseJson.hash(baselinePath)
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    baselineHash,
                    TrainingArtifacts.readModel(baselinePath),
                )
            val frozen =
                JointActorBundle.read(
                    resolve(
                        PurchaseJson.text(
                            node.required("frozenCardPurchaseBundle"),
                            "frozenCardPurchaseBundle",
                        )
                    ),
                    configuration,
                    baseline,
                )
            fun bundle(text: String): TerritoryActorBundle =
                TerritoryActorBundle.read(resolve(text), configuration, baseline, frozen)
            val own = bundle(PurchaseJson.text(node.required("bundle"), "bundle"))
            val opponents =
                PurchaseJson.strings(node.required("opponents"), "opponents").map(::bundle)
            require(
                PurchaseJson.hash(file) == hash && PurchaseJson.hash(baselinePath) == baselineHash
            ) {
                "Territory source or F prior changed while being read."
            }
            return TerritoryCollectionInput(
                file,
                hash,
                configuration,
                seed,
                List(games) { StrategyArena.seed(seed, it) },
                baseline,
                frozen,
                own,
                opponents,
            )
        }
    }
}
