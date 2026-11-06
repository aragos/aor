package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RuleOptions
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BotConfigurationTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val rules =
        RuleOptions(
            deckModification = true,
            initialCard = InitialCardRule.NO_CRUSADES,
            immediateMasterArtDiscard = true,
            map = MapRule.STANDARD,
        )

    @Test
    fun observationConfigurationComesFromThePublicSetup() {
        val state = GameState(GameHeader(players, players.first().id), rules = rules)

        val observation = BotObservations.from(state, players.first().id)

        assertThat(observation.configuration)
            .isEqualTo(
                BotConfiguration(
                    playerCount = 4,
                    map = MapRule.STANDARD,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    deckModification = true,
                    immediateMasterArtDiscard = true,
                )
            )
    }

    @Test
    fun existingCatalogGamesRetainTheirCatalogAlongsideTheSetup() {
        val header = GameHeader(players, players.first().id, dataVersion = "ah1996-catalog-15")
        val state = GameState(header, rules = rules)

        val observation = BotObservations.from(state, players.first().id)

        assertThat(observation.dataVersion).isEqualTo("ah1996-catalog-15")
        assertThat(observation.configuration?.playerCount).isEqualTo(4)
    }

    @Test
    fun modelConfigurationsRejectUnsupportedPlayerCounts() {
        for (count in listOf(2, 7)) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    BotConfiguration(count, MapRule.STANDARD, InitialCardRule.ANY, false, false)
                }

            assertThat(failure).hasMessageThat().contains("three through six players")
        }
    }
}
