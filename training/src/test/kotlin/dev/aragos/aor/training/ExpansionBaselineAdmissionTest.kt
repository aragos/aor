package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionBaselineAdmissionTest {
    @Test
    fun completeEighthActorsHaveSeparateExplicitArenaRoles() {
        Fixture().use { fixture ->
            val input = ExpansionBaselineInput.read(fixture.study())
            assertThat(input.side(ExpansionBaselineRole.TREATMENT)).isEqualTo(ArenaSide.CANDIDATE)
            assertThat(input.side(ExpansionBaselineRole.CONTROL)).isEqualTo(ArenaSide.INCUMBENT)
            assertThat(input.specification(ExpansionBaselineRole.RAW_G3).expansionActor).isNull()
            for (role in listOf(ExpansionBaselineRole.CONTROL, ExpansionBaselineRole.TREATMENT)) {
                val actor = input.specification(role).expansionActor?.actor(input.side(role))
                assertThat(actor?.path).isEqualTo(fixture.policy(role.name).toRealPath())
            }
        }
    }

    @Test
    fun explicitMacReplicationHasItsOwnAdmissionIdentity() {
        Fixture(replica = "MAC_REPLICATION").use { fixture ->
            assertThat(fixture.read().replica).isEqualTo(ExpansionBaselineReplica.MAC_REPLICATION)
        }
    }

    @Test
    fun otherReplicaAuditCannotAdmitTheseActors() {
        Fixture(auditReplica = "MAC_REPLICATION").use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun otherReplicaTreatmentIdentityCannotBeSubstituted() {
        Fixture(objectiveReplica = "MAC_REPLICATION").use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun incompleteLearningCannotProduceAScreen() {
        Fixture(completed = 7).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun failedFirstBatchEqualityCannotProduceAScreen() {
        Fixture(parity = false).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun intermediateActorCannotMasqueradeAsEighth() {
        Fixture(batch = 7).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun constantObjectiveCannotMasqueradeAsTreatment() {
        Fixture(treatmentVersion = "expansion-mc-actor-only-1").use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun initializerMustBeOriginalProtocolSubject() {
        Fixture(initialSha = "0".repeat(64)).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun everyOriginalArmReceiptMustRemainPinned() {
        Fixture(omitReceipt = true).use { fixture ->
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun swappedSameByteActorPathsAreRejected() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                ExpansionBaselineInput.read(fixture.study(swapActors = true))
            }
        }
    }

    @Test
    fun alteredPolicyBytesRejectBeforeControllerCreation() {
        Fixture().use { fixture ->
            Files.writeString(fixture.policy("TREATMENT"), "{}")
            assertFailsWith<IllegalArgumentException> { fixture.read() }
        }
    }

    @Test
    fun oldFinal64AdmissionRejectsNewEightBatchKind() {
        Fixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                ExpansionFinalAdmission.read(fixture.subject())
            }
        }
    }

    @Test
    fun zeroGamePreparationUsesActualNewWireAndExactCounts() {
        Fixture().use { fixture ->
            val output = fixture.directory.resolve("prepared-screen")
            ExpansionBaselineMain.run(
                listOf(
                    "--mode",
                    "expansion-baseline-screen-prepare",
                    "--source",
                    fixture.study().toString(),
                    "--output",
                    output.toString(),
                )
            )
            val manifest = PurchaseJson.read(output.resolve("manifest.json"))
            assertThat(manifest["kind"].asText()).isEqualTo("expansion-baseline-screen-1")
            assertThat(manifest["roles"].map(JsonNode::asText))
                .containsExactly("TREATMENT", "CONTROL", "RAW_G3")
                .inOrder()
            assertThat(manifest["maximumTerminalExecutions"].intValue()).isEqualTo(3086)
            val finished = PurchaseJson.read(output.resolve("finished.json"))
            assertThat(finished["worlds"].intValue()).isEqualTo(0)
            assertThat(finished["reproductions"].intValue()).isEqualTo(0)
            assertThat(finished["complete"].booleanValue()).isTrue()
        }
    }

    private class Fixture(
        completed: Int = 8,
        parity: Boolean = true,
        batch: Int = 8,
        treatmentVersion: String = "expansion-previous-batch-state-baseline-1",
        initialSha: String? = null,
        omitReceipt: Boolean = false,
        replica: String = "PRIMARY_DESKTOP",
        auditReplica: String = replica,
        objectiveReplica: String = replica,
    ) : AutoCloseable {
        private val base = ExpansionOnPolicyFixture()
        val directory: Path = base.files.directory
        private val armDirectories =
            listOf("CONTROL", "TREATMENT").associateWith {
                directory.resolve("learning/batches/batch-${batch.toString().padStart(2, '0')}/$it")
            }
        private val admission = directory.resolve("admission.json")

        init {
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
                    "kind" to "expansion-baseline-control-loop-1",
                    "replica" to replica,
                    "rounds" to 8,
                    "worldsPerBatch" to 256,
                    "prefixRounds" to 2,
                    "actor" to base.actorPath.toString(),
                    "actorSha256" to (initialSha ?: PurchaseJson.hash(base.actorPath)),
                    "arena" to originalArena.toString(),
                    "arenaSha256" to PurchaseJson.hash(originalArena),
                    "prepared" to subject(prepared),
                    "files" to mapOf(ordinary.toString() to PurchaseJson.hash(ordinary)),
                ),
            )
            val finalSubjects =
                listOf("CONTROL", "TREATMENT")
                    .flatMap { arm ->
                        val armDirectory =
                            requireNotNull(armDirectories[arm]) { "Synthetic arm must exist." }
                        val actor = armDirectory.resolve("numeric/actor")
                        Files.createDirectories(actor)
                        Files.copy(base.actorPath, actor.resolve("policy.json"))
                        val objective =
                            if (arm == "CONTROL") {
                                mapOf(
                                    "expansionMcObjective" to
                                        mapOf("version" to "expansion-mc-actor-only-1")
                                )
                            } else {
                                mapOf(
                                    "expansionBaselineObjective" to
                                        mapOf(
                                            "version" to treatmentVersion,
                                            "phase" to "expansion",
                                            "arm" to arm,
                                            "batch" to batch,
                                            "replica" to objectiveReplica,
                                        )
                                )
                            }
                        write(
                            actor.resolve("identity.json"),
                            mapOf("actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION) +
                                objective,
                        )
                        write(
                            actor.resolve("checkpoint.json"),
                            mapOf("payload" to mapOf("model" to PurchaseJson.read(policy(arm)))),
                        )
                        write(
                            armDirectory.resolve("receipt.json"),
                            mapOf(
                                "kind" to "expansion-baseline-batch-1",
                                "replica" to replica,
                                "protocolSha256" to PurchaseJson.hash(protocol),
                                "arm" to arm,
                                "batch" to batch,
                                "complete" to true,
                                "actorSha256" to PurchaseJson.hash(policy(arm)),
                            ),
                        )
                        listOf("Policy", "Identity", "Checkpoint", "Receipt").map { suffix ->
                            "${arm.lowercase()}$suffix" to
                                if (suffix == "Receipt") armDirectory.resolve("receipt.json")
                                else actor.resolve("${suffix.lowercase()}.json")
                        }
                    }
                    .toMap()
            val learning = directory.resolve("learning-audit.json")
            write(
                learning,
                mapOf(
                    "kind" to "expansion-baseline-learning-audit-1",
                    "replica" to auditReplica,
                    "passed" to true,
                    "complete" to true,
                    "completedRounds" to completed,
                    "completedBatches" to mapOf("CONTROL" to completed, "TREATMENT" to completed),
                    "scientificWorlds" to completed * 512,
                    "historicalReproductions" to completed * 2,
                    "firstBatchParityPassed" to parity,
                    "operationalPrefixPassed" to true,
                    "eligibleFinalActors" to true,
                    "protocolSha256" to PurchaseJson.hash(protocol),
                    "preparedSha256" to PurchaseJson.hash(prepared),
                    "receiptSha256" to
                        listOf("CONTROL", "TREATMENT").associateWith { arm ->
                            (if (omitReceipt) 2..8 else 1..8).associate {
                                it.toString() to
                                    PurchaseJson.hash(
                                        requireNotNull(finalSubjects["${arm.lowercase()}Receipt"]) {
                                            "Receipt fixture is required."
                                        }
                                    )
                            }
                        },
                    "arms" to
                        listOf("CONTROL", "TREATMENT").associateWith { arm ->
                            listOf("Policy", "Identity", "Checkpoint", "Receipt")
                                .flatMap { suffix ->
                                    val path =
                                        requireNotNull(finalSubjects["${arm.lowercase()}$suffix"]) {
                                            "Final synthetic subject is required."
                                        }
                                    listOf(
                                        "${suffix.lowercase()}Path" to path.toString(),
                                        "${suffix.lowercase()}Sha256" to PurchaseJson.hash(path),
                                    )
                                }
                                .toMap()
                        },
                    "artifactPins" to
                        finalSubjects.values.associate { it.toString() to PurchaseJson.hash(it) },
                ),
            )
            write(
                admission,
                mapOf(
                    "kind" to ExpansionBaselineAdmission.VERSION,
                    "subjects" to
                        (finalSubjects +
                                mapOf(
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

        fun policy(arm: String): Path =
            requireNotNull(armDirectories[arm]) { "Synthetic arm must exist." }
                .resolve("numeric/actor/policy.json")

        fun subject() =
            ExpansionScreenSubject(admission, PurchaseJson.hash(admission), admission.toString())

        fun read() = ExpansionBaselineAdmission.read(subject())

        fun study(swapActors: Boolean = false): Path {
            val arena = PurchaseJson.read(base.input.arenaSource).deepCopy<ObjectNode>()
            arena.put("pairsPerConfiguration", 1024)
            arena.set<JsonNode>(
                "expansionActor",
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "version" to ArenaExpansionActor.VERSION,
                        "candidate" to subject(policy(if (swapActors) "CONTROL" else "TREATMENT")),
                        "incumbent" to subject(policy(if (swapActors) "TREATMENT" else "CONTROL")),
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
                    "kind" to ExpansionBaselineInput.VERSION,
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
