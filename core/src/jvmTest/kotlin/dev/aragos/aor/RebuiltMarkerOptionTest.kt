package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RebuiltMarkerOptionTest {
    @Test
    fun newGamesExcludeEventRebuildsFromTheExpansionBonus() {
        assertThat(GameState(header()).rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.EXCLUDE)
    }

    @Test
    fun creatorCanIncludeEventRebuildsInTheExpansionBonus() {
        val state = GameState(header()).play("p1: set-option rebuilt-marker-bonus include")

        assertThat(state.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.INCLUDE)
    }

    @Test
    fun creatorCanExcludeEventRebuildsAfterIncludingThem() {
        val initial = GameState(header()).play("p1: set-option rebuilt-marker-bonus include")

        val state = initial.play("p1: set-option rebuilt-marker-bonus exclude")

        assertThat(state.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.EXCLUDE)
    }

    @Test
    fun optionAuthorizationUsesTheExplicitCreatorRole() {
        val initial = GameState(header().copy(creatorId = PlayerId("p2")))

        val state = initial.play("p2: set-option rebuilt-marker-bonus include")

        assertThat(state.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.INCLUDE)
    }

    @Test
    fun otherPlayersCannotChangeTheRebuildingPolicy() {
        val failure =
            assertFailsWith<RuleViolation> {
                GameState(header()).play("p2: set-option rebuilt-marker-bonus include")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun rebuildingPolicyIsFrozenAfterTheGameStarts() {
        val started = GameState(header()).play("p1: start")

        val failure =
            assertFailsWith<RuleViolation> {
                started.play("p1: set-option rebuilt-marker-bonus include")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun creatorReceivesBothRebuildingOptionsAsLegalChoices() {
        val choices = GameState(header()).legalChoices(PlayerId("p1"))

        assertThat(choices.commands)
            .containsAtLeast(
                "set-option rebuilt-marker-bonus exclude",
                "set-option rebuilt-marker-bonus include",
            )
    }

    @Test
    fun otherPlayersReceiveNoConfigurationChoices() {
        val choices = GameState(header()).legalChoices(PlayerId("p2"))

        assertThat(choices.commands).isEmpty()
    }

    @Test
    fun startedGamesDoNotOfferRebuildingOptions() {
        val started = GameState(header()).play("p1: start")

        assertThat(started.legalChoices(PlayerId("p1")).commands)
            .containsNoneOf(
                "set-option rebuilt-marker-bonus exclude",
                "set-option rebuilt-marker-bonus include",
            )
    }

    @Test
    fun replayPreservesTheIncludedRebuildingPolicy() {
        val state =
            replay(header(), listOf("p1: set-option rebuilt-marker-bonus include", "p1: start"))

        assertThat(state.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.INCLUDE)
    }

    @Test
    fun replayPreservesTheExcludedRebuildingPolicy() {
        val state =
            replay(
                header(),
                listOf(
                    "p1: set-option rebuilt-marker-bonus include",
                    "p1: set-option rebuilt-marker-bonus exclude",
                    "p1: start",
                ),
            )

        assertThat(state.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.EXCLUDE)
    }

    @Test
    fun everyPlayerCanSeeTheConfiguredRebuildingPolicy() {
        val state =
            GameState(header()).play("p1: set-option rebuilt-marker-bonus include", "p1: start")

        for (player in state.playerIds) {
            assertThat(state.viewForPlayer(player).rebuiltMarkerBonus)
                .isEqualTo(RebuiltMarkerBonus.INCLUDE)
        }
    }

    private fun header() =
        GameHeader(
            creatorId = PlayerId("p1"),
            players =
                (1..3).map { number ->
                    Player(id = PlayerId("p$number"), name = "Player $number")
                },
        )

    private fun GameState.play(vararg entries: String): GameState =
        entries.fold(this) { state, text -> state.step(TextCodec.parse(text)) }
}
