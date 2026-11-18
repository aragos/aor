package dev.aragos.aor

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.security.MessageDigest

/** Tiny engineering comparison on one constructed snapshot; no games, RNG or policy forwards. */
object LegalChoicesQueryBenchmark {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val mode = arguments.single()
        require(mode in setOf("choices", "views")) { "Choose the explicit query benchmark path." }
        val thread = Thread.currentThread().threadId()
        val allocations = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocations.isThreadAllocatedMemoryEnabled = true
        val first = PlayerId("p1")
        val prototype = LegalChoicesCacheFixture.expansion()
        val snapshot =
            listOf(
                    prototype.legalChoices(first),
                    prototype.viewForDecision(first),
                    prototype.viewForPlayer(first),
                )
                .joinToString("\n")
        val snapshotSha256 =
            MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") {
                value ->
                "%02x".format(value)
            }
        fun query(): Long {
            val state = prototype.copy()
            return if (mode == "choices") {
                (1..4).sumOf { state.legalChoices(first).commands.size.toLong() }
            } else {
                val decision = state.viewForDecision(first)
                val commands = state.legalChoices(first)
                val full = state.viewForPlayer(first)
                decision.expansionOffers.size.toLong() +
                    commands.commands.size +
                    full.expansionOffers.size +
                    full.markerReductionOffers.size
            }
        }
        var checksum = 0L
        repeat(100) { checksum += query() }
        val initialBytes = allocations.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        repeat(200) { checksum += query() }
        val nanos = System.nanoTime() - started
        val bytes = allocations.getThreadAllocatedBytes(thread) - initialBytes
        val rss =
            ProcessBuilder("/bin/ps", "-p", ProcessHandle.current().pid().toString(), "-o", "rss=")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
                .toLong()
        println(
            """{"mode":"$mode","batches":200,"warmup":100,"nanoseconds":$nanos,"allocatedBytes":$bytes,"rssKiB":$rss,"checksum":$checksum,"snapshotSha256":"$snapshotSha256","newGames":0,"modelForwards":0,"numericDraws":0}"""
        )
    }
}
