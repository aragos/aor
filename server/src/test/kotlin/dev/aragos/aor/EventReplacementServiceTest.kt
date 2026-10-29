package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Normal commands establish a multi-owner event; no state injection bypasses service replay. */
class EventReplacementServiceTest {
    private val directories = mutableListOf<Path>()
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val genoa = ProvinceId("genoa")
    private val florence = ProvinceId("florence")

    @AfterTest
    fun clean() {
        directories.forEach { directory -> directory.toFile().deleteRecursively() }
    }

    @Test
    fun serviceOffersEveryOwnersProvinceBeforeTheNextOwnerInCurrentOrder() {
        val fixture = ready()
        val first = fixture.view(1)

        val next = fixture.submit(1, "replace-event-tokens florence genoa")
        val serialized = json.readTree(json.writeValueAsString(fixture.view(2)))

        assertThat(first.game.turnOrder).containsExactly(PlayerId("p3"), p2, p1).inOrder()
        assertThat(first.game.capitalOrder).containsExactly(p1, p2, PlayerId("p3")).inOrder()
        assertThat(first.game.replacementToken?.player).isEqualTo(p2)
        assertThat(first.game.replacementToken?.provinces).containsExactly(genoa, florence)
        assertThat(next.game.replacementToken?.player).isEqualTo(p1)
        assertThat(next.game.replacementToken?.provinces).containsExactly(ProvinceId("venice"))
        assertThat(next.game.players.single { it.id == p2 }.poolTokens).isEqualTo(34)
        assertThat(serialized.path("game").path("replacementToken").path("player").asText())
            .isEqualTo("p1")
        assertThat(serialized.path("game").path("hand").map { it.path("reference").asText() })
            .containsNoneIn(first.game.hand.map(HandCard::reference))
        assertThat(serialized.path("legalChoices").path("commands").isEmpty).isTrue()
    }

    @Test
    fun onlyTheCurrentOwnerMayConfirmAndTheSavedGameRemainsUnchanged() {
        val fixture = ready()
        val before = Files.readString(fixture.path)

        val failure =
            assertFailsWith<RuleViolation> {
                fixture.submit(0, "replace-event-tokens venice")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
        assertThat(Files.readString(fixture.path)).isEqualTo(before)
    }

    @Test
    fun allEmptyConfirmationPersistsAndReplaysWithoutNewRandomness() {
        val fixture = ready()

        val after = fixture.submit(1, "replace-event-tokens none")
        val recovered =
            createGameService(fixture.directory, ReplayOnlyEventRandom)
                .get(fixture.game.gameId, fixture.token(1))

        assertThat(recovered).isEqualTo(after)
        assertThat(after.game.board.none { it.id == genoa }).isTrue()
        assertThat(after.game.board.none { it.id == florence }).isTrue()
        assertThat(after.game.players.single { it.id == p2 }.poolTokens).isEqualTo(36)
        assertThat(after.game.replacementToken?.player).isEqualTo(p1)
    }

    @Test
    fun aStaleBatchCannotConfirmTheNextOwnersDecision() {
        val fixture = ready()
        val revision = fixture.view(1).revision
        fixture.submit(1, "replace-event-tokens none")

        val failure =
            assertFailsWith<ServiceFailure> {
                fixture.service.submit(
                    fixture.game.gameId,
                    fixture.token(1),
                    revision,
                    "replace-event-tokens genoa",
                )
            }

        assertThat(failure.code).isEqualTo("STALE_REVISION")
    }

    private fun ready(): EventFixture {
        val directory = Files.createTempDirectory("aor-event-replacements-").also(directories::add)
        val service = createGameService(directory, EventRandom)
        val created = service.create(listOf("One", "Two", "Three"))
        val fixture = EventFixture(directory, service, created)
        listOf(3, 2, 1).forEachIndexed { seat, bid -> fixture.submit(seat, "bid-capital $bid") }
        listOf("venice", "genoa", "barcelona").forEachIndexed { seat, capital ->
            fixture.submit(seat, "choose-capital $capital")
        }
        repeat(600) {
            val public = fixture.view(0)
            val actor =
                checkNotNull(public.prompt?.players?.firstOrNull()) {
                    "The service fixture requires an actionable decision at ${public.game.stage}."
                }
            val seat = created.links.indexOfFirst { it.id == actor }
            val current = fixture.view(seat)
            val event = current.game.hand.firstOrNull { it.definition.id == "black-death" }
            if (current.game.stage == Stage.PLAY_CARDS && event != null) {
                fixture.submit(seat, "play ${event.reference} area vii")
                return fixture
            }
            val commands = current.legalChoices.commands
            val safeReferences =
                current.game.hand
                    .filter { it.definition.kind != CardKind.EVENT }
                    .map(HandCard::reference)
                    .toSet()
            val safePlay =
                current.game.cardOffers
                    .filter { it.reference in safeReferences && it.playable }
                    .flatMap(CardOfferView::choices)
                    .firstNotNullOfOrNull(CardChoiceView::command)
            val command =
                when {
                    current.game.stage == Stage.TOKEN_PURCHASE -> {
                        val amount =
                            if (current.game.turnNumber == 1) {
                                listOf(0, 8, 2)[seat]
                            } else if (current.game.deckRemaining <= 3) {
                                listOf(37, 36, 0)[seat]
                            } else {
                                seat
                            }
                        "buy-tokens $amount"
                    }
                    current.game.stage == Stage.EXPANSION &&
                        actor == p2 &&
                        current.game.turnNumber == 1 &&
                        current.game.board.none { it.id == florence && it.marker?.owner == p2 } -> {
                        val offer =
                            checkNotNull(
                                current.game.expansionOffers.firstOrNull {
                                    it.destination == florence
                                }
                            ) {
                                "Genoa must reach Florence in the normal expansion fixture."
                            }
                        "place ${offer.requiredTokens} florence"
                    }
                    current.game.stage == Stage.PLAY_CARDS && safePlay != null -> safePlay
                    "stabilize cash" in commands -> "stabilize cash"
                    "stabilize misery" in commands -> "stabilize misery"
                    "pass" in commands -> "pass"
                    else -> commands.first()
                }
            try {
                fixture.submit(seat, command)
            } catch (failure: Exception) {
                throw AssertionError(
                    "Rejected $command in ${current.game.stage}, turn ${current.game.turnNumber}, " +
                        "cash ${current.game.players.single { it.id == actor }.cash}, " +
                        "deck ${current.game.deckRemaining}.",
                    failure,
                )
            }
        }
        error("Normal command progression must draw Black Death in epoch two.")
    }
}

private data class EventFixture(
    val directory: Path,
    val service: GameService,
    val game: CreatedGame,
) {
    val path: Path
        get() = directory.resolve("${game.gameId}.json")

    fun token(seat: Int): String = game.links[seat].path.substringAfter("token=")

    fun view(seat: Int): GameSnapshot = service.get(game.gameId, token(seat))

    fun submit(seat: Int, command: String): GameSnapshot =
        service.submit(game.gameId, token(seat), view(seat).revision, command)
}

private object EventRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = cards.sortedBy { it != "black-death" }

    override fun roll(count: Int): List<DieRoll> = List(count) { DieRoll(6) }
}

private object ReplayOnlyEventRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> =
        error("Recovery must replay shuffles.")

    override fun roll(count: Int): List<DieRoll> = error("Recovery must replay dice.")
}
