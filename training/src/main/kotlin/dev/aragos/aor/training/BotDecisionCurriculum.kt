package dev.aragos.aor.training

import dev.aragos.aor.AdvanceAcquisition
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.BoardCommand
import dev.aragos.aor.BoardState
import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardData
import dev.aragos.aor.CardGame
import dev.aragos.aor.Command
import dev.aragos.aor.DieRoll
import dev.aragos.aor.Entry
import dev.aragos.aor.Epoch
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameRollKind
import dev.aragos.aor.GameState
import dev.aragos.aor.GeographicalArea
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerAdvances
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PlayerState
import dev.aragos.aor.Prompt
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.PurchaseStep
import dev.aragos.aor.RuleOptions
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnCommand
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection

/**
 * Offline established-position curriculum for rare player decisions. These are external setup
 * fixtures, not saved games or invented replay histories. Each rare window is reached through the
 * ordinary domain command/outcome that creates it. No fixture or authority state enters a policy;
 * callers must project the deciding seat through BotObservations before inference.
 */
object BotDecisionCurriculum {
    fun positions(playerCount: Int, rules: RuleOptions): List<GameState> =
        CurriculumPositions(playerCount, rules).positions()
}

private class CurriculumPositions(playerCount: Int, private val rules: RuleOptions) {
    private val ids = (1..playerCount).map { PlayerId("p$it") }
    private val owner = ids[0]
    private val opponent = ids[1]
    private val catalog = PublicCatalog.forPlayers(playerCount, rules)
    private val capitals =
        listOf("venice", "genoa", "barcelona", "paris", "london", "hamburg").take(playerCount)
    private val base =
        GameState(
            header = GameHeader(ids.map { Player(it, "Curriculum ${it.value}") }, owner),
            currentEpoch = Epoch.THREE,
            rules = rules,
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            capitalSelection =
                CapitalSelection.fromBids(
                    ids.mapIndexed { index, id ->
                            id to playerCount - index
                        }
                        .toMap()
                ),
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, "Curriculum ${id.value}"),
                                capital = capitals[index],
                                capitalBid = playerCount - index,
                                cash = 1000,
                                writtenCash = 1000,
                                poolTokens = 36,
                                stockTokens = 0,
                            )
                    }
                    .toMap(),
            board =
                ids.zip(capitals).fold(BoardState(definition = rules.map.definition)) {
                    board,
                    (id, capital) ->
                    board.placeMarker(ProvinceId(capital), id).board
                },
            deck = rules.cardsForEpoch(Epoch.THREE),
            flow = TurnFlow(actor = owner, phaseStarted = true, phaseBookkeepingApplied = true),
        )

    fun positions(): List<GameState> {
        val replacement = civilWarReplacement()
        val loss = action(replacement, opponent, CardCommand.ReplaceEventTokens(emptySet()))
        val battle = war()
        val surrender =
            battle.step(GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(6), DieRoll(1))))
        val tied = battle.step(GameOutcome.Roll(GameRollKind.WAR, listOf(DieRoll(3), DieRoll(3))))
        val preparation = action(tied, owner, TurnCommand.Pass)
        val cathedral = cathedral()
        val windmill = windmill()
        val attempt = action(windmill, owner, BoardCommand.Windmill)
        val trade = attempt.step(GameOutcome.Roll(GameRollKind.WINDMILL, listOf(DieRoll(1))))
        return listOfNotNull(
                replacement,
                loss,
                preparation,
                surrender,
                eventMarker(),
                cathedral,
                windmill,
                trade,
                renaissance(),
                patronage(),
                immediateArt(),
            )
            .onEach { state ->
                require(state.prompts().singleOrNull() is Prompt.SinglePlayer) {
                    "Every curriculum position must stop at one player decision: ${state.prompts()}."
                }
            }
    }

    private fun civilWarReplacement(): GameState {
        val state = cards(base, owner, "civil-war")
        return action(
            state,
            owner,
            CardCommand.PlayCard(reference(owner, 0), CardChoice.PlayerChoice(opponent)),
        )
    }

    private fun war(): GameState {
        val state = cards(cards(base, owner, "war", "armor"), opponent, "stirrups")
        return action(
            state,
            owner,
            CardCommand.PlayCard(reference(owner, 0), CardChoice.PlayerChoice(opponent)),
        )
    }

    private fun cathedral(): GameState {
        val (state, target) = competition(AdvanceId.CATHEDRAL)
        return action(state, owner, BoardCommand.Attack(target))
    }

    private fun windmill(): GameState {
        val (state, target) = competition(AdvanceId.WIND_WATERMILL)
        val attack =
            action(
                cards(cards(state, owner, "armor"), opponent, "stirrups"),
                owner,
                BoardCommand.Attack(target),
            )
        return attack.step(
            GameOutcome.Roll(
                GameRollKind.COMPETITION,
                listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
            )
        )
    }

    private fun competition(advance: AdvanceId): Pair<GameState, ProvinceId> {
        val candidates =
            catalog.provinces
                .filter {
                    !it.isSatellite && it.access.isEmpty() && it.id.value !in capitals
                }
                .map { it.id }
                .toSet()
        val connection =
            catalog.landBorders.first {
                it.first in candidates && it.second in candidates
            }
        val board =
            base.board
                .placeMarker(connection.first, owner)
                .board
                .placeMarker(connection.second, opponent)
                .board
        return advances(at(base.copy(board = board), Stage.EXPANSION), owner, advance) to
            connection.second
    }

    private fun eventMarker(): GameState {
        val destination =
            catalog.provinces
                .first {
                    it.area == GeographicalArea.VI && !it.isSatellite
                }
                .id
        val available =
            catalog.provinces
                .filter {
                    !it.isSatellite &&
                        it.overseas == null &&
                        it.id != destination &&
                        it.id.value !in capitals.drop(1) &&
                        it.id.value != capitals.first()
                }
                .take(24)
        require(available.size == 24) {
            "The marker curriculum requires 25 supported mainland markets."
        }
        val board =
            available.fold(base.board) { current, province ->
                current.placeMarker(province.id, owner).board
            }
        val established = advances(base.copy(board = board), owner, AdvanceId.OVERLAND_EAST)
        return action(
            cards(established, owner, "the-crusades"),
            owner,
            CardCommand.PlayCard(reference(owner, 0), CardChoice.ProvinceChoice(destination)),
        )
    }

    private fun renaissance(): GameState {
        val established = advances(base, owner, AdvanceId.RENAISSANCE)
        val lastRound = at(established, Stage.PLAY_CARDS, ids.last())
        return action(lastRound, ids.last(), TurnCommand.Pass)
    }

    private fun patronage(): GameState {
        val established = advances(base, owner, AdvanceId.PATRONAGE)
        val hand =
            cards(
                at(established, Stage.PLAY_CARDS, opponent),
                opponent,
                "charlemagne",
                "marco-polo",
            )
        val first = CardGame.play(hand, opponent, reference(opponent, 0), CardChoice.None)
        val second =
            CardGame.play(first, opponent, reference(opponent, 1), CardChoice.LeaderProtection(5))
        return at(second, Stage.PURCHASE)
    }

    private fun immediateArt(): GameState? {
        if (!rules.immediateMasterArtDiscard) return null
        val established =
            advances(
                at(base, Stage.PURCHASE),
                owner,
                AdvanceId.WRITTEN_RECORD,
                AdvanceId.PRINTED_WORD,
            )
        return action(
            cards(established, owner, "armor"),
            owner,
            TurnCommand.BuyAdvance(AdvanceId.MASTER_ART.letter),
        )
    }

    private fun at(state: GameState, stage: Stage, actor: PlayerId = owner): GameState =
        state.copy(
            stage = stage,
            flow =
                TurnFlow(
                    actor = actor,
                    cursor = ids.indexOf(actor),
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                    purchaseStep = PurchaseStep.ADVANCES,
                ),
        )

    private fun cards(state: GameState, actor: PlayerId, vararg cardIds: String): GameState {
        val participant =
            requireNotNull(state.players[actor]) { "A card fixture requires its seat." }
        return state.copy(
            players =
                state.players +
                    (actor to
                        participant.copy(
                            hand =
                                cardIds.mapIndexed { index, id ->
                                    HandCard(reference(actor, index), CardData.definition(id))
                                }
                        )),
            deck = state.deck.filterNot(cardIds::contains),
        )
    }

    private fun advances(state: GameState, actor: PlayerId, vararg advances: AdvanceId): GameState {
        val participant =
            requireNotNull(state.players[actor]) { "An advance fixture requires its seat." }
        return state.copy(
            players =
                state.players +
                    (actor to
                        participant.copy(
                            advances = PlayerAdvances(advances.map { AdvanceAcquisition(it, 1) })
                        ))
        )
    }

    private fun reference(actor: PlayerId, index: Int): String =
        "hand-${ids.indexOf(actor) * 64 + index + 1}"

    private fun action(state: GameState, actor: PlayerId, command: Command): GameState =
        state.step(Entry.Action(actor, command))
}
