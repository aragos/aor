package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test

class RuleVariantServiceTest {
    @Test
    fun openingReturnSurvivesRestartAndRemainsPrivate() {
        val directory = Files.createTempDirectory("aor-opening-")
        try {
            val random = FixedRandom()
            val service = createGameService(directory, random)
            val created =
                service.create(
                    listOf("Alice", "Bob", "Carol"),
                    RuleOptions(drawThreeKeepTwo = true, initialCard = InitialCardRule.NO_EVENTS),
                )
            val ownerToken = created.links.first().path.substringAfter("#token=")
            val otherToken = created.links[1].path.substringAfter("#token=")
            val opening = service.get(created.gameId, ownerToken)
            val card = opening.game.hand.first()
            val command = "return-card ${card.reference}"
            val returned = service.submit(created.gameId, ownerToken, opening.revision, command)
            val restarted =
                createGameService(
                    directory,
                    object : SetupRandom {
                        override fun shuffle(cards: List<String>): List<String> =
                            error("Reload must not shuffle.")

                        override fun roll(count: Int): List<DieRoll> =
                            error("Reload must not roll.")
                    },
                )
            val owner = restarted.get(created.gameId, ownerToken)
            val other = restarted.get(created.gameId, otherToken)

            assertThat(owner).isEqualTo(returned)
            assertThat(owner.game.hand).hasSize(2)
            assertThat(other.game.hand).hasSize(3)
            assertThat(random.shuffles).isEqualTo(2)
            assertThat(owner.history).contains("p1: $command")
            assertThat(other.history).contains("p1: return-card <hidden>")
            assertThat(owner.events.single { it.kind == HistoryEventKind.RETURN_CARD }.card)
                .isEqualTo(card.definition.id)
            assertThat(other.events.single { it.kind == HistoryEventKind.RETURN_CARD }.card)
                .isNull()
            assertThat(other.legalChoices.commands).hasSize(3)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun creationVariantsAreRecordedAndSurviveRestart() {
        val directory = Files.createTempDirectory("aor-variants-")
        try {
            val options =
                RuleOptions(
                    deckModification = true,
                    initialCard = InitialCardRule.NO_EVENTS,
                    immediateMasterArtDiscard = true,
                    map = MapRule.EUROPEAN,
                )
            val service = createGameService(directory)
            val created = service.create(listOf("Alice", "Bob", "Carol"), options)
            val token = created.links.first().path.substringAfter("#token=")
            val before = service.get(created.gameId, token)
            val after = createGameService(directory).get(created.gameId, token)

            assertThat(after.game.rules).isEqualTo(options)
            assertThat(after).isEqualTo(before)
            assertThat(after.history)
                .containsAtLeastElementsIn(
                    options.configurationCommands().map { "p1: ${it.toText()}" }
                )
            assertThat(after.game.stage).isEqualTo(Stage.BIDDING)
            assertThat(after.history).contains("p1: start")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
