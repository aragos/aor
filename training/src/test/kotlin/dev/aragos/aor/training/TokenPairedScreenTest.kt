package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenPairedScreenTest {
    @Test
    fun pureCompactReportUsesAllWorldsAndSharesFinalAcrossContrasts() {
        temporary { root ->
            val source = compact(root)
            TokenPairedScreenMain.compactReport(source, root.resolve("report"))
            val report = PurchaseJson.read(root.resolve("report/report.json"))
            assertThat(report["worlds"].intValue()).isEqualTo(1024)
            assertThat(report["finalMinusRawT"]["rewards"]["mean"].doubleValue())
                .isWithin(1e-12)
                .of(.2)
            assertThat(report["finalMinusInitializer"]["rewards"]["mean"].doubleValue())
                .isWithin(1e-12)
                .of(.1)
            assertThat(report["developmentGatePassed"].booleanValue()).isTrue()
            assertThat(report["qualification"].booleanValue()).isFalse()
            assertThat(report["intervalMultiplier"].doubleValue()).isEqualTo(1.96)
        }
    }

    @Test
    fun compactWorldMutationFailsItsOriginalSemanticReceipt() {
        temporary { root ->
            val source = compact(root)
            val node = PurchaseJson.read(source) as ObjectNode
            (node["worlds"][100]["roles"]["FINAL"] as ObjectNode).set<JsonNode>(
                "rewards",
                PurchaseJson.mapper.valueToTree(List(4) { .9 }),
            )
            TrainingArtifacts(root).write("compact.json", node)
            assertFailsWith<IllegalArgumentException> {
                TokenPairedScreenMain.compactReport(source, root.resolve("bad"))
            }
        }
    }

    @Test
    fun duplicateOrPartialWorldsNeverProduceEffects() {
        assertFailsWith<IllegalArgumentException> { TokenPairedScreen.report(emptyList()) }
    }

    @Test
    fun compactWorldHashIgnoresOnlyJsonObjectKeyOrder() {
        val first = PurchaseJson.mapper.readTree("{\"a\":1,\"b\":[2,3]}")
        val second = PurchaseJson.mapper.readTree("{\"b\":[2,3],\"a\":1}")
        assertThat(TokenPairedScreen.semanticHash(first))
            .isEqualTo(TokenPairedScreen.semanticHash(second))
        assertThat(TokenPairedScreen.semanticHash(first))
            .isNotEqualTo(
                TokenPairedScreen.semanticHash(
                    PurchaseJson.mapper.readTree("{\"a\":1,\"b\":[3,2]}")
                )
            )
    }

    private fun compact(root: Path): Path {
        val identity =
            mapOf(
                "kind" to TokenPairedScreenInput.VERSION,
                "scopeSha256" to "a".repeat(64),
                "ecologySha256" to "b".repeat(64),
                "finalActorSha256" to "c".repeat(64),
                "originalActorSha256" to "d".repeat(64),
                "seeds" to List(1024) { StrategyArena.seed(719, it) },
                "replayIndices" to TokenPairedScreenInput.REPLAYS,
                "roles" to TokenPairedScreenRole.entries.map { it.name },
            )
        val science = trainingSignature(PurchaseJson.mapper.writeValueAsString(identity))
        val worlds =
            List(1024) { index ->
                fun game(value: Double) =
                    mapOf(
                        "wins" to List(4) { value },
                        "rewards" to List(4) { value },
                        "ranks" to List(4) { value },
                        "scores" to List(4) { mapOf("total" to 1) },
                        "decisions" to 1,
                        "elapsedMillis" to 0,
                    )
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "kind" to TokenPairedScreenInput.VERSION,
                        "signature" to (index / 128).toString().repeat(64),
                        "scienceSha256" to science,
                        "index" to index,
                        "seed" to StrategyArena.seed(719, index),
                        "seat" to index % 4,
                        "configuration" to "players-4/standard/no-crusades/deck-true/art-true",
                        "opponentIndices" to List(4) { 0 },
                        "roles" to
                            mapOf("FINAL" to game(.6), "RAW_T" to game(.4), "INITIAL" to game(.5)),
                    )
                )
            }
        val chunks =
            List(8) { chunk ->
                val indices = chunk * 128 until (chunk + 1) * 128
                val replays = TokenPairedScreenInput.REPLAYS.count { it in indices } * 3
                val name = "finished-$chunk.json"
                TrainingArtifacts(root)
                    .write(
                        name,
                        mapOf(
                            "kind" to TokenPairedScreenInput.VERSION,
                            "complete" to true,
                            "stage" to "chunk",
                            "globalStart" to chunk * 128,
                            "count" to 128,
                            "signature" to chunk.toString().repeat(64),
                            "scienceSha256" to science,
                            "scienceIdentity" to identity,
                            "finalAdmissionSha256" to "e".repeat(64),
                            "historicalAdmissionSha256" to "f".repeat(64),
                            "reproductions" to replays,
                            "scientificTerminalExecutions" to 384 + replays,
                            "files" to indices.associate { "world-$it.json.gz" to "0".repeat(64) },
                            "worlds" to
                                indices.map { index ->
                                    mapOf(
                                        "index" to index,
                                        "file" to "world-$index.json.gz",
                                        "sha256" to "0".repeat(64),
                                        "semanticSha256" to
                                            TokenPairedScreen.semanticHash(worlds[index]),
                                    )
                                },
                        ),
                    )
                mapOf("path" to name, "sha256" to PurchaseJson.hash(root.resolve(name)))
            }
        TrainingArtifacts(root)
            .write(
                "compact.json",
                mapOf(
                    "kind" to "token-paired-screen-compact-1",
                    "chunks" to chunks,
                    "worlds" to worlds,
                ),
            )
        return root.resolve("compact.json")
    }

    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("token-screen-unit-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
