package dev.aragos.aor.training

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.CardData
import dev.aragos.aor.CardDefinition
import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.CardKind
import dev.aragos.aor.Commodity
import dev.aragos.aor.DeckHistory
import dev.aragos.aor.Epoch
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.HandCard
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PieceSide
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.ShippingFleet
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import kotlin.random.Random

/** The pilot compares complete continuations of these deliberately small plan families. */
enum class CommodityExperimentStrategy {
    KEEP_BASELINE,
    PLAY_NOW,
    HOLD_ORDINARY,
    HOLD_EXPAND,
}

/**
 * Versioned established-position fixture, not a claim that an opening history reached this state.
 * Opponent hands are publicly empty. The remaining physical-card multiset is public by
 * construction: every other introduced card is publicly played. Only the order of [state]'s deck is
 * unknown. Replay starts from this fixture and records ordinary engine commands and outcomes
 * thereafter.
 */
data class CommodityExperimentScenario(
    val id: String,
    val seed: Int,
    val state: GameState,
    val actor: PlayerId,
    val commodity: Commodity,
    val cardReference: String,
    val startTurn: Int,
    val tag: String,
)

/**
 * Narrow offline curriculum and observation-only continuation policies. Fixture generation can see
 * authority state; [features], [focus] and [command] accept only a deciding seat's safe
 * projections.
 */
object CommodityExperiment {
    const val VERSION = "commodity-pilot-2"

    val configuration =
        TrainingConfiguration(
            playerCount = 4,
            rules =
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                    map = MapRule.STANDARD,
                ),
        )

    /** Fixed-order normalized numerical inputs. Adjacency is a feature, not a legal-route claim. */
    val featureNames =
        listOf(
            "cash",
            "poolTokens",
            "misery",
            "markers",
            "commodityMarkers",
            "colouredCommodityMarkers",
            "rivalMaximumCommodityMarkers",
            "rivalTotalCommodityMarkers",
            "rivalMaximumCash",
            "handCount",
            "stabilization",
            "turnPosition",
            "deckRemaining",
            "endingTriggered",
            "finalCardWindow",
            "commodityBaseValue",
            "income",
            "adjacentEmptyCommodityMarkets",
            "adjacentCommodityMarketCost",
            "emptyCommodityMarkets",
            "ownCommodityForecast",
            "rivalMaximumCommodityForecast",
            "advanceCount",
            "advanceScore",
            "rivalMaximumAdvanceScore",
            "shippingLevel",
            "sameCommodityCards",
            "otherCommodityCards",
            "leaderCards",
            "rivalMaximumAdvanceCount",
        ) +
            AdvanceId.entries.map { "advance.${it.name.lowercase()}" } +
            ShippingFleet.entries.map { "fleet.${it.name.lowercase()}" } +
            Commodity.entries.map { "commodity.${it.name.lowercase()}" }

    private val catalog = PublicCatalog.forPlayers(4, configuration.rules)
    private val ids = (1..4).map { PlayerId("p$it") }
    private val capitals = listOf("venice", "genoa", "barcelona", "paris")
    private val provinces = catalog.provinces.associateBy(ProvinceDefinition::id)
    private val introduced = Epoch.entries.flatMap(configuration.rules::cardsForEpoch).distinct()
    private val currentDeck = introduced.filter { id ->
        val definition = CardData.definition(id)
        definition.epoch == Epoch.THREE || definition.shape.recycledOnEpochChange
    }
    private val singleCommodities =
        currentDeck.map(CardData::definition).mapNotNull { definition ->
            val commodity = commodities(definition).singleOrNull() ?: return@mapNotNull null
            val markets = accessibleMarkets(commodity)
            val origins = originsFor(markets, commodity)
            if (markets.size < 2 || origins.isEmpty()) null else definition to commodity
        }

    /** Seed selects public position facts; hidden deck ordering is independently sampled later. */
    fun scenario(seed: Int): CommodityExperimentScenario {
        val random = Random(seed)
        val variant = Math.floorMod(seed, 8)
        val actor = ids[Math.floorMod(seed / 8, ids.size)]
        val rival = ids[(ids.indexOf(actor) + 1) % ids.size]
        val (card, commodity) = singleCommodities[random.nextInt(singleCommodities.size)]
        val markets = accessibleMarkets(commodity).shuffled(random)
        val origin = originsFor(markets, commodity).random(random)
        val ownCount =
            when (variant) {
                1 -> 2
                3,
                4,
                6 -> 1
                else -> 0
            }
        val rivalCount =
            when (variant) {
                2 -> 3
                6 -> 2
                7 -> 1
                else -> 0
            }
        val ownMarkets = markets.take(ownCount)
        val rivalMarkets = markets.drop(ownCount).take(rivalCount)
        val capitalBoard =
            ids.zip(capitals).fold(BoardState(definition = configuration.rules.map.definition)) {
                board,
                (player, capital) ->
                board.placeMarker(ProvinceId(capital), player).board
            }
        val established = capitalBoard.placeMarker(origin.id, actor).board
        val owned =
            ownMarkets.fold(established) { board, province ->
                board.placeMarker(province.id, actor).board
            }
        val board =
            rivalMarkets.fold(owned) { current, province ->
                current.placeMarker(province.id, rival).board
            }
        val cash =
            when (variant) {
                1 -> 15
                4 -> 0
                else -> 60 + random.nextInt(601)
            }
        val pool = if (variant == 5) 0 else 8 + random.nextInt(17)
        val turnOrder = ids.shuffled(random)
        val finalWindow = variant == 3
        val additionalCards =
            currentDeck
                .map(CardData::definition)
                .filter {
                    it.id != card.id && it.kind in setOf(CardKind.COMMODITY, CardKind.LEADER)
                }
                .shuffled(random)
                .take(random.nextInt(3))
        val hand =
            (listOf(card) + additionalCards).mapIndexed { index, definition ->
                HandCard("hand-${index + 1}", definition)
            }
        val handIds = hand.map { it.definition.id }.toSet()
        val deck =
            if (finalWindow) {
                emptyList()
            } else {
                currentDeck
                    .filterNot { it in handIds }
                    .shuffled(random)
                    .take(4 + random.nextInt(21))
            }
        val excluded = introduced.filterNot { it in handIds || it in deck }
        // Match development so these focused examples remain sensitive to commodity decisions.
        val development = DevelopmentProfile.entries.random(random)
        val players =
            ids.mapIndexed { index, id ->
                    val money = if (id == actor) cash else 60 + random.nextInt(601)
                    val tokens = if (id == actor) pool else 6 + random.nextInt(13)
                    id to
                        PlayerState(
                            player = Player(id, "Experiment ${id.value}"),
                            hand = if (id == actor) hand else emptyList(),
                            capital = capitals[index],
                            capitalBid = ids.size - index,
                            cash = money,
                            writtenCash = money,
                            poolTokens = tokens,
                            stockTokens = 36 - tokens,
                            misery = MiseryStep.entries[random.nextInt(17)],
                            advances = development.ownership,
                            ships = development.ships,
                        )
                }
                .toMap()
        val state =
            GameState(
                header = GameHeader(ids.map { Player(it, "Experiment ${it.value}") }, ids.first()),
                currentEpoch = Epoch.THREE,
                rules = configuration.rules,
                stage = if (finalWindow) Stage.FINAL_CARD_PLAY else Stage.PLAY_CARDS,
                deck = deck,
                deckHistory =
                    DeckHistory(
                        discarded =
                            excluded.filter { CardData.definition(it).shape.recycledOnEpochChange },
                        removed =
                            excluded.filterNot {
                                CardData.definition(it).shape.recycledOnEpochChange
                            },
                        nextDraw = hand.size + 1,
                    ),
                players = players,
                board = board,
                publiclyDiscardedCards = excluded.toSet(),
                capitalSelection =
                    CapitalSelection.fromBids(
                        ids.mapIndexed { index, id -> id to ids.size - index }.toMap()
                    ),
                turnOrderSelection = TurnOrderSelection(turnOrder),
                turnNumber = 4,
                endingTriggered = finalWindow,
                flow =
                    TurnFlow(
                        actor = actor,
                        cursor = turnOrder.indexOf(actor),
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                    ),
            )
        return CommodityExperimentScenario(
            id = "$VERSION/$seed",
            seed = seed,
            state = state,
            actor = actor,
            commodity = commodity,
            cardReference = "hand-1",
            startTurn = 4,
            tag =
                listOf(
                    "opportunity",
                    "holding-cost",
                    "rival-holdings",
                    "final-window",
                    "liquidity",
                    "no-pool",
                    "competition",
                    "mixed",
                )[variant],
        )
    }

    /** Independently permute the fixture's known deck, never inspect its original hidden order. */
    fun sampledState(scenario: CommodityExperimentScenario, sampleSeed: Int): GameState =
        scenario.state.copy(deck = scenario.state.deck.sorted().shuffled(Random(sampleSeed)))

    /** The pilot handles single-commodity cards; other cards retain the baseline policy. */
    fun focus(view: GameView): Pair<HandCard, Commodity>? =
        view.hand.sortedBy(HandCard::reference).firstNotNullOfOrNull { card ->
            commodities(card.definition).singleOrNull()?.let { card to it }
        }

    fun features(view: GameView, seat: PlayerId, commodity: Commodity): List<Double> {
        val self = view.players.single { it.id == seat }
        val publicCatalog =
            requireNotNull(view.catalog) { "Commodity features require the public catalog." }
        val occupied = view.board.associateBy { it.id }
        val markets =
            publicCatalog.provinces.filter { commodity in it.commodities && !it.isSatellite }
        fun count(player: PlayerId): Int = markets.count {
            occupied[it.id]?.marker?.owner == player
        }
        val empty = markets.filter { occupied[it.id] == null }
        val ownBases =
            view.board
                .filter { province ->
                    province.marker?.let { it.owner == seat && it.side == PieceSide.COLOURED } ==
                        true || province.tokens.any { it.owner == seat && it.coloured > 0 }
                }
                .map { it.id }
                .toSet()
        val adjacent =
            publicCatalog.landBorders
                .flatMap { connection ->
                    listOfNotNull(
                        connection.second.takeIf { connection.first in ownBases },
                        connection.first.takeIf { connection.second in ownBases },
                    )
                }
                .toSet()
        val adjacentEmpty = empty.filter { province ->
            province.id in adjacent && province.access.all { it.isSatisfiedBy(self.advances) }
        }
        val rivals = view.players.filterNot { it.id == seat }
        val forecast = view.commodityPayouts.firstOrNull { it.commodity == commodity }
        val baseValue = publicCatalog.commodities.single { it.id == commodity }.baseValue
        fun advanceScore(owned: Set<AdvanceId>): Int =
            publicCatalog.advances.filter { it.id in owned }.sumOf { it.cost }
        val numerical =
            listOf(
                self.cash / 400.0,
                self.poolTokens / 36.0,
                (self.misery.value ?: 1000) / 200.0,
                self.boardMarkers / 25.0,
                count(seat) / 10.0,
                markets.count {
                    occupied[it.id]?.marker?.let { marker ->
                        marker.owner == seat && marker.side == PieceSide.COLOURED
                    } == true
                } / 10.0,
                (rivals.maxOfOrNull { count(it.id) } ?: 0) / 10.0,
                rivals.sumOf { count(it.id) } / 20.0,
                (rivals.maxOfOrNull { it.cash } ?: 0) / 400.0,
                self.handCount / 10.0,
                (view.stabilizationCost ?: 0L) / 100.0,
                view.turnOrder.indexOf(seat).coerceAtLeast(0).toDouble() / (view.players.size - 1),
                view.deckRemaining / 20.0,
                if (view.endingTriggered) 1.0 else 0.0,
                if (view.stage == Stage.FINAL_CARD_PLAY) 1.0 else 0.0,
                baseValue / 10.0,
                (self.income?.ordinary ?: 0) / 100.0,
                adjacentEmpty.size / 8.0,
                adjacentEmpty.sumOf(ProvinceDefinition::marketNumber) / 36.0,
                empty.size / 16.0,
                (forecast?.owners?.firstOrNull { it.player == seat }?.payout ?: 0) / 200.0,
                (forecast?.owners?.filterNot { it.player == seat }?.maxOfOrNull { it.payout }
                    ?: 0) / 200.0,
                self.advances.size / AdvanceId.entries.size.toDouble(),
                advanceScore(self.advances) / 2000.0,
                (rivals.maxOfOrNull { advanceScore(it.advances) } ?: 0) / 2000.0,
                self.ships.level / 16.0,
                view.hand.count { commodity in commodities(it.definition) } / 5.0,
                view.hand.count {
                    it.definition.kind == CardKind.COMMODITY &&
                        commodity !in commodities(it.definition)
                } / 5.0,
                view.hand.count { it.definition.kind == CardKind.LEADER } / 5.0,
                (rivals.maxOfOrNull { it.advances.size } ?: 0) / AdvanceId.entries.size.toDouble(),
            ) +
                AdvanceId.entries.map { if (it in self.advances) 1.0 else 0.0 } +
                ShippingFleet.entries.map { if (it == self.ships.fleet) 1.0 else 0.0 } +
                Commodity.entries.map { if (it == commodity) 1.0 else 0.0 }
        check(numerical.size == featureNames.size) {
            "Commodity feature names must match their values."
        }
        return numerical.map { it.coerceIn(-4.0, 4.0) }
    }

    /** Simple feature-only comparator: acquire affordable adjacent markets when their gain pays. */
    fun heuristic(
        view: GameView,
        seat: PlayerId,
        commodity: Commodity,
    ): CommodityExperimentStrategy {
        if (view.stage == Stage.FINAL_CARD_PLAY) return CommodityExperimentStrategy.KEEP_BASELINE
        val values = features(view, seat, commodity)
        val cash = values[0] * 400.0
        val tokens = values[1] * 36.0
        val holdings = values[4] * 10.0
        val stabilization = values[10] * 100.0
        val base = values[15] * 10.0
        val adjacent = values[17] * 8.0
        val required = values[18] * 36.0
        val immediate = values[20] * 200.0
        if (cash < 50.0 && cash + immediate >= 50.0) {
            return CommodityExperimentStrategy.KEEP_BASELINE
        }
        if (adjacent == 0.0 || required == 0.0) return CommodityExperimentStrategy.KEEP_BASELINE
        val additional = minOf(adjacent, kotlin.math.floor(tokens / (required / adjacent)))
        val futureGain =
            base * ((holdings + additional) * (holdings + additional) - holdings * holdings)
        return if (additional > 0.0 && futureGain > stabilization + 8.0) {
            CommodityExperimentStrategy.HOLD_EXPAND
        } else {
            CommodityExperimentStrategy.KEEP_BASELINE
        }
    }

    /** Execute one ordinary action of a plan using only the acting seat's permitted information. */
    fun command(
        view: GameView,
        observation: BotObservation,
        strategy: CommodityExperimentStrategy,
        commodity: Commodity,
        cardReference: String,
        startTurn: Int,
        baseline: BotModel,
        seed: Int,
    ): String {
        if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
            return BotPolicy.choose(observation, BotDifficulty.HARD, seed, baseline)
        }
        val held = view.hand.any { it.reference == cardReference }
        val commands = observation.candidates.map { it.command }.toSet()
        if (held && observation.decision == "play-cards") {
            val play =
                listOf(
                        "play $cardReference",
                        "play $cardReference commodity ${commodity.name.lowercase()}",
                    )
                    .firstOrNull { it in commands }
            val holding =
                strategy != CommodityExperimentStrategy.PLAY_NOW &&
                    (view.turnNumber ?: startTurn) <= startTurn &&
                    view.stage != Stage.FINAL_CARD_PLAY
            if (holding && "pass" in commands) {
                val remaining =
                    observation.candidates.filterNot { candidate ->
                        candidate.command == "play $cardReference" ||
                            candidate.command.startsWith("play $cardReference ")
                    }
                val remainingCommands = remaining.map { it.command }.toSet()
                val leaderPlanning =
                    observation.leaderPlanning?.let { planning ->
                        val leaders = planning.leaders.filterKeys { it in remainingCommands }
                        if (leaders.isEmpty()) null
                        else
                            planning.copy(
                                leaders = leaders,
                                cashCards = planning.cashCards.intersect(remainingCommands),
                            )
                    }
                return BotPolicy.choose(
                    observation.copy(candidates = remaining, leaderPlanning = leaderPlanning),
                    BotDifficulty.HARD,
                    seed,
                    baseline,
                )
            }
            if (!holding && play != null) return play
        }
        if (
            held &&
                observation.decision == "expand" &&
                strategy == CommodityExperimentStrategy.HOLD_EXPAND
        ) {
            val publicCatalog =
                requireNotNull(view.catalog) { "Commodity expansion requires the public catalog." }
            val targets =
                publicCatalog.provinces.filter { commodity in it.commodities }.map { it.id }.toSet()
            val placement =
                view.expansionOffers
                    .filter { offer ->
                        offer.destination in targets &&
                            offer.primaryAction == ExpansionAction.PLACE &&
                            offer.establishesMarker &&
                            offer.blockers.isEmpty() &&
                            offer.requiredTokens > 0
                    }
                    .sortedWith(compareBy({ it.requiredTokens }, { it.destination.value }))
                    .firstOrNull { "place ${it.requiredTokens} ${it.destination}" in commands }
            if (placement != null) {
                return "place ${placement.requiredTokens} ${placement.destination}"
            }
        }
        return BotPolicy.choose(observation, BotDifficulty.HARD, seed, baseline)
    }

    private fun commodities(card: CardDefinition): Set<Commodity> =
        (CardEffectData.forCard(card.id) as? CardEffectSpec.CommodityChoices)?.choices.orEmpty()

    private fun accessibleMarkets(commodity: Commodity): List<ProvinceDefinition> =
        catalog.provinces.filter {
            commodity in it.commodities &&
                !it.isSatellite &&
                it.access.isEmpty() &&
                it.id.value !in capitals
        }

    private fun originsFor(
        markets: List<ProvinceDefinition>,
        commodity: Commodity,
    ): List<ProvinceDefinition> {
        val targets = markets.map(ProvinceDefinition::id).toSet()
        val neighbors =
            catalog.landBorders
                .flatMap { edge ->
                    listOfNotNull(
                        edge.first.takeIf { edge.second in targets },
                        edge.second.takeIf { edge.first in targets },
                    )
                }
                .toSet()
        return neighbors
            .mapNotNull { provinces[it] }
            .filter {
                !it.isSatellite &&
                    it.access.isEmpty() &&
                    it.id.value !in capitals &&
                    commodity !in it.commodities
            }
    }
}

/** Public established-position profiles; prerequisites must precede their dependents. */
private enum class DevelopmentProfile(
    advances: List<AdvanceId>,
    val ships: ShippingCapability,
) {
    COMMERCE(
        listOf(
            AdvanceId.THE_HEAVENS,
            AdvanceId.PATRONAGE,
            AdvanceId.CARAVAN,
            AdvanceId.WIND_WATERMILL,
            AdvanceId.IMPROVED_AGRICULTURE,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.URBAN_ASCENDANCY,
        ),
        ShippingCapability.GALLEY_6,
    ),
    NAVIGATION(
        listOf(
            AdvanceId.THE_HEAVENS,
            AdvanceId.HUMAN_BODY,
            AdvanceId.CARAVAN,
            AdvanceId.WIND_WATERMILL,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.PRINTED_WORD,
            AdvanceId.OVERLAND_EAST,
            AdvanceId.SEAWORTHY_VESSELS,
            AdvanceId.OCEAN_NAVIGATION,
            AdvanceId.URBAN_ASCENDANCY,
            AdvanceId.COSMOPOLITAN,
        ),
        ShippingCapability.OCEAN_2,
    ),
    INDUSTRY(
        listOf(
            AdvanceId.THE_HEAVENS,
            AdvanceId.HUMAN_BODY,
            AdvanceId.LAWS_OF_MATTER,
            AdvanceId.PATRONAGE,
            AdvanceId.CARAVAN,
            AdvanceId.WIND_WATERMILL,
            AdvanceId.IMPROVED_AGRICULTURE,
            AdvanceId.INTEREST_PROFIT,
            AdvanceId.INDUSTRY,
            AdvanceId.WRITTEN_RECORD,
            AdvanceId.PRINTED_WORD,
            AdvanceId.SEAWORTHY_VESSELS,
            AdvanceId.URBAN_ASCENDANCY,
            AdvanceId.INSTITUTIONAL_RESEARCH,
            AdvanceId.MIDDLE_CLASS,
        ),
        ShippingCapability.SEAWORTHY_12,
    );

    val ownership: PlayerAdvances

    init {
        for ((index, advance) in advances.withIndex()) {
            require(
                advances.take(index).containsAll(AdvanceData.definition(advance).prerequisites)
            ) {
                "Development profile $name must acquire prerequisites before $advance."
            }
        }
        require(ships.fleet != ShippingFleet.SEAWORTHY || AdvanceId.SEAWORTHY_VESSELS in advances) {
            "Development profile $name requires Seaworthy Vessels for its shipping capability."
        }
        require(ships.fleet != ShippingFleet.OCEAN || AdvanceId.OCEAN_NAVIGATION in advances) {
            "Development profile $name requires Ocean Navigation for its shipping capability."
        }
        ownership = PlayerAdvances(advances.map { AdvanceAcquisition(it, turnNumber = 2) })
    }
}
