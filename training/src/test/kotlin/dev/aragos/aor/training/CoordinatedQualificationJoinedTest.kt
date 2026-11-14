package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Joined admission SHAPE only: reuse the83 invented16-lineage fixture, then admit both supported
 * ecologies through their real readers. Expected original pins are replaced only inside this test
 * JVM and restored in finally. No actual original/candidate, whole game, or ES update is evaluated.
 */
class CoordinatedQualificationJoinedTest {
    @Test
    fun admittedFinalEntersSupportedMixedThenOnlyPassedIndependentHomogeneous() =
        scenario { fixture ->
            val mixedHash = prepareMixed(fixture)
            exportWire(fixture)
            val gate = fixture.confirmation()
            val homogeneous =
                CoordinatedQualificationInput.read(fixture.source("homogeneous", gate).path)
            homogeneous.verify()
            assertThat(homogeneous.expectedOpponents(4095)).containsExactly(0, 0, 0, 0).inOrder()
            assertThat(homogeneous.admission.finalBundle.source.sha256).isEqualTo(mixedHash)
        }

    private fun prepareMixed(fixture: Fixture): String {
        val mixed = CoordinatedQualificationInput.read(fixture.source("mixed", null).path)
        mixed.verify()
        assertThat(mixed.expectedOpponents(4095)).containsExactly(3, 0, 1, 2).inOrder()
        assertThat(mixed.executionReceipt(0)["completedGames"]).isEqualTo(0)
        val prepared = fixture.root.resolve("prepared")
        mixed.snapshot(TrainingArtifacts(prepared))
        TrainingArtifacts(prepared).write("manifest.json", mixed.manifest())
        assertThat(
                PurchaseJson.read(prepared.resolve("manifest.json"))["policyBindings"][
                        "coordinatedEs"]["supportProfile"]
                    .asText()
            )
            .isEqualTo("known-own-chaos-support-1")
        return mixed.admission.finalBundle.source.sha256
    }

    /**
     * Real native DTO/finish owners with an explicitly stubbed terminal provider, never Simulation.
     */
    private fun exportWire(fixture: Fixture) {
        val requested = System.getenv("AOR_QUALIFICATION_CONSTRUCTED_FIXTURE") ?: return
        val output = Path.of(requested).toAbsolutePath().normalize()
        require(!Files.exists(output)) { "Constructed wire evidence must use an absent output." }
        Files.createDirectories(output)
        val source = fixture.source("mixed", null, start = 128)
        val input = CoordinatedQualificationInput.read(source.path)
        val chunk = output.resolve("chunk")
        val artifacts = TrainingArtifacts(chunk)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        artifacts.write(
            "started.json",
            mapOf("signature" to input.signature, "workers" to 10, "maximumSeconds" to 60),
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
            object : ThreeRoleScreenInput by input {
                override fun executeGame(
                    deadline: Long,
                    operation: (Long) -> ArenaGame,
                ): ArenaGame {
                    calls++
                    return terminal
                }

                override fun executionReceipt(expected: Int?): Map<String, Int> {
                    require(expected == calls) {
                        "Only the exact constructed terminal invocations count."
                    }
                    return mapOf("attemptedGames" to calls, "completedGames" to calls)
                }
            }
        val rows =
            input.indices.map { index ->
                val world =
                    ThreeRoleScreen.play(constructed, index, Long.MAX_VALUE) { _, _ ->
                        error(
                            "This constructed non-replay shard cannot produce a canonical history."
                        )
                    }
                val value = ExpansionOnPolicyStore.wire(world)
                val name = "world-$index.json.gz"
                CardTrajectoryFiles.save(chunk.resolve(name), value)
                mapOf(
                    "index" to index,
                    "file" to name,
                    "sha256" to PurchaseJson.hash(chunk.resolve(name)),
                    "semanticSha256" to ThreeRoleScreen.semanticHash(value),
                )
            }
        val field =
            CoordinatedQualificationMain::class.java.getDeclaredField("delegate").also {
                it.isAccessible = true
            }
        val delegate = field.get(null)
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
        finish.invoke(delegate, constructed, chunk, "chunk", rows)
        val originals =
            input.inputPins.entries.mapIndexed { ordinal, entry ->
                val target = output.resolve("inputs/$ordinal")
                Files.createDirectories(target.parent)
                Files.copy(Path.of(entry.key), target, StandardCopyOption.COPY_ATTRIBUTES)
                require(PurchaseJson.hash(target) == entry.value) {
                    "Constructed input copy must be exact."
                }
                mapOf(
                    "originalPath" to entry.key,
                    "path" to output.relativize(target).toString(),
                    "sha256" to entry.value,
                )
            }
        TrainingArtifacts(output)
            .write(
                "CONSTRUCTED-FIXTURE.json",
                mapOf(
                    "kind" to "coordinated-qualification-native-wire-fixture-1",
                    "constructedOnly" to true,
                    "newGames" to 0,
                    "scientificFits" to 0,
                    "executedGenerations" to 0,
                    "constructedLineageGenerations" to 16,
                    "stubbedTerminalInvocations" to calls,
                    "scientificOriginalAdmission" to false,
                    "candidateBundleSha256" to input.admission.finalBundle.source.sha256,
                    "limitations" to
                        listOf(
                            "Expected original pins substituted only in the test JVM and restored in finally.",
                            "Origin proof is constructed from this toy admission; it does not attest scientific83 provenance.",
                            "Terminal provider is invented; no Simulation or real replay occurred.",
                            "Complete native serialization/receipt shape is proved, not scientific outcome fidelity.",
                        ),
                    "originalInputs" to originals,
                ),
            )
    }

    @Test
    fun changedClosureIsRejectedBeforeCurrentRoleDecode() = scenario { fixture ->
        val target =
            requireNotNull(fixture.admission.subjects["generationAudit"]) {
                "Constructed audit subject."
            }
        Files.writeString(target.path, "{}")
        val error =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedQualificationInput.read(fixture.source("mixed", null).path)
            }
        assertThat(error.message).contains("origin closure file changed")
    }

    @Test
    fun proofFromAnotherHostIsRejected() = scenario { fixture ->
        val value = PurchaseJson.read(fixture.proof.path) as ObjectNode
        value.put("host", "MAC")
        val changed = fixture.save(fixture.proof.path, value)
        val error =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedQualificationOrigin.read(
                    changed,
                    fixture.admission.source,
                    fixture.originArena,
                    QualificationHost.DESKTOP,
                )
            }
        assertThat(error.message).contains("exact host")
    }

    @Test
    fun supportedHomogeneousCannotOpenWithoutTheMixedGate() = scenario { fixture ->
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationInput.read(fixture.source("homogeneous", null).path)
        }
    }

    @Test
    fun mixedRivalSupportCannotHideAnUnchangedHomogeneousEcology() = scenario { fixture ->
        val arena = PurchaseJson.read(fixture.mixed.path) as ObjectNode
        (arena["tokenOrder"] as ObjectNode).putArray("opponentSelectors").also { array ->
            repeat(4) { array.add("FIXED_16_TO_15") }
        }
        fixture.save(fixture.mixed.path, arena)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationInput.read(fixture.source("mixed", null).path)
        }
    }

    internal fun scenario(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("coordinated-qualification-joined-shape")
        CoordinatedEsFixture().use { toy ->
            @Suppress("UNCHECKED_CAST")
            val expected =
                CoordinatedEsBundle.ORIGINAL_HASHES as MutableMap<CoordinatedEsHead, String>
            val scientific = expected.toMap()
            val arena = CoordinatedEsWire.source(toy.files.directory.resolve("arena.json"))
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsScope.read(arena, toy.original.source)
            }
            val helper = Class.forName("dev.aragos.aor.training.CoordinatedEsFinalAdmissionShape")
            val names = listOf("output", "headReceipt", "arena", "original", "scope")
            val fields = names.associateWith {
                helper.getDeclaredField(it).also { field -> field.isAccessible = true }
            }
            val saved = fields.mapValues { (_, field) -> field.get(null) }
            try {
                expected.clear()
                expected.putAll(toy.original.subjects.mapValues { it.value.sha256 })
                val scope = CoordinatedEsScope.read(arena, toy.original.source)
                fun set(name: String, value: Any) =
                    requireNotNull(fields[name]) { "Existing shape fixture field." }
                        .set(null, value)
                set("output", root)
                set("arena", arena)
                set("original", toy.original.source)
                set("scope", scope)
                set(
                    "headReceipt",
                    requireNotNull(
                            javaClass.getResourceAsStream(
                                "/coordinated-es-calibration-v2-head.json"
                            )
                        ) {
                            "Constructed V2 receipt resource."
                        }
                        .use { PurchaseJson.mapper.readTree(it) },
                )
                val nested =
                    Class.forName(
                        "dev.aragos.aor.training.CoordinatedEsFinalAdmissionShape\$Fixture"
                    )
                val constructor =
                    nested
                        .getDeclaredConstructor(
                            String::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                        )
                        .also { it.isAccessible = true }
                val old = constructor.newInstance("qualification-origin", 2, 2)
                val reader = nested.getDeclaredMethod("read").also { it.isAccessible = true }
                val admitted = reader.invoke(old) as CoordinatedEsAdmission
                block(Fixture(root, toy, arena, admitted))
            } finally {
                expected.clear()
                expected.putAll(scientific)
                fields.forEach { (name, field) -> field.set(null, saved[name]) }
                root.toFile().deleteRecursively()
            }
            assertThat(expected).containsExactlyEntriesIn(scientific)
            assertFailsWith<IllegalArgumentException> {
                CoordinatedEsScope.read(arena, toy.original.source)
            }
        }
    }

    internal class Fixture(
        val root: Path,
        val toy: CoordinatedEsFixture,
        val originArena: TokenPairedSubject,
        val admission: CoordinatedEsAdmission,
    ) {
        val mixed = arena("mixed")
        val homogeneous = arena("homogeneous")
        val proof = originProof()
        val protocol: TokenPairedSubject

        init {
            val bindings = admission.scope.policyBindings
            protocol =
                saveProtocol(
                    root.resolve("qualification-protocol.json"),
                    mapOf(
                        "kind" to CoordinatedQualificationProtocol.VERSION,
                        "profile" to CoordinatedQualificationProtocol.PROFILE,
                        "supportProfile" to "known-own-chaos-support-1",
                        "candidateBundleSha256" to admission.finalBundle.source.sha256,
                        "originalBundleSha256" to admission.scope.original.source.sha256,
                        "calibrationSha256" to admission.calibration.source.sha256,
                        "originAdmissionSha256" to admission.source.sha256,
                        "originArenaSha256" to
                            mapOf("DESKTOP" to originArena.sha256, "MAC" to originArena.sha256),
                        "originProofSha256" to
                            mapOf("DESKTOP" to proof.sha256, "MAC" to proof.sha256),
                        "rulesVersion" to RULES_VERSION,
                        "dataVersion" to DATA_VERSION,
                        "configuration" to CoordinatedQualificationProtocol.CONFIGURATION,
                        "maximumSeconds" to 28800,
                        "auditSeconds" to 900,
                        "maximumTerminalExecutions" to 16400,
                        "references" to
                            mapOf(
                                "qualifiedOrdinarySha256" to "1".repeat(64),
                                "cardM8Sha256" to TokenPairedEcology.ordinaryHash(originArena.path),
                                "purchaseF0Sha256" to admission.scope.baseline.sha256,
                                "expansionESha256" to
                                    bindings["expansionImitation"]["candidateModel"]["sha256"]
                                        .asText(),
                                "tokenControlVersion" to "FIXED_16_TO_15",
                            ),
                        "interval" to
                            mapOf(
                                "confidence" to .99,
                                "multiplier" to CoordinatedQualificationProtocol.MULTIPLIER,
                                "requiredMetrics" to listOf("wins", "rewards"),
                                "lowerBoundStrictlyPositive" to true,
                            ),
                        "banks" to mapOf("mixed" to bank(41), "homogeneous" to bank(42)),
                    ),
                )
        }

        private fun ref(subject: TokenPairedSubject) =
            mapOf("path" to subject.path.toString(), "sha256" to subject.sha256)

        private fun originProof(): TokenPairedSubject {
            val directory =
                Path.of(
                    requireNotNull(System.getenv("AOR_QUALIFICATION_ORIGIN_RUNTIME")) {
                        "Joined shape proof requires the preserved83 runtime directory; no runtime rebuilding."
                    }
                )
            val runtime =
                CoordinatedQualificationOrigin.RUNTIME_NAMES.associateWith {
                    CoordinatedEsWire.source(directory.resolve(it))
                }
            return save(
                root.resolve("constructed-origin-proof.json"),
                mapOf(
                    "kind" to CoordinatedQualificationOrigin.VERSION,
                    "host" to "DESKTOP",
                    "passed" to true,
                    "complete" to true,
                    "admission" to ref(admission.source),
                    "arena" to ref(originArena),
                    "runtime" to runtime.mapValues { ref(it.value) },
                    "scopeIdentity" to admission.scope.identity,
                    "scopeSha256" to admission.scope.sha256,
                    "ecologySha256" to admission.scope.ecologySha256,
                    "subjects" to admission.subjects.mapValues { ref(it.value) },
                    "inputPins" to
                        (admission.pins +
                            runtime.values.associate { it.path.toString() to it.sha256 }),
                    "execution" to mapOf("newGames" to 0, "policyCalls" to 0, "actualFits" to 0),
                ),
            )
        }

        private fun bank(offset: Int) =
            mapOf(
                "nativeOffset" to offset,
                "count" to 4096,
                "seeds" to List(4096) { StrategyArena.seed(offset, it) },
            )

        private fun arena(bank: String): TokenPairedSubject {
            val value = PurchaseJson.read(originArena.path) as ObjectNode
            value
                .put("purpose", "CONFIRMATION")
                .put("pairsPerConfiguration", 4096)
                .put("seed", if (bank == "mixed") 41 else 42)
            val ordinary = value["candidate"].asText()
            val model = value["expansionImitation"]["candidateModel"].asText()
            val opponents = value.putArray("opponents")
            repeat(if (bank == "mixed") 4 else 1) { opponents.add(ordinary) }
            val expansions = (value["expansionImitation"] as ObjectNode).putArray("opponentModels")
            val tokens = (value["tokenOrder"] as ObjectNode).putArray("opponentSelectors")
            if (bank == "mixed") {
                expansions.addNull().add(model).addNull().add(model)
                tokens.add("ORDINARY").add("ORDINARY").add("FIXED_16_TO_15").add("FIXED_16_TO_15")
            } else {
                expansions.add(model)
                tokens.add("FIXED_16_TO_15")
            }
            return save(toy.files.directory.resolve("qualification-$bank-arena.json"), value)
        }

        fun source(bank: String, gate: TokenPairedSubject?, start: Int = 0): TokenPairedSubject =
            save(
                root.resolve("$bank-$start-source.json"),
                mapOf(
                    "kind" to "coordinated-qualification-input-1",
                    "host" to "DESKTOP",
                    "protocol" to ref(protocol),
                    "originArena" to ref(originArena),
                    "originAdmission" to ref(admission.source),
                    "originProof" to ref(proof),
                    "arena" to
                        ref(
                            CoordinatedEsWire.source(
                                if (bank == "mixed") mixed.path else homogeneous.path
                            )
                        ),
                    "bankName" to bank,
                    "globalStart" to start,
                    "count" to 128,
                    "confirmation" to gate?.let(::ref),
                ),
            )

        fun confirmation(): TokenPairedSubject {
            val worlds =
                (0..4095).map { index ->
                    fun game(value: Double) =
                        mapOf(
                            "wins" to List(4) { value },
                            "rewards" to List(4) { value },
                            "ranks" to List(4) { value },
                            "scores" to List(4) { mapOf("total" to 1) },
                        )
                    ExpansionOnPolicyStore.wire(
                        mapOf(
                            "index" to index,
                            "seat" to index % 4,
                            "roles" to mapOf("CANDIDATE" to game(.6), "CONTROL" to game(.4)),
                        )
                    )
                }
            val report =
                save(
                    root.resolve("mixed-report.json"),
                    CoordinatedQualificationReport.report(worlds),
                )
            val evidence =
                save(
                    root.resolve("mixed-evidence.json"),
                    mapOf(
                        "kind" to "coordinated-qualification-bank-evidence-1",
                        "complete" to true,
                        "bank" to "mixed",
                        "protocolSha256" to protocol.sha256,
                        "candidateBundleSha256" to admission.finalBundle.source.sha256,
                        "scienceSha256" to "2".repeat(64),
                        "worlds" to 4096,
                        "games" to 8192,
                        "reproductions" to 8,
                        "scientificTerminalExecutions" to 8200,
                        "gatePassed" to true,
                        "chunks" to
                            List(32) {
                                mapOf("path" to "invented-$it", "sha256" to "3".repeat(64))
                            },
                        "compact" to ref(report),
                        "report" to ref(report),
                        "reportFinished" to ref(report),
                    ),
                )
            return save(
                root.resolve("confirmation.json"),
                mapOf(
                    "kind" to "coordinated-support-confirmation-1",
                    "passed" to true,
                    "complete" to true,
                    "protocolSha256" to protocol.sha256,
                    "candidateBundleSha256" to admission.finalBundle.source.sha256,
                    "bank" to "mixed",
                    "worlds" to 4096,
                    "report" to ref(report),
                    "evidence" to ref(evidence),
                ),
            )
        }

        private fun saveProtocol(path: Path, value: Any): TokenPairedSubject {
            val sorted =
                CpePairedDigest::class.java.getDeclaredMethod("sorted", JsonNode::class.java).also {
                    it.isAccessible = true
                }
            val tree =
                sorted.invoke(CpePairedDigest, ExpansionOnPolicyStore.wire(value)) as JsonNode
            Files.writeString(path, PurchaseJson.mapper.writeValueAsString(tree) + "\n")
            return CoordinatedEsWire.source(path)
        }

        fun save(path: Path, value: Any): TokenPairedSubject {
            TrainingArtifacts(path.parent).write(path.fileName.toString(), value)
            return CoordinatedEsWire.source(path)
        }
    }
}
