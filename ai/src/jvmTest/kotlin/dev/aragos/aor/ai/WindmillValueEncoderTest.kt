package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardDecision
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindmillValueEncoderTest {
    private val actor = PlayerId("p1")
    private val victim = PlayerId("p3")

    @Test
    fun sharedResourcesRetainExactRawUnitsAndVictimRole() {
        val view = fixture().viewForDecision(actor)
        val raw = RawActorState.from(view, actor, "windmill")
        val encoded = columns(view)

        assertThat(encoded["own.cash"]).isEqualTo(raw["role.0.cash"])
        assertThat(encoded["victim.cash"]).isEqualTo(raw["role.2.cash"])
        assertThat(encoded["own.hand"]).isEqualTo(raw["role.0.hand"])
        assertThat(encoded["hand.famine"]).isEqualTo(1.0)
        assertThat(encoded["offer.tokenCost"]).isEqualTo(view.windmillOffer!!.tokenCost / 36.0)
        assertThat(encoded["offer.successProbability"])
            .isEqualTo(view.windmillOffer!!.successProbability)
    }

    @Test
    fun unknownOpponentCardIdentitiesDoNotChangeFeatures() {
        val original = fixture()
        val changed =
            original.copy(
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == victim)
                            player.copy(
                                hand = listOf(HandCard("secret", CardData.definition("war")))
                            )
                        else player
                    }
            )

        assertThat(columns(original.viewForDecision(actor)))
            .isEqualTo(columns(changed.viewForDecision(actor)))
    }

    @Test
    fun authoritativeBurdenReferencesDriveOnlySpecifiedCountInteractions() {
        val view = fixture().viewForDecision(actor)
        val encoded = columns(view)
        val count = view.burdenReferences.size.toDouble()

        assertThat(encoded["own.burdenCount"]).isEqualTo(count / 10.0)
        assertThat(encoded["own.burdenFraction"]).isEqualTo(count / view.hand.size)
        assertThat(encoded["interaction.successBurden"])
            .isEqualTo(count / 10.0 * view.windmillOffer!!.successProbability)
    }

    @Test
    fun publicMarkerInventoryUsesCatalogCommoditiesWithoutPayoutQuotes() {
        val view = fixture().viewForDecision(actor)
        val commodities =
            view.catalog!!.provinces.single { it.id == ProvinceId("venice") }.commodities
        val encoded = columns(view)

        assertThat(view.commodityPayouts).isEmpty()
        for (commodity in commodities) {
            assertThat(encoded["own.markets.${commodity.name}"]).isEqualTo(0.1)
        }
    }

    @Test
    fun otherActorOrMissingWindowIsRejected() {
        val view = fixture().viewForDecision(actor)

        assertFailsWith<IllegalArgumentException> { WindmillValueEncoder.from(view, victim) }
        assertFailsWith<IllegalArgumentException> {
            WindmillValueEncoder.from(view.copy(windmillOffer = null), actor)
        }
    }

    @Test
    fun dictionaryIsCompleteAndDoesNotContainAuthoredUtilityOrProvenance() {
        assertThat(WindmillValueEncoder.featureNames).hasSize(273)
        assertThat(WindmillValueEncoder.featureNames.filter { it.startsWith("hand.") }).hasSize(64)
        assertThat(WindmillValueEncoder.featureNames.filter { it.startsWith("unplayed.") })
            .hasSize(64)
        assertThat(
                WindmillValueEncoder.featureNames.any {
                    it.contains("cohort") ||
                        it.contains("seed") ||
                        it.contains("chosen") ||
                        it == "cards"
                }
            )
            .isFalse()
    }

    private fun columns(view: dev.aragos.aor.GameView) =
        WindmillValueEncoder.featureNames.zip(WindmillValueEncoder.from(view, actor)).toMap()

    private fun fixture(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player$it") }
        return GameState(
            header = GameHeader(players, actor),
            stage = Stage.EXPANSION,
            turnNumber = 3,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            board = BoardState().placeMarker(ProvinceId("venice"), actor).board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = listOf("venice", "genoa", "barcelona", "paris")[index],
                                cash = (index + 1) * 40,
                                poolTokens = 12,
                                hand =
                                    listOf(
                                        HandCard(
                                            "own-$index",
                                            CardData.definition(
                                                listOf("famine", "cloth-2", "wine-2", "metal-2")[
                                                    index]
                                            ),
                                        )
                                    ),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = actor,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    decisions =
                        listOf(
                            BoardDecision.WindmillOpportunity(actor, victim, ProvinceId("venice"))
                        ),
                ),
        )
    }
}
