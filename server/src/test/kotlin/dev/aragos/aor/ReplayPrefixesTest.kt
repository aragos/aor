package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ReplayPrefixesTest {
    private val owner = PlayerId("p1")
    private val header =
        GameHeader(
            listOf(
                Player(owner, "Alice"),
                Player(PlayerId("p2"), "Bob"),
                Player(PlayerId("p3"), "Carol"),
            ),
            owner,
        )

    @Test
    fun restoringAnEarlierPrefixInvalidatesStatesFromTheDiscardedBranch() {
        val original =
            listOf(
                "p1: set-option deck-modification disabled",
                "p1: set-option initial-card any",
                "p1: set-option map standard",
            )
        val prefixes = prefixes(1, 2)
        prefixes.reset(original)
        val earlier = prefixes.restore(original, 1)
        val alternative = original.take(1) + "p1: set-option initial-card no-crusades"
        prefixes.step(earlier, TextCodec.parse(alternative.last()))

        val restored = prefixes.restore(alternative, 2)

        assertThat(restored).isEqualTo(replay(header, alternative))
    }

    @Test
    fun evictedPrefixesAreReconstructedFromTheEffectiveLog() {
        val entries =
            (0..40).map { index ->
                "p1: set-option deck-modification ${if (index % 2 == 0) "enabled" else "disabled"}"
            }
        val prefixes = prefixes(*(0..40).toList().toIntArray())
        prefixes.reset(entries)

        val restored = prefixes.restore(entries, 1)

        assertThat(restored).isEqualTo(replay(header, entries.take(1)))
    }

    private fun prefixes(vararg positions: Int): ReplayPrefixes =
        ReplayPrefixes(
            header,
            positions.mapIndexed { index, position ->
                Revision(
                    index + 1,
                    index.takeIf { it > 0 },
                    owner,
                    "undo $position",
                    emptyList(),
                    position,
                )
            },
        )
}
