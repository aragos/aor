package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test

class RuleVariantServiceTest {
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
