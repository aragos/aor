package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardDecision
import dev.aragos.aor.CardDecision
import dev.aragos.aor.DieRoll
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameRollKind
import dev.aragos.aor.GameState
import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.ShufflePurpose
import dev.aragos.aor.Stage
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TurnDecision
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.WarState
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExperimentChanceTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val header =
        GameHeader(listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three")), p1)
    private val first = state(BoardDecision.WindmillDice(p1, p2, 2))
    private val unrelated = state(BoardDecision.WindmillDice(p1, p3, 2))
    private val need = SystemNeed.GameRoll(GameRollKind.WINDMILL, 1)

    @Test
    fun requestBytesMatchTheIndependentLengthPrefixedGoldenVector() {
        assertThat(ExperimentChanceRequest.from(first, need).sha256)
            .isEqualTo("4a07ef8bc76951faaed849c74d7b1a158e2ad03db328cb705f526f682ed9614f")
    }

    @Test
    fun legacyDiagnosticReproducesTheOriginalSealedBatchAndAuthorityBytes() {
        val session = initial()
        val ordinary = session.fork(73)
        val diagnostic = session.chanceFork(73, ExperimentChanceMode.LEGACY)
        val prefixSize = ordinary.entries.size
        val commands = ordinary.actors().associateWith { "bid-capital 0" }

        ordinary.apply(commands)
        diagnostic.apply(commands)

        assertThat(diagnostic.state).isEqualTo(ordinary.state)
        assertThat(diagnostic.entries).isEqualTo(ordinary.entries.drop(prefixSize))
    }

    @Test
    fun privateChanceMasterAndCountersDoNotEnterActorObservations() {
        val session = initial()
        val actor = session.actors().first()

        val diagnostic = session.chanceFork(999, ExperimentChanceMode.LOGICAL_REQUEST_1)

        assertThat(diagnostic.observation(actor)).isEqualTo(session.observation(actor))
    }

    @Test
    fun unrelatedRequestDoesNotShiftLaterLogicalDraw() {
        val direct = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        val inserted = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        accept(inserted, unrelated)

        val expected = words(direct, first)
        val actual = words(inserted, first)

        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun repeatedRequestHasDistinctOccurrenceAndRandomWords() {
        val sampler = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        val initial = words(sampler, first)
        accept(sampler, first)

        val repeated = words(sampler, first)

        assertThat(repeated).isNotEqualTo(initial)
        assertThat(sampler.prepare(first, need, ExperimentRandom(0)).occurrence).isEqualTo(1)
    }

    @Test
    fun preparingAnUnacceptedRequestDoesNotConsumeAnOccurrence() {
        val sampler = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        val initial = words(sampler, first)

        val retry = words(sampler, first)

        assertThat(retry).isEqualTo(initial)
        assertThat(sampler.trace).isEmpty()
    }

    @Test
    fun forkCopiesCounterPositionWithoutSharingMutation() {
        val parent = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        accept(parent, first)
        val child = parent.copy()

        accept(child, first)

        assertThat(parent.trace).hasSize(1)
        assertThat(child.trace).hasSize(2)
        assertThat(child.trace.last().occurrence).isEqualTo(1)
    }

    @Test
    fun unmodifiedForkUsesExactFutureWords() {
        val parent = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        accept(parent, first)

        val child = parent.copy()

        assertThat(words(child, first)).isEqualTo(words(parent, first))
    }

    @Test
    fun replacingForkMasterPreservesOccurrenceButChangesFutureWords() {
        val parent = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        accept(parent, first)

        val child = parent.copy(456L)

        assertThat(child.prepare(first, need, ExperimentRandom(0)).occurrence).isEqualTo(1)
        assertThat(words(child, first)).isNotEqualTo(words(parent, first))
    }

    @Test
    fun targetVictimsHaveDistinctLogicalAddresses() {
        assertThat(ExperimentChanceRequest.from(first, need))
            .isNotEqualTo(ExperimentChanceRequest.from(unrelated, need))
    }

    @Test
    fun warStartedTurnIsPartOfTheAddress() {
        val older = state(CardDecision.WarRoll(WarState(p1, p2, 1)))
        val newer = state(CardDecision.WarRoll(WarState(p1, p2, 2)))

        assertThat(ExperimentChanceRequest.from(older, need))
            .isNotEqualTo(ExperimentChanceRequest.from(newer, need))
    }

    @Test
    fun shuffleOrderingDoesNotChangeItsAddress() {
        val original = SystemNeed.Shuffle(listOf("stone-1", "stone-2"), ShufflePurpose.NEXT_EPOCH)
        val reordered = original.copy(cards = original.cards.reversed())

        assertThat(ExperimentChanceRequest.from(first, original))
            .isEqualTo(ExperimentChanceRequest.from(first, reordered))
    }

    @Test
    fun shuffleInventoryChangesItsAddress() {
        val original = SystemNeed.Shuffle(listOf("stone-1", "stone-2"), ShufflePurpose.NEXT_EPOCH)
        val changed = original.copy(cards = listOf("stone-1", "wool-1"))

        assertThat(ExperimentChanceRequest.from(first, original))
            .isNotEqualTo(ExperimentChanceRequest.from(first, changed))
    }

    @Test
    fun lengthPrefixSeparatesEmbeddedDelimiters() {
        val first = ExperimentChanceRequest(listOf("a\nb", "c"))
        val second = ExperimentChanceRequest(listOf("a", "b\nc"))

        assertThat(first.bytes.toList()).isNotEqualTo(second.bytes.toList())
    }

    @Test
    fun legacyAuditConsumesOnlyTheOriginalRandomTape() {
        val sampler = ExperimentChance(ExperimentChanceMode.LEGACY, 123L)
        val actual = ExperimentRandom(456L)
        val expected = ExperimentRandom(456L)

        val pending = sampler.prepare(first, need, actual)
        val values = List(20) { pending.random.nextInt(1, 7) }

        assertThat(values).isEqualTo(List(20) { expected.nextInt(1, 7) })
        assertThat(actual.nextInt()).isEqualTo(expected.nextInt())
    }

    @Test
    fun fixedDeckDrawDoesNotCreateAChanceRequest() {
        assertFailsWith<IllegalStateException> {
            ExperimentChanceRequest.from(first, SystemNeed.Draw(p1, "hand-1", "stone-1"))
        }
    }

    @Test
    fun anAcceptedAddressCannotBeAcceptedTwice() {
        val sampler = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123L)
        val pending = sampler.prepare(first, need, ExperimentRandom(0))
        val outcome = GameOutcome.Roll(GameRollKind.WINDMILL, listOf(DieRoll(1)))
        sampler.accepted(pending, outcome)

        assertFailsWith<IllegalStateException> { sampler.accepted(pending, outcome) }
    }

    private fun words(sampler: ExperimentChance, state: GameState): List<Int> =
        sampler.prepare(state, need, ExperimentRandom(0)).random.let { random ->
            List(8) { random.nextInt() }
        }

    private fun accept(sampler: ExperimentChance, state: GameState) {
        val pending = sampler.prepare(state, need, ExperimentRandom(0))
        val outcome: Entry =
            GameOutcome.Roll(GameRollKind.WINDMILL, listOf(DieRoll(pending.random.nextInt(1, 7))))
        sampler.accepted(pending, outcome)
    }

    private fun state(decision: TurnDecision): GameState =
        GameState(
            header,
            stage = Stage.EXPANSION,
            turnNumber = 3,
            flow = TurnFlow(actor = p1, decisions = listOf(decision)),
        )

    private fun initial(): ExperimentSession =
        ExperimentSession.start(
            TrainingConfiguration(
                4,
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_CRUSADES,
                    immediateMasterArtDiscard = true,
                    map = MapRule.STANDARD,
                ),
            ),
            51L,
            recordEntries = true,
        )
}
