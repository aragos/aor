package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryAction
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import java.nio.file.Files
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CpePairedStoreTest {
    @Test
    fun preparationUsesOnlyDeclaredModelsAndPublishesAllCompleteHeadSubjects() {
        CpePairedFixture().use { fixture ->
            val output = fixture.prior.output
            CpePairedMain.run(
                listOf(
                    "--mode",
                    "cpe-paired-prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    output.toString(),
                )
            )
            val prepared = PurchaseJson.read(output.resolve("prepared.json"))
            assertThat(prepared["head"].asText()).isEqualTo("EXPANSION")
            assertThat(prepared["dictionaries"].size()).isEqualTo(3)
            assertThat(prepared["currentBundle"]["heads"].size()).isEqualTo(3)
            assertThat(prepared["fits"].intValue()).isEqualTo(0)
            assertThat(Files.exists(output.resolve("started.json"))).isFalse()
            assertThat(Files.exists(output.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun publicAndLabelSidecarsNeverExposeEachOthersPayload() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val store = CpePairedStore(input, fixture.prior.output)
            store.prepare()
            val receipt = store.save(origin(input, 0, ready = true))
            val public = PurchaseJson.read(store.directory.resolve(receipt.public.file))
            val labels = PurchaseJson.read(store.directory.resolve(receipt.labels.file))
            assertThat(public.has("arms")).isFalse()
            assertThat(public.has("backbone")).isFalse()
            assertThat(public.has("backboneTerminal")).isFalse()
            assertThat(public["publicRoot"]["observation"].properties().map { it.key }.toSet())
                .containsExactly("encoderVersion", "stateFeatures", "actions", "baselineIndex")
            assertThat(labels.has("publicRoot")).isFalse()
            assertThat(labels.has("anchors")).isFalse()
            assertThat(labels["arms"][0]["outcomes"][0].has("canonicalEntries")).isFalse()
            assertThat(public["originSha256"]).isEqualTo(labels["originSha256"])
        }
    }

    @Test
    fun completedShardRequiresAllOriginalIndicesAndRechecksEveryReceipt() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val store = CpePairedStore(input, fixture.prior.output)
            store.prepare()
            val receipts =
                input.range.indices.map { store.save(origin(input, it, ready = it == 0)) }
            store.finish(receipts)
            val finished = PurchaseJson.read(store.directory.resolve("finished.json"))
            assertThat(finished["head"].asText()).isEqualTo("EXPANSION")
            assertThat(finished["backboneGames"].intValue()).isEqualTo(64)
            assertThat(finished["readyRoots"].intValue()).isEqualTo(1)
            assertThat(finished["scientificTerminalGames"].intValue()).isEqualTo(68)
            assertThat(store.readCompleted(0).second).isEqualTo(receipts.first().origin)
        }
    }

    @Test
    fun noPartialOrDuplicateCohortCanPublishCompletion() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val store = CpePairedStore(input, fixture.prior.output)
            store.prepare()
            val receipt = store.save(origin(input, 0, ready = false))
            assertFailsWith<IllegalArgumentException> { store.finish(List(64) { receipt }) }
            assertThat(Files.exists(store.directory.resolve("finished.json"))).isFalse()
        }
    }

    @Test
    fun changedActualReferenceCannotBeRelabeledAsThePrior() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val wire = wire(origin(input, 0, ready = true))
            (wire["publicRoot"] as ObjectNode).put("sampledCommandIndex", 1)
            assertFailsWith<IllegalArgumentException> { CpePairedEvidence(input).validate(wire, 0) }
        }
    }

    @Test
    fun terminalRewardMustBelongToTheControlledSeat() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val wire = wire(origin(input, 1, ready = false))
            (wire["backboneTerminal"] as ObjectNode).put("reward", 0.0)
            assertFailsWith<IllegalArgumentException> { CpePairedEvidence(input).validate(wire, 1) }
        }
    }

    @Test
    fun reproductionMayOmitOnlyTailsAndCannotReselectTheReservoirRoot() {
        CpePairedFixture().use { fixture ->
            val input = fixture.input
            val expected = origin(input, 0, ready = true)
            val replay = wire(expected.copy(arms = emptyList()))
            CpePairedEvidence(input).validate(replay, 0, reproduction = true)
            TokenPairedMain.verifyReproduction(wire(expected), replay)
            replay.put("selectedOrdinal", 2)
            assertFailsWith<IllegalArgumentException> {
                TokenPairedMain.verifyReproduction(wire(expected), replay)
            }
        }
    }

    private fun origin(input: CpePairedInput, index: Int, ready: Boolean): CpePairedOrigin {
        val header = expansionPairedState().header
        val hash = CardAdvantageExecutor.hash(emptyList<String>())
        val backbone = TokenPairedCanonical(header, emptyList(), hash, 1, true)
        val observation =
            CpePairedObservation.Expansion(
                TerritoryObservation(
                    TerritoryEncoder.EXPANSION_2.version,
                    emptyMap(),
                    listOf(
                        TerritoryAction("expand-card", emptyMap()),
                        TerritoryAction("pass", emptyMap()),
                    ),
                    0,
                )
            )
        val botSeed =
            simulationPolicySeed(input.origins[index].gameSeed, 0, input.request(index).seat)
        val frame =
            CpePairedDecision(
                CpeHead.EXPANSION,
                0,
                0,
                botSeed,
                TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, botSeed),
                observation,
                0,
                ln(.97),
                .5,
                listOf(.97, .03),
                listOf(.97, .03),
            )
        val public =
            if (ready) CpePairedPublicRoot.from(frame, input.origins[index].proposalSeed) else null
        fun terminal(seed: Long) =
            TokenValueOutcome(
                1.0,
                1.0,
                1.0,
                List(4) { seat ->
                    PilotReplayScore(
                        "p${seat + 1}",
                        0,
                        if (seat == input.request(index).seat) 10 else 0,
                        0,
                        if (seat == input.request(index).seat) 10 else 0,
                        seat == input.request(index).seat,
                    )
                },
                1,
                seed,
            )
        return CpePairedOrigin(
            signature = input.signature,
            bundleScopeSha256 = input.bundleScopeSha256,
            scopeSha256 = input.scopeSha256,
            scienceSha256 = input.scienceSha256,
            iteration = input.iteration,
            request = input.request(index),
            streams = input.origins[index],
            status = if (ready) CpePairedStatus.READY else CpePairedStatus.MISSING,
            missingReason = if (ready) null else "NO_BRANCHING_WINDOW_IN_ASSIGNED_EPOCH",
            observedWindows = 1,
            branchingWindows = if (ready) 1 else 0,
            selectedOrdinal = if (ready) 1 else null,
            publicRoot = public,
            anchors =
                if (ready)
                    listOf(
                        CpePairedAnchor(
                            1,
                            CpeHead.EXPANSION,
                            index,
                            0,
                            0,
                            observation,
                            listOf(.97, .03),
                        )
                    )
                else emptyList(),
            prefix = if (ready) backbone.copy(playerDecisions = 0, terminal = false) else backbone,
            backbone = backbone,
            backboneTerminal = terminal(input.origins[index].gameSeed.toLong()),
            arms =
                public?.proposedIndices?.map { action ->
                    TokenPairedArm(
                        action,
                        observation.commands[action],
                        1.0,
                        input.origins[index].future.mapIndexed { replicate, seed ->
                            TokenPairedTail(replicate, terminal(seed), emptyList(), hash)
                        },
                    )
                } ?: emptyList(),
        )
    }

    private fun wire(value: Any): ObjectNode =
        PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value)) as ObjectNode
}
