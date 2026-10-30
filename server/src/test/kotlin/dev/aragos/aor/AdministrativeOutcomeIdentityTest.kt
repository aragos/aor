package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AdministrativeOutcomeIdentityTest {
    @Test
    fun unchangedDiceCannotBeReboundToAnotherLegalCompetition() {
        val directory = Files.createTempDirectory("aor-outcome-identity-")
        try {
            val store = FileGameStore(directory)
            val service = GameService(store, GameDriver(FixedRandom()))
            val created = service.create(listOf("Alice", "Bob", "Carol"))
            val tokens = created.links.map { it.path.substringAfter("#token=") }
            fun view(seat: Int) = service.get(created.gameId, tokens[seat])
            fun send(seat: Int, text: String) =
                service.submit(created.gameId, tokens[seat], view(seat).revision, text)
            for (seat in 0..2) send(seat, "bid-capital ${3 - seat}")
            listOf("venice", "genoa", "barcelona").forEachIndexed { seat, capital ->
                send(seat, "choose-capital $capital")
            }
            fun reachExpansion() {
                repeat(100) {
                    val snapshot = view(0)
                    if (snapshot.game.stage == Stage.EXPANSION) return
                    val seat =
                        created.links.indexOfFirst {
                            it.id == snapshot.prompt?.players?.firstOrNull()
                        }
                    check(seat >= 0) { "The opening must expose its next participant." }
                    val current = view(seat)
                    val choices = current.legalChoices.commands
                    val command =
                        when {
                            current.game.stage == Stage.TOKEN_PURCHASE -> "buy-tokens ${10 + seat}"
                            "upgrade-ships" in choices -> "upgrade-ships"
                            "stabilize cash" in choices -> "stabilize cash"
                            "pass" in choices -> "pass"
                            else -> choices.first()
                        }
                    send(seat, command)
                }
                error("The fixture must reach expansion.")
            }
            reachExpansion()
            val destinations =
                view(0)
                    .game
                    .expansionOffers
                    .filter { offer ->
                        offer.primaryAction == ExpansionAction.PLACE &&
                            offer.requiredTokens in 2..3 &&
                            offer.partialPlacements.any { it.tokens == 1 }
                    }
                    .map { it.destination }
            for (destination in destinations) {
                if (
                    view(0).game.expansionOffers.any {
                        it.destination == destination &&
                            it.partialPlacements.any { choice -> choice.tokens == 1 }
                    }
                ) {
                    send(0, "place 1 $destination")
                }
            }
            send(0, "pass")
            send(1, "pass")
            send(2, "pass")
            reachExpansion()
            send(0, "pass")
            val attacks =
                view(1).game.expansionOffers.filter {
                    it.primaryAction == ExpansionAction.ATTACK && it.blockers.isEmpty()
                }
            check(attacks.size >= 2) { "The reachable fixture needs two legal contests: $attacks" }
            val prefix = store.read(created.gameId).effectiveEntries().map(RecordedEntry::text)
            send(1, "attack ${attacks[0].destination}")
            val original = store.document(created.gameId)
            val stored = store.read(created.gameId)
            val recorded = stored.effectiveEntries().map(RecordedEntry::text)
            val suffix = recorded.drop(prefix.size)
            val alternate = prefix + "p2: attack ${attacks[1].destination}" + suffix.drop(1)
            // Establish the counterexample: byte-preserving replacement really is legal replay
            // input.
            replay(stored.header, alternate)
            assertThat(alternate.filter { it.startsWith('!') })
                .isEqualTo(recorded.filter { it.startsWith('!') })
            val admin =
                GameAdministration(store, FileAdministrationAudit(directory.resolve("audit")))

            val validation =
                assertFailsWith<ServiceFailure> {
                    admin.validateLog(
                        created.gameId,
                        AccountId("admin"),
                        stored.revision,
                        alternate,
                    )
                }
            val publication =
                assertFailsWith<ServiceFailure> {
                    admin.replaceLog(created.gameId, AccountId("admin"), stored.revision, alternate)
                }

            assertThat(validation.code).isEqualTo("HISTORY_RANDOMNESS_CHANGED")
            assertThat(publication.code).isEqualTo("HISTORY_RANDOMNESS_CHANGED")
            assertThat(store.document(created.gameId)).isEqualTo(original)
            assertThat(store.archives(created.gameId)).isEmpty()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
