package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatedEsScreenTest {
    @Test
    fun finalTwoRoleReportUsesCompleteIndependentWorlds() {
        val result = ExpansionOnPolicyStore.wire(CoordinatedEsScreenMain.report(worlds()))
        assertThat(result["worlds"].asInt()).isEqualTo(2048)
        assertThat(result["finalMinusRawG3"]["rewards"]["mean"].doubleValue())
            .isWithin(1e-12)
            .of(.2)
        assertThat(result["developmentGatePassed"].booleanValue()).isTrue()
        assertThat(result["qualification"].booleanValue()).isFalse()
        assertThat(result["intervalMultiplier"].doubleValue()).isEqualTo(1.96)
    }

    @Test
    fun missingFinalWorldNeverProducesPartialEffects() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedEsScreenMain.report(worlds().dropLast(1))
        }
    }

    @Test
    fun genericCompactOwnerRetainsSixteenSlicesAndTwoRoleCounts() {
        val root = Files.createTempDirectory("coordinated-es-report-unit")
        try {
            val values = worlds()
            val identity =
                mapOf(
                    "seeds" to List(2048) { StrategyArena.seed(81, it) },
                    "roles" to listOf("FINAL", "RAW_G3"),
                    "replayIndices" to listOf(0, 682, 1365, 2047),
                )
            val science = CpePairedDigest.json(identity)
            val normalized = values.mapIndexed { index, world ->
                (world as com.fasterxml.jackson.databind.node.ObjectNode).apply {
                    put("kind", "coordinated-es-screen-1")
                    put("scienceSha256", science)
                    put("signature", (index / 128).toString())
                    put("seed", StrategyArena.seed(81, index))
                    put("configuration", "players-4/standard/no-crusades/deck-true/art-true")
                    set<JsonNode>("opponentIndices", PurchaseJson.mapper.valueToTree(List(4) { 0 }))
                }
            }
            val chunks =
                (0..15).map { ordinal ->
                    val indices = ordinal * 128 until (ordinal + 1) * 128
                    val repeats = listOf(0, 682, 1365, 2047).count { it in indices } * 2
                    val members = indices.associate { "world-$it.json.gz" to "a".repeat(64) }
                    TrainingArtifacts(root)
                        .write(
                            "chunk-$ordinal.json",
                            mapOf(
                                "kind" to "coordinated-es-screen-1",
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
                        "kind" to "coordinated-es-screen-compact-1",
                        "chunks" to chunks,
                        "worlds" to normalized,
                    ),
                )
            CoordinatedEsScreenMain.compactReport(
                root.resolve("compact.json"),
                root.resolve("report"),
            )
            assertThat(PurchaseJson.read(root.resolve("report/finished.json"))["worlds"].asInt())
                .isEqualTo(2048)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun worlds(): List<JsonNode> =
        (0..2047).map { index ->
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
                    "roles" to mapOf("FINAL" to game(.6), "RAW_G3" to game(.4)),
                )
            )
        }
}
