package dev.aragos.aor.training

import dev.aragos.aor.PlayerId

/**
 * Preserve the entire pre-command session and other actors' commitments across one intervention.
 */
internal class FrozenDecisionRoot(
    session: ExperimentSession,
    private val actor: PlayerId,
    commands: Map<PlayerId, String>,
) {
    private val commands = commands.toMap()
    private val snapshot: ExperimentSession

    init {
        require(actor in commands && commands.keys == session.actors().toSet()) {
            "A decision fork requires every command from the same complete pre-batch view."
        }
        snapshot = session.fork()
    }

    fun branch(command: String, seed: Long, deadlineNanos: Long): ExperimentSession =
        snapshot.fork(seed).also { it.apply(commands + (actor to command), deadlineNanos) }
}
