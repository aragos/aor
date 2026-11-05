package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardPlacementRules
import dev.aragos.aor.BoardPlayerProfile
import dev.aragos.aor.BoardRulesContext
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardContext
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.CardMarker
import dev.aragos.aor.CardPlayerContext
import dev.aragos.aor.CardRules
import dev.aragos.aor.CatalogConfiguration
import dev.aragos.aor.CatalogVersion
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityHoldingBasis
import dev.aragos.aor.ExpansionReach
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlacementPlan
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ProvincePieces
import dev.aragos.aor.RuleViolation
import dev.aragos.aor.Stage
import dev.aragos.aor.TokenHolding
import kotlinx.collections.immutable.toPersistentMap

/**
 * Advisory next-turn opportunities from public board facts and the owner's private cards. Pieces
 * become bases through the ordinary Income flip; no newly placed piece is treated as a live base.
 * Future shortage/surplus rolls and opponents' intervening moves remain unknown.
 */
internal class StrategicExpansion(private val view: GameView, private val owner: PlayerId) {
    private val self = view.players.single { it.id == owner }
    private val catalog = requireNotNull(view.catalog) { "Strategic expansion requires a catalog." }
    private val configuration =
        CatalogConfiguration(
            view.players.size,
            view.rules,
            CatalogVersion.fromWire(catalog.dataVersion),
        )
    private val provinces = configuration.provinces.associateBy(ProvinceDefinition::id)
    private val currentBoard =
        BoardState(
            pieces =
                view.board
                    .associate { province ->
                        province.id to
                            (province.marker?.let { marker ->
                                ProvincePieces.Marker(marker.owner, marker.side)
                            }
                                ?: ProvincePieces.Tokens(
                                    province.tokens
                                        .associate { token ->
                                            token.owner to TokenHolding(token.coloured, token.white)
                                        }
                                        .toPersistentMap()
                                ))
                    }
                    .toPersistentMap(),
            definition = configuration.map,
        )
    private val board = currentBoard.flipWhitePieces()
    private val context =
        BoardRulesContext(
            configuration = configuration,
            board = board,
            players =
                view.players.associate { player ->
                    player.id to
                        BoardPlayerProfile(
                            player = player.id,
                            capital =
                                ProvinceId(
                                    requireNotNull(player.capital) {
                                        "Expansion opportunities require selected capitals."
                                    }
                                ),
                            advances = player.advances,
                            ships = player.ships,
                            chaos = player.misery == MiseryStep.CHAOS,
                        )
                },
        )
    private val cardPlayers =
        view.players.associate { player ->
            player.id to
                CardPlayerContext(
                    cash = player.cash,
                    writtenCash = player.writtenCash,
                    poolTokens = player.poolTokens,
                    stockTokens = player.stockTokens,
                    advances = player.advances,
                    capital =
                        ProvinceId(
                            requireNotNull(player.capital) {
                                "Commodity forecasts require selected capitals."
                            }
                        ),
                    misery = player.misery,
                    ships = player.ships,
                )
        }
    private val heldCards =
        view.hand
            .mapNotNull { card ->
                val commodities =
                    (CardEffectData.forCard(card.definition.id) as? CardEffectSpec.CommodityChoices)
                        ?.choices
                        .orEmpty()
                commodities.takeIf { it.isNotEmpty() }?.let { card.reference to it }
            }
            .toMap()
    private val heldCommodities = heldCards.values.flatten().distinct()
    private val baselineReach by lazy { reachable(context) }
    private val baselineHeldValue by lazy { heldValue(board) }
    private val heldCommodityValues = mutableMapOf<ProvinceId, Double>()
    private val reachValues = mutableMapOf<Pair<ProvinceId, Boolean>, Double>()

    /** Incremental own payout, choosing one commodity per physical dual card. */
    fun heldCommodityGain(province: ProvinceId): Double =
        if (self.stockMarkers == 0 || heldCards.isEmpty()) 0.0
        else
            heldCommodityValues.getOrPut(province) {
                heldValue(board.placeMarker(province, owner).board) - baselineHeldValue
            }

    /** Additional independently valuable markets that become reachable next round. */
    fun futureReach(province: ProvinceId, tokens: Int, complete: Boolean): Double =
        reachValues.getOrPut(province to complete) { futureReachValue(province, tokens, complete) }

    private fun futureReachValue(province: ProvinceId, tokens: Int, complete: Boolean): Double {
        if (board.hasPresence(owner, province)) return 0.0
        val definition = configuration.map.province(province)
        val after =
            if (complete) {
                if (definition.isSatellite) board.replaceWithToken(province, owner).board
                else board.placeMarker(province, owner).board
            } else {
                val holdings =
                    (board.pieces[province] as? ProvincePieces.Tokens)?.holdings.orEmpty()
                val current = holdings[owner]?.total ?: 0
                board.copy(
                    pieces =
                        board.pieces.putting(
                            province,
                            ProvincePieces.Tokens(
                                (holdings + (owner to TokenHolding(coloured = current + tokens)))
                                    .toPersistentMap()
                            ),
                        )
                )
            }
        val next = context.copy(board = after)
        return (reachable(next) - baselineReach)
            .filter { destination -> destination != province }
            .map { destination -> opportunity(configuration.map.province(destination)) }
            .sortedDescending()
            .take(3)
            .sum()
    }

    /**
     * Future own payout forgone by playing now. Only peaceful purchases within the already revealed
     * expansion pool qualify. A small beam allows concentration across multiple markets without
     * assuming a new base can be used during this Expansion phase.
     */
    fun retentionValue(reference: String): Double {
        if (view.stage != Stage.PLAY_CARDS || view.endingTriggered || self.poolTokens == 0)
            return 0.0
        if (view.currentEpoch == 3 && view.deckRemaining <= view.players.size) return 0.0
        val commodities = heldCards[reference] ?: return 0.0
        val stabilization =
            PlayerEconomy.stabilizationCost(
                self.handCount,
                AdvanceId.MIDDLE_CLASS in self.advances,
            )
        if (self.cash < stabilization) return 0.0
        val now =
            view.cardOffers
                .firstOrNull { it.reference == reference }
                ?.choices
                ?.maxOfOrNull { choice ->
                    choice.cashChanges
                        .filter { it.player == owner }
                        .sumOf { it.after - it.before }
                        .toDouble()
                } ?: 0.0
        val holdingCost =
            stabilization -
                PlayerEconomy.stabilizationCost(
                    self.handCount - 1,
                    AdvanceId.MIDDLE_CLASS in self.advances,
                )
        val targetProvinces =
            configuration.provinces.filter { province ->
                province.commodities.any { it in commodities }
            }
        data class Position(val context: BoardRulesContext, val tokens: Int, val value: Double)
        var frontier = listOf(Position(context.copy(board = currentBoard), self.poolTokens, now))
        var best = now
        repeat(3) {
            frontier =
                frontier
                    .flatMap { position ->
                        val local = position.context
                        targetProvinces.mapNotNull { province ->
                            if (
                                local.board.hasPresence(owner, province.id) &&
                                    local.board.pieces[province.id] is ProvincePieces.Marker
                            ) {
                                return@mapNotNull null
                            }
                            val existing =
                                (local.board.pieces[province.id] as? ProvincePieces.Tokens)
                                    ?.holdings
                                    .orEmpty()[owner]
                                    ?.total ?: 0
                            val required = province.marketNumber - existing
                            val plan =
                                try {
                                    BoardPlacementRules.evaluate(
                                        local,
                                        owner,
                                        province.id,
                                        required,
                                        position.tokens,
                                    )
                                } catch (_: RuleViolation) {
                                    null
                                }
                            if (plan !is PlacementPlan.Peaceful) return@mapNotNull null
                            val nextBoard = BoardPlacementRules.applyPeaceful(local, plan).board
                            val nextPayments = payments(nextBoard)
                            Position(
                                BoardPlacementRules.recordShipping(local, plan)
                                    .copy(board = nextBoard),
                                position.tokens - required,
                                commodities.maxOf { nextPayments[it] ?: 0.0 },
                            )
                        }
                    }
                    .sortedByDescending(Position::value)
                    .take(6)
            best = maxOf(best, frontier.maxOfOrNull(Position::value) ?: now)
        }
        val delayed = best * 0.85
        return if (delayed > now + holdingCost) delayed else 0.0
    }

    private fun reachable(position: BoardRulesContext): Set<ProvinceId> =
        configuration.provinces
            .filter { province ->
                !province.isSatellite &&
                    (position.board.pieces[province.id] as? ProvincePieces.Marker)?.owner !=
                        owner &&
                    ExpansionReach.hasRoute(position, owner, province.id)
            }
            .map(ProvinceDefinition::id)
            .toSet()

    private fun opportunity(province: ProvinceDefinition): Double {
        val income =
            catalog.incomeTrack
                .getOrNull(self.boardMarkers + 1)
                ?.minus(catalog.incomeTrack.getOrNull(self.boardMarkers) ?: 0) ?: 0
        val commodity =
            province.commodities.sumOf { commodity ->
                val owned = board.commodityCount(owner, commodity)
                val base = catalog.commodities.firstOrNull { it.id == commodity }?.baseValue ?: 0
                base * (2.0 * owned + 1.0)
            }
        val held = heldCommodityGain(province.id)
        // Normalize by physical commitment; later planning can compare actual competitive costs.
        return (income + commodity * 0.25 + held * 0.5) / province.marketNumber / 4.0
    }

    private fun heldValue(position: BoardState): Double {
        if (heldCards.isEmpty()) return 0.0
        val payment = payments(position)
        return heldCards.values.sumOf { commodities ->
            commodities.maxOf { payment[it] ?: 0.0 }
        }
    }

    private fun payments(position: BoardState): Map<Commodity, Double> {
        val cardContext =
            CardContext(
                players = cardPlayers,
                markers =
                    position.pieces
                        .mapNotNull { (province, occupants) ->
                            (occupants as? ProvincePieces.Marker)?.let {
                                province to CardMarker(it.owner)
                            }
                        }
                        .toMap(),
                map = view.rules.map,
                provinces = provinces,
            )
        return heldCommodities.associateWith { commodity ->
            (CardRules.commodityPayment(
                        cardContext,
                        commodity,
                        CommodityHoldingBasis.CURRENT_MARKERS,
                    )
                    .payments[owner] ?: 0)
                .toDouble()
        }
    }
}
