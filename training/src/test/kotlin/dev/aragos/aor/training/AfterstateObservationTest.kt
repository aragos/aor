package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.CardData
import dev.aragos.aor.HandCard
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder
import kotlin.test.Test

class AfterstateObservationTest {
    private val owner = PlayerId("p1")

    @Test
    fun dictionaryPreservesEveryOldCoordinateAndAppendsOnlyThreeContextKeys() {
        val configuration = TrainingMatrix.stratified.first { it.playerCount == 4 }
        val catalog = PublicCatalog.forPlayers(4, configuration.rules)
        val original = JointStateChronology.from(configuration).stateFeatureNames
        val expanded = AfterstateValueEncoder.dictionary(catalog)
        assertThat(expanded.take(original.size)).isEqualTo(original)
        assertThat(expanded.drop(original.size))
            .containsExactly(
                "purchase.decision.purchase-owner-exit",
                "purchase.decision.purchase-relief",
                "purchase.decision.purchase-stabilization",
            )
            .inOrder()
    }

    @Test
    fun cardStateRetainsExactOriginalFeatures() {
        val state = jointStateFixture()
        val view = state.viewForPlayer(owner)
        val observation = BotObservations.from(state, owner)
        val original =
            CardObservationEncoder.from(
                view,
                owner,
                observation,
                observation.candidates.first().command,
            )
        assertThat(AfterstateValueEncoder.from(view, owner, AfterstateContext.CARD, observation))
            .isEqualTo(original.stateFeatures.mapKeys { "card.${it.key}" })
    }

    @Test
    fun purchaseStateRetainsExactOriginalFeatures() {
        val state = jointStateFixture().copy(stage = Stage.PURCHASE)
        val view = state.viewForPlayer(owner)
        val observation = BotObservations.from(state, owner)
        assertThat(AfterstateValueEncoder.from(view, owner, AfterstateContext.SHIPPING))
            .isEqualTo(
                PurchaseObservationEncoder.project(view, owner, observation).stateFeatures.mapKeys {
                    "purchase.${it.key}"
                }
            )
    }

    @Test
    fun actualPassCapturesOwnerExitWithoutChangingStateOrCommand() {
        val state =
            jointStateFixture()
                .copy(
                    stage = Stage.PURCHASE,
                    flow = jointStateFixture().flow.copy(purchaseStep = PurchaseStep.ADVANCES),
                    players =
                        jointStateFixture().players.mapValues { (_, player) ->
                            player.copy(hand = emptyList())
                        },
                )
        val observation = BotObservations.from(state, owner)
        val observer = AfterstateObservation(0)
        observer.chosen(state, 41, observation, "pass")
        assertThat(observer.rows.map { it.context })
            .containsExactly(AfterstateContext.ADVANCES, AfterstateContext.OWNER_EXIT)
            .inOrder()
        assertThat(observer.rows.map { it.ordinal }).containsExactly(0, 1).inOrder()
        assertThat(state.flow.actor).isEqualTo(owner)
        assertThat(observation).isEqualTo(BotObservations.from(state, owner))
    }

    @Test
    fun rivalHiddenCardsAndDeckOrderCannotChangeRecordedInputs() {
        val state =
            jointStateFixture()
                .copy(
                    stage = Stage.PURCHASE,
                    deck = CardData.epochOne.map { it.id },
                    players =
                        jointStateFixture().players.mapValues { (id, player) ->
                            if (id == owner) player
                            else
                                player.copy(
                                    hand = listOf(HandCard("private", CardData.epochOne.first()))
                                )
                        },
                )
        val changed =
            state.copy(
                deck = state.deck.reversed(),
                players =
                    state.players.mapValues { (id, player) ->
                        if (id == owner) player
                        else
                            player.copy(
                                hand =
                                    listOf(
                                        HandCard(
                                            "different-private-reference",
                                            CardData.epochOne.last(),
                                        )
                                    )
                            )
                    },
            )
        assertThat(
                AfterstateValueEncoder.from(
                    state.viewForPlayer(owner),
                    owner,
                    AfterstateContext.SHIPPING,
                )
            )
            .isEqualTo(
                AfterstateValueEncoder.from(
                    changed.viewForPlayer(owner),
                    owner,
                    AfterstateContext.SHIPPING,
                )
            )
    }
}
