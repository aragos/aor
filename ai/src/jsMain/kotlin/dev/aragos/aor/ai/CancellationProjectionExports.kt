@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import dev.aragos.aor.AdjustmentCancellationContext
import dev.aragos.aor.AdjustmentCancellationOffer
import dev.aragos.aor.AdjustmentCancellationProjection
import dev.aragos.aor.AdjustmentCancellationQuote
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.CommodityOwnerView
import dev.aragos.aor.CommodityPayoutView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/** Experimental public quote boundary; no policy weights, actions, randomness or activation. */
@JsExport
fun quoteAdjustmentCancellations(contextJson: String): String {
    require(contextJson.length <= 500_000) { "Cancellation context exceeds its transport bound." }
    val raw = JSON.parse<dynamic>(contextJson)
    fields(raw, setOf("actor", "cash", "offers", "payouts"))
    val context =
        AdjustmentCancellationContext(
            actor = player(raw.actor),
            cash = purchaseInteger(raw.cash),
            offers =
                purchaseArray(raw.offers).map { offer ->
                    fields(offer, setOf("command", "commodities", "cost", "cashAfter"))
                    AdjustmentCancellationOffer(
                        purchaseString(offer.command),
                        purchaseArray(offer.commodities)
                            .map { Commodity.valueOf(purchaseString(it)) }
                            .toSet(),
                        purchaseInteger(offer.cost),
                        purchaseInteger(offer.cashAfter),
                    )
                },
            payouts =
                purchaseArray(raw.payouts).map { forecast ->
                    fields(forecast, setOf("commodity", "adjustments", "owners"))
                    CommodityPayoutView(
                        Commodity.valueOf(purchaseString(forecast.commodity)),
                        purchaseArray(forecast.adjustments).map {
                            CommodityAdjustment.valueOf(purchaseString(it))
                        },
                        purchaseArray(forecast.owners).map { owner ->
                            fields(
                                owner,
                                setOf(
                                    "player",
                                    "provinces",
                                    "marketCount",
                                    "effectiveBox",
                                    "payout",
                                    "industryBonus",
                                ),
                            )
                            CommodityOwnerView(
                                player(owner.player),
                                purchaseArray(owner.provinces).map { province ->
                                    fields(province, setOf("value"))
                                    ProvinceId(purchaseString(province.value))
                                },
                                purchaseInteger(owner.marketCount),
                                purchaseInteger(owner.effectiveBox),
                                purchaseInteger(owner.payout),
                                purchaseInteger(owner.industryBonus),
                            )
                        },
                    )
                },
        )
    return JSON.stringify(
        AdjustmentCancellationProjection.quotes(context).map(::wire).toTypedArray()
    )
}

private fun fields(raw: dynamic, expected: Set<String>) {
    require(raw != null && jsTypeOf(raw) == "object" && !(js("Array.isArray(raw)") as Boolean)) {
        "Cancellation JSON requires an object."
    }
    val keys = js("Object.keys(raw)") as Array<String>
    require(keys.toSet() == expected) { "Cancellation JSON has unexpected or missing fields." }
}

private fun player(raw: dynamic): PlayerId {
    fields(raw, setOf("value"))
    return PlayerId(purchaseString(raw.value))
}

private fun playerWire(player: PlayerId): dynamic {
    val result = js("({})")
    result.value = player.value
    return result
}

private fun wire(quote: AdjustmentCancellationQuote): dynamic {
    val result = js("({})")
    result.command = quote.command
    result.adjustment = quote.adjustment?.name
    result.cost = quote.cost
    result.cashBefore = quote.cashBefore
    result.cashAfter = quote.cashAfter
    result.commodities =
        quote.commodities
            .map { commodity ->
                val item = js("({})")
                item.commodity = commodity.commodity.name
                item.before = commodity.before.map { it.name }.toTypedArray()
                item.after = commodity.after.map { it.name }.toTypedArray()
                item.owners =
                    commodity.owners
                        .map { owner ->
                            val value = js("({})")
                            value.player = playerWire(owner.player)
                            value.marketCount = owner.marketCount
                            value.industryBonus = owner.industryBonus
                            value.boxBefore = owner.boxBefore
                            value.boxAfter = owner.boxAfter
                            value.payoutBefore = owner.payoutBefore
                            value.payoutAfter = owner.payoutAfter
                            value
                        }
                        .toTypedArray()
                item
            }
            .toTypedArray()
    return result
}
