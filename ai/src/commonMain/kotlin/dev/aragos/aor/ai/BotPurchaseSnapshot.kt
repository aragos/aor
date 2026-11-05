package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderInPlay
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.CardTurnEffects
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.LeaderId
import dev.aragos.aor.MapRule
import dev.aragos.aor.MiseryReliefUsage
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Prompt
import dev.aragos.aor.PurchasePlanningPosition
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage

/** Public seat facts needed by Purchase; names, private hands and sealed decisions are omitted. */
data class BotPurchaseSeat(val id: String, val capital: String, val cash: Int)

/** Owner-visible physical card identity and occurrence reference. */
data class BotPurchaseCard(
    val reference: String,
    val card: String,
    val value: Double = 7.5,
    val burden: Boolean = false,
)

/** Purchase turn is retained because category credits start in the following turn. */
data class BotPurchaseAdvance(val letter: String, val turn: Int)

/** Public leader-play facts; wire values use stable card and seat identifiers. */
data class BotPurchaseLeader(
    val card: String,
    val owner: String,
    val exposed: Boolean,
    val protectionCash: Int,
    val claimants: List<String>,
    val mongolEnhanced: Boolean,
)

/**
 * Portable Purchase-only facts. It contains the owner's hand and acquisition turns, public cash,
 * capitals and played leaders. It cannot represent the draw pile or another player's cards. Misery
 * and ships use enum names; acquisition letters are canonical A through Z.
 */
data class BotPurchaseSnapshot(
    val owner: String,
    val currentTurn: Int,
    val decision: String,
    val seats: List<BotPurchaseSeat>,
    val cash: Int,
    val misery: String,
    val ships: String,
    val acquisitions: List<BotPurchaseAdvance>,
    val hand: List<BotPurchaseCard>,
    val lastShipUpgradeTurn: Int?,
    val reliefSpaces: Int,
    val reliefRatingSpent: Int,
    val immediateMasterArtDiscard: Boolean,
    val europeanMap: Boolean,
    val papalCategory: String?,
    val crusadesPlayed: Boolean,
    val leaders: List<BotPurchaseLeader>,
    val dataVersion: String,
) {
    init {
        require(currentTurn > 0 && cash >= 0) {
            "Purchase planning requires a turn and nonnegative cash."
        }
        require(
            seats.size in 3..6 && seats.map(BotPurchaseSeat::id).distinct().size == seats.size
        ) {
            "Purchase planning requires three through six distinct seats."
        }
        require(seats.any { it.id == owner }) { "Purchase planning requires its owner seat." }
        require(acquisitions.size <= 26 && hand.size <= 64 && leaders.size <= 19) {
            "Purchase planning facts exceed the physical game bounds."
        }
    }

    fun position(): PurchasePlanningPosition {
        val ownerId = PlayerId(owner)
        val players = seats.map { seat ->
            val identity = Player(PlayerId(seat.id), seat.id)
            if (identity.id != ownerId) {
                PlayerState(identity, capital = seat.capital, cash = seat.cash)
            } else {
                PlayerState(
                    player = identity,
                    capital = seat.capital,
                    cash = cash,
                    misery = MiseryStep.valueOf(misery),
                    ships = ShippingCapability.valueOf(ships),
                    advances =
                        PlayerAdvances(
                            acquisitions.map { acquisition ->
                                AdvanceAcquisition(
                                    AdvanceId.fromLetter(acquisition.letter.single()),
                                    acquisition.turn,
                                )
                            }
                        ),
                    hand =
                        hand.map { card ->
                            HandCard(card.reference, CardData.definition(card.card))
                        },
                    lastShipUpgradeTurn = lastShipUpgradeTurn,
                    miseryReliefUsage =
                        MiseryReliefUsage(currentTurn, reliefSpaces, reliefRatingSpent),
                )
            }
        }
        return PurchasePlanningPosition.create(
            players = players,
            owner = ownerId,
            currentTurn = currentTurn,
            decision = decision,
            rules =
                RuleOptions(
                    immediateMasterArtDiscard = immediateMasterArtDiscard,
                    map = if (europeanMap) MapRule.EUROPEAN else MapRule.STANDARD,
                ),
            effects =
                CardTurnEffects(
                    papalCategory = papalCategory?.let(AdvanceCategory::valueOf),
                    crusadesPlayed = crusadesPlayed,
                    leaders =
                        leaders.map { leader ->
                            CardLeaderInPlay(
                                leader = LeaderId.entries.single { it.cardId == leader.card },
                                owner = PlayerId(leader.owner),
                                role =
                                    if (leader.exposed) CardLeaderRole.EXPOSED
                                    else CardLeaderRole.DISPLAY,
                                protectionCash = leader.protectionCash,
                                patronageClaimants = leader.claimants.map(::PlayerId).toSet(),
                                mongolEnhanced = leader.mongolEnhanced,
                            )
                        },
                ),
            dataVersion = dataVersion,
        )
    }
}

/**
 * The adapter reads only the deciding owner's private facts and explicitly public Purchase facts.
 */
object BotPurchaseSnapshots {
    fun from(state: GameState, seat: PlayerId): BotPurchaseSnapshot? {
        if (state.stage != Stage.PURCHASE) return null
        val prompt = state.prompts().singleOrNull() as? Prompt.SinglePlayer ?: return null
        if (
            prompt.player != seat ||
                (!prompt.choice.startsWith("purchase-") &&
                    prompt.choice != "immediate-master-art-discard")
        ) {
            return null
        }
        val player = state.playerState(seat)
        val view = state.viewForPlayer(seat)
        val turn = requireNotNull(state.turnNumber) { "Purchase planning requires a running turn." }
        val relief = player.miseryReliefUsage?.takeIf { it.turnNumber == turn }
        return BotPurchaseSnapshot(
            owner = seat.value,
            currentTurn = turn,
            decision = prompt.choice,
            seats =
                state.header.players.map { identity ->
                    val public = state.playerState(identity.id)
                    BotPurchaseSeat(
                        identity.id.value,
                        requireNotNull(public.capital) {
                            "Purchase planning requires assigned capitals."
                        },
                        public.cash,
                    )
                },
            cash = player.cash,
            misery = player.misery.name,
            ships = player.ships.name,
            acquisitions =
                player.advances.acquisitions.map {
                    BotPurchaseAdvance(it.advance.letter.toString(), it.turnNumber)
                },
            hand =
                player.hand.map { card ->
                    val burden = card.reference in view.burdenReferences
                    val cash =
                        view.cardOffers
                            .firstOrNull { it.reference == card.reference }
                            ?.choices
                            ?.maxOfOrNull { choice ->
                                choice.cashChanges
                                    .filter { it.player == seat }
                                    .sumOf { it.after - it.before }
                            } ?: 0
                    BotPurchaseCard(
                        card.reference,
                        card.definition.id,
                        if (burden) 0.0 else maxOf(4.0, cash.toDouble()),
                        burden,
                    )
                },
            lastShipUpgradeTurn = player.lastShipUpgradeTurn,
            reliefSpaces = relief?.spaces ?: 0,
            reliefRatingSpent = relief?.ratingSpent ?: 0,
            immediateMasterArtDiscard = state.rules.immediateMasterArtDiscard,
            europeanMap = state.rules.map == MapRule.EUROPEAN,
            papalCategory = state.effects.papalCategory?.name,
            crusadesPlayed = state.effects.crusadesPlayed,
            leaders =
                state.effects.leaders.map { leader ->
                    BotPurchaseLeader(
                        leader.leader.cardId,
                        leader.owner.value,
                        leader.role == CardLeaderRole.EXPOSED,
                        leader.protectionCash,
                        leader.patronageClaimants.map(PlayerId::value).sorted(),
                        leader.mongolEnhanced,
                    )
                },
            dataVersion = state.header.dataVersion,
        )
    }
}
