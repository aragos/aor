package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/** Fixed native slices and a separate pure compact report; no partial-bank effects. */
internal class ThreeRoleScreenMain(
    private val prefix: String,
    private val roles: List<String>,
    private val rawRole: String,
    private val rawName: String,
    private val maximumSeconds: Int,
    private val readInput: (Path) -> ThreeRoleScreenInput,
    private val historical: (Path, Path, Long) -> Unit,
    private val worldCount: Int = 1024,
    private val replayIndices: List<Int> = listOf(0, 341, 682, 1023),
    private val scienceDigest: (JsonNode) -> String = {
        trainingSignature(PurchaseJson.mapper.writeValueAsString(it))
    },
    private val report: ((List<JsonNode>) -> Map<String, Any>)? = null,
    private val requireExecutionCounts: Boolean = false,
    private val expectedOpponents: (JsonNode, Int) -> List<Int> = { _, _ -> List(4) { 0 } },
) {
    val modes =
        setOf(
            "$prefix-screen-prepare",
            "$prefix-screen-chunk",
            "$prefix-screen-report",
            "$prefix-historical",
        )

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Paired screen options require key/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        val mode = fields["--mode"]
        val keys =
            when (mode) {
                "$prefix-screen-prepare" -> setOf("--mode", "--source", "--output")
                "$prefix-screen-chunk" ->
                    setOf("--mode", "--source", "--output", "--seconds", "--workers")
                "$prefix-historical" -> setOf("--mode", "--source", "--output", "--seconds")
                "$prefix-screen-report" -> setOf("--mode", "--worlds", "--output")
                else -> error("Use one explicit finite paired screen mode.")
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
            "Use a new paired screen output; no overwrite, retry or resume."
        }
        val seconds =
            fields["--seconds"]?.let {
                requireNotNull(it.toIntOrNull()) { "Remaining original seconds must be integral." }
            } ?: 0
        require("--seconds" !in fields || seconds in 1..maximumSeconds) {
            "Every stage must remain within the original declared ceiling."
        }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        if (mode == "$prefix-screen-report") {
            compactReport(path("--worlds"), output)
            return
        }
        if (mode == "$prefix-historical") {
            historical(path("--source"), output, deadline)
            return
        }
        val input = readInput(path("--source"))
        val artifacts = TrainingArtifacts(output)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        if (mode == "$prefix-screen-prepare") {
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
        try {
            val indices =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        input.indices.map { index ->
                            {
                                val world =
                                    ThreeRoleScreen.play(input, index, deadline) { role, canonical
                                        ->
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
                for (role in input.replayRoles(index)) {
                    val replay =
                        ThreeRoleScreen.reproduce(
                            input,
                            index,
                            role,
                            world,
                            PurchaseJson.read(output.resolve(canonicalName(index, role))),
                            deadline,
                        )
                    artifacts.write("replay-$index-${role}.json", replay)
                }
            }
            finish(
                input,
                output,
                "chunk",
                input.indices.map { index ->
                    val world = readWorld(output, index)
                    ThreeRoleScreen.validateWorld(
                        world,
                        index,
                        input.scienceSha256,
                        StrategyArena.seed(input.raw.seed, index),
                        input.kind,
                        input.roles,
                        input.expectedOpponents(index),
                    )
                    mapOf(
                        "index" to index,
                        "file" to worldName(index),
                        "sha256" to PurchaseJson.hash(output.resolve(worldName(index))),
                        "semanticSha256" to ThreeRoleScreen.semanticHash(world),
                    )
                },
            )
        } catch (failure: Exception) {
            val counts = input.executionReceipt(null)
            if (counts.isNotEmpty()) {
                artifacts.write(
                    "failed.json",
                    counts +
                        mapOf(
                            "signature" to input.signature,
                            "failure" to failure.toString(),
                        ),
                )
            }
            throw failure
        }
    }

    private fun finish(
        input: ThreeRoleScreenInput,
        output: Path,
        stage: String,
        worlds: List<Map<String, Any>>,
    ) {
        input.verify()
        val reproductions =
            if (stage == "chunk") input.ownedReplays.sumOf { input.replayRoles(it).size } else 0
        TrainingArtifacts(output)
            .write(
                "finished.json",
                mapOf(
                    "kind" to "$prefix-screen-1",
                    "complete" to true,
                    "stage" to stage,
                    "signature" to input.signature,
                    "scienceSha256" to input.scienceSha256,
                    "scienceIdentity" to input.scienceIdentity,
                    "globalStart" to input.globalStart,
                    "count" to worlds.size,
                    "worlds" to worlds,
                    "reproductions" to reproductions,
                    "scientificTerminalExecutions" to worlds.size * roles.size + reproductions,
                    "finalAdmissionSha256" to input.finalAdmissionSha256,
                    "historicalAdmissionSha256" to input.historicalAdmissionSha256,
                    "files" to ExpansionFinalMain.inventory(output),
                ) + input.executionReceipt(worlds.size * roles.size + reproductions),
            )
    }

    /** Reads compact receipts/worlds only: no model, trajectory tree, inference or native game. */
    internal fun compactReport(source: Path, output: Path) {
        val sourceHash = PurchaseJson.hash(source)
        val compact = PurchaseJson.read(source)
        PurchaseJson.fields(compact, setOf("kind", "chunks", "worlds"))
        require(compact["kind"]?.asText() == "$prefix-screen-compact-1") {
            "Use the exact compact complete-screen contract."
        }
        val subjects =
            PurchaseJson.array(compact["chunks"], "chunks").map {
                TokenPairedSubject.read(it, source.parent)
            }
        require(
            subjects.size == worldCount / 128 &&
                subjects.map { it.sha256 }.distinct().size == worldCount / 128
        ) {
            "The complete screen requires ${worldCount / 128} distinct original chunk completions."
        }
        val chunks = subjects.map { PurchaseJson.read(it.path) }
        val first = chunks.first()
        val science = first["scienceSha256"].asText()
        val identity = first.required("scienceIdentity")
        require(
            science == scienceDigest(identity) &&
                identity["seeds"]?.size() == worldCount &&
                identity["replayIndices"]?.map(JsonNode::intValue) == replayIndices &&
                identity["roles"]?.map(JsonNode::asText) == roles
        ) {
            "Compact completions must retain the same full bank, role identities and replay schedule."
        }
        chunks.forEachIndexed { ordinal, chunk ->
            val start = ordinal * 128
            val indices = start until start + 128
            val reproductions = replayIndices.count { it in indices } * roles.size
            if (requireExecutionCounts) {
                val expected = 128 * roles.size + reproductions
                require(
                    chunk["attemptedGames"]?.asInt() == expected &&
                        chunk["completedGames"]?.asInt() == expected
                ) {
                    "A bounded complete screen cannot contain failed or missing role executions."
                }
            }
            require(
                chunk["kind"]?.asText() == "$prefix-screen-1" &&
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
                    chunk["scientificTerminalExecutions"]?.intValue() ==
                        128 * roles.size + reproductions
            ) {
                "Each fixed chunk must complete its own128 worlds and exact side replay count."
            }
        }
        val worlds = PurchaseJson.array(compact["worlds"], "worlds")
        require(worlds.size == worldCount) { "No partial-bank report is permitted." }
        worlds.forEachIndexed { index, world ->
            val chunk = chunks[index / 128]
            val receipt = chunk["worlds"][index % 128]
            ThreeRoleScreen.validateWorld(
                world,
                index,
                science,
                identity["seeds"][index].intValue(),
                "$prefix-screen-1",
                roles,
                expectedOpponents(identity, index),
            )
            require(
                world["signature"] == chunk["signature"] &&
                    receipt["file"]?.asText() == worldName(index) &&
                    receipt["sha256"] == chunk["files"][worldName(index)] &&
                    receipt["semanticSha256"]?.asText() == ThreeRoleScreen.semanticHash(world)
            ) {
                "Every compact world must equal its original native semantic receipt and raw archived member subject."
            }
        }
        subjects.forEach(TokenPairedSubject::verify)
        require(PurchaseJson.hash(source) == sourceHash) {
            "Compact screen inputs changed during pure reporting."
        }
        val artifacts = TrainingArtifacts(output)
        artifacts.write(
            "report.json",
            report?.invoke(worlds) ?: ThreeRoleScreen.report(worlds, prefix, rawRole, rawName),
        )
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
                "kind" to "$prefix-screen-report-complete-1",
                "complete" to true,
                "worlds" to worldCount,
                "newGames" to 0,
                "newFits" to 0,
                "files" to ExpansionFinalMain.inventory(output),
            ),
        )
    }

    internal fun worldName(index: Int) = "world-$index.json.gz"

    private fun canonicalName(index: Int, role: String) = "canonical-$index-${role}.json"

    private fun readWorld(directory: Path, index: Int) =
        requireNotNull(CardTrajectoryFiles.readIfPresent(directory.resolve(worldName(index)))) {
            "The original compact three-role world is required."
        }
}
