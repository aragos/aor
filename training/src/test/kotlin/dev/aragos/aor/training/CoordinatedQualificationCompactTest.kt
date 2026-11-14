package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test

/** Full4096 invented compact worlds, real report owner; no native game is run. */
class CoordinatedQualificationCompactTest {
    @Test
    fun completeMixedReportBindsEveryGlobalLineupAndTwoRoleCount() {
        val root = Files.createTempDirectory("coordinated-qualification-report-unit")
        try {
            val values = worlds()
            val identity =
                mapOf(
                    "bankName" to "mixed",
                    "seeds" to List(4096) { StrategyArena.seed(81, it) },
                    "roles" to listOf("CANDIDATE", "CONTROL"),
                    "replayIndices" to listOf(0, 1365, 2730, 4095),
                )
            val science = CpePairedDigest.json(identity)
            val normalized = values.mapIndexed { index, world ->
                (world as com.fasterxml.jackson.databind.node.ObjectNode).apply {
                    put("kind", "coordinated-qualification-screen-1")
                    put("scienceSha256", science)
                    put("signature", (index / 128).toString())
                    put("seed", StrategyArena.seed(81, index))
                    put("configuration", "players-4/standard/no-crusades/deck-true/art-true")
                    set<JsonNode>(
                        "opponentIndices",
                        PurchaseJson.mapper.valueToTree(
                            CoordinatedQualificationInput.opponents("mixed", index)
                        ),
                    )
                }
            }
            val chunks =
                (0..31).map { ordinal ->
                    val indices = ordinal * 128 until (ordinal + 1) * 128
                    val repeats = listOf(0, 1365, 2730, 4095).count { it in indices } * 2
                    val members = indices.associate { "world-$it.json.gz" to "a".repeat(64) }
                    TrainingArtifacts(root)
                        .write(
                            "chunk-$ordinal.json",
                            mapOf(
                                "kind" to "coordinated-qualification-screen-1",
                                "complete" to true,
                                "stage" to "chunk",
                                "signature" to ordinal.toString(),
                                "scienceSha256" to science,
                                "scienceIdentity" to identity,
                                "globalStart" to ordinal * 128,
                                "count" to 128,
                                "reproductions" to repeats,
                                "scientificTerminalExecutions" to 256 + repeats,
                                "attemptedGames" to 256 + repeats,
                                "completedGames" to 256 + repeats,
                                "finalAdmissionSha256" to "b".repeat(64),
                                "historicalAdmissionSha256" to "c".repeat(64),
                                "files" to members,
                                "worlds" to
                                    indices.map { index ->
                                        mapOf(
                                            "index" to index,
                                            "file" to "world-$index.json.gz",
                                            "sha256" to "a".repeat(64),
                                            "semanticSha256" to
                                                ThreeRoleScreen.semanticHash(normalized[index]),
                                        )
                                    },
                            ),
                        )
                    CoordinatedEsWire.subject(
                        CoordinatedEsWire.source(root.resolve("chunk-$ordinal.json"))
                    )
                }
            TrainingArtifacts(root)
                .write(
                    "compact.json",
                    mapOf(
                        "kind" to "coordinated-qualification-screen-compact-1",
                        "chunks" to chunks,
                        "worlds" to normalized,
                    ),
                )
            CoordinatedQualificationMain.compactReport(
                root.resolve("compact.json"),
                root.resolve("report"),
            )
            assertThat(PurchaseJson.read(root.resolve("report/finished.json"))["worlds"].asInt())
                .isEqualTo(4096)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun worlds(): List<JsonNode> =
        (0..4095).map { index ->
            fun game(value: Double) =
                mapOf(
                    "wins" to List(4) { value },
                    "rewards" to List(4) { value },
                    "ranks" to List(4) { value },
                    "scores" to List(4) { mapOf("total" to 1) },
                    "decisions" to 1,
                )
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "index" to index,
                    "seat" to index % 4,
                    "roles" to mapOf("CANDIDATE" to game(.6), "CONTROL" to game(.4)),
                )
            )
        }
}
