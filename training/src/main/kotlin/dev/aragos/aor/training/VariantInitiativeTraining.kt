package dev.aragos.aor.training

import dev.aragos.aor.Command
import dev.aragos.aor.FinalAdvanceGuard
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.InitiativePolicyConfig
import dev.aragos.aor.ai.PhaseInitiativeConfig
import dev.aragos.aor.ai.PhaseInitiativePolicy

/** Tune the value of earlier and later order against distinct public bidding opponents. */
internal object VariantInitiativeTraining {
    private enum class Opposition {
        ORDINARY,
        UNDERCUT,
        PAIRED_OFFSETS,
        SWITCHING,
    }

    private data class Design(
        val name: String,
        val config: PhaseInitiativeConfig? = null,
        val model: BotModel? = null,
    )

    private data class Game(
        val configuration: TrainingConfiguration,
        val seed: Int,
        val seat: Int,
        val opposition: Opposition,
    )

    private data class Outcome(val winShare: Double, val reward: Double, val changedBids: Int)

    private data class World(
        val game: Game,
        val control: Outcome,
        val candidates: Map<String, Outcome>,
    )

    private data class Comparison(
        val worlds: Int,
        val changedBidWorlds: Int,
        val winEffect: PairedEffect,
        val rewardEffect: PairedEffect,
    )

    private val designs =
        listOf(
            Design("conservative", design(3, 2, .55, .65, .5, .45, .018)),
            Design("card-economy", design(4, 3, 1.2, 1.0, .5, .30, .020)),
            Design("defensive", design(4, 4, .9, .65, 1.5, .2, .020)),
            Design("flexible", design(6, 5, 1.3, .9, 1.15, .4, .032)),
        )

    fun run(
        tournaments: Tournaments,
        configurations: List<TrainingConfiguration>,
        seeds: Int,
        seedOffset: Int,
        incumbent: BotModel,
        artifacts: TrainingArtifacts,
        ledger: CoverageLedger,
        opponentModels: List<BotModel>,
        confirmWithFinishGuard: Boolean,
        challenger: BotModel?,
    ) {
        if (confirmWithFinishGuard) {
            evaluate(
                tournaments,
                configurations,
                seeds,
                seedOffset,
                incumbent,
                artifacts,
                ledger,
                opponentModels,
                challenger?.let { Design("challenger-model", model = it) } ?: designs.first(),
                FinalAdvanceGuard(currentLead = 100, projectedLead = 250),
            )
            return
        }
        // Train on complete player-count blocks spread through both opening variants.
        val development =
            configurations
                .groupBy { it.rules.drawThreeKeepTwo }
                .values
                .flatMap { group ->
                    group
                        .groupBy { it.rules }
                        .values
                        .filterIndexed { index, _ -> index % 3 == 0 }
                        .flatten()
                }
        println(
            "Screening ${designs.size} initiative policies in ${development.size} variant setups."
        )
        val screening =
            play(
                tournaments,
                development,
                seeds.coerceAtMost(2),
                seedOffset,
                Opposition.entries.filterNot { it == Opposition.SWITCHING },
                designs,
                incumbent,
                ledger,
                opponentModels,
                heldOut = false,
            )
        val byOpponent = screening.groupBy { it.game.opposition }
        val scored = designs.associate { design ->
            val outcomes = byOpponent.mapValues { (_, rows) -> compare(rows, design.name) }
            val overall = compare(screening, design.name)
            val worst = outcomes.values.minOf { it.winEffect.mean }
            design.name to Triple(overall, outcomes, .6 * overall.winEffect.mean + .4 * worst)
        }
        val best =
            designs
                .filter { design ->
                    val (overall, outcomes) =
                        checkNotNull(scored[design.name]) {
                            "Every configured initiative design needs completed outcomes."
                        }
                    overall.winEffect.mean >= .01 &&
                        outcomes.values.minOf { it.winEffect.mean } >= -.025
                }
                .maxByOrNull { design -> scored[design.name]?.third ?: Double.NEGATIVE_INFINITY }
        artifacts.write(
            "initiative-screen.json",
            mapOf(
                "selected" to best?.name,
                "configurations" to development.map(TrainingConfiguration::id),
                "candidates" to
                    scored.mapValues { (_, score) ->
                        mapOf(
                            "comparison" to score.first,
                            "byOpponent" to score.second,
                            "rank" to score.third,
                        )
                    },
            ),
        )
        if (best == null) {
            artifacts.write("evaluation.json", mapOf("qualified" to false, "phase" to "screening"))
            println("The initiative screen produced no candidate for a held-out test.")
            return
        }
        evaluate(
            tournaments,
            configurations,
            seeds,
            seedOffset,
            incumbent,
            artifacts,
            ledger,
            opponentModels,
            best,
        )
    }

    private fun evaluate(
        tournaments: Tournaments,
        configurations: List<TrainingConfiguration>,
        seeds: Int,
        seedOffset: Int,
        incumbent: BotModel,
        artifacts: TrainingArtifacts,
        ledger: CoverageLedger,
        opponentModels: List<BotModel>,
        best: Design,
        finishGuard: FinalAdvanceGuard? = null,
    ) {
        best.config?.let { artifacts.write("initiative-candidate.json", it) }
        println(
            "Testing ${best.name} on fresh seeds in all ${configurations.size} selected setups."
        )
        val holdout =
            play(
                tournaments,
                configurations,
                seeds,
                seedOffset,
                Opposition.entries,
                listOf(best),
                incumbent,
                ledger,
                opponentModels,
                heldOut = true,
                finishGuard = finishGuard,
            )
        artifacts.write(
            "paired-worlds.json",
            holdout.map { world ->
                mapOf(
                    "configuration" to world.game.configuration.id,
                    "seed" to world.game.seed,
                    "seat" to world.game.seat,
                    "opponent" to world.game.opposition,
                    "control" to world.control,
                    "candidate" to world.candidates[best.name],
                )
            },
        )
        val overall = compare(holdout, best.name)
        val groups =
            holdout
                .groupBy { it.game.opposition }
                .mapValues { (_, rows) -> compare(rows, best.name) }
        val byCount =
            holdout
                .groupBy { it.game.configuration.playerCount }
                .toSortedMap()
                .mapValues { (_, rows) -> compare(rows, best.name) }
        val qualified =
            overall.winEffect.lower95 > 0 &&
                groups.values.minOf { it.winEffect.lower95 } > -.045 &&
                byCount.values.minOf { it.winEffect.lower95 } > -.05
        artifacts.write(
            "evaluation.json",
            mapOf(
                "qualified" to qualified,
                "selected" to best.name,
                "finishGuard" to finishGuard,
                "comparison" to overall,
                "byOpponent" to groups,
                "byPlayerCount" to byCount,
                "byDrawThreeKeepTwo" to
                    holdout
                        .groupBy { it.game.configuration.rules.drawThreeKeepTwo }
                        .mapValues { (_, rows) -> compare(rows, best.name) },
                "byConfiguration" to
                    holdout
                        .groupBy { it.game.configuration.id }
                        .toSortedMap()
                        .mapValues { (_, rows) -> compare(rows, best.name) },
            ),
        )
        println("Held-out initiative qualification passed=$qualified $overall")
    }

    private fun play(
        tournaments: Tournaments,
        configurations: List<TrainingConfiguration>,
        seeds: Int,
        seedOffset: Int,
        opponents: List<Opposition>,
        designs: List<Design>,
        incumbent: BotModel,
        ledger: CoverageLedger,
        opponentModels: List<BotModel>,
        heldOut: Boolean,
        finishGuard: FinalAdvanceGuard? = null,
    ): List<World> {
        val games = opponents.flatMap { opposition ->
            configurations.flatMapIndexed { index, configuration ->
                (0 until seeds).map { repetition ->
                    Game(
                        configuration,
                        tournamentSeed(
                            seedOffset + opposition.ordinal * 17_009_111,
                            index,
                            repetition,
                            heldOut,
                        ),
                        (index + repetition) % configuration.playerCount,
                        opposition,
                    )
                }
            }
        }
        check(games.map(Game::seed).distinct().size == games.size) {
            "Initiative comparisons require distinct prospectively selected game seeds."
        }
        val results =
            tournaments.run(
                games.map { game ->
                    {
                        val control =
                            simulate(
                                game,
                                incumbent,
                                null,
                                tournaments.deadlineNanos,
                                ledger,
                                opponentModels,
                                finishGuard,
                            )
                        val outcomes = designs.associate { design ->
                            design.name to
                                simulate(
                                    game,
                                    incumbent,
                                    design,
                                    tournaments.deadlineNanos,
                                    ledger,
                                    opponentModels,
                                    finishGuard,
                                )
                        }
                        World(game, control, outcomes)
                    }
                }
            )
        return results.sortedBy { it.game.seed }
    }

    private fun simulate(
        game: Game,
        incumbent: BotModel,
        design: Design?,
        deadline: Long,
        ledger: CoverageLedger,
        opponentModels: List<BotModel>,
        finishGuard: FinalAdvanceGuard?,
    ): Outcome {
        val count = game.configuration.playerCount
        val competitors =
            List(count) { seat ->
                if (seat == game.seat) {
                    Competitor(design?.model ?: incumbent, BotDifficulty.HARD)
                } else if (opponentModels.isNotEmpty()) {
                    val modelIndex = Math.floorMod(game.seed xor (seat * 31), opponentModels.size)
                    Competitor(opponentModels[modelIndex], BotDifficulty.HARD)
                } else {
                    val difficulty =
                        if ((seat + game.seed) % 3 == 0) BotDifficulty.HARD
                        else BotDifficulty.NORMAL
                    Competitor(incumbent, difficulty)
                }
            }
        val actor = PlayerId("p${game.seat + 1}")
        val adversary = (game.seat + 1) % count
        var changes = 0
        val controllers = buildMap {
            if (design != null) {
                put(
                    game.seat,
                    tokenController { decision ->
                        val nominal = ordinary(decision, competitors[game.seat])
                        val selected =
                            design.config?.let { config ->
                                PhaseInitiativePolicy.bid(
                                    decision.view,
                                    actor,
                                    nominal,
                                    decision.seed,
                                    config,
                                )
                            } ?: nominal
                        val baseline =
                            if (design.model == null) nominal
                            else ordinary(decision, Competitor(incumbent, BotDifficulty.HARD))
                        if (selected != baseline) changes++
                        selected
                    },
                )
            }
            if (game.opposition != Opposition.ORDINARY) {
                put(
                    adversary,
                    tokenController { decision ->
                        opposedBid(decision, actor, game.opposition)
                            ?: ordinary(decision, competitors[adversary])
                    },
                )
            }
        }
        val terminal =
            Simulation()
                .play(
                    game.configuration,
                    game.seed,
                    competitors,
                    deadline,
                    controllers = controllers,
                    finalAdvanceGuards =
                        finishGuard
                            ?.let { guard -> competitors.indices.associateWith { guard } }
                            .orEmpty(),
                )
        synchronized(ledger) { ledger.add(terminal) }
        return Outcome(terminal.winShare(game.seat), terminal.reward(game.seat), changes)
    }

    private fun opposedBid(
        decision: SimulationDecision,
        target: PlayerId,
        style: Opposition,
    ): String? {
        val turn = decision.view.turnNumber ?: return null
        val history = decision.view.pastTokenPurchases.takeLast(4).mapNotNull { it[target] }
        if (turn <= 2 || history.size < 2) return null
        val counts = history.groupingBy { it }.eachCount()
        val maximum = counts.values.max()
        val forecast = history.last { counts[it] == maximum }
        val offset =
            when (style) {
                Opposition.UNDERCUT -> -1
                Opposition.PAIRED_OFFSETS -> if ((turn - 3) % 4 < 2) -1 else 2
                Opposition.SWITCHING -> if (decision.view.hand.size <= 1 || turn % 3 == 0) -2 else 3
                Opposition.ORDINARY -> return null
            }
        val command = Command.BuyTokens(forecast + offset).toText()
        return command.takeIf { offer ->
            decision.observation.candidates.any { it.command == offer }
        }
    }

    private fun ordinary(decision: SimulationDecision, competitor: Competitor): String =
        BotPolicy.choose(
            decision.observation,
            competitor.difficulty,
            decision.seed,
            competitor.model,
        )

    private fun tokenController(select: (SimulationDecision) -> String): SimulationController =
        object : SimulationController {
            override fun controls(decision: String): Boolean = decision == "buy-tokens"

            override fun choose(decision: SimulationDecision): String = select(decision)
        }

    private fun compare(worlds: List<World>, design: String): Comparison {
        val comparisons = worlds.map { world ->
            val candidate =
                checkNotNull(world.candidates[design]) {
                    "Every initiative world needs the declared candidate."
                }
            candidate to world.control
        }
        return Comparison(
            worlds.size,
            comparisons.count { (candidate, _) -> candidate.changedBids > 0 },
            pairedEffect(
                comparisons.map { (candidate, control) -> candidate.winShare - control.winShare }
            ),
            pairedEffect(
                comparisons.map { (candidate, control) -> candidate.reward - control.reward }
            ),
        )
    }

    private fun design(
        loss: Int,
        extra: Int,
        payout: Double,
        cards: Double,
        safety: Double,
        conflict: Double,
        expansion: Double,
    ): PhaseInitiativeConfig =
        PhaseInitiativeConfig(
            InitiativePolicyConfig(
                maximumTokenLoss = loss,
                tokenPenalty = .10,
                participation = 1.0,
                requireAffordableContest = false,
                attackBonus = 0.0,
                historyDecay = .9,
                bidScoreTolerance = .25,
                maximumExtraTokens = extra,
                planWeight = expansion,
                extraCashPenalty = .24,
                counterAnticipation = .35,
                respectRivalCash = true,
            ),
            earlyPayout = payout,
            buyingCard = cards,
            secureMarkets = safety,
            earlyCompetition = conflict,
        )
}
