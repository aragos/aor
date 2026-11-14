package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Constructed descriptors and finite admission only; no Simulation, real worlds or scientific
 * models.
 */
class CoordinatedDiagnosticTest {
    @Test
    fun allComplementPairsCoverEveryMaskExactlyOnceInEveryContext() {
        for (ecology in DiagnosticEcology.entries) for (support in DiagnosticSupport.entries) {
            val cells = (0..7).map { DiagnosticCell(ecology, support, it) }
            assertThat(cells.flatMap { it.roleMasks.values }.sorted())
                .containsExactlyElementsIn(0..15)
                .inOrder()
        }
    }

    @Test
    fun fixedReplaysCoverEachSeatAndGiveOneExecutionPerHostPerPair() {
        val cells =
            DiagnosticEcology.entries.flatMap { ecology ->
                DiagnosticSupport.entries.flatMap { support ->
                    (0..7).map { DiagnosticCell(ecology, support, it) }
                }
            }
        val seats =
            cells
                .flatMap { it.roleMasks.values }
                .map { DiagnosticCell.replay(it) % 4 }
                .groupingBy { it }
                .eachCount()
        assertThat(seats).containsExactly(0, 16, 1, 16, 2, 16, 3, 16)
        for (cell in cells) for (host in QualificationHost.entries) {
            assertThat(cell.replayIndices.filter { it in DiagnosticCell.range(host) }).hasSize(1)
            assertThat(cell.replayIndices.flatMap(cell::replayRoles))
                .containsExactly("CANDIDATE", "INCUMBENT")
        }
    }

    @Test
    fun hostRangesAndGlobalLineupsStayExact() {
        assertThat(DiagnosticCell.range(QualificationHost.DESKTOP)).isEqualTo(0..319)
        assertThat(DiagnosticCell.range(QualificationHost.MAC)).isEqualTo(320..511)
        val cell = DiagnosticCell(DiagnosticEcology.MIXED, DiagnosticSupport.RAW, 0)
        assertThat(cell.opponents(511)).containsExactly(3, 0, 1, 2).inOrder()
        assertFailsWith<IllegalArgumentException> { cell.opponents(512) }
    }

    @Test
    fun allContextAndMaskBindingsUseTheOriginalSharedControllerOwner() = scenario { fixture ->
        for (ecology in DiagnosticEcology.entries) for (support in
            DiagnosticSupport.entries) for (pair in 0..7) {
            val input = fixture.input(DiagnosticCell(ecology, support, pair))
            input.verify()
            assertThat(input.indices.count()).isEqualTo(320)
            assertThat(input.executionReceipt(0)["completedGames"]).isEqualTo(0)
            val binding = ExpansionOnPolicyStore.wire(input.raw.overlayMetadata())["coordinatedEs"]
            assertThat(binding["roles"].fieldNames().asSequence().toSet())
                .containsExactly("CANDIDATE", "INCUMBENT")
            assertThat(binding.has("supportProfile"))
                .isEqualTo(support == DiagnosticSupport.SUPPORT1)
            assertThat(input.maskBindings.keys).containsExactly("CANDIDATE", "INCUMBENT")
        }
    }

    @Test
    fun wrongMaskHeadIsRejectedBeforeItsModelCanBeDecoded() = scenario { fixture ->
        val source = fixture.source()
        val arena = PurchaseJson.read(fixture.lastArena.path)
        val descriptor =
            TokenPairedSubject.read(
                arena["coordinatedEs"]["candidate"],
                fixture.lastArena.path.parent,
            )
        val node = PurchaseJson.read(descriptor.path) as ObjectNode
        (node["heads"]["CARD"] as ObjectNode)
            .put("sha256", "a".repeat(64))
            .put("path", "absent-model.json")
        val changed = fixture.base.save(descriptor.path, node)
        (arena["coordinatedEs"]["candidate"] as ObjectNode).put("sha256", changed.sha256)
        val updated = fixture.base.save(fixture.lastArena.path, arena)
        val request = PurchaseJson.read(source.path) as ObjectNode
        (request["arena"] as ObjectNode).put("sha256", updated.sha256)
        fixture.base.save(source.path, request)
        val error =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedDiagnosticInput.read(source.path)
            }
        assertThat(error.message).contains("Every mask bit")
    }

    @Test
    fun rebasedCalibrationIsRejected() = scenario { fixture ->
        val source = fixture.source()
        val arena = PurchaseJson.read(fixture.lastArena.path) as ObjectNode
        (arena["coordinatedEs"] as ObjectNode).set<JsonNode>(
            "calibration",
            arena["coordinatedEs"]["baseline"],
        )
        val changed = fixture.base.save(fixture.lastArena.path, arena)
        val request = PurchaseJson.read(source.path) as ObjectNode
        (request["arena"] as ObjectNode).put("sha256", changed.sha256)
        fixture.base.save(source.path, request)
        val error =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedDiagnosticInput.read(source.path)
            }
        assertThat(error.message).contains("cannot rebase")
    }

    @Test
    fun wrongHostRangeIsRejectedBeforeOriginAccess() = scenario { fixture ->
        val source = fixture.source()
        val node = PurchaseJson.read(source.path) as ObjectNode
        node.put("globalStart", 320).put("count", 192)
        fixture.base.save(source.path, node)
        val error =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedDiagnosticInput.read(source.path)
            }
        assertThat(error.message).contains("fixed320/192")
    }

    @Test
    fun supportCannotBeAppliedToOnlyTheReferenceThenOverwritten() = scenario { fixture ->
        val source =
            fixture.source(DiagnosticCell(DiagnosticEcology.MIXED, DiagnosticSupport.SUPPORT1, 0))
        val arena = PurchaseJson.read(fixture.lastArena.path) as ObjectNode
        (arena["coordinatedEs"] as ObjectNode).remove("supportProfile")
        val changed = fixture.base.save(fixture.lastArena.path, arena)
        val node = PurchaseJson.read(source.path) as ObjectNode
        (node["arena"] as ObjectNode).put("sha256", changed.sha256)
        fixture.base.save(source.path, node)
        assertFailsWith<IllegalArgumentException> { CoordinatedDiagnosticInput.read(source.path) }
    }

    @Test
    fun promotionFlagIsRejected() = scenario { fixture ->
        val value = PurchaseJson.read(fixture.protocol.path) as ObjectNode
        value.put("promotionAllowed", true)
        val changed = fixture.base.save(fixture.protocol.path, value)
        assertFailsWith<IllegalArgumentException> { CoordinatedDiagnosticProtocol(changed) }
    }

    @Test
    fun legacyScreenReplayDefaultStillSelectsEveryRole() = scenario { fixture ->
        val input = CoordinatedQualificationInput.read(fixture.base.source("mixed", null).path)
        assertThat(input.replayRoles(0)).containsExactly("CANDIDATE", "CONTROL")
        assertThat(input.replayRoles(170)).isEmpty()
    }

    @Test
    fun wrongRoleCannotRequestAnAdditionalReproduction() = scenario { fixture ->
        val input = fixture.input()
        val error =
            assertFailsWith<IllegalArgumentException> {
                ThreeRoleScreen.reproduce(
                    input,
                    0,
                    "INCUMBENT",
                    PurchaseJson.mapper.createObjectNode(),
                    PurchaseJson.mapper.createObjectNode(),
                    0,
                )
            }
        assertThat(error.message).contains("owner shard")
        assertThat(input.executionReceipt(0)["attemptedGames"]).isEqualTo(0)
    }

    @Test
    fun exportsConstructedNativeWireWithoutExecutingAnyGame() = scenario { fixture ->
        val input = fixture.input()
        val output =
            System.getenv("AOR_DIAGNOSTIC_CONSTRUCTED_FIXTURE")?.let(Path::of) ?: return@scenario
        require(!Files.exists(output)) {
            "Constructed evidence is immutable and requires an absent output."
        }
        Files.createDirectories(output)
        val chunk = output.resolve("chunk")
        val artifacts = TrainingArtifacts(chunk)
        input.snapshot(artifacts)
        artifacts.write("manifest.json", input.manifest())
        artifacts.write(
            "started.json",
            mapOf("signature" to input.signature, "workers" to 10, "maximumSeconds" to 60),
        )
        val terminal =
            ArenaGame(
                List(4) { .25 },
                List(4) { .3 },
                List(4) { .5 },
                List(4) { PilotReplayScore("p${it + 1}", 0, 10, 0, 10, true) },
                1,
                0,
            )
        val rows =
            input.indices.map { index ->
                val world =
                    ThreeRoleScreenWorld(
                        input.kind,
                        input.signature,
                        input.scienceSha256,
                        index,
                        input.protocol.seeds[index],
                        index % 4,
                        CoordinatedQualificationProtocol.CONFIGURATION,
                        input.expectedOpponents(index),
                        input.roles.associateWith { terminal },
                    )
                val wire = ExpansionOnPolicyStore.wire(world)
                ThreeRoleScreen.validateWorld(
                    wire,
                    index,
                    input.scienceSha256,
                    input.protocol.seeds[index],
                    input.kind,
                    input.roles,
                    input.expectedOpponents(index),
                )
                val name = "world-$index.json.gz"
                CardTrajectoryFiles.save(chunk.resolve(name), wire)
                mapOf(
                    "index" to index,
                    "file" to name,
                    "sha256" to PurchaseJson.hash(chunk.resolve(name)),
                    "semanticSha256" to ThreeRoleScreen.semanticHash(wire),
                )
            }
        for (index in input.ownedReplays) for (role in input.replayRoles(index)) {
            val canonical =
                mapOf(
                    "header" to mapOf("constructedOnly" to true),
                    "entries" to listOf("INVENTED CONSTRUCTED REPLAY"),
                )
            artifacts.write("canonical-$index-$role.json", canonical)
            artifacts.write(
                "replay-$index-$role.json",
                canonical +
                    mapOf(
                        "index" to index,
                        "seed" to input.protocol.seeds[index],
                        "seat" to index % 4,
                        "configuration" to CoordinatedQualificationProtocol.CONFIGURATION,
                        "opponentIndices" to input.expectedOpponents(index),
                        "side" to role,
                        "game" to terminal,
                        "replayVerified" to true,
                        "coordinatedEs" to input.raw.coordinatedEs?.binding(),
                    ),
            )
        }
        val constructed =
            object : ThreeRoleScreenInput by input {
                override fun executionReceipt(expected: Int?): Map<String, Int> {
                    require(expected == 641) {
                        "The invented complete native shape declares exactly641 terminals."
                    }
                    return mapOf("attemptedGames" to 641, "completedGames" to 641)
                }
            }
        val field =
            CoordinatedDiagnosticMain::class.java.getDeclaredField("delegate").also {
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
        finish.invoke(field.get(null), constructed, chunk, "chunk", rows)
        val originals =
            input.inputPins.entries.mapIndexed { ordinal, entry ->
                val target = output.resolve("inputs/$ordinal")
                Files.createDirectories(target.parent)
                Files.copy(Path.of(entry.key), target, StandardCopyOption.COPY_ATTRIBUTES)
                require(PurchaseJson.hash(target) == entry.value) {
                    "Constructed provenance copy is byte exact."
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
                    "kind" to "coordinated-diagnostic-native-wire-fixture-1",
                    "constructedOnly" to true,
                    "newGames" to 0,
                    "scientificFits" to 0,
                    "scientificOriginalAdmission" to false,
                    "declaredInventedTerminals" to 641,
                    "candidateBundleSha256" to input.admission.finalBundle.source.sha256,
                    "limitations" to
                        listOf(
                            "Toy original pins are temporarily substituted in an isolated test JVM, then restored.",
                            "Origin attestation, terminal outcomes and canonical replay are invented shape evidence; no Simulation or replay was executed.",
                        ),
                    "originalInputs" to originals,
                ),
            )
        assertThat(input.executionReceipt(0)["completedGames"]).isEqualTo(0)
    }

    private fun scenario(block: (Fixture) -> Unit) =
        CoordinatedQualificationJoinedTest().scenario { block(Fixture(it)) }

    private class Fixture(val base: CoordinatedQualificationJoinedTest.Fixture) {
        val root = base.root.resolve("diagnostic").also(Files::createDirectories).toRealPath()
        lateinit var lastArena: TokenPairedSubject
        val protocol: TokenPairedSubject

        init {
            val node = PurchaseJson.read(base.protocol.path) as ObjectNode
            node.remove(listOf("profile", "supportProfile", "banks"))
            node
                .put("kind", "coordinated-diagnostic-protocol-1")
                .put("maximumSeconds", 14400)
                .put("maximumTerminalExecutions", 32832)
                .put("diagnosticOnly", true)
                .put("promotionAllowed", false)
            fun field(key: String, value: Any) {
                node.set<JsonNode>(key, ExpansionOnPolicyStore.wire(value))
            }
            field("heads", mapOf("CARD" to 1, "PURCHASE" to 2, "EXPANSION" to 4, "TOKEN" to 8))
            field("masks", (0..15).toList())
            field("supportProfiles", listOf("RAW", "SUPPORT1"))
            field("ecologies", listOf("HOMOGENEOUS", "MIXED"))
            field("replayWorlds", CoordinatedDiagnosticProtocol.REPLAYS)
            field(
                "interval",
                mapOf(
                    "confidence" to .95,
                    "multiplier" to 1.96,
                    "unit" to "WORLD",
                    "gates" to false,
                ),
            )
            field(
                "bank",
                mapOf(
                    "nativeOffset" to 17,
                    "count" to 512,
                    "seeds" to List(512) { StrategyArena.seed(17, it) },
                ),
            )
            val sorted =
                CpePairedDigest::class.java.getDeclaredMethod("sorted", JsonNode::class.java).also {
                    it.isAccessible = true
                }
            val path = root.resolve("protocol.json")
            Files.writeString(
                path,
                PurchaseJson.mapper.writeValueAsString(sorted.invoke(CpePairedDigest, node)) + "\n",
            )
            protocol = CoordinatedEsWire.source(path)
        }

        private fun ref(subject: TokenPairedSubject) =
            mapOf("path" to subject.path.toString(), "sha256" to subject.sha256)

        private fun mask(mask: Int): TokenPairedSubject {
            val target = root.resolve("mask-$mask.json")
            return base.save(
                target,
                mapOf(
                    "kind" to CoordinatedEsBundle.VERSION,
                    "heads" to
                        CoordinatedEsHead.entries.associate { head ->
                            val bundle =
                                if (mask and (1 shl head.ordinal) == 0)
                                    base.admission.scope.original
                                else base.admission.finalBundle
                            val subject = bundle.subject(head)
                            head.name to
                                mapOf(
                                    "path" to root.relativize(subject.path).toString(),
                                    "sha256" to subject.sha256,
                                    "dictionarySha256" to bundle.dictionaries[head]?.sha256(),
                                )
                        },
                ),
            )
        }

        fun source(
            cell: DiagnosticCell = DiagnosticCell(DiagnosticEcology.MIXED, DiagnosticSupport.RAW, 0)
        ): TokenPairedSubject {
            val originalArena =
                if (cell.ecology == DiagnosticEcology.MIXED) base.mixed else base.homogeneous
            val arena = PurchaseJson.read(originalArena.path) as ObjectNode
            arena.put("purpose", "SCREENING").put("pairsPerConfiguration", 512).put("seed", 17)
            val overlay =
                mapOf(
                    "version" to
                        if (cell.support == DiagnosticSupport.RAW) ArenaCoordinatedPolicy.VERSION
                        else ArenaCoordinatedPolicy.SUPPORTED_VERSION,
                    "execution" to "CALIBRATED",
                    "originalBundle" to ref(base.admission.scope.original.source),
                    "baseline" to
                        mapOf(
                            "path" to base.admission.scope.baseline.path.toString(),
                            "sha256" to base.admission.scope.baseline.sha256,
                        ),
                    "calibration" to ref(base.admission.calibration.source),
                    "candidate" to ref(mask(cell.pair)),
                    "incumbent" to ref(mask(15 - cell.pair)),
                ) +
                    if (cell.support == DiagnosticSupport.SUPPORT1)
                        mapOf("supportProfile" to "known-own-chaos-support-1")
                    else emptyMap()
            arena.set<JsonNode>("coordinatedEs", ExpansionOnPolicyStore.wire(overlay))
            lastArena =
                base.save(
                    base.toy.files.directory.resolve(
                        "diagnostic-${cell.ecology}-${cell.support}-${cell.pair}.json"
                    ),
                    arena,
                )
            return base.save(
                root.resolve("source-${cell.ecology}-${cell.support}-${cell.pair}.json"),
                mapOf(
                    "kind" to "coordinated-diagnostic-input-1",
                    "host" to "DESKTOP",
                    "protocol" to ref(protocol),
                    "originArena" to ref(base.originArena),
                    "originAdmission" to ref(base.admission.source),
                    "originProof" to ref(base.proof),
                    "arena" to ref(lastArena),
                    "ecology" to cell.ecology.name,
                    "support" to cell.support.name,
                    "pair" to cell.pair,
                    "globalStart" to 0,
                    "count" to 320,
                ),
            )
        }

        fun input(
            cell: DiagnosticCell = DiagnosticCell(DiagnosticEcology.MIXED, DiagnosticSupport.RAW, 0)
        ) = CoordinatedDiagnosticInput.read(source(cell).path)
    }
}
