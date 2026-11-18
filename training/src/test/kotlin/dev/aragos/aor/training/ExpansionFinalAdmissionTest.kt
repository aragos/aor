package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionFinalAdmissionTest {
    @Test
    fun exactCompleted64AuditAndOriginalInitializationAreAdmitted() {
        Fixture().use { fixture ->
            val admission = fixture.read()
            admission.validateEcology(fixture.base.input.arena)
            assertThat(admission.finalPolicy.sha256)
                .isEqualTo(PurchaseJson.hash(fixture.finalPolicy))
        }
    }

    @Test
    fun completed16CannotBecomeFinalCandidate() {
        Fixture(completed = 16).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun uncleanTerminalCannotBeAdmitted() {
        Fixture(clean = false).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun batch63PolicyCannotMasqueradeAsCompleted64() {
        Fixture(batch = 63).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun changedFinalBytesRejectBeforeAnyController() {
        Fixture().use { fixture ->
            Files.writeString(fixture.finalPolicy, "{}")
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun initializerMustMatchOriginalProtocolSubject() {
        Fixture(initialSha = "0".repeat(64)).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun nativePreparationUsesActualTypedAdmissionAndNoSimulation() {
        Fixture().use { fixture ->
            val study = fixture.study()
            val output = fixture.directory.resolve("prepared-screen")
            ExpansionFinalMain.run(
                listOf(
                    "--mode",
                    "expansion-final-prepare",
                    "--source",
                    study.toString(),
                    "--output",
                    output.toString(),
                )
            )
            val manifest = PurchaseJson.read(output.resolve("manifest.json"))
            assertThat(manifest["worlds"].intValue()).isEqualTo(1024)
            assertThat(manifest["maximumTerminalExecutions"].intValue()).isEqualTo(3086)
            assertThat(PurchaseJson.read(output.resolve("finished.json"))["worlds"].intValue())
                .isEqualTo(0)
        }
    }

    private class Fixture(
        completed: Int = 64,
        clean: Boolean = true,
        batch: Int = 64,
        initialSha: String? = null,
    ) : AutoCloseable {
        val base = ExpansionOnPolicyFixture()
        val directory: Path = base.files.directory
        val finalPolicy: Path =
            directory.resolve("learning/batches/batch-$batch/numeric/policy.json")
        private val admission = directory.resolve("admission.json")

        init {
            Files.createDirectories(finalPolicy.parent)
            Files.copy(base.actorPath, finalPolicy)
            write(
                finalPolicy.parent.resolve("identity.json"),
                mapOf(
                    "actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION,
                    "modelSha256" to PurchaseJson.hash(base.actorPath),
                    "expansionMcObjective" to mapOf("version" to "expansion-mc-actor-only-1"),
                ),
            )
            write(
                finalPolicy.parent.resolve("checkpoint.json"),
                mapOf("payload" to mapOf("model" to PurchaseJson.read(finalPolicy))),
            )
            write(
                finalPolicy.parent.parent.resolve("receipt.json"),
                mapOf(
                    "batch" to batch,
                    "complete" to true,
                    "actorSha256" to PurchaseJson.hash(finalPolicy),
                ),
            )
            val prepared = directory.resolve("prepared.json")
            write(prepared, ExpansionOnPolicyStore.manifest(base.input))
            val originalArena = base.input.arenaSource
            val ordinary =
                originalArena.parent
                    .resolve(PurchaseJson.read(originalArena)["candidate"].asText())
                    .toAbsolutePath()
                    .normalize()
            val protocol = directory.resolve("protocol.json")
            write(
                protocol,
                mapOf(
                    "kind" to "expansion-on-policy-loop-1",
                    "batches" to 64,
                    "actor" to base.actorPath.toString(),
                    "actorSha256" to (initialSha ?: PurchaseJson.hash(base.actorPath)),
                    "arena" to originalArena.toString(),
                    "arenaSha256" to PurchaseJson.hash(originalArena),
                    "prepared" to subject(prepared),
                    "files" to mapOf(ordinary.toString() to PurchaseJson.hash(ordinary)),
                ),
            )
            val finalSubjects =
                mapOf(
                    "finalPolicy" to finalPolicy,
                    "finalIdentity" to finalPolicy.parent.resolve("identity.json"),
                    "finalCheckpoint" to finalPolicy.parent.resolve("checkpoint.json"),
                    "finalReceipt" to finalPolicy.parent.parent.resolve("receipt.json"),
                )
            val learning = directory.resolve("learning-audit.json")
            write(
                learning,
                mapOf(
                    "kind" to "expansion-on-policy-learning-audit-1",
                    "passed" to true,
                    "complete" to true,
                    "completedBatches" to completed,
                    "scientificWorlds" to completed * 256,
                    "historicalReproductions" to completed,
                    "eligibleFinalActor" to true,
                    "finalActorSha256" to PurchaseJson.hash(finalPolicy),
                    "batches" to (1..completed).map { mapOf("batch" to it) },
                    "artifactPins" to
                        finalSubjects.values.associate { it.toString() to PurchaseJson.hash(it) },
                ),
            )
            val terminal = directory.resolve("terminal-audit.json")
            write(
                terminal,
                mapOf(
                    "kind" to "expansion-on-policy-terminal-audit-1",
                    "passed" to true,
                    "status" to "completed",
                    "allOwnedProcessesExited" to clean,
                    "clockGuardStopped" to clean,
                    "completedBatches" to completed,
                    "eligibleFinalActor" to true,
                    "protocolSha256" to PurchaseJson.hash(protocol),
                    "learningAuditSha256" to PurchaseJson.hash(learning),
                    "learningAudit" to PurchaseJson.read(learning),
                ),
            )
            write(
                admission,
                mapOf(
                    "kind" to ExpansionFinalAdmission.VERSION,
                    "subjects" to
                        (finalSubjects +
                                mapOf(
                                    "terminalAudit" to terminal,
                                    "learningAudit" to learning,
                                    "learningProtocol" to protocol,
                                    "prepared" to prepared,
                                    "initialPolicy" to base.actorPath,
                                    "originalArena" to originalArena,
                                    "ordinary" to ordinary,
                                ))
                            .mapValues { subject(it.value) },
                ),
            )
        }

        fun read() =
            ExpansionFinalAdmission.read(
                ExpansionScreenSubject(
                    admission,
                    PurchaseJson.hash(admission),
                    admission.toString(),
                )
            )

        fun study(): Path {
            val arena = PurchaseJson.read(base.input.arenaSource).deepCopy<ObjectNode>()
            arena.put("pairsPerConfiguration", 1024)
            arena.set<JsonNode>(
                "expansionActor",
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "version" to ArenaExpansionActor.VERSION,
                        "candidate" to subject(finalPolicy),
                        "incumbent" to subject(base.actorPath),
                    )
                ),
            )
            val arenaFile = directory.resolve("screen-arena.json")
            write(arenaFile, arena)
            val histories =
                (0..1).map { index ->
                    val replay = directory.resolve("historical-$index.json")
                    write(replay, mapOf("syntheticOnly" to index))
                    mapOf(
                        "source" to subject(base.input.arenaSource),
                        "pair" to subject(replay),
                        "replay" to subject(replay),
                        "index" to index,
                        "side" to "candidate",
                    )
                }
            val study = directory.resolve("screen-study.json")
            write(
                study,
                mapOf(
                    "kind" to ExpansionFinalInput.VERSION,
                    "arena" to subject(arenaFile),
                    "admission" to subject(admission),
                    "historical" to histories,
                ),
            )
            return study
        }

        private fun subject(path: Path) =
            mapOf("path" to path.toString(), "sha256" to PurchaseJson.hash(path))

        private fun write(path: Path, value: Any) =
            Files.write(path, PurchaseJson.mapper.writeValueAsBytes(value))

        override fun close() = base.close()
    }
}
