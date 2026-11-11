package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Separate opt-in profile; the original recorded-action diagnostic keeps its old defaults. */
internal object RecordedLeaderHoldMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Leader hold options require key/value pairs." }
        val pairs = arguments.chunked(2)
        val options = pairs.associate { it[0] to it[1] }
        require(
            options.size == pairs.size &&
                options.keys.all {
                    it in
                        setOf(
                            "--mode",
                            "--source",
                            "--source-sha256",
                            "--output",
                            "--prepared",
                            "--prepared-sha256",
                        )
                }
        ) {
            "Leader hold options must be unique and recognized."
        }
        fun option(key: String): String =
            requireNotNull(options[key]) { "Leader hold requires $key." }
        val mode = option("--mode")
        require(mode in setOf("recorded-leader-hold-prepare", "recorded-leader-hold-run")) {
            "Unknown leader hold mode."
        }
        val source = Path.of(option("--source")).toAbsolutePath().normalize()
        require(PurchaseJson.hash(source) == option("--source-sha256")) {
            "Leader hold source pin changed."
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3600)
        val diagnostic =
            RecordedLeaderHold(RecordedPrefixInput.read(source, proposalSampling = false))
        val (roots, receipt) = diagnostic.prepare()
        val output = Path.of(option("--output")).toAbsolutePath().normalize()
        if (mode == "recorded-leader-hold-prepare") {
            RecordedPrefixMain.createOutput(output)
            RecordedPrefixMain.write(output.resolve("prepared.json"), receipt)
            return
        }
        val prepared = Path.of(option("--prepared"))
        require(
            PurchaseJson.hash(prepared) == option("--prepared-sha256") &&
                PurchaseJson.read(prepared) == PurchaseJson.mapper.valueToTree<JsonNode>(receipt)
        ) {
            "Leader hold prefixes, streams, policies and first commands must match preparation exactly."
        }
        RecordedPrefixMain.createOutput(output)
        RecordedPrefixMain.write(output.resolve("prepared.json"), receipt)
        RecordedPrefixMain.write(
            output.resolve("run.json"),
            mapOf(
                "startedAt" to started.toString(),
                "deadline" to started.plusSeconds(3600).toString(),
                "workers" to 3,
                "preparedSha256" to PurchaseJson.hash(prepared),
                "sourceSha256" to PurchaseJson.hash(source),
                "attempt" to 1,
                "resumable" to false,
                "selectionPerformed" to false,
            ),
        )
        RecordedPrefixMain.execute(
            roots,
            output,
            deadline,
            workers = 3,
            totalTails = 448,
            collect = diagnostic::replicate,
        )
    }
}
