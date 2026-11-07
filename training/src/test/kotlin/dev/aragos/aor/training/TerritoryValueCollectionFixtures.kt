package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryValuePolicy
import dev.aragos.aor.ai.TokenObservationEncoder

/** The existing joint fixture supplies exactly the same frozen card/purchase continuation. */
internal class TerritoryValueCollectionFixture(
    games: Int = 1,
    learnedRivals: Boolean = false,
    expansionEncoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_1,
) : AutoCloseable {
    val joint = JointCollectionFixture(games, learnedRivals = true)
    val artifacts = joint.artifacts
    val output = joint.directory.resolve("territory-output")
    val source = artifacts.directory.resolve("territory-source.json")
    val configuration = joint.configuration

    init {
        val catalog = PublicCatalog.forPlayers(4, configuration.rules)
        for (phase in TerritoryPhase.entries) {
            val names =
                if (phase == TerritoryPhase.TOKEN) TokenObservationEncoder.dictionary(catalog)
                else ExpansionObservationEncoder.dictionary(catalog, expansionEncoder)
            artifacts.write(
                "${phase.name.lowercase()}-policy.json",
                mapOf(
                    "schemaVersion" to 1,
                    "architecture" to TerritoryValuePolicy.ARCHITECTURE,
                    "encoderVersion" to
                        if (phase == TerritoryPhase.EXPANSION) expansionEncoder.version
                        else phase.encoderVersion,
                    "hiddenSize" to 64,
                    "stateFeatureNames" to names.stateFeatureNames,
                    "actionFeatureNames" to names.actionFeatureNames,
                    "stateWeights" to List(64 * names.stateFeatureNames.size) { 0.0 },
                    "stateBias" to List(64) { 0.0 },
                    "actionWeights" to List(64 * names.actionFeatureNames.size) { 0.0 },
                    "actionBias" to List(64) { 0.0 },
                    "outputWeights" to List(64) { 0.0 },
                    "outputBias" to .5,
                ),
            )
        }
        writeBundle("territory.json")
        writeBundle("territory-rival.json", token = learnedRivals, expansion = learnedRivals)
        artifacts.write(
            "territory-source.json",
            mapOf(
                "schemaVersion" to 1,
                "kind" to "territory-value-collect",
                "epsilon" to .2,
                "configuration" to configuration.id,
                "games" to games,
                "seed" to 71,
                "baseline" to "baseline.json",
                "frozenCardPurchaseBundle" to "bundle.json",
                "bundle" to "territory.json",
                "opponents" to listOf("territory-rival.json"),
            ),
        )
    }

    val input: TerritoryValueCollectionInput
        get() = TerritoryValueCollectionInput.read(source)

    fun writeBundle(name: String, token: Boolean = true, expansion: Boolean = true) {
        fun component(name: String) =
            mapOf("path" to name, "sha256" to PurchaseJson.hash(artifacts.directory.resolve(name)))
        artifacts.write(
            name,
            mapOf(
                "schemaVersion" to 1,
                "kind" to TerritoryValueBundle.VERSION,
                "configuration" to configuration.id,
                "baselineSha256" to PurchaseJson.hash(artifacts.directory.resolve("baseline.json")),
                "frozenCardPurchaseBundleSha256" to
                    PurchaseJson.hash(artifacts.directory.resolve("bundle.json")),
                "token" to if (token) component("token-policy.json") else null,
                "expansion" to if (expansion) component("expansion-policy.json") else null,
            ),
        )
    }

    fun store(input: TerritoryValueCollectionInput = this.input, signature: String = "fixture") =
        TerritoryValueCollectionStore(input, output, signature)

    fun syntheticGame(
        input: TerritoryValueCollectionInput = this.input,
        index: Int = 0,
    ): TerritoryValueGameTrajectory {
        val seat = input.seat(index)
        fun row(phase: TerritoryPhase, number: Int, batch: Int): TerritoryValueDecisionRecord {
            val seed = input.seeds[index] xor (batch * 65537 + seat * 8191)
            return TerritoryValueDecisionRecord(
                number,
                batch,
                seed,
                TerritoryValuePolicy.selectorSeed(phase, seed),
                phase.decision,
                1,
                TerritoryObservation(
                    requireNotNull(
                            if (phase == TerritoryPhase.TOKEN) input.bundle.token
                            else input.bundle.expansion
                        ) {
                            "The fixture has both Q models."
                        }
                        .model
                        .encoderVersion,
                    emptyMap(),
                    listOf(
                        TerritoryAction(
                            if (phase == TerritoryPhase.TOKEN) "buy-tokens 0" else "pass",
                            emptyMap(),
                        )
                    ),
                    0,
                ),
                0,
                .5,
                1.0,
            )
        }
        return TerritoryValueGameTrajectory(
            signature = "fixture",
            index = index,
            configuration = configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = input.opponentIndices(index),
            bundleSha256 = input.bundle.sha256,
            frozenCardPurchaseBundleSha256 = input.frozen.sha256,
            tokenModelSha256 =
                requireNotNull(input.bundle.token) { "The fixture has a token actor." }.sha256,
            expansionModelSha256 =
                requireNotNull(input.bundle.expansion) { "The fixture has an expansion actor." }
                    .sha256,
            tokenDecisions = listOf(row(TerritoryPhase.TOKEN, seat, 0)),
            expansionDecisions = listOf(row(TerritoryPhase.EXPANSION, 4, 4)),
            terminal =
                PurchaseTerminal(
                    1.0,
                    1.0,
                    1.0,
                    List(4) { player ->
                        PilotReplayScore(
                            "p${player + 1}",
                            0,
                            if (player == seat) 10 else 0,
                            0,
                            if (player == seat) 10 else 0,
                            player == seat,
                        )
                    },
                    5,
                ),
        )
    }

    fun evaluation(seed: Int, seat: Int = 0): CardAdvantageEvaluationInput {
        artifacts.write(
            "territory-evaluation.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "baseline" to "baseline.json",
                "frozenPurchasePolicy" to "purchase-policy.json",
                "policies" to
                    mapOf(
                        "baseline" to mapOf("kind" to "baseline"),
                        "candidate" to
                            mapOf(
                                "kind" to "territory-value-bundle",
                                "epsilon" to 0.0,
                                "path" to "territory.json",
                                "frozenCardPurchaseBundle" to "bundle.json",
                            ),
                        "rival" to
                            mapOf(
                                "kind" to "territory-value-bundle",
                                "epsilon" to 0.0,
                                "path" to "territory-rival.json",
                                "frozenCardPurchaseBundle" to "bundle.json",
                            ),
                    ),
                "jobs" to
                    listOf(
                        CardAdvantageEvaluationJob(
                            0,
                            seed,
                            seat,
                            "candidate",
                            "candidate",
                            List(4) { "rival" },
                        )
                    ),
            ),
        )
        return CardAdvantageEvaluationInput.read(
            artifacts.directory.resolve("territory-evaluation.json")
        )
    }

    override fun close() = joint.close()
}
