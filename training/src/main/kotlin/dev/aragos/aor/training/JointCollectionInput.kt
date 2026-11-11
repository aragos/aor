package dev.aragos.aor.training

import java.nio.file.Path

/**
 * One fixed joint behavior bundle and explicit frozen rival bundles over a prospective seed bank.
 */
internal data class JointCollectionInput(
    val source: Path,
    val sourceSha256: String,
    val configuration: TrainingConfiguration,
    val seed: Int,
    val seeds: List<Int>,
    val baseline: PurchaseBaselineInput,
    val bundle: JointActorBundle,
    val opponents: List<JointActorBundle>,
    val ecology: JointEcology? = null,
) {
    init {
        require(bundle.card != null) { "The joint learning seat requires both behavior actors." }
        require(opponents.size in 1..16) {
            "Joint collection requires one through sixteen frozen rival bundles."
        }
        require(
            (opponents + bundle).all {
                it.configuration == configuration && it.baselineSha256 == purchaseBaseline.sha256
            }
        ) {
            "Every joint role must preserve the same setup and exact F prior."
        }
        require(
            seeds.isNotEmpty() &&
                seeds.size <= 4096 &&
                seed in 0..100_000_000 &&
                seeds == List(seeds.size) { StrategyArena.seed(seed, it) }
        ) {
            "A joint collection requires a bounded, exact arena seed bank."
        }
    }

    val purchaseBaseline: PurchaseBaselineInput
        get() = ecology?.purchaseBaseline ?: baseline

    fun seat(index: Int): Int = index % configuration.playerCount

    fun opponentIndices(index: Int): List<Int> =
        List(configuration.playerCount) { player ->
            (index / configuration.playerCount + player) % opponents.size
        }

    fun signature(): String =
        trainingSignature(
            listOf(
                    "joint-collect-1",
                    CardTrajectoryFiles.ENCODING,
                    source,
                    sourceSha256,
                    baseline.sha256,
                    bundle.metadata(),
                    opponents.map(JointActorBundle::metadata),
                )
                .plus(listOfNotNull(ecology?.identity()))
                .joinToString("\n")
        )

    fun files(): List<Path> =
        listOf(source, baseline.path) +
            bundle.files() +
            opponents.flatMap(JointActorBundle::files) +
            ecology?.files.orEmpty()

    companion object {
        fun read(source: Path): JointCollectionInput {
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
                    "bundle",
                    "opponents",
                ),
                JointEcology.FIELDS,
            )
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    PurchaseJson.text(node.required("kind"), "kind") == "joint-collect"
            ) {
                "Joint collection requires explicit kind joint-collect and schemaVersion 1."
            }
            val configurationId = PurchaseJson.text(node.required("configuration"), "configuration")
            val configuration =
                requireNotNull(
                    TrainingMatrix.configurations.singleOrNull { it.id == configurationId }
                ) {
                    "Unsupported joint collection configuration: $configurationId."
                }
            val games = PurchaseJson.integer(node.required("games"), "games")
            val seed = PurchaseJson.integer(node.required("seed"), "seed")
            require(games in 1..4096 && seed in 0..100_000_000) {
                "Joint games/seed exceed the declared collection bounds."
            }
            fun resolve(text: String): Path = file.parent.resolve(text).normalize().toRealPath()
            val transfer = ExperimentalRulesTransfer.read(node["experimentalRulesTransfer"])
            val baselinePath = resolve(PurchaseJson.text(node.required("baseline"), "baseline"))
            val baselineHash = PurchaseJson.hash(baselinePath)
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    baselineHash,
                    TrainingArtifacts.readModel(baselinePath, transfer),
                )
            val opponentPaths = PurchaseJson.strings(node.required("opponents"), "opponents")
            val ecology =
                JointEcology.read(file, node, configuration, baseline, opponentPaths.size, games)
            fun bundle(text: String): JointActorBundle =
                JointActorBundle.read(
                    resolve(text),
                    configuration,
                    ecology?.purchaseBaseline ?: baseline,
                )
            val own = bundle(PurchaseJson.text(node.required("bundle"), "bundle"))
            val opponents = opponentPaths.map(::bundle)
            require(
                PurchaseJson.hash(file) == hash && PurchaseJson.hash(baselinePath) == baselineHash
            ) {
                "Joint source or F prior changed while being read."
            }
            return JointCollectionInput(
                file,
                hash,
                configuration,
                seed,
                List(games) { StrategyArena.seed(seed, it) },
                baseline,
                own,
                opponents,
                ecology,
            )
        }
    }
}
