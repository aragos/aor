package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class GameDriverTest {
    @Test
    fun initialConfigurationUsesTheExplicitCreator() {
        val header =
            GameHeader(
                players =
                    listOf(
                        Player(PlayerId("p1"), "Alice"),
                        Player(PlayerId("p2"), "Bob"),
                        Player(PlayerId("p3"), "Carol"),
                    ),
                creatorId = PlayerId("p2"),
            )

        val stored =
            GameDriver(FixedRandom())
                .create(
                    id = "test-game",
                    header = header,
                    credentials = emptyMap(),
                )

        assertThat(stored.revisions.first().entries)
            .containsExactlyElementsIn(defaultConfigurationText(PlayerId("p2")))
            .inOrder()
        assertThat(stored.state().stage).isEqualTo(Stage.BIDDING)
        assertThat(stored.revisions.last().author).isEqualTo(PlayerId("p2"))
        assertThat(stored.revisions.last().entries.first()).isEqualTo("p2: start")
        requireCompletedTransaction(stored.state())
    }
}
