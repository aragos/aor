package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Actual native admission and store serialization; terminal values and all lineage attestations are
 * invented.
 */
class CoordinatedWinWireTest {
    @Test
    fun completeControlAndPopulationShardsRetainAllExactNativeSubjects() {
        val requested = System.getenv("AOR_WIN_CONSTRUCTED_FIXTURE")
        val root =
            requested?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: Files.createTempDirectory("coordinated-win-wire")
        if (requested != null) {
            require(!Files.exists(root)) {
                "Use one absent immutable constructed export directory."
            }
            Files.createDirectories(root)
        }
        try {
            CoordinatedWinFixture(root.resolve("inputs")).use { fixture ->
                val receipts =
                    listOf(CoordinatedEsStage.CONTROLS, CoordinatedEsStage.POPULATION).associate {
                        stage ->
                        val source = fixture.source(stage = stage)
                        val input = CoordinatedWinInput.read(source.path)
                        val output = root.resolve(stage.name.lowercase())
                        val store = CoordinatedEsGames(input, output)
                        store.prepare()
                        TrainingArtifacts(output)
                            .write(
                                "started.json",
                                mapOf(
                                    "signature" to input.signature,
                                    "workers" to 10,
                                    "startedAt" to "2000-01-01T00:00:00Z",
                                    "deadlineAt" to "2000-01-01T00:01:00Z",
                                ),
                            )
                        val attempts = CoordinatedEsAttempts(Long.MAX_VALUE)
                        val game =
                            ArenaGame(
                                List(4) { .25 },
                                List(4) { .3 },
                                List(4) { .5 },
                                List(4) { PilotReplayScore("p${it + 1}", 0, 10, 0, 10, true) },
                                1,
                                0,
                            )
                        val rows =
                            input.indices.flatMap { index ->
                                input.roles.map { role ->
                                    attempts.execute { store.save(index, role, game, null, null) }
                                }
                            }
                        store.finish(rows, attempts)
                        assertThat(attempts.completedGames)
                            .isEqualTo(if (stage == CoordinatedEsStage.CONTROLS) 32 else 1024)
                        assertThat(input.roles).doesNotContain("RAW_G3")
                        stage.name to
                            mapOf(
                                "source" to fixture.ref(source),
                                "output" to output.toString(),
                                "finishedSha256" to
                                    PurchaseJson.hash(output.resolve("finished.json")),
                            )
                    }
                val screenSource =
                    fixture.save(
                        "screen-source.json",
                        mapOf(
                            "kind" to "coordinated-win-screen-1",
                            "host" to "DESKTOP",
                            "originProof" to fixture.ref(fixture.proof),
                            "arena" to fixture.ref(fixture.arena),
                            "admission" to fixture.ref(fixture.finalAdmission),
                            "historical" to fixture.ref(fixture.historical),
                            "globalStart" to 128,
                            "count" to 128,
                            "bank" to fixture.plan.record["screenBank"],
                        ),
                    )
                val screen = CoordinatedWinScreenInput.read(screenSource.path)
                val screenOutput = root.resolve("screen")
                val screenArtifacts = TrainingArtifacts(screenOutput)
                screen.snapshot(screenArtifacts)
                screenArtifacts.write("manifest.json", screen.manifest())
                screenArtifacts.write(
                    "started.json",
                    mapOf("signature" to screen.signature, "workers" to 10, "maximumSeconds" to 60),
                )
                var calls = 0
                val terminal =
                    ArenaGame(
                        List(4) { .25 },
                        List(4) { .3 },
                        List(4) { .5 },
                        List(4) { PilotReplayScore("p${it + 1}", 0, 10, 0, 10, true) },
                        1,
                        0,
                    )
                val constructed =
                    object : ThreeRoleScreenInput by screen {
                        override fun executeGame(
                            deadline: Long,
                            operation: (Long) -> ArenaGame,
                        ): ArenaGame {
                            calls++
                            return terminal
                        }

                        override fun executionReceipt(expected: Int?): Map<String, Int> {
                            require(expected == calls) {
                                "Constructed terminal invocation count must remain exact."
                            }
                            return mapOf("attemptedGames" to calls, "completedGames" to calls)
                        }
                    }
                val worlds =
                    screen.indices.map { index ->
                        val world =
                            ThreeRoleScreen.play(constructed, index, Long.MAX_VALUE) { _, _ ->
                                error("The constructed non-replay shard has no canonical replay.")
                            }
                        val value = ExpansionOnPolicyStore.wire(world)
                        val name = "world-$index.json.gz"
                        CardTrajectoryFiles.save(screenOutput.resolve(name), value)
                        mapOf(
                            "index" to index,
                            "file" to name,
                            "sha256" to PurchaseJson.hash(screenOutput.resolve(name)),
                            "semanticSha256" to ThreeRoleScreen.semanticHash(value),
                        )
                    }
                val delegateField =
                    CoordinatedWinScreenMain::class.java.getDeclaredField("delegate").also {
                        it.isAccessible = true
                    }
                val finish =
                    ThreeRoleScreenMain::class
                        .java
                        .getDeclaredMethod(
                            "finish",
                            ThreeRoleScreenInput::class.java,
                            Path::class.java,
                            String::class.java,
                            List::class.java,
                        )
                        .also { it.isAccessible = true }
                finish.invoke(delegateField.get(null), constructed, screenOutput, "chunk", worlds)
                assertThat(calls).isEqualTo(384)
                TrainingArtifacts(root)
                    .write(
                        "CONSTRUCTED-FIXTURE.json",
                        mapOf(
                            "kind" to "coordinated-win-native-wire-fixture-1",
                            "constructedOnly" to true,
                            "simulationCalls" to 0,
                            "newGames" to 0,
                            "policyCalls" to 0,
                            "actualFits" to 0,
                            "executedGenerations" to 0,
                            "constructedLineageGenerations" to 16,
                            "stubbedTerminalInvocations" to 1440,
                            "scientificOriginalAdmission" to false,
                            "protocol" to fixture.ref(fixture.protocol),
                            "stages" to receipts,
                            "screenSource" to fixture.ref(screenSource),
                            "limitations" to
                                listOf(
                                    "Original proof/runtime entries are invented and protocol-pinned for shape only.",
                                    "All terminal values, materialization relations and later lineage are constructed; no Simulation or optimizer ran.",
                                    "Actual native parsing, all32 member decoding and complete store serialization are exercised.",
                                ),
                        ),
                    )
            }
        } finally {
            if (requested == null) root.toFile().deleteRecursively()
        }
    }
}
