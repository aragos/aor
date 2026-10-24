package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RuleVariantServiceTest {
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun clean() {
        directories.forEach { directory -> directory.toFile().deleteRecursively() }
    }

    @Test
    fun presetRecordsFunctionalOptionsAndSurvivesRecovery() {
        val fixture = fixture()
        val configured = fixture.submit(0, "set-option preset second-edition")
        val stored = FileGameStore(fixture.directory).read(fixture.game.gameId)
        val recovered =
            createGameService(fixture.directory, VariantReplayOnlyRandom)
                .get(
                    fixture.game.gameId,
                    fixture.token(0),
                )

        assertThat(configured.game.rules).isEqualTo(RuleOptions.SECOND_EDITION)
        assertThat(stored.revisions.last().entries).hasSize(11)
        assertThat(stored.revisions.last().entries.any { entry -> "preset" in entry }).isFalse()
        assertThat(recovered).isEqualTo(configured)
    }

    @Test
    fun pendingSetupReturnSurvivesRestartWithoutChangingCommittedHands() {
        val fixture = improvedSetup()
        val before = fixture.view(0)
        val reference = before.game.hand.first().reference
        val submitted = fixture.submit(0, "return-card $reference")
        val recovered =
            createGameService(fixture.directory, VariantReplayOnlyRandom)
                .get(
                    fixture.game.gameId,
                    fixture.token(0),
                )

        assertThat(submitted.revision).isEqualTo(before.revision)
        assertThat(submitted.game.hand).isEqualTo(before.game.hand)
        assertThat(recovered.pendingReturn).isEqualTo(reference)
        assertThat(recovered).isEqualTo(submitted)
    }

    @Test
    fun anotherSeatSeesSubmissionStatusWithoutThePrivateReturnReference() {
        val fixture = improvedSetup()
        fixture.submit(0, "return-card ${fixture.view(0).game.hand.first().reference}")

        val other = fixture.view(1)

        assertThat(other.submitted).containsExactly(PlayerId("p1"))
        assertThat(other.pendingReturn).isNull()
        assertThat(other.history.none { line -> "return-card" in line }).isTrue()
        assertThat(other.game.players.map(PlayerView::handCount)).containsExactly(3, 3, 3)
    }

    @Test
    fun pendingReturnCanBeReplacedBeforeTheBatchCompletes() {
        val fixture = improvedSetup()
        val hand = fixture.view(0).game.hand
        fixture.submit(0, "return-card ${hand.first().reference}")

        val replaced = fixture.submit(0, "return-card ${hand.last().reference}")

        assertThat(replaced.pendingReturn).isEqualTo(hand.last().reference)
        assertThat(FileGameStore(fixture.directory).read(fixture.game.gameId).pendingReturns)
            .containsExactly(PlayerId("p1"), hand.last().reference)
    }

    @Test
    fun lastReturnCommitsOneAtomicBatchAndPrivateShuffle() {
        val fixture = improvedSetup()
        val returned = (0..2).associateWith { seat -> fixture.view(seat).game.hand.first() }
        val before = fixture.view(0).revision
        for (seat in 0..2) {
            fixture.submit(
                seat,
                "return-card ${checkNotNull(returned[seat]) {
                "Each seat has a selected return."
            }.reference}",
            )
        }
        val saved = FileGameStore(fixture.directory).read(fixture.game.gameId)
        val snapshot = fixture.view(0)

        assertThat(snapshot.revision).isEqualTo(before + 1)
        assertThat(snapshot.game.stage).isEqualTo(Stage.BIDDING)
        assertThat(snapshot.game.players.map(PlayerView::handCount)).containsExactly(2, 2, 2)
        assertThat(saved.pendingReturns).isEmpty()
        assertThat(saved.revisions.last().entries.count { entry -> ": return-card " in entry })
            .isEqualTo(3)
        assertThat(saved.revisions.last().entries.last()).startsWith("!shuffle ")
        assertThat(snapshot.history).contains("p2: return-card <hidden>")
        assertThat(snapshot.history).contains("p3: return-card <hidden>")
        assertThat(snapshot.history.last()).isEqualTo("!shuffle <hidden>")
    }

    @Test
    fun invalidReturnDoesNotChangeTheSavedBatch() {
        val fixture = improvedSetup()
        fixture.submit(0, "return-card ${fixture.view(0).game.hand.first().reference}")
        val path = fixture.directory.resolve("${fixture.game.gameId}.json")
        val before = Files.readString(path)

        val failure = assertFailsWith<RuleViolation> { fixture.submit(1, "return-card hand-999") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_SETUP_RETURN)
        assertThat(Files.readString(path)).isEqualTo(before)
    }

    @Test
    fun staleReturnAfterTheBatchCommitsDoesNotChangeTheSavedGame() {
        val fixture = improvedSetup()
        val revision = fixture.view(0).revision
        val reference = fixture.view(0).game.hand.first().reference
        for (seat in 0..2) {
            fixture.submit(seat, "return-card ${fixture.view(seat).game.hand.first().reference}")
        }
        val path = fixture.directory.resolve("${fixture.game.gameId}.json")
        val before = Files.readString(path)

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.submit(
                    fixture.game.gameId,
                    fixture.token(0),
                    revision,
                    "return-card $reference",
                )
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
        assertThat(Files.readString(path)).isEqualTo(before)
    }

    @Test
    fun failedFinalReturnWritePreservesThePreviousPrivateBatch() {
        val directory = Files.createTempDirectory("aor-failed-return-").also(directories::add)
        var rejectWrites = false
        val writer = GameWriter { path, contents ->
            if (rejectWrites) throw java.io.IOException("Injected return-batch write failure.")
            AtomicGameWriter.write(path, contents)
        }
        val service = createGameService(directory, OrderedVariantRandom, writer)
        val fixture = Fixture(directory, service, service.create(listOf("Alice", "Bob", "Carol")))
        fixture.submit(0, "set-option setup improved")
        fixture.submit(0, "start")
        fixture.submit(0, "return-card ${fixture.view(0).game.hand.first().reference}")
        fixture.submit(1, "return-card ${fixture.view(1).game.hand.first().reference}")
        val reference = fixture.view(2).game.hand.first().reference
        val path = directory.resolve("${fixture.game.gameId}.json")
        val before = Files.readString(path)
        rejectWrites = true

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.submit(2, "return-card $reference")
            }
        val recovered = FileGameStore(directory).read(fixture.game.gameId)

        assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
        assertThat(Files.readString(path)).isEqualTo(before)
        assertThat(recovered.pendingReturns.keys).containsExactly(PlayerId("p1"), PlayerId("p2"))
        assertThat(recovered.state().stage).isEqualTo(Stage.SETUP_RETURN)
        assertThat(recovered.state().players.values.map { player -> player.hand.size })
            .containsExactly(3, 3, 3)
    }

    private fun improvedSetup(): Fixture =
        fixture().also { fixture ->
            fixture.submit(0, "set-option setup improved")
            fixture.submit(0, "start")
        }

    private fun fixture(): Fixture {
        val directory = Files.createTempDirectory("aor-rule-variants-").also(directories::add)
        val service = createGameService(directory, OrderedVariantRandom)
        return Fixture(directory, service, service.create(listOf("Alice", "Bob", "Carol")))
    }

    private data class Fixture(
        val directory: Path,
        val service: GameService,
        val game: CreatedGame,
    ) {
        fun token(seat: Int): String = game.links[seat].path.substringAfter("token=")

        fun view(seat: Int): GameSnapshot = service.get(game.gameId, token(seat))

        fun submit(seat: Int, text: String): GameSnapshot =
            service.submit(
                game.gameId,
                token(seat),
                view(seat).revision,
                text,
            )
    }
}

private object OrderedVariantRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = cards

    override fun roll(count: Int): List<DieRoll> = List(count) { index -> DieRoll(index + 1) }
}

private object VariantReplayOnlyRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = error("Recovery must reuse shuffles.")

    override fun roll(count: Int): List<DieRoll> = error("Recovery must reuse dice.")
}
