package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CommandInputTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")

    @Test
    fun aSinglePromptSuppliesTheActor() {
        val action = TextCodec.parseAction("start", Scenario.create().state)

        assertThat(action.toText()).isEqualTo("p1: start")
    }

    @Test
    fun simultaneousPromptsRequireAnActor() {
        val state = Scenario.create().play("p1: start").state

        val failure =
            assertFailsWith<RuleViolation> { TextCodec.parseAction("bid-capital 3", state) }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun authenticationSuppliesAnActorForSimultaneousPrompts() {
        val state = Scenario.create().play("p1: start").state

        val action = TextCodec.parseAction("bid-capital 3", state, actor = p2)

        assertThat(action.toText()).isEqualTo("p2: bid-capital 3")
    }

    @Test
    fun anAssignedCapitalResolvesToItsRegisteredPlayer() {
        val action = TextCodec.parseAction("venice: pass", playing())

        assertThat(action.toText()).isEqualTo("p1: pass")
    }

    @Test
    fun anUnassignedCapitalCannotIdentifyAnActor() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseAction("venice: start", Scenario.create().state)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNKNOWN_PLAYER)
    }

    @Test
    fun aPrefixCannotOverrideTheAuthenticatedActor() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseAction("venice: pass", playing(), actor = p2)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun everyChoiceKindHasAnUnqualifiedInputForm() {
        val examples =
            listOf(
                Triple("cloth-wine", "cloth", "commodity cloth"),
                Triple("war", "p2", "player p2"),
                Triple("the-crusades", "alexandria", "province alexandria"),
                Triple("pirates-vikings", "tunis acre", "provinces tunis acre"),
                Triple("black-death", "vi", "area vi"),
                Triple("papal-decree", "none", "category none"),
                Triple("charlemagne", "5", "protect 5"),
            )

        for ((card, input, canonical) in examples) {
            val action = TextCodec.parseAction("play hand-1 $input", playing(card))

            assertThat(action.toText()).isEqualTo("p1: play hand-1 $canonical")
        }
    }

    @Test
    fun aDualCommodityNameAcceptsEitherPrintedOrder() {
        val action = TextCodec.parseAction("play wine/cloth cloth", playing("cloth-wine"))

        assertThat(action.toText()).isEqualTo("p1: play hand-1 commodity cloth")
    }

    @Test
    fun crusadesHasAShortName() {
        val action = TextCodec.parseAction("play crusades alexandria", playing("the-crusades"))

        assertThat(action.toText()).isEqualTo("p1: play hand-1 province alexandria")
    }

    @Test
    fun explicitChoicesAlsoAcceptCardNames() {
        val action = TextCodec.parseAction("play war player p2", playing("war"))

        assertThat(action.toText()).isEqualTo("p1: play hand-1 player p2")
    }

    @Test
    fun duplicateCommodityNamesRequireAReference() {
        val state =
            playing("stone-1").updatePlayer(p1) { player ->
                player.copy(hand = player.hand + HandCard("hand-4", CardData.definition("stone-2")))
            }

        val failure = assertFailsWith<RuleViolation> { TextCodec.parseAction("play stone", state) }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun aPhysicalCardIdDisambiguatesCopies() {
        val state =
            playing("stone-1").updatePlayer(p1) { player ->
                player.copy(hand = player.hand + HandCard("hand-4", CardData.definition("stone-2")))
            }

        val action = TextCodec.parseAction("play stone-2", state)

        assertThat(action.toText()).isEqualTo("p1: play hand-4")
    }

    @Test
    fun otherPlayersCardsDoNotAffectNameResolution() {
        val state =
            playing("stone-1").updatePlayer(p2) { player ->
                player.copy(hand = listOf(HandCard("hand-2", CardData.definition("stone-2"))))
            }

        val action = TextCodec.parseAction("play stone", state)

        assertThat(action.toText()).isEqualTo("p1: play hand-1")
    }

    @Test
    fun invalidShorthandHasATypedDiagnostic() {
        val state = playing("charlemagne")
        val inputs = listOf("play", "play charlemagne -1", "play charlemagne 2147483648")

        val codes = inputs.map { input ->
            assertFailsWith<RuleViolation> { TextCodec.parseAction(input, state) }.code
        }

        assertThat(codes)
            .containsExactlyElementsIn(List(inputs.size) { ViolationCode.INVALID_COMMAND })
    }

    @Test
    fun liveInputCannotContainMultipleLines() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseAction("p1\n: start", Scenario.create().state)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun replayStillRequiresExplicitActorIdsAndChoiceKinds() {
        val inputs = listOf("start", "venice: pass", "p1: play hand-1 cloth")

        val codes = inputs.map { input ->
            assertFailsWith<RuleViolation> { TextCodec.parse(input) }.code
        }

        assertThat(codes)
            .containsExactlyElementsIn(List(inputs.size) { ViolationCode.INVALID_COMMAND })
    }

    private fun playing(card: String = "fur"): GameState {
        val state = Scenario.create().state
        val capitals = listOf("venice", "genoa", "barcelona")
        return state.copy(
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(state.playerIds),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            players =
                state.header.players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                hand =
                                    if (player.id == p1) {
                                        listOf(HandCard("hand-1", CardData.definition(card)))
                                    } else {
                                        emptyList()
                                    },
                            )
                    }
                    .toMap(),
        )
    }
}
