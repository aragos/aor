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

        assertThat(stored.revisions.single().entries)
            .containsExactlyElementsIn(defaultConfigurationText(PlayerId("p2")))
            .inOrder()
    }
}
