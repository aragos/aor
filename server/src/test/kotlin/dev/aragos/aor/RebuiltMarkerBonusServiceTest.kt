package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RebuiltMarkerBonusServiceTest {
    private val directories = mutableListOf<Path>()

    private fun directory(): Path =
        Files.createTempDirectory("aor-option-test-").also(directories::add)

    private fun token(game: CreatedGame, player: Int): String =
        game.links[player].path.substringAfter("token=")

    @AfterTest
    fun clean() {
        directories.forEach { directory -> directory.toFile().deleteRecursively() }
    }

    @Test
    fun creationRecordsTheResolvedDefaultInTheReplayLog() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), 3)

        val snapshot = service.get(game.gameId, token(game, 1))
        val saved = FileGameStore(directory).read(game.gameId)

        assertThat(snapshot.game.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.EXCLUDE)
        assertThat(saved.fileVersion).isEqualTo(2)
        assertThat(saved.revisions.single().entries)
            .containsExactly(
                "p1: set-option epochs 3",
                "p1: set-option rebuilt-marker-bonus exclude",
            )
            .inOrder()
    }

    @Test
    fun configuredBonusSurvivesStartAndRestartReplay() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), 3)
        service.submit(game.gameId, token(game, 0), 1, "set-option rebuilt-marker-bonus include")
        val started = service.submit(game.gameId, token(game, 0), 2, "start")
        val restartRandom = FixedRandom()

        val replayed = createGameService(directory, restartRandom).get(game.gameId, token(game, 0))
        val other = service.get(game.gameId, token(game, 1))
        val saved = FileGameStore(directory).read(game.gameId)

        assertThat(replayed).isEqualTo(started)
        assertThat(replayed.game.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.INCLUDE)
        assertThat(other.game.rebuiltMarkerBonus).isEqualTo(RebuiltMarkerBonus.INCLUDE)
        assertThat(saved.revisions[1].entries)
            .containsExactly("p1: set-option rebuilt-marker-bonus include")
        assertThat(restartRandom.shuffles).isEqualTo(0)
        assertThat(restartRandom.rolls).isEqualTo(0)
    }

    @Test
    fun nonCreatorCannotConfigureTheBonus() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), 3)
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)

        val failure =
            assertFailsWith<RuleViolation> {
                service.submit(
                    game.gameId,
                    token(game, 1),
                    1,
                    "set-option rebuilt-marker-bonus include",
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
        assertThat(Files.readString(path)).isEqualTo(before)
    }

    @Test
    fun startFreezesTheBonusOption() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), 3)
        service.submit(game.gameId, token(game, 0), 1, "start")
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)

        val failure =
            assertFailsWith<RuleViolation> {
                service.submit(
                    game.gameId,
                    token(game, 0),
                    2,
                    "set-option rebuilt-marker-bonus include",
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
        assertThat(Files.readString(path)).isEqualTo(before)
    }

    @Test
    fun staleOptionChangesLeaveTheSelectedBonusUnchanged() {
        val directory = directory()
        val service = createGameService(directory, FixedRandom())
        val game = service.create(listOf("Alice", "Bob", "Carol"), 3)
        service.submit(game.gameId, token(game, 0), 1, "set-option rebuilt-marker-bonus include")
        val path = directory.resolve("${game.gameId}.json")
        val before = Files.readString(path)

        val failure =
            assertFailsWith<ServiceFailure> {
                service.submit(
                    game.gameId,
                    token(game, 0),
                    1,
                    "set-option rebuilt-marker-bonus exclude",
                )
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
        assertThat(Files.readString(path)).isEqualTo(before)
        assertThat(service.get(game.gameId, token(game, 0)).game.rebuiltMarkerBonus)
            .isEqualTo(RebuiltMarkerBonus.INCLUDE)
    }
}
