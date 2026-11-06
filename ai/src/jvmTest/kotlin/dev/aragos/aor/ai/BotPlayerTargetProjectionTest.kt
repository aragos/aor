package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import kotlin.test.Test

class BotPlayerTargetProjectionTest {
    private val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
    private val actor = players.last().id

    @Test
    fun onlyAuthorizedPlayerCardChoicesReceiveTypedTargets() {
        val observation = BotObservations.from(fixture(), actor)

        val targeted = observation.candidates.filter { it.playerTarget != null }

        assertThat(targeted.map { it.command })
            .containsExactlyElementsIn(players.map { "play event player ${it.id}" })
        assertThat(targeted.map { it.playerTarget })
            .containsExactlyElementsIn(players.map { it.id })
        val commodity =
            observation.candidates.single { it.command == "play commodity commodity stone" }
        assertThat(commodity.playerTarget).isNull()
        assertThat(observation.candidates.single { it.command == "pass" }.playerTarget).isNull()
    }

    @Test
    fun aCivilWarTieCanChooseAnotherLegalOpponentWithoutChangingTheCard() {
        val before = fixture()
        val observation = BotObservations.from(before, actor)
        val model =
            BotModel(
                weights =
                    mapOf(
                        "opponentMisery" to 1.0,
                        "misery" to -1.0,
                        BotTargetTieBreak.GATE to 1.0,
                    )
            )

        val command = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)
        val after = before.step(TextCodec.parse("$actor: $command"))

        assertThat(command).isEqualTo("play event player p3")
        assertThat(after.playerState(PlayerId("p3")).misery.value).isEqualTo(10)
    }

    @Test
    fun uncertainWarTargetsRemainEqualScoredAndExcludeTheActor() {
        val observation =
            BotObservations.from(fixture(event = "war").copy(currentEpoch = Epoch.TWO), actor)
        val model = BotModel(weights = mapOf("card.war" to 1.0, BotTargetTieBreak.GATE to 1.0))

        val command = BotPolicy.choose(observation, BotDifficulty.HARD, 17, model)

        val targeted = observation.candidates.filter { it.playerTarget != null }
        assertThat(targeted.map { it.playerTarget })
            .containsExactlyElementsIn(players.dropLast(1).map { it.id })
        assertThat(targeted.map { BotPolicy.score(it, model) }.distinct()).containsExactly(1.0)
        assertThat(command).isEqualTo("play event player p3")
    }

    @Test
    fun anOpponentsHiddenCardIdentityCannotChangeTargetMetadataOrChoice() {
        val before = fixture()
        val opponent = players.first().id
        fun withSecret(card: String): GameState =
            before.copy(
                players =
                    before.players +
                        (opponent to
                            before
                                .playerState(opponent)
                                .copy(hand = listOf(HandCard("secret", CardData.definition(card)))))
            )
        val original = BotObservations.from(withSecret("armor"), actor)
        val other = BotObservations.from(withSecret("charlemagne"), actor)

        assertThat(original.candidates).isEqualTo(other.candidates)
        val model =
            BotModels.default.copy(
                weights = BotModels.default.weights + (BotTargetTieBreak.GATE to 1.0)
            )
        assertThat(BotPolicy.choose(original, BotDifficulty.HARD, 17, model))
            .isEqualTo(BotPolicy.choose(other, BotDifficulty.HARD, 17, model))
    }

    private fun fixture(event: String = "civil-war"): GameState {
        val capitals = listOf("genoa", "venice", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, players.first().id),
            currentEpoch = Epoch.ONE,
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection =
                TurnOrderSelection(listOf(actor) + players.dropLast(1).map { it.id }),
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 30,
                                poolTokens = 10,
                                stockTokens = 26,
                                hand =
                                    if (player.id == actor) {
                                        listOf(
                                            HandCard("event", CardData.definition(event)),
                                            HandCard("commodity", CardData.definition("stone-1")),
                                        )
                                    } else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = actor, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
