package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/** Fixed native slices and a separate pure compact report; no partial-bank effects. */
internal object TokenPairedScreenMain {
    val modes =
        setOf(
            "token-paired-screen-prepare",
            "token-paired-screen-chunk",
            "token-paired-screen-report",
            "token-paired-historical",
        )

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Token screen options require key/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        val mode = fields["--mode"]
        val keys =
            when (mode) {
                "token-paired-screen-prepare" -> setOf("--mode", "--source", "--output")
                "token-paired-screen-chunk" ->
                    setOf("--mode", "--source", "--output", "--seconds", "--workers")
                "token-paired-historical" -> setOf("--mode", "--source", "--output", "--seconds")
                "token-paired-screen-report" -> setOf("--mode", "--worlds", "--output")
                else -> error("Use one explicit finite token screen mode.")
            }
        require(fields.size * 2 == arguments.size && fields.keys == keys) {
            "Only this screen stage's declared arguments are permitted."
        }
        fun path(key: String) =
            Path.of(requireNotNull(fields[key]) { "$key is required." })
                .toAbsolutePath()
                .normalize()
        val output = path("--output")
        require(!Files.exists(output)) {
            "Use a new token screen output; no overwrite, retry or resume."
        }
        val seconds =
            fields["--seconds"]?.let {
                requireNotNull(it.toIntOrNull()) { "Remaining original seconds must be integral." }
            } ?: 0
        require("--seconds" !in fields || seconds in 1..21600) {
            "Every stage must remain within the original six-hour ceiling."
        }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        if (mode == "token-paired-screen-report") {
            compactReport(path("--worlds"), output)
            return
        }
        if (mode == "token-paired-historical") {
            TokenPairedHistorical.run(path("--source"), output, deadline)
            return
        }
        val input = TokenPairedScreenInput.read(path("--source"))
        val artifacts = TrainingArtifacts(output)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        if (mode == "token-paired-screen-prepare") {
            finish(input, output, stage = "prepare", worlds = emptyList())
            return
        }
        val workers =
            requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
        require(workers in 1..10) {
            "The unchanged native pool permits at most ten screen workers."
        }
        artifacts.write(
            "started.json",
            mapOf(
                "signature" to input.signature,
                "workers" to workers,
                "maximumSeconds" to seconds,
            ),
        )
        val indices =
            Tournaments(workers, deadline).use { pool ->
                pool.run(
                    input.indices.map { index ->
                        {
                            val world =
                                TokenPairedScreen.play(input, index, deadline) { role, canonical ->
                                    artifacts.write(canonicalName(index, role), canonical)
                                }
                            CardTrajectoryFiles.save(
                                output.resolve(worldName(index)),
                                ExpansionOnPolicyStore.wire(world),
                            )
                            index
                        }
                    }
                )
            }
        require(indices.sorted() == input.indices.toList()) {
            "Every screen index must complete exactly once in its owner shard."
        }
        for (index in input.ownedReplays) {
            val world = readWorld(output, index)
            for (role in TokenPairedScreenRole.entries) {
                val replay =
                    TokenPairedScreen.reproduce(
                        input,
                        index,
                        role,
                        world,
                        PurchaseJson.read(output.resolve(canonicalName(index, role))),
                        deadline,
                    )
                artifacts.write("replay-$index-${role.name}.json", replay)
            }
        }
        finish(
            input,
            output,
            "chunk",
            input.indices.map { index ->
                val world = readWorld(output, index)
                TokenPairedScreen.validateWorld(
                    world,
                    index,
                    input.scienceSha256,
                    StrategyArena.seed(input.raw.seed, index),
                )
                mapOf(
                    "index" to index,
                    "file" to worldName(index),
                    "sha256" to PurchaseJson.hash(output.resolve(worldName(index))),
                    "semanticSha256" to TokenPairedScreen.semanticHash(world),
                )
            },
        )
    }

    private fun finish(
        input: TokenPairedScreenInput,
        output: Path,
        stage: String,
        worlds: List<Map<String, Any>>,
    ) {
        input.verify()
        val reproductions = if (stage == "chunk") input.ownedReplays.size * 3 else 0
        TrainingArtifacts(output)
            .write(
                "finished.json",
                mapOf(
                    "kind" to TokenPairedScreenInput.VERSION,
                    "complete" to true,
                    "stage" to stage,
                    "signature" to input.signature,
                    "scienceSha256" to input.scienceSha256,
                    "scienceIdentity" to input.scienceIdentity,
                    "globalStart" to input.globalStart,
                    "count" to worlds.size,
                    "worlds" to worlds,
                    "reproductions" to reproductions,
                    "scientificTerminalExecutions" to worlds.size * 3 + reproductions,
                    "finalAdmissionSha256" to input.admission.source.sha256,
                    "historicalAdmissionSha256" to input.historical.sha256,
                    "files" to ExpansionFinalMain.inventory(output),
                ),
            )
    }

    /** Reads compact receipts/worlds only: no model, trajectory tree, inference or native game. */
    internal fun compactReport(source: Path, output: Path) {
        val sourceHash = PurchaseJson.hash(source)
        val compact = PurchaseJson.read(source)
        PurchaseJson.fields(compact, setOf("kind", "chunks", "worlds"))
        require(compact["kind"]?.asText() == "token-paired-screen-compact-1") {
            "Use the exact compact complete-screen contract."
        }
        val subjects =
            PurchaseJson.array(compact["chunks"], "chunks").map {
                TokenPairedSubject.read(it, source.parent)
            }
        require(subjects.size == 8 && subjects.map { it.sha256 }.distinct().size == 8) {
            "The complete screen requires eight distinct original chunk completions."
        }
        val chunks = subjects.map { PurchaseJson.read(it.path) }
        val first = chunks.first()
        val science = first["scienceSha256"].asText()
        val identity = first.required("scienceIdentity")
        require(
            science == trainingSignature(PurchaseJson.mapper.writeValueAsString(identity)) &&
                identity["seeds"]?.size() == 1024 &&
                identity["replayIndices"]?.map(JsonNode::intValue) ==
                    TokenPairedScreenInput.REPLAYS &&
                identity["roles"]?.map(JsonNode::asText) ==
                    TokenPairedScreenRole.entries.map { it.name }
        ) {
            "Compact completions must retain the same full bank, role identities and replay schedule."
        }
        chunks.forEachIndexed { ordinal, chunk ->
            val start = ordinal * 128
            val indices = start until start + 128
            val reproductions = TokenPairedScreenInput.REPLAYS.count { it in indices } * 3
            require(
                chunk["kind"]?.asText() == TokenPairedScreenInput.VERSION &&
                    chunk["complete"]?.booleanValue() == true &&
                    chunk["stage"]?.asText() == "chunk" &&
                    chunk["globalStart"]?.intValue() == start &&
                    chunk["count"]?.intValue() == 128 &&
                    chunk["scienceSha256"]?.asText() == science &&
                    chunk["scienceIdentity"] == identity &&
                    chunk["finalAdmissionSha256"] == first["finalAdmissionSha256"] &&
                    chunk["historicalAdmissionSha256"] == first["historicalAdmissionSha256"] &&
                    chunk["worlds"]?.map { it["index"].intValue() } == indices.toList() &&
                    chunk["reproductions"]?.intValue() == reproductions &&
                    chunk["scientificTerminalExecutions"]?.intValue() == 384 + reproductions
            ) {
                "Each of eight fixed chunks must complete its own 128 worlds and exact side replay count."
            }
        }
        val worlds = PurchaseJson.array(compact["worlds"], "worlds")
        require(worlds.size == 1024) { "No partial-bank report is permitted." }
        worlds.forEachIndexed { index, world ->
            val chunk = chunks[index / 128]
            val receipt = chunk["worlds"][index % 128]
            TokenPairedScreen.validateWorld(
                world,
                index,
                science,
                identity["seeds"][index].intValue(),
            )
            require(
                world["signature"] == chunk["signature"] &&
                    receipt["file"]?.asText() == worldName(index) &&
                    receipt["sha256"] == chunk["files"][worldName(index)] &&
                    receipt["semanticSha256"]?.asText() == TokenPairedScreen.semanticHash(world)
            ) {
                "Every compact world must equal its original native semantic receipt and raw archived member subject."
            }
        }
        subjects.forEach(TokenPairedSubject::verify)
        require(PurchaseJson.hash(source) == sourceHash) {
            "Compact screen inputs changed during pure reporting."
        }
        val artifacts = TrainingArtifacts(output)
        artifacts.write("report.json", TokenPairedScreen.report(worlds))
        artifacts.write(
            "inputs.json",
            mapOf(
                "sourceSha256" to sourceHash,
                "chunks" to compact["chunks"],
                "scienceSha256" to science,
                "finalAdmissionSha256" to first["finalAdmissionSha256"],
                "historicalAdmissionSha256" to first["historicalAdmissionSha256"],
            ),
        )
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "token-paired-screen-report-complete-1",
                "complete" to true,
                "worlds" to 1024,
                "newGames" to 0,
                "newFits" to 0,
                "files" to ExpansionFinalMain.inventory(output),
            ),
        )
    }

    internal fun worldName(index: Int) = "world-$index.json.gz"

    private fun canonicalName(index: Int, role: TokenPairedScreenRole) =
        "canonical-$index-${role.name}.json"

    private fun readWorld(directory: Path, index: Int) =
        requireNotNull(CardTrajectoryFiles.readIfPresent(directory.resolve(worldName(index)))) {
            "The original compact three-role world is required."
        }
}
