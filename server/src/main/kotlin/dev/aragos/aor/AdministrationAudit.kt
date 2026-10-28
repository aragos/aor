package dev.aragos.aor

import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/** Durable audit destination; SQL receipts commit in the same transaction as their operation. */
fun interface AdministrationAudit {
    fun record(actor: AccountId, action: AdministrativeAction, target: String, revision: Int)
}

/** Coordinates repositories participating in one synchronous application operation. */
interface StorageTransactions {
    fun <T> transaction(block: () -> T): T
}

/** Files retain their existing per-game atomicity and separate intent receipts. */
object FileTransactions : StorageTransactions {
    override fun <T> transaction(block: () -> T): T = block()
}

/** Destructive requests retain intent receipts even if the following storage operation fails. */
class FileAdministrationAudit(
    private val directory: Path,
    private val writer: GameWriter = AtomicGameWriter,
    private val clock: Clock = Clock.systemUTC(),
) : AdministrationAudit {
    override fun record(
        actor: AccountId,
        action: AdministrativeAction,
        target: String,
        revision: Int,
    ) {
        val record =
            mapOf(
                "at" to clock.millis(),
                "actor" to actor.value,
                "action" to action.name,
                "target" to target,
                "revision" to revision,
                "outcome" to "REQUESTED",
            )
        writer.write(
            directory.resolve("${clock.millis()}-${UUID.randomUUID()}.json"),
            json.writeValueAsString(record),
        )
    }
}
