package dev.aragos.aor.training

import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.replay

internal data class LeaderCoordinationRoot(
    val world: Int,
    val state: GameState,
    val actor: PlayerId,
    val command: String,
    val target: LeaderPurchaseTarget,
    val receipt: Map<String, Any?>,
)

internal data class LeaderCoordinationWorld(
    val world: Int,
    val root: LeaderCoordinationRoot?,
    val receipt: Map<String, Any?>,
)

/** Fresh ordinary-policy worlds; only deterministic actor-visible eligibility locates a root. */
internal class LeaderCoordinationCollection(private val input: LeaderCoordinationInput) {
    fun collect(world: Int, deadline: Long): LeaderCoordinationWorld {
        val session =
            ExperimentSession.start(
                input.arena.configurations.single(),
                input.originChance[world].toLong(),
                recordEntries = true,
            )
        val seat = world % 4
        val actor = session.state.playerIds[seat]
        val continuation = continuation(seat, input.originPolicy[world], deadline)
        var leaderVisits = 0
        while (session.state.stage != Stage.FINISHED) {
            continuation.check(session)
            val observations = session.actors().associateWith(session::observation)
            val commands = continuation.commands(session, observations)
            if (session.state.stage == Stage.PLAY_CARDS && actor in observations) {
                val command =
                    requireNotNull(commands[actor]) { "A live actor has its chosen command." }
                val view = session.state.viewForPlayer(actor)
                val reference = command.takeIf { it.startsWith("play ") }?.split(' ')?.get(1)
                val card =
                    view.cardOffers.firstOrNull {
                        it.reference == reference && it.leaderDiscounts.isNotEmpty()
                    }
                if (card != null) {
                    leaderVisits++
                    val cardId = view.hand.single { it.reference == reference }.definition.id
                    // The chosen ordinary leader action is deterministic. No future outcome is
                    // sampled.
                    val after =
                        session.state.step(TextCodec.parseAction(command, session.state, actor))
                    val target = LeaderPurchaseQuotes.target(after.viewForPlayer(actor), cardId)
                    if (target != null) {
                        val prefix = prefix(session, world, seat, leaderVisits)
                        val receipt =
                            prefix +
                                mapOf(
                                    "command" to command,
                                    "target" to target,
                                    "actor" to actor.value,
                                    "turn" to view.turnNumber,
                                    "observation" to observations[actor],
                                    "postLeaderQuote" to
                                        after.viewForPlayer(actor).advanceOffers.single {
                                            it.advance == target.advance
                                        },
                                )
                        return LeaderCoordinationWorld(
                            world,
                            LeaderCoordinationRoot(
                                world,
                                session.state,
                                actor,
                                command,
                                target,
                                receipt,
                            ),
                            receipt + mapOf("status" to "eligible"),
                        )
                    }
                }
            }
            session.apply(commands, deadline)
        }
        return LeaderCoordinationWorld(
            world,
            null,
            prefix(session, world, seat, leaderVisits) + mapOf("status" to "no-eligible-leader"),
        )
    }

    fun replicate(
        root: LeaderCoordinationRoot,
        rootIndex: Int,
        replicate: Int,
        deadline: Long,
    ): List<Map<String, Any?>> {
        val index = rootIndex * 16 + replicate
        val chance = input.tailChance[index]
        val policy = input.tailPolicy[index]
        val seat = root.state.playerIds.indexOf(root.actor)
        return (0..1).map { action ->
            val session = ExperimentSession(root.state, chance.toLong(), recordEntries = true)
            val ordinary = controllers(seat)
            val coordinated =
                if (action == 1) {
                    LeaderPurchaseCoordination(
                        root.actor,
                        requireNotNull(root.state.turnNumber) { "A leader root has a live turn." },
                        root.target,
                        requireNotNull(ordinary[seat]) {
                            "The controlled seat has its frozen Purchase controller."
                        },
                    )
                } else {
                    null
                }
            val active = if (coordinated == null) ordinary else ordinary + (seat to coordinated)
            val continuation =
                FrozenPolicyContinuation(
                    List(4) { input.arena.candidate },
                    active,
                    policy,
                    deadline,
                )
            session.apply(mapOf(root.actor to root.command), deadline)
            val terminal =
                continuation.finish(
                    session,
                    seat,
                    input.arena.configurations.single().id,
                    chance.toLong(),
                )
            val economics =
                RecordedPrefixReplay.verify(
                    root.state,
                    root.actor,
                    session.entries,
                    session.state,
                    deadline,
                )
            mapOf(
                "world" to root.world,
                "rootIndex" to rootIndex,
                "actor" to root.actor.value,
                "action" to action,
                "replicate" to replicate,
                "split" to "evaluation",
                "command" to root.command,
                "target" to root.target,
                "policySeed" to policy,
                "terminal" to terminal,
                "entries" to session.entries.map(TextCodec::print),
                "economics" to economics,
                "coordination" to coordinated?.receipt(),
                "replayVerified" to true,
            )
        }
    }

    private fun controllers(seat: Int): Map<Int, SimulationController> =
        input.arena.controllers(4, seat, ArenaSide.CANDIDATE, input.arena.candidate, List(4) { 0 })

    private fun continuation(seat: Int, seed: Int, deadline: Long): FrozenPolicyContinuation =
        FrozenPolicyContinuation(
            List(4) { input.arena.candidate },
            controllers(seat),
            seed,
            deadline,
        )

    private fun prefix(
        session: ExperimentSession,
        world: Int,
        seat: Int,
        visits: Int,
    ): Map<String, Any?> {
        val entries = session.entries.map(TextCodec::print)
        require(replay(session.state.header, entries) == session.state) {
            "Every origin prefix must replay exactly."
        }
        val header = session.state.header
        return mapOf(
            "world" to world,
            "seat" to seat,
            "originChance" to input.originChance[world],
            "originPolicy" to input.originPolicy[world],
            "decisions" to session.decisions,
            "leaderVisits" to visits,
            "entries" to entries,
            "replayVerified" to true,
            "header" to
                mapOf(
                    "players" to
                        header.players.map { mapOf("id" to it.id.value, "name" to it.name) },
                    "creatorId" to header.creatorId.value,
                    "rulesVersion" to header.rulesVersion,
                    "dataVersion" to header.dataVersion,
                    "drawRenaissanceFromTurn" to header.drawRenaissanceFromTurn,
                ),
        )
    }
}
