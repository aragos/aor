package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.replay
import java.nio.file.Files
import java.nio.file.Path

/** Explicit independent streams; each replicate reuses these two seeds across its root's arms. */
internal data class RecordedPrefixRequest(
    val entry: Int,
    val proposalSeed: Int?,
    val chanceSeeds: List<Int>,
    val policySeeds: List<Int>,
) {
    init {
        require(entry >= 0 && chanceSeeds.size == 32 && policySeeds.size == 32) {
            "Each recorded root requires a nonnegative entry and exactly 32 paired streams."
        }
    }
}

/**
 * Executor-only history. This private realized world is never a policy observation or oracle label.
 */
internal data class RecordedPrefixInput(
    val sourceSha256: String,
    val originalSha256: String,
    val historySha256: String,
    val observationsSha256: String,
    val header: GameHeader,
    val entries: List<String>,
    val observations: Map<Int, JsonNode>,
    val arena: ArenaSpecification,
    val requests: List<RecordedPrefixRequest>,
    val expectedTerminal: JsonNode,
) {
    init {
        require(
            requests.size in 1..19 && requests.map { it.entry }.distinct().size == requests.size
        ) {
            "A recorded diagnostic requires one through nineteen distinct predeclared roots."
        }
        val streams = requests.flatMap {
            it.chanceSeeds + it.policySeeds + listOfNotNull(it.proposalSeed)
        }
        require(streams.distinct().size == streams.size) {
            "Recorded roots require distinct proposal, chance and continuation policy streams."
        }
        FrozenDiagnosticArena.validate(arena, header.players.size)
    }

    /**
     * Incremental prefixes and a separate complete replay must reach the recorded terminal state.
     */
    fun states(): Pair<Map<Int, GameState>, GameState> {
        val wanted = requests.map { it.entry }.toSet()
        val roots = mutableMapOf<Int, GameState>()
        var state = GameState(header)
        entries.forEachIndexed { index, text ->
            if (index in wanted) roots[index] = state
            val parsed = TextCodec.parse(text)
            require(TextCodec.print(parsed) == text) {
                "Recorded engine history must use canonical entry text."
            }
            state = state.step(parsed)
        }
        require(roots.keys == wanted) {
            "Every requested root must belong to the effective history."
        }
        require(state.stage == Stage.FINISHED && replay(header, entries) == state) {
            "The complete canonical history must replay exactly to FINISHED."
        }
        val terminal =
            PurchaseJson.mapper.valueToTree<JsonNode>(
                mapOf(
                    "stage" to state.stage.name,
                    "turn" to state.turnNumber,
                    "scores" to state.publicView().scores,
                )
            )
        require(terminal == expectedTerminal) {
            "Recorded terminal scores differ from their audit."
        }
        return roots.toMap() to state
    }

    companion object {
        fun read(source: Path, proposalSampling: Boolean = true): RecordedPrefixInput {
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "history",
                    "historySha256",
                    "observations",
                    "observationsSha256",
                    "arena",
                    "arenaSha256",
                    "roots",
                    "expectedTerminal",
                ),
            )
            require(node["schemaVersion"]?.asInt() == 1) { "Recorded input requires schema 1." }
            fun pinned(name: String): Path {
                val path =
                    source.parent.resolve(PurchaseJson.text(node.required(name), name)).normalize()
                require(PurchaseJson.hash(path) == node.required("${name}Sha256").asText()) {
                    "Recorded diagnostic input $name differs from its pinned bytes."
                }
                return path
            }
            val historyPath = pinned("history")
            val history = PurchaseJson.read(historyPath)
            PurchaseJson.fields(
                history,
                setOf("schemaVersion", "kind", "sourceSha256", "header", "entries"),
            )
            require(
                history["schemaVersion"]?.asInt() == 1 &&
                    history["kind"]?.asText() == "private-engine-history"
            ) {
                "Only a credential-free canonical engine-history export is accepted."
            }
            val header = header(history.required("header"))
            val observationPath = pinned("observations")
            val rows =
                Files.readAllLines(observationPath).filter(String::isNotBlank).map {
                    PurchaseJson.mapper.readTree(it)
                }
            val observations = rows.associateBy {
                PurchaseJson.integer(it.required("entry"), "entry")
            }
            require(
                observations.size == rows.size &&
                    rows.all {
                        it["sourceSha256"]?.asText() == history["sourceSha256"]?.asText()
                    }
            ) {
                "Sanitized observations must be unique and bind the same original source."
            }
            val requests =
                PurchaseJson.array(node.required("roots"), "roots").map {
                    PurchaseJson.fields(
                        it,
                        setOf("entry", "chanceSeeds", "policySeeds") +
                            if (proposalSampling) setOf("proposalSeed") else emptySet(),
                    )
                    RecordedPrefixRequest(
                        PurchaseJson.integer(it.required("entry"), "entry"),
                        if (proposalSampling) {
                            PurchaseJson.integer(it.required("proposalSeed"), "proposalSeed")
                        } else {
                            null
                        },
                        PurchaseJson.array(it.required("chanceSeeds"), "chanceSeeds").map { seed ->
                            PurchaseJson.integer(seed, "chanceSeed")
                        },
                        PurchaseJson.array(it.required("policySeeds"), "policySeeds").map { seed ->
                            PurchaseJson.integer(seed, "policySeed")
                        },
                    )
                }
            return RecordedPrefixInput(
                PurchaseJson.hash(source),
                history.required("sourceSha256").asText(),
                PurchaseJson.hash(historyPath),
                PurchaseJson.hash(observationPath),
                header,
                PurchaseJson.strings(history.required("entries"), "entries"),
                observations,
                ArenaSpecification.read(pinned("arena")),
                requests,
                node.required("expectedTerminal"),
            )
        }

        private fun header(node: JsonNode): GameHeader {
            PurchaseJson.fields(
                node,
                setOf(
                    "players",
                    "creatorId",
                    "rulesVersion",
                    "dataVersion",
                    "drawRenaissanceFromTurn",
                ),
            )
            val players =
                PurchaseJson.array(node.required("players"), "players").map {
                    PurchaseJson.fields(it, setOf("id", "name"))
                    Player(
                        PlayerId(PurchaseJson.text(it.required("id"), "player id")),
                        PurchaseJson.text(it.required("name"), "player name"),
                    )
                }
            return GameHeader(
                players,
                PlayerId(PurchaseJson.text(node.required("creatorId"), "creatorId")),
                PurchaseJson.text(node.required("rulesVersion"), "rulesVersion"),
                PurchaseJson.text(node.required("dataVersion"), "dataVersion"),
                PurchaseJson.integer(
                    node.required("drawRenaissanceFromTurn"),
                    "drawRenaissanceFromTurn",
                ),
            )
        }
    }
}
