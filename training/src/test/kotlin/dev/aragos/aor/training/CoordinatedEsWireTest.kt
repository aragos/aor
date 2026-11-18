package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test

/** Real native storage, invented positions/results; never invokes Simulation.play. */
class CoordinatedEsWireTest {
    @Test
    fun exportConstructedCompleteControlShardForStrictPublicWireProof() {
        val root =
            Path.of("build", "test-fixtures", "coordinated-es-wire").toAbsolutePath().normalize()
        root.toFile().deleteRecursively()
        Files.createDirectories(root)
        CoordinatedEsFixture().use { fixture ->
            val arenaSubject = CoordinatedEsWire.source(fixture.prior.prior.prior.arenaSource)
            val scope =
                CoordinatedEsScope(
                    arenaSubject,
                    fixture.input.arena,
                    fixture.original,
                    fixture.input.purchaseBaseline,
                )
            fixture.files.write("unit-subject.json", mapOf("kind" to "CONSTRUCTED-NOT-SCIENTIFIC"))
            val unit =
                CoordinatedEsWire.source(fixture.files.directory.resolve("unit-subject.json"))
            val subjects =
                CoordinatedEsInput.SUBJECTS.associateWith { name ->
                    when (name) {
                        "population",
                        "calibration",
                        "previousUpdate" -> null
                        "arena" -> arenaSubject
                        "originalBundle" -> fixture.original.source
                        else -> unit
                    }
                }
            val bank = CoordinatedEsBank(81, 256, List(256) { StrategyArena.seed(81, it) })
            fixture.files.write(
                "unit-generation.json",
                mapOf(
                    "kind" to CoordinatedEsInput.VERSION,
                    "stage" to "CONTROLS",
                    "generation" to 1,
                    "globalStart" to 0,
                    "count" to 32,
                    "bank" to bank,
                ) + subjects.mapValues { it.value?.let(CoordinatedEsWire::subject) },
            )
            val input =
                CoordinatedEsInput(
                    CoordinatedEsWire.source(
                        fixture.files.directory.resolve("unit-generation.json")
                    ),
                    subjects,
                    scope,
                    CoordinatedEsStage.CONTROLS,
                    1,
                    0,
                    32,
                    bank,
                    null,
                    null,
                )
            val output = root.resolve("collection")
            val store = CoordinatedEsGames(input, output)
            store.prepare()
            TrainingArtifacts(output)
                .write(
                    "started.json",
                    mapOf(
                        "signature" to input.signature,
                        "workers" to 1,
                        "constructedOnly" to true,
                        "nativeGames" to 0,
                    ),
                )
            val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
            val rows =
                input.indices.map { index ->
                    val panel = CoordinatedEsPanel(index)
                    if (index == 0) {
                        for (head in listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)) {
                            val decision = fixture.decision(head, batchStart = 19)
                            val reference =
                                requireNotNull(
                                    input.arena
                                        .controllers(
                                            4,
                                            0,
                                            ArenaSide.CANDIDATE,
                                            input.arena.candidate,
                                            List(4) { 0 },
                                        )[0]
                                ) {
                                    "Constructed reference."
                                }
                            val command =
                                requireNotNull(reference.choose(decision)) {
                                    "Constructed offered command."
                                }
                            panel.observe(
                                head,
                                decision.number,
                                decision.batchStartNumber,
                                1,
                                CoordinatedEsController.observation(decision, command),
                            )
                        }
                    }
                    val game =
                        ArenaGame(
                            List(4) { .25 },
                            List(4) { .5 },
                            List(4) { .5 },
                            List(4) { PilotReplayScore("unit-$it", 0, 0, 0, 0, false) },
                            1,
                            0,
                        )
                    attempts.execute { store.save(index, "RAW_G3", game, null, panel) }
                }
            store.finish(rows, attempts)
            val relocation =
                input.inputPins().mapValues { (path, hash) ->
                    val source = Path.of(path)
                    require(source.startsWith(fixture.files.directory.toRealPath())) {
                        "Constructed inputs stay inside the fixture owner."
                    }
                    val destination =
                        root
                            .resolve("inputs")
                            .resolve(fixture.files.directory.toRealPath().relativize(source))
                    Files.createDirectories(destination.parent)
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                    mapOf("path" to destination.toString(), "sha256" to hash)
                }
            TrainingArtifacts(root)
                .write(
                    "CONSTRUCTED-FIXTURE.json",
                    mapOf(
                        "kind" to "coordinated-es-native-wire-unit-1",
                        "constructedOnly" to true,
                        "scientificAdmission" to false,
                        "nativeGames" to 0,
                        "newFits" to 0,
                        "constructedStoredGames" to 32,
                        "originalPathMap" to relocation,
                        "files" to ExpansionFinalMain.inventory(output),
                    ),
                )
            val prepared = PurchaseJson.read(output.resolve("prepared.json"))
            assertThat(prepared["scopeSha256"].asText()).isEqualTo(scope.sha256)
            assertThat(PurchaseJson.read(output.resolve("finished.json"))["completedGames"].asInt())
                .isEqualTo(32)
            assertThat(PurchaseJson.read(output.resolve("panel-0.json"))["heads"]["TOKEN"].size())
                .isEqualTo(1)
            assertThat(PurchaseJson.read(output.resolve("panel-1.json"))["heads"]["TOKEN"].size())
                .isEqualTo(0)
        }
    }
}
