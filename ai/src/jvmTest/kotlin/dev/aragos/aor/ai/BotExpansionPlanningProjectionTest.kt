package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.Entry
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.PieceSide
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.ShippingUsageView
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotExpansionPlanningProjectionTest {
    private val actor = PlayerId("p1")
    private val rival = PlayerId("p2")

    @Test
    fun aNewOverseasPartialReservesASlotWithoutReservingAMarker() {
        val observation = BotObservations.from(oceanFixture(), actor)

        val planning =
            checkNotNull(observation.expansionPlanning) { "Expansion requires planning facts." }

        assertThat(planning.overseasSlots).isEqualTo(1)
        assertThat(planning.steps["place 1 india"])
            .isEqualTo(BotExpansionStep(markerCost = 0, newOverseasCost = 1))
    }

    @Test
    fun completingAnExistingOverseasPartialDoesNotReserveAnotherSlot() {
        val state =
            oceanFixture().step(Entry.Action(actor, TextCodec.parseCommand("place 1 india")))

        val observation = BotObservations.from(state, actor)
        val planning =
            checkNotNull(observation.expansionPlanning) { "Expansion requires planning facts." }
        val completion =
            planning.steps.entries.single { (command, step) ->
                command.endsWith(" india") && step.markerCost == 1
            }

        assertThat(planning.overseasSlots).isEqualTo(0)
        assertThat(completion.value.newOverseasCost).isEqualTo(0)
    }

    @Test
    fun projectedStepsUseOnlyFullCertainLandMarkersForCommodityGains() {
        val observation = BotObservations.from(fixture(), actor)
        val planning =
            checkNotNull(observation.expansionPlanning) { "Expansion requires planning facts." }

        assertThat(planning.steps["place 2 smyrna"])
            .isEqualTo(BotExpansionStep(1, setOf(Commodity.WOOL)))
        assertThat(planning.steps["place 1 smyrna"]).isNull()
        assertThat(
                planning.steps.values
                    .filter { it.commodities.isNotEmpty() }
                    .all { it.markerCost == 1 }
            )
            .isTrue()
    }

    @Test
    fun shippingReservesItsMarkerWithoutEarningALandBundleGain() {
        val original = fixture().viewForDecision(actor)
        val offer = original.expansionOffers.single { it.destination == ProvinceId("smyrna") }
        val view =
            original.copy(
                expansionOffers = listOf(offer.copy(shipping = ShippingUsageView(0, 6, 2)))
            )
        val candidate =
            BotCandidate("place 2 smyrna", emptyMap(), tokenCost = 2, family = "expansion")

        val planning = BotEconomicProjection(view, actor).planning(listOf(candidate))

        assertThat(planning.steps[candidate.command]).isEqualTo(BotExpansionStep(1))
    }

    @Test
    fun competitionReservesItsMarkerWithoutTreatingItsOutcomeAsCertain() {
        val original = fixture().viewForDecision(actor)
        val offer = original.expansionOffers.single { it.destination == ProvinceId("smyrna") }
        val view = original.copy(expansionOffers = listOf(offer.copy(uncertain = true)))
        val candidate =
            BotCandidate("attack smyrna", emptyMap(), tokenCost = 2, family = "expansion")

        val planning = BotEconomicProjection(view, actor).planning(listOf(candidate))

        assertThat(planning.steps[candidate.command]).isEqualTo(BotExpansionStep(1))
    }

    @Test
    fun aNewWhiteMarkerCannotInventAPlanningBase() {
        val original = fixture()
        val state =
            original.copy(
                board =
                    original.board.placeMarker(ProvinceId("smyrna"), actor, PieceSide.WHITE).board
            )

        val observation = BotObservations.from(state, actor)

        assertThat(observation.candidates.any { it.command.endsWith(" adalia") }).isFalse()
        assertThat(observation.expansionPlanning?.steps?.keys?.any { it.endsWith(" adalia") })
            .isFalse()
    }

    @Test
    fun hiddenRivalCardsAndDeckOrderCannotChangePlanningFacts() {
        val original = fixture()
        val opponent = checkNotNull(original.players[rival]) { "The fixture requires its rival." }
        val changed =
            original.copy(
                deck = original.deck.reversed(),
                players =
                    original.players +
                        (rival to
                            opponent.copy(
                                hand = listOf(HandCard("hidden", CardData.definition("stirrups")))
                            )),
            )

        val first = BotObservations.from(original, actor)
        val second = BotObservations.from(changed, actor)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun physicalOwnCardsAreKeptSeparateFromPublicExposure() {
        val observation = BotObservations.from(fixture(), actor)
        val planning =
            checkNotNull(observation.expansionPlanning) { "Expansion requires planning facts." }
        val view = fixture().viewForDecision(actor)
        val publicWool = view.commodityCards.single { it.commodity == Commodity.WOOL }.unplayed ?: 0

        assertThat(planning.heldCards)
            .containsExactly(
                setOf(Commodity.WOOL),
                setOf(Commodity.WOOL),
                setOf(Commodity.CLOTH, Commodity.WINE),
            )
        assertThat(planning.commodities.single { it.commodity == Commodity.WOOL }.outsideCards)
            .isEqualTo((publicWool - 2).coerceAtLeast(0))
    }

    private fun oceanFixture(): GameState {
        val initial = fixture()
        val owner = checkNotNull(initial.players[actor]) { "An ocean fixture requires its actor." }
        return initial.copy(
            players =
                initial.players +
                    (actor to
                        owner.copy(
                            ships = ShippingCapability.OCEAN_1,
                            advances =
                                PlayerAdvances(
                                    listOf(AdvanceAcquisition(AdvanceId.OCEAN_NAVIGATION, 1))
                                ),
                        ))
        )
    }

    private fun fixture(): GameState {
        val ids = listOf(actor, rival, PlayerId("p3"))
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals)
                .fold(BoardState()) { current, (id, capital) ->
                    current.placeMarker(ProvinceId(capital), id).board
                }
                .placeMarker(ProvinceId("constantinople"), actor)
                .board
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, actor),
            currentEpoch = Epoch.THREE,
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board = board,
            deck = listOf("armor", "stirrups"),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                poolTokens = 6,
                                stockTokens = 30,
                                hand =
                                    if (id == actor) {
                                        listOf("wool-1", "wool-2", "cloth-wine").mapIndexed {
                                            cardIndex,
                                            card ->
                                            HandCard("held-$cardIndex", CardData.definition(card))
                                        }
                                    } else if (id == rival) {
                                        listOf(HandCard("hidden", CardData.definition("armor")))
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
