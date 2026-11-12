package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** UNIT detached labels only; never opens retained research origins. */
class PurchaseMacroLabelsTest {
    private val mapper = PurchaseJson.mapper
    private val streams = listOf(-941999933L, -941999932L)
    private val plans =
        mapper.readTree(
            """[
        {"planIndex":0,"commands":["pass"],"support":"SUPPORTED"},
        {"planIndex":1,"commands":["buy-advance N","pass"],"support":"SUPPORTED"},
        {"planIndex":2,"commands":["buy-advance P"],"support":"INTERVENING_CHOICE"}]
    """
        )

    private fun tail(
        commands: List<String>,
        stream: Long,
        arm: String = "COMPLETE_PREFIX",
        stop: String = "PREFIX_COMPLETE",
        poison: Boolean = false,
    ): Map<String, Any> =
        linkedMapOf(
            "arm" to arm,
            "commands" to commands,
            "continuationSeed" to stream,
            "stop" to stop,
            "steps" to
                if (poison) "unread UNIT effects"
                else
                    commands.map {
                        mapOf(
                            "chosenCommand" to it,
                            "forced" to true,
                            "observation" to mapOf("unit" to "skipped"),
                        )
                    },
            "outcome" to
                if (poison) "unread UNIT label"
                else
                    mapOf(
                        "reward" to .9,
                        "winShare" to 1.0,
                        "rankReward" to .5,
                        "continuationSeed" to stream,
                        "scores" to
                            listOf(mapOf("player" to "p1", "misery" to 100, "total" to 200)),
                    ),
            "replayVerified" to true,
        )

    private fun retained(tails: List<Map<String, Any>>) = mapOf("root" to mapOf("tails" to tails))

    private fun valid() =
        listOf(listOf("pass"), listOf("buy-advance N", "pass")).flatMap { commands ->
            streams.map { tail(commands, it) }
        }

    @Test
    fun onlySupportedCompletePrefixMetricsAreDecoded() {
        val path = Files.createTempFile("macro-labels-unit", ".json")
        try {
            val ignored = streams.flatMap { stream ->
                listOf(
                    tail(listOf("buy-advance N"), stream, arm = "FIRST_ACTION_ONLY", poison = true),
                    tail(listOf("buy-advance P"), stream, poison = true),
                )
            }
            mapper.writeValue(path.toFile(), retained(valid() + ignored))
            val result = PurchaseMacroLabels.readReturns(path, plans, streams, "p1")
            assertThat(result.map { it["planIndex"] }).containsExactly(0, 1).inOrder()
            assertThat(result.map { it["continuationSeeds"] }).containsExactly(streams, streams)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun changedExecutionStopCannotSupplyCompleteMacroLabels() {
        val path = Files.createTempFile("macro-changed-stop-unit", ".json")
        try {
            val changed =
                valid().mapIndexed { index, value ->
                    if (index == 2) value + ("stop" to "CHANGED_OFFER") else value
                }
            mapper.writeValue(path.toFile(), retained(changed))
            assertFailsWith<IllegalArgumentException> {
                PurchaseMacroLabels.readReturns(path, plans, streams, "p1")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingSecondFutureIsRejected() {
        val path = Files.createTempFile("macro-missing-stream-unit", ".json")
        try {
            mapper.writeValue(path.toFile(), retained(valid().dropLast(1)))
            assertFailsWith<IllegalArgumentException> {
                PurchaseMacroLabels.readReturns(path, plans, streams, "p1")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun duplicateSiblingReturnIsRejected() {
        val path = Files.createTempFile("macro-duplicate-unit", ".json")
        try {
            mapper.writeValue(path.toFile(), retained(valid() + valid().first()))
            assertFailsWith<IllegalArgumentException> {
                PurchaseMacroLabels.readReturns(path, plans, streams, "p1")
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
