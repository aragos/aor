package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.CardAction
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.PurchaseAction
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Files
import java.nio.file.Path

/** Complete four-player dictionaries with small independent bundle/source fixtures. */
internal class JointCollectionFixture(
    games: Int = 1,
    learnedRivals: Boolean = false,
    val configuration: TrainingConfiguration =
        TrainingMatrix.stratified.first { it.playerCount == 4 },
) : AutoCloseable {
    val directory: Path = Files.createTempDirectory("aor-joint-collection-")
    val artifacts = TrainingArtifacts(directory.resolve("inputs"))
    val source: Path = artifacts.directory.resolve("source.json")
    val output: Path = directory.resolve("output")

    init {
        artifacts.model(BotModels.default, "baseline.json")
        val catalog = PublicCatalog.forPlayers(4, configuration.rules)
        val card = CardObservationEncoder.dictionary(catalog)
        val purchase = PurchaseObservationEncoder.dictionary(catalog)
        artifacts.write(
            "card-policy.json",
            model(
                CardObservationEncoder.ENCODER_VERSION,
                card.stateFeatureNames,
                card.actionFeatureNames,
            ),
        )
        artifacts.write(
            "purchase-policy.json",
            model(
                PurchaseObservationEncoder.ENCODER_VERSION,
                purchase.stateFeatureNames,
                purchase.actionFeatureNames,
            ),
        )
        writeBundle("bundle.json")
        writeBundle("rival.json", cardName = if (learnedRivals) "card-policy.json" else null)
        artifacts.write(
            "source.json",
            mapOf(
                "schemaVersion" to 1,
                "kind" to "joint-collect",
                "configuration" to configuration.id,
                "games" to games,
                "seed" to 71,
                "baseline" to "baseline.json",
                "bundle" to "bundle.json",
                "opponents" to listOf("rival.json"),
            ),
        )
    }

    val input: JointCollectionInput
        get() = JointCollectionInput.read(source)

    fun writeBundle(
        name: String,
        cardName: String? = "card-policy.json",
        purchaseName: String = "purchase-policy.json",
    ) {
        fun component(file: String): Map<String, String> =
            mapOf(
                "path" to file,
                "sha256" to PurchaseJson.hash(artifacts.directory.resolve(file)),
            )
        artifacts.write(
            name,
            mapOf(
                "schemaVersion" to 1,
                "kind" to JointActorBundle.VERSION,
                "configuration" to configuration.id,
                "baselineSha256" to PurchaseJson.hash(artifacts.directory.resolve("baseline.json")),
                "card" to cardName?.let(::component),
                "purchase" to component(purchaseName),
            ),
        )
    }

    fun store(
        input: JointCollectionInput = this.input,
        signature: String = "fixture",
    ): JointCollectionStore = JointCollectionStore(input, output, signature)

    fun arguments(): List<String> =
        listOf(
            "--mode",
            "joint-collect",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
            "--workers",
            "1",
            "--hours",
            ".01",
        )

    fun syntheticGame(input: JointCollectionInput = this.input): JointGameTrajectory =
        JointGameTrajectory(
            signature = "fixture",
            index = 0,
            configuration = configuration.id,
            gameSeed = input.seeds[0],
            controlledSeat = 0,
            opponentIndices = input.opponentIndices(0),
            bundleSha256 = input.bundle.sha256,
            cardModelSha256 =
                requireNotNull(input.bundle.card) { "The fixture requires a card actor." }.sha256,
            purchaseModelSha256 = input.bundle.purchase.sha256,
            cardDecisions =
                listOf(
                    CardDecisionRecord(
                        0,
                        CardPolicy.actorSeed(input.seeds[0]),
                        "play-cards",
                        1,
                        CardObservation(
                            stateFeatures = emptyMap(),
                            actions = listOf(CardAction("pass", emptyMap())),
                            baselineIndex = 0,
                        ),
                        0,
                        0.0,
                        .5,
                    )
                ),
            purchaseDecisions =
                listOf(
                    PurchaseDecisionRecord(
                        1,
                        PurchasePolicy.actorSeed(input.seeds[0] xor 65537),
                        "purchase-advances",
                        1,
                        PurchaseObservation(
                            stateFeatures = emptyMap(),
                            actions = listOf(PurchaseAction("pass", emptyMap())),
                            baselineIndex = 0,
                        ),
                        0,
                        0.0,
                        .5,
                    )
                ),
            terminal =
                PurchaseTerminal(
                    1.0,
                    1.0,
                    1.0,
                    List(4) { seat ->
                        PilotReplayScore(
                            "p${seat + 1}",
                            0,
                            if (seat == 0) 10 else 0,
                            0,
                            if (seat == 0) 10 else 0,
                            seat == 0,
                        )
                    },
                    2,
                ),
        )

    fun evaluation(job: CardAdvantageEvaluationJob): CardAdvantageEvaluationInput {
        artifacts.write(
            "evaluation.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "baseline" to "baseline.json",
                "frozenPurchasePolicy" to "purchase-policy.json",
                "policies" to
                    mapOf(
                        "baseline" to mapOf("kind" to "baseline"),
                        "candidate" to mapOf("kind" to "joint-bundle", "path" to "bundle.json"),
                        "rival" to mapOf("kind" to "joint-bundle", "path" to "rival.json"),
                    ),
                "jobs" to listOf(job),
            ),
        )
        return CardAdvantageEvaluationInput.read(artifacts.directory.resolve("evaluation.json"))
    }

    fun legacyInput(): CardCollectionInput {
        val current = input
        artifacts.write(
            "legacy.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "games" to current.seeds.size,
                "seed" to current.seed,
                "baseline" to "baseline.json",
                "opponents" to listOf("baseline.json"),
                "policy" to "card-policy.json",
                "frozenPurchasePolicy" to "purchase-policy.json",
                "opponentCardPolicy" to current.opponents.single().card?.path?.toString(),
            ),
        )
        return CardCollectionInput.read(artifacts.directory.resolve("legacy.json"))
    }

    override fun close() {
        directory.toFile().deleteRecursively()
    }

    private fun model(
        encoder: String,
        states: List<String>,
        actions: List<String>,
    ): Map<String, Any> =
        mapOf(
            "schemaVersion" to 1,
            "encoderVersion" to encoder,
            "hiddenSize" to 64,
            "baselineProbability" to .97,
            "stateFeatureNames" to states,
            "actionFeatureNames" to actions,
            "stateWeights" to List(64 * states.size) { 0.0 },
            "stateBias" to List(64) { 0.0 },
            "actionWeights" to List(64 * actions.size) { 0.0 },
            "actionBias" to List(64) { 0.0 },
            "actorWeights" to List(64) { 0.0 },
            "valueWeights" to List(64) { 0.0 },
            "valueBias" to 0.0,
        )
}
