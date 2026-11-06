package dev.aragos.aor.training

import dev.aragos.aor.GameView
import dev.aragos.aor.IncomeView
import dev.aragos.aor.ScoreView

/**
 * A diagnostic replay starts from [scenarioId] sampled with [seed], not an ordinary saved opening.
 * [entries] contain canonical authority outcomes, including simulated private draws, for exact
 * offline replay. Only [decisions]' acting-seat views are policy inputs; entries are never inputs.
 * Timing includes tracing overhead and must not be compared with ordinary throughput benchmarks.
 */
data class PilotReplay(
    val scenarioId: String,
    val scenarioSeed: Int,
    val seed: Int,
    val strategy: CommodityExperimentStrategy,
    val actor: String,
    val commodity: String,
    val game: PilotGame,
    val entries: List<String>,
    val decisions: List<PilotReplayDecision>,
    val finalScores: List<PilotReplayScore>,
    val replayVerified: Boolean,
)

/** Snapshot immediately before the controlled seat's command, using its ordinary safe view. */
data class PilotReplayDecision(
    val number: Int,
    val turn: Int?,
    val stage: String,
    val decision: String,
    val command: String,
    val deckRemaining: Int,
    val endingTriggered: Boolean,
    val stabilizationCost: Long?,
    val players: List<PilotReplayPlayer>,
    val hand: List<PilotReplayCard>,
    val board: List<PilotReplayProvince>,
    val commodityForecasts: List<PilotReplayPayout>,
    val expansionOffers: List<PilotReplayExpansion>,
) {
    companion object {
        fun from(
            number: Int,
            view: GameView,
            decision: String,
            command: String,
        ): PilotReplayDecision {
            val provinces =
                checkNotNull(view.catalog) {
                        "A diagnostic decision requires its public province catalog."
                    }
                    .provinces
                    .associateBy { it.id }
            return PilotReplayDecision(
                number = number,
                turn = view.turnNumber,
                stage = view.stage.name,
                decision = decision,
                command = command,
                deckRemaining = view.deckRemaining,
                endingTriggered = view.endingTriggered,
                stabilizationCost = view.stabilizationCost,
                players =
                    view.players.map { player ->
                        PilotReplayPlayer(
                            id = player.id.value,
                            cash = player.cash,
                            poolTokens = player.poolTokens,
                            stockTokens = player.stockTokens,
                            markers = player.boardMarkers,
                            misery = player.misery.value,
                            advances = player.advances.map { it.name }.sorted(),
                            shipping = player.ships.name,
                            income = player.income,
                            handCount = player.handCount,
                        )
                    },
                hand =
                    view.hand.map { card ->
                        PilotReplayCard(
                            card.reference,
                            card.definition.id,
                            card.definition.messageId,
                        )
                    },
                board =
                    view.board.map { province ->
                        val definition =
                            checkNotNull(provinces[province.id]) {
                                "An occupied province must belong to the public catalog."
                            }
                        PilotReplayProvince(
                            id = province.id.value,
                            commodities = definition.commodities.map { it.name },
                            markerOwner = province.marker?.owner?.value,
                            markerSide = province.marker?.side?.name,
                            tokens =
                                province.tokens.map { tokens ->
                                    PilotReplayTokens(
                                        tokens.owner.value,
                                        tokens.coloured,
                                        tokens.white,
                                    )
                                },
                        )
                    },
                commodityForecasts =
                    view.commodityPayouts.flatMap { forecast ->
                        forecast.owners.map { owner ->
                            PilotReplayPayout(
                                commodity = forecast.commodity.name,
                                player = owner.player.value,
                                provinces = owner.provinces.map { it.value },
                                payout = owner.payout,
                            )
                        }
                    },
                expansionOffers =
                    view.expansionOffers.map { offer ->
                        PilotReplayExpansion(
                            destination = offer.destination.value,
                            action = offer.primaryAction.name,
                            requiredTokens = offer.requiredTokens,
                            resultingPool = offer.resultingPool,
                            establishesMarker = offer.establishesMarker,
                            blockers = offer.blockers.map { it.name },
                            incomeBefore = offer.incomeBefore,
                            incomeAfter = offer.incomeAfter,
                        )
                    },
            )
        }
    }
}

/** Public resource values; a null numeric misery value denotes Chaos. */
data class PilotReplayPlayer(
    val id: String,
    val cash: Int,
    val poolTokens: Int,
    val stockTokens: Int,
    val markers: Int,
    val misery: Int?,
    val advances: List<String>,
    val shipping: String,
    val income: IncomeView?,
    val handCount: Int,
)

data class PilotReplayCard(val reference: String, val id: String, val messageId: String)

data class PilotReplayProvince(
    val id: String,
    val commodities: List<String>,
    val markerOwner: String?,
    val markerSide: String?,
    val tokens: List<PilotReplayTokens>,
)

data class PilotReplayTokens(val owner: String, val coloured: Int, val white: Int)

data class PilotReplayPayout(
    val commodity: String,
    val player: String,
    val provinces: List<String>,
    val payout: Int,
)

data class PilotReplayExpansion(
    val destination: String,
    val action: String,
    val requiredTokens: Int,
    val resultingPool: Int?,
    val establishesMarker: Boolean,
    val blockers: List<String>,
    val incomeBefore: Int?,
    val incomeAfter: Int?,
)

data class PilotReplayScore(
    val player: String,
    val advancePoints: Int,
    val cash: Int,
    val misery: Int?,
    val total: Int?,
    val winner: Boolean,
) {
    companion object {
        fun from(score: ScoreView): PilotReplayScore =
            PilotReplayScore(
                player = score.player.value,
                advancePoints = score.advancePoints,
                cash = score.cash,
                misery = score.misery,
                total = score.total,
                winner = score.winner,
            )
    }
}
