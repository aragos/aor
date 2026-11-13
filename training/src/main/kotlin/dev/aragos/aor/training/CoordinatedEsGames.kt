package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** A failed or over-budget game never receives an invented terminal value or replacement. */
internal class CoordinatedEsAttempts(private val deadline: Long) {
    private val attempted = AtomicInteger()
    private val completed = AtomicInteger()
    val attemptedGames: Int
        get() = attempted.get()

    val completedGames: Int
        get() = completed.get()

    fun <T> execute(deadlineLimit: Long = deadline, operation: (Long) -> T): T {
        val originalDeadline = minOf(deadline, deadlineLimit)
        check(!Thread.currentThread().isInterrupted && System.nanoTime() < originalDeadline) {
            "The original scientific deadline elapsed before the next game."
        }
        attempted.incrementAndGet()
        val gameDeadline = minOf(originalDeadline, System.nanoTime() + 300_000_000_000L)
        val result = operation(gameDeadline)
        check(!Thread.currentThread().isInterrupted && System.nanoTime() <= gameDeadline) {
            "The bounded game exceeded its original deadline; its result cannot complete the shard."
        }
        completed.incrementAndGet()
        return result
    }

    companion object {
        fun terminal(game: ArenaGame) {
            require(
                game.decisions in 1..12000 &&
                    game.wins.size == 4 &&
                    game.rewards.size == 4 &&
                    game.ranks.size == 4 &&
                    game.scores.size == 4 &&
                    (game.wins + game.rewards + game.ranks).all { it.isFinite() && it in 0.0..1.0 }
            ) {
                "A complete bounded four-seat game must contain authoritative finite normalized terminal arrays."
            }
        }
    }
}

/** Workers write one compact record immediately and retain only this small completion receipt. */
internal data class CoordinatedEsGameReceipt(
    val globalIndex: Int,
    val role: String,
    val files: Map<String, String>,
)

internal class CoordinatedEsGames(val input: CoordinatedEsInput, val output: Path) {
    private val artifacts = TrainingArtifacts(output)

    fun prepare() {
        artifacts.ensureFileSnapshot(input.source.path, input.source.sha256, "source.json")
        artifacts.ensureFileSnapshot(
            input.scope.arenaSource.path,
            input.scope.arenaSource.sha256,
            "arena.json",
        )
        artifacts.write("prepared.json", input.metadata())
    }

    fun play(
        index: Int,
        role: String,
        deadline: Long,
        reproduce: Boolean = false,
    ): CoordinatedEsGameReceipt {
        require(index in input.indices && role in input.roles) {
            "A game must belong to its exact global shard and population role."
        }
        require(!reproduce || input.canonical(role, index)) {
            "Only the two prescribed original role/world games may reproduce."
        }
        val specification =
            requireNotNull(input.specifications[role]) {
                "The complete role specification is required."
            }
        val lineup = StrategyArena.lineup(specification, index)
        val panel =
            if (input.stage == CoordinatedEsStage.CONTROLS && input.generation == 1) {
                CoordinatedEsPanel(index)
            } else {
                null
            }
        val canonical = input.canonical(role, index)
        val trace =
            if (panel != null || canonical) {
                CoordinatedEsTrace(lineup.seat, lineup.seed, panel, canonical)
            } else {
                null
            }
        val game =
            StrategyArena.tracedGame(specification, index, ArenaSide.CANDIDATE, deadline, trace)
        return save(index, role, game, trace?.canonical(game.decisions), panel)
    }

    /**
     * Storage is independent of execution, allowing constructed wire proofs without a simulator.
     */
    internal fun save(
        index: Int,
        role: String,
        game: ArenaGame,
        canonical: TokenPairedCanonical?,
        panel: CoordinatedEsPanel?,
    ): CoordinatedEsGameReceipt {
        require(
            index in input.indices &&
                role in input.roles &&
                (panel == null || panel.globalIndex == index)
        ) {
            "The recorded game and public panel must belong to their exact original shard slot."
        }
        require((canonical != null) == input.canonical(role, index)) {
            "Only prescribed original population games retain full canonical histories."
        }
        val lineup = StrategyArena.lineup(input.arena, index)
        CoordinatedEsAttempts.terminal(game)
        val identity = identity(index, role)
        val name = gameName(role, index)
        artifacts.write(
            name,
            identity +
                mapOf(
                    "kind" to "coordinated-es-game-1",
                    "configuration" to lineup.configuration.id,
                    "opponentIndices" to lineup.opponentIndices,
                    "bundleSha256" to input.population?.members?.let { it[role] }?.source?.sha256,
                    "terminal" to game,
                    "canonical" to canonical,
                ),
        )
        val files = linkedMapOf(name to PurchaseJson.hash(output.resolve(name)))
        if (input.stage == CoordinatedEsStage.CONTROLS) {
            val public = panel ?: CoordinatedEsPanel(index)
            val panelName = "panel-$index.json"
            artifacts.write(
                panelName,
                identity.filterKeys { it != "role" } +
                    mapOf(
                        "kind" to "coordinated-es-panel-1",
                        "assignedEpoch" to public.assignedEpoch,
                        "heads" to public.heads(),
                        "windows" to public.windows(),
                        "game" to mapOf("file" to name, "sha256" to files[name]),
                    ),
            )
            files[panelName] = PurchaseJson.hash(output.resolve(panelName))
        }
        return CoordinatedEsGameReceipt(index, role, files)
    }

    private fun identity(index: Int, role: String): Map<String, Any> =
        mapOf(
            "signature" to input.signature,
            "scopeSha256" to input.scope.sha256,
            "scienceSha256" to input.scienceSha256,
            "sourceSha256" to input.source.sha256,
            "originalBundleSha256" to input.scope.original.source.sha256,
            "generation" to input.generation,
            "globalIndex" to index,
            "seed" to input.bank.seeds[index],
            "seat" to index % 4,
            "role" to role,
        )

    fun finish(receipts: List<CoordinatedEsGameReceipt>, attempts: CoordinatedEsAttempts) {
        require(
            PurchaseJson.read(output.resolve("prepared.json"))["inputPins"] ==
                PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    input.inputPins()
                )
        ) {
            "The complete shard must retain every original prepared input subject."
        }
        val expected = input.indices.flatMap { index -> input.roles.map { index to it } }.toSet()
        require(
            receipts.size == expected.size &&
                receipts.map { it.globalIndex to it.role }.toSet() == expected &&
                attempts.attemptedGames == expected.size &&
                attempts.completedGames == expected.size
        ) {
            "A completed shard requires every original role/world exactly once with no failed attempt."
        }
        val files =
            receipts.flatMap { it.files.entries }.associate { it.key to it.value } +
                listOf("source.json", "arena.json", "prepared.json", "started.json").associateWith {
                    PurchaseJson.hash(output.resolve(it))
                }
        val declared = expectedFileNames()
        require(files.keys == declared) {
            "The shard inventory must contain exactly its original compact games and public sidecars."
        }
        files.forEach { (name, hash) ->
            require(PurchaseJson.hash(output.resolve(name)) == hash) {
                "A completed shard member changed: $name."
            }
        }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "coordinated-es-complete-1",
                "signature" to input.signature,
                "scienceSha256" to input.scienceSha256,
                "scopeSha256" to input.scope.sha256,
                "stage" to input.stage,
                "generation" to input.generation,
                "globalStart" to input.globalStart,
                "count" to input.count,
                "attemptedGames" to attempts.attemptedGames,
                "completedGames" to attempts.completedGames,
                "expectedGames" to expected.size,
                "canonicalGames" to expected.count { input.canonical(it.second, it.first) },
                "files" to files.toSortedMap(),
            ),
        )
    }

    private fun expectedFileNames(): Set<String> =
        setOf("source.json", "arena.json", "prepared.json", "started.json") +
            input.indices.flatMap { index ->
                input.roles.map { gameName(it, index) } +
                    if (input.stage == CoordinatedEsStage.CONTROLS) {
                        listOf("panel-$index.json")
                    } else {
                        emptyList()
                    }
            }

    fun readCompleted(index: Int, role: String): Pair<JsonNode, TokenPairedSubject> {
        require(index in input.indices && input.canonical(role, index)) {
            "The original must be a prescribed complete shard replay."
        }
        val finished = PurchaseJson.read(output.resolve("finished.json"))
        require(
            finished["kind"]?.asText() == "coordinated-es-complete-1" &&
                finished["signature"]?.asText() == input.signature &&
                finished["scienceSha256"]?.asText() == input.scienceSha256 &&
                finished["completedGames"]?.asInt() == input.count * input.roles.size &&
                finished["attemptedGames"] == finished["completedGames"] &&
                finished["files"]?.fieldNames()?.asSequence()?.toSet() == expectedFileNames()
        ) {
            "Reproduction requires the complete unchanged original shard receipt."
        }
        finished["files"].properties().forEach { (name, hash) ->
            require(PurchaseJson.hash(output.resolve(name)) == hash.asText()) {
                "Original shard member changed: $name."
            }
        }
        val subject = CoordinatedEsWire.source(output.resolve(gameName(role, index)))
        return PurchaseJson.read(subject.path) to subject
    }

    companion object {
        fun gameName(role: String, index: Int): String = "game-$role-$index.json"

        fun verifyReproduction(expected: JsonNode, actual: JsonNode) {
            fun semantic(node: JsonNode): JsonNode =
                node.deepCopy<ObjectNode>().also {
                    (it.required("terminal") as ObjectNode).remove("elapsedMillis")
                }
            require(
                expected["canonical"]?.get("replayVerified")?.booleanValue() == true &&
                    actual["canonical"]?.get("replayVerified")?.booleanValue() == true &&
                    semantic(expected) == semantic(actual)
            ) {
                "The complete original canonical history, role and authoritative terminal must reproduce exactly."
            }
        }
    }
}
