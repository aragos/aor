package dev.aragos.aor.ai

import dev.aragos.aor.AdjustmentCancellationContext
import dev.aragos.aor.AdjustmentCancellationProjection
import dev.aragos.aor.AdjustmentCancellationQuote
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId

/** Public/current quotes and the acting player's hand only; no physical IDs or future inputs. */
data class CancellationValueInput(
    val context: AdjustmentCancellationContext,
    val turnOrder: List<PlayerId>,
    val epoch: Int,
    val turn: Int,
    val deckRemaining: Int,
    val endingTriggered: Boolean,
    val handCount: Int,
    val boardMarkers: Int,
    val ownCardDefinitions: List<String>,
) {
    init {
        require(
            turnOrder.size == 4 && turnOrder.distinct().size == 4 && turnOrder[0] == context.actor
        ) {
            "Cancellation value inputs require the four-seat first-player order."
        }
        require(
            epoch in 1..3 && turn > 0 && deckRemaining >= 0 && handCount >= 0 && boardMarkers >= 0
        ) {
            "Cancellation value inputs require valid current public counters."
        }
        require(ownCardDefinitions.size == handCount) { "The owner hand must be complete." }
        ownCardDefinitions.forEach(CardEffectData::forCard)
        require(
            context.payouts.all { forecast -> forecast.owners.all { it.player in turnOrder } }
        ) {
            "Every public payout owner must belong to this game."
        }
    }

    companion object {
        fun from(view: GameView, actor: PlayerId): CancellationValueInput {
            val context = AdjustmentCancellationContext.from(view, actor)
            val own =
                requireNotNull(view.players.singleOrNull { it.id == actor }) {
                    "The cancellation actor must belong to its public view."
                }
            require(view.hand.map { it.reference }.distinct().size == view.hand.size) {
                "Each physical own card must occur once."
            }
            return CancellationValueInput(
                context,
                view.turnOrder,
                view.currentEpoch,
                requireNotNull(view.turnNumber) { "Cancellation requires a current turn." },
                view.deckRemaining,
                view.endingTriggered,
                own.handCount,
                own.boardMarkers,
                view.hand.map { it.definition.id },
            )
        }
    }
}

/** Frozen standardized ridge coefficients. Zero TRAIN-variance columns contribute exactly zero. */
data class CancellationValueModel(
    val version: String,
    val featureNames: List<String>,
    val coefficients: List<Double>,
    val mean: List<Double>,
    val standardDeviation: List<Double>,
    val intercept: Double,
    val sourceSha256: String,
) {
    init {
        require(
            version == CancellationValuePolicy.VERSION &&
                featureNames == CancellationValuePolicy.featureNames
        ) {
            "Cancellation value model requires its exact version and ordered 58-column dictionary."
        }
        require(
            listOf(coefficients, mean, standardDeviation).all {
                it.size == featureNames.size && it.all(Double::isFinite)
            }
        ) {
            "Cancellation value vectors must be finite and match their dictionary."
        }
        require(standardDeviation.all { it >= 0.0 } && intercept.isFinite()) {
            "Cancellation scales must be nonnegative and intercept finite."
        }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Cancellation coefficients require their original diagnostic source digest."
        }
    }

    fun predict(features: List<Double>): Double {
        require(features.size == featureNames.size && features.all(Double::isFinite)) {
            "Cancellation prediction requires exactly 58 finite original-unit features."
        }
        val prediction =
            features.indices.fold(intercept) { sum, index ->
                val scale = standardDeviation[index]
                sum +
                    if (scale == 0.0) 0.0
                    else (features[index] - mean[index]) / scale * coefficients[index]
            }
        require(prediction.isFinite()) { "Cancellation prediction must remain finite." }
        return prediction
    }
}

/** Experimental value inference only; this owner never changes the ordinary bot or activates it. */
object CancellationValuePolicy {
    const val VERSION = "cancellation-value-policy-1"
    val commodities =
        listOf(
            Commodity.STONE,
            Commodity.WOOL,
            Commodity.TIMBER,
            Commodity.GRAIN,
            Commodity.CLOTH,
            Commodity.WINE,
            Commodity.METAL,
            Commodity.FUR,
            Commodity.SILK,
            Commodity.SPICE,
            Commodity.GOLD,
            Commodity.IVORY,
        )
    val featureNames: List<String> =
        listOf(
            "epoch1",
            "epoch2",
            "epoch3",
            "turn",
            "deckRemaining",
            "endingTriggered",
            "cashBefore",
            "handCount",
            "boardMarkers",
            "isShortage",
            "isSurplus",
            "cost",
            "cashAfter",
            "costOverCashBefore",
            "affectedCommodityCount",
            "sharedGoldIvory",
            "queueLengthSum",
            "shortageCountSum",
            "surplusCountSum",
            "requestedKindCountSum",
            "requestedKindHeadCount",
            "requestedKindFirstIndexSum",
            "remainingRequestedKindCountSum",
            "unchangedHeadCount",
        ) +
            listOf("own", "rival1", "rival2", "rival3").flatMap { owner ->
                listOf(
                        "marketCountSum",
                        "industryBonusSum",
                        "boxBeforeSum",
                        "payoutBeforeSum",
                        "payoutDeltaSum",
                    )
                    .map { "$owner.$it" }
            } +
            commodities.map { "commodity.${it.name}" } +
            listOf(
                "ownPhysicalMatchingCardCount",
                "ownPhysicalMatchingCardCountTimesOwnPayoutDeltaSum",
            )

    /** Every offered CANCEL is retained; PASS is an exact zero reference, never a learned row. */
    fun features(input: CancellationValueInput): Map<String, List<Double>> =
        AdjustmentCancellationProjection.quotes(input.context)
            .filter { it.command != "pass" }
            .associate { it.command to features(input, it) }

    fun predictions(
        input: CancellationValueInput,
        model: CancellationValueModel,
    ): Map<String, Double> = features(input).mapValues { (_, values) -> model.predict(values) }

    /** PASS wins zero ties; strictly positive CANCEL ties use canonical command ordering. */
    fun choose(predictions: Map<String, Double>): String {
        require("pass" !in predictions && predictions.values.all(Double::isFinite)) {
            "Cancellation selection accepts finite CANCEL predictions only."
        }
        val best =
            predictions.keys.sorted().maxByOrNull {
                predictions[it] ?: error("Missing prediction.")
            } ?: return "pass"
        return if ((predictions[best] ?: error("Missing prediction.")) > 0.0) best else "pass"
    }

    private fun features(
        input: CancellationValueInput,
        quote: AdjustmentCancellationQuote,
    ): List<Double> {
        val changed = quote.commodities
        val kind =
            requireNotNull(quote.adjustment) { "A CANCEL feature row requires a marker kind." }
        val affected = changed.map { it.commodity }.toSet()
        val owners =
            input.turnOrder.map { player ->
                changed.flatMap { change -> change.owners.filter { it.player == player } }
            }
        val ownDelta = owners.first().sumOf { it.payoutAfter - it.payoutBefore }
        val matching =
            input.ownCardDefinitions.count { definition ->
                (CardEffectData.forCard(definition) as? CardEffectSpec.CommodityChoices)
                    ?.choices
                    ?.any { it in affected } == true
            }
        fun flag(value: Boolean) = if (value) 1.0 else 0.0
        return (listOf(
                flag(input.epoch == 1),
                flag(input.epoch == 2),
                flag(input.epoch == 3),
                input.turn.toDouble(),
                input.deckRemaining.toDouble(),
                flag(input.endingTriggered),
                quote.cashBefore.toDouble(),
                input.handCount.toDouble(),
                input.boardMarkers.toDouble(),
                flag(kind == CommodityAdjustment.SHORTAGE),
                flag(kind == CommodityAdjustment.SURPLUS),
                quote.cost.toDouble(),
                quote.cashAfter.toDouble(),
                quote.cost.toDouble() / quote.cashBefore,
                affected.size.toDouble(),
                flag(affected == setOf(Commodity.GOLD, Commodity.IVORY)),
                changed.sumOf { it.before.size }.toDouble(),
                changed
                    .sumOf { it.before.count { item -> item == CommodityAdjustment.SHORTAGE } }
                    .toDouble(),
                changed
                    .sumOf { it.before.count { item -> item == CommodityAdjustment.SURPLUS } }
                    .toDouble(),
                changed.sumOf { it.before.count { item -> item == kind } }.toDouble(),
                changed.count { it.before.firstOrNull() == kind }.toDouble(),
                changed.sumOf { it.before.indexOf(kind) }.toDouble(),
                changed.sumOf { it.after.count { item -> item == kind } }.toDouble(),
                changed.count { it.before.take(1) == it.after.take(1) }.toDouble(),
            ) +
                owners.flatMap { rows ->
                    listOf(
                        rows.sumOf { it.marketCount }.toDouble(),
                        rows.sumOf { it.industryBonus }.toDouble(),
                        rows.sumOf { it.boxBefore }.toDouble(),
                        rows.sumOf { it.payoutBefore }.toDouble(),
                        rows.sumOf { it.payoutAfter - it.payoutBefore }.toDouble(),
                    )
                } +
                commodities.map { flag(it in affected) } +
                listOf(matching.toDouble(), matching.toDouble() * ownDelta))
            .also {
                check(it.size == featureNames.size && it.all(Double::isFinite)) {
                    "Cancellation feature dictionary must remain finite and complete."
                }
            }
    }
}
