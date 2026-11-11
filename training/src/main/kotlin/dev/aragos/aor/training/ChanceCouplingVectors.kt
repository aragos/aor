package dev.aragos.aor.training

import dev.aragos.aor.BoardDecision
import dev.aragos.aor.CardDecision
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameRollKind
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ShufflePurpose
import dev.aragos.aor.Stage
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.WarState

/** Deterministic synthetic codec/sampler vectors; no game is started or policy invoked. */
internal object ChanceCouplingVectors {
    fun result(): Map<String, Any> {
        val players = (1..3).map { Player(PlayerId("p$it"), "Fixture $it") }
        val p1 = players[0].id
        val p2 = players[1].id
        val state =
            GameState(
                GameHeader(players, p1),
                stage = Stage.EXPANSION,
                turnNumber = 3,
                flow =
                    TurnFlow(actor = p1, decisions = listOf(BoardDecision.WindmillDice(p1, p2, 2))),
            )
        val cases =
            listOf(
                "windmill" to (state to SystemNeed.GameRoll(GameRollKind.WINDMILL, 1)),
                "shuffle" to
                    (state to
                        SystemNeed.Shuffle(
                            listOf("stone-2", "stone-1"),
                            ShufflePurpose.NEXT_EPOCH,
                        )),
                "war" to
                    (state.copy(
                        flow =
                            state.flow.copy(
                                decisions = listOf(CardDecision.WarRoll(WarState(p1, p2, 1)))
                            )
                    ) to SystemNeed.GameRoll(GameRollKind.WAR, 2)),
            )
        return mapOf(
            "kind" to "experimental-chance-codec-vectors-1",
            "newGames" to 0,
            "vectors" to
                cases.associate { (name, fixture) ->
                    val (position, need) = fixture
                    val sampler = ExperimentChance(ExperimentChanceMode.LOGICAL_REQUEST_1, 123)
                    val pending = sampler.prepare(position, need, ExperimentRandom(0))
                    name to
                        mapOf(
                            "requestSha256" to pending.request.sha256,
                            "words" to List(8) { pending.random.nextInt() },
                        )
                },
            "unicodeSha256" to ExperimentChanceRequest(listOf("κοινό", "a\nb", "")).sha256,
        )
    }
}
