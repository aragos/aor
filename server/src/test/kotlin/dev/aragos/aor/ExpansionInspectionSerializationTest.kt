package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ExpansionInspectionSerializationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun aFilteredPoolFailureStillSerializesItsAuthoritativeInspection() {
        val before =
            state().copy(board = BoardState().placeMarker(ProvinceId("florence"), p2).board)

        val view = json.readTree(json.writeValueAsString(before.viewForPlayer(p1)))
        val inspection =
            view.path("expansionInspections").single {
                it.path("destination").asText() == "florence"
            }

        assertThat(view.path("expansionOffers").map { it.path("destination").asText() })
            .doesNotContain("florence")
        assertThat(inspection.path("blockers").single().asText())
            .isEqualTo("INSUFFICIENT_EXPANSION_TOKENS")
        assertThat(inspection.path("availablePool").asInt()).isEqualTo(1)
        assertThat(inspection.path("requiredTokens").asInt()).isGreaterThan(1)
    }

    @Test
    fun missingAccessUsesTypedRequirementNamesInTheInspection() {
        val view = json.readTree(json.writeValueAsString(state().viewForPlayer(p1)))

        val inspection =
            view.path("expansionInspections").single {
                it.path("destination").asText() == "china"
            }

        assertThat(inspection.path("blockers").single().asText())
            .isEqualTo("PROVINCE_ACCESS_DENIED")
        assertThat(inspection.path("missingAccess").single().asText()).isEqualTo("OCEAN_NAVIGATION")
    }

    private fun state(): GameState {
        val players = listOf(Player(p1, "First"), Player(p2, "Second"), Player(p3, "Third"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = 1,
                                stockTokens = 35,
                            )
                    }
                    .toMap(),
        )
    }
}
