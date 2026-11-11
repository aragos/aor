package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.BoardState
import dev.aragos.aor.CardData
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchasePrivilegedPrefixTest {
    @Test
    fun originalCanonicalHeaderRetainsStrongPlayerIdentifiers() {
        val original = state().header
        val stored = PurchaseJson.mapper.valueToTree<JsonNode>(original)

        val decoded = PurchasePrivilegedFiles.header(stored)

        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun privateCountsUseRelativeSeatsAndExcludeOwnCardsAndReferences() {
        val state =
            state()
                .copy(
                    players =
                        state().players.mapValues { (id, player) ->
                            player.copy(
                                hand = listOf(HandCard("secret-$id", CardData.definition("wool-1")))
                            )
                        }
                )
        val actor = PlayerId("p3")
        val own =
            PurchasePrivilegedPrefix.observation(
                state.copy(flow = state.flow.copy(actor = actor)),
                actor,
                "pass",
            )

        val features = PurchasePrivilegedPrefix.features(state, actor, own)

        assertThat(features.opponentHandCounts)
            .containsExactly(
                "role.1.card.wool-1",
                1,
                "role.2.card.wool-1",
                1,
                "role.3.card.wool-1",
                1,
            )
        assertThat(PurchaseJson.mapper.writeValueAsString(features)).doesNotContain("secret-")
    }

    @Test
    fun changedPrivateCardDoesNotChangeActorObservationProbabilitiesOrModelExport() {
        val original = state()
        val changed =
            original.copy(
                players =
                    original.players.mapValues { (id, player) ->
                        if (id == PlayerId("p2")) {
                            player.copy(
                                hand =
                                    listOf(
                                        HandCard(
                                            "different-reference",
                                            CardData.definition("armor"),
                                        )
                                    )
                            )
                        } else {
                            player
                        }
                    }
            )
        val before = PurchasePrivilegedPrefix.observation(original, actor, "pass")
        val model = model(original)
        val exported = PurchaseJson.mapper.writeValueAsString(model)
        val probabilities = PurchasePolicy.evaluate(before, model).probabilities

        val after = PurchasePrivilegedPrefix.observation(changed, actor, "pass")
        val privileged = PurchasePrivilegedPrefix.features(changed, actor, after)

        assertThat(privileged.opponentHandCounts)
            .isNotEqualTo(
                PurchasePrivilegedPrefix.features(original, actor, before).opponentHandCounts
            )
        assertThat(after).isEqualTo(before)
        assertThat(PurchasePolicy.evaluate(after, model).probabilities).isEqualTo(probabilities)
        assertThat(PurchaseJson.mapper.writeValueAsString(model)).isEqualTo(exported)
    }

    @Test
    fun futureSuffixIsNotParsedAndCannotAffectEarlierFeatures() {
        val state = state()
        val record = record(state, 0)
        val first = pass.toText()
        val before = PurchasePrivilegedPrefix.extract(state, actor, listOf(first), listOf(record))

        val after =
            PurchasePrivilegedPrefix.extract(
                state,
                actor,
                listOf(first, "not a canonical future entry"),
                listOf(record),
            )

        assertThat(after).isEqualTo(before)
        assertThat(after.single().entryIndex).isEqualTo(0)
    }

    @Test
    fun sequentialPurchasesUseActualPreActionStates() {
        val initial = state()
        val afterShipping = initial.step(pass)
        val records = listOf(record(initial, 0), record(afterShipping, 1))

        val captures =
            PurchasePrivilegedPrefix.extract(
                initial,
                actor,
                listOf(pass.toText(), pass.toText()),
                records,
            )

        assertThat(captures.map { it.decision })
            .containsExactly("purchase-shipping", "purchase-advances")
            .inOrder()
        assertThat(captures.map { it.entryIndex }).containsExactly(0, 1).inOrder()
        assertThat(captures.last().features.stateFeatures)
            .isEqualTo(
                PurchasePrivilegedPrefix.observation(afterShipping, actor, "pass").stateFeatures
            )
    }

    @Test
    fun alteredOriginalActorFeatureIsRejected() {
        val state = state()
        val record = record(state, 0)
        (record.required("observation").required("stateFeatures") as ObjectNode).put("turn", 999.0)

        assertFailsWith<IllegalArgumentException> {
            PurchasePrivilegedPrefix.extract(state, actor, listOf(pass.toText()), listOf(record))
        }
    }

    @Test
    fun missingControlledPurchaseCannotBeSkipped() {
        val state = state()
        val onlyLater = record(state.step(pass), 1)

        assertFailsWith<IllegalArgumentException> {
            PurchasePrivilegedPrefix.extract(
                state,
                actor,
                listOf(pass.toText(), pass.toText()),
                listOf(onlyLater),
            )
        }
    }

    private fun record(state: GameState, number: Int): JsonNode {
        val observation = PurchasePrivilegedPrefix.observation(state, actor, "pass")
        return PurchaseJson.mapper.valueToTree(
            mapOf(
                "number" to number,
                "turn" to state.turnNumber,
                "decision" to BotObservations.from(state, actor).decision,
                "observation" to observation,
                "selectedIndex" to observation.actions.indexOfFirst { it.command == "pass" },
            )
        )
    }

    private fun model(state: GameState): PurchasePolicyModel {
        val dictionary = PurchaseObservationEncoder.dictionary(state.viewForPlayer(actor))
        return PurchasePolicyModel(
            schemaVersion = 1,
            encoderVersion = PurchaseObservationEncoder.ENCODER_VERSION,
            hiddenSize = 64,
            baselineProbability = .97,
            stateFeatureNames = dictionary.stateFeatureNames,
            actionFeatureNames = dictionary.actionFeatureNames,
            stateWeights = List(64 * dictionary.stateFeatureNames.size) { 0.0 },
            stateBias = List(64) { 0.0 },
            actionWeights = List(64 * dictionary.actionFeatureNames.size) { 0.0 },
            actionBias = List(64) { 0.0 },
            actorWeights = List(64) { 0.0 },
            valueWeights = List(64) { 0.0 },
            valueBias = 0.0,
        )
    }

    private fun state(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Fixture $it") }
        val capitals = listOf("venice", "genoa", "barcelona", "paris")
        val board =
            players.zip(capitals).fold(BoardState()) { current, (player, capital) ->
                current.placeMarker(ProvinceId(capital), player.id).board
            }
        return GameState(
            header = GameHeader(players, actor),
            stage = Stage.PURCHASE,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                cash = 100,
                                hand =
                                    if (index == 1)
                                        listOf(HandCard("hidden", CardData.definition("stirrups")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow =
                TurnFlow(
                    actor = actor,
                    purchaseStep = PurchaseStep.SHIPPING,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
        )
    }

    private val actor = PlayerId("p1")
    private val pass = Entry.Action(actor, TurnCommand.Pass)
}
