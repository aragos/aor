package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RecordedPrefixDiagnosticTest {
    @Test
    fun proposalsRetainRecordedCurrentAndPassBeforeDiverseFill() {
        val chosen = RecordedPrefixProposals.commands(observation(), "play c1", "play c2", 731)

        assertThat(chosen.take(3)).containsExactly("play c1", "play c2", "pass").inOrder()
        assertThat(chosen.last()).isEqualTo("play c3")
    }

    @Test
    fun diversityDoesNotRepeatParameterizationsOfOnePhysicalCard() {
        val chosen = RecordedPrefixProposals.commands(observation(), "pass", "pass", 731)

        assertThat(chosen).hasSize(4)
        assertThat(chosen.count { it.startsWith("play c1") }).isEqualTo(1)
    }

    @Test
    fun proposalsIgnoreFeatureValuesAndCandidateInputOrder() {
        val original = observation()
        val modified =
            original.copy(
                candidates =
                    original.candidates.reversed().map {
                        it.copy(features = mapOf("irrelevant.public.signal" to 999.0))
                    }
            )

        assertThat(RecordedPrefixProposals.commands(modified, "pass", "pass", 731))
            .isEqualTo(RecordedPrefixProposals.commands(original, "pass", "pass", 731))
    }

    @Test
    fun anUnofferedRecordedActionIsRejectedBeforeSimulation() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                RecordedPrefixProposals.commands(observation(), "play missing", "pass", 731)
            }

        assertThat(failure).hasMessageThat().contains("must be offered")
    }

    @Test
    fun replicationCountCannotBeExpandedOrReduced() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                RecordedPrefixRequest(1, 11, (1..16).toList(), (21..36).toList())
            }

        assertThat(failure).hasMessageThat().contains("exactly 32")
    }

    private fun observation(): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "play",
            context = emptyMap(),
            availableCash = 0,
            availableTokens = 0,
            candidates =
                listOf(
                    BotCandidate("pass", emptyMap(), family = "pass"),
                    BotCandidate("play c1", emptyMap(), group = "card.c1", family = "play"),
                    BotCandidate("play c1 p2", emptyMap(), group = "card.c1", family = "play"),
                    BotCandidate("play c2", emptyMap(), group = "card.c2", family = "play"),
                    BotCandidate("play c3", emptyMap(), group = "card.c3", family = "play"),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )
}
