package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardProposals
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardContinuationDiagnosticTest {
    @Test
    fun rootIsCapturedUnderFDespiteAnOwnTailThatWouldChoosePass() {
        val root =
            requireNotNull(collected.origin.root) { "The diagnostic fixture reaches its root." }
        val priorityChoice =
            CardAdvantagePolicy.choose(
                root.observation,
                collected.input.priority.model,
                root.botSeed,
            )

        assertThat(priorityChoice.index).isNotEqualTo(root.referenceIndex)
        assertThat(priorityChoice.command).isEqualTo("pass")
        assertThat(root.proposalIndices)
            .isEqualTo(CardProposals.indices(root.observation, root.botSeed))
        assertThat(root.referenceIndex).isEqualTo(root.observation.baselineIndex)
        assertThat(root.botSeed)
            .isEqualTo(
                CardAdvantageExecutor.policySeed(
                    collected.origin.seed,
                    root.number,
                    collected.origin.seat,
                )
            )
    }

    @Test
    fun rootAndBackboneExactlyMatchTheSchemaTwoFCapture() {
        val root = requireNotNull(collected.origin.root) { "The fixture has a root." }
        val captured = requireNotNull(collected.capture.root) { "F reaches its prospective root." }
        val executor =
            CardAdvantageExecutor(
                collected.input.backboneInput,
                collected.input.origins.single(),
                Long.MAX_VALUE,
            )
        val decision = executor.decision(captured.state, captured.actor, captured.number)
        val safe = CardAdvantageController.observation(decision, captured.chosenCommand)

        assertThat(root.observation).isEqualTo(safe)
        assertThat(root.number).isEqualTo(captured.number)
        assertThat(collected.origin.backboneDecisions)
            .isEqualTo(collected.capture.backboneDecisions)
        assertThat(collected.origin.backboneCanonicalSha256)
            .isEqualTo(collected.capture.backboneCanonicalSha256)
    }

    @Test
    fun allFourArmsRetainTheSameFourChanceStartsAndDistinctOutcomes() {
        val origin = collected.origin
        val root = requireNotNull(origin.root) { "The fixture has a root." }

        assertThat(root.arms.map { it.id }).isEqualTo(CardContinuationArm.IDS)
        assertThat(origin.outcomes).hasSize(16)
        root.arms.forEach { arm ->
            assertThat(arm.actionIndex)
                .isEqualTo(
                    if (arm.id.startsWith("a-")) root.alternativeIndex else root.referenceIndex
                )
            arm.outcomeIds.forEachIndexed { replicate, id ->
                val outcome =
                    requireNotNull(origin.outcomes[id]) { "Every arm has its complete tail." }
                assertThat(outcome.chanceSeed)
                    .isEqualTo(CardAdvantageExecutor.chanceSeed(origin.seed, replicate))
                assertThat(outcome.gameDecisions).isGreaterThan(root.number)
            }
        }
    }

    @Test
    fun cachedArmCannotChangeItsContinuationIdentity() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val arm = altered.required("root").required("arms")[0] as ObjectNode
        arm.put("continuation", "priority64")

        assertFailsWith<IllegalArgumentException> {
            CardContinuationArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedOutcomeCannotChangeItsPairedFutureStream() {
        val altered = collected.document.deepCopy<ObjectNode>()
        val outcome = altered.required("outcomes").first() as ObjectNode
        outcome.put("chanceSeed", outcome.required("chanceSeed").longValue() + 1)

        assertFailsWith<IllegalArgumentException> {
            CardContinuationArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun cachedRootCannotRestartItsAbsolutePolicySeed() {
        val altered = collected.document.deepCopy<ObjectNode>()
        (altered.required("root") as ObjectNode).put("botSeed", 0)

        assertFailsWith<IllegalArgumentException> {
            CardContinuationArtifacts.validate(altered, collected.input, 0)
        }
    }

    @Test
    fun aTrivialRootAliasesOnlyCorrespondingActionArms() {
        val origin = collected.origin
        val original = requireNotNull(origin.root) { "The fixture has a root." }
        val pass = original.observation.actions.single { it.command == "pass" }
        val observation = original.observation.copy(actions = listOf(pass), baselineIndex = 0)
        val arms =
            CardContinuationArm.IDS.map { id ->
                CardContinuationArm(
                    id,
                    0,
                    CardContinuationArm.tail(id),
                    CardContinuationArm.outcomeIds(id, null),
                )
            }
        val root =
            original.copy(
                observation = observation,
                proposalIndices = listOf(0),
                referenceIndex = 0,
                acquisition = CardAdvantageAcquisition.select(listOf(0), 0, original.botSeed, true),
                alternativeIndex = null,
                arms = arms,
            )
        val referenced = arms.flatMap { it.outcomeIds }.toSet()
        val document =
            PurchaseJson.mapper.valueToTree<JsonNode>(
                origin.copy(
                    root = root,
                    outcomes = origin.outcomes.filterKeys { it in referenced },
                )
            )

        CardContinuationArtifacts.validate(document, collected.input, 0)
        assertThat(document.required("outcomes").size()).isEqualTo(8)
        assertThat(arms[0].outcomeIds).isEqualTo(arms[1].outcomeIds)
        assertThat(arms[2].outcomeIds).isEqualTo(arms[3].outcomeIds)
        assertThat(arms[0].outcomeIds).containsNoneIn(arms[2].outcomeIds)
    }

    @Test
    fun declaredDiagnosticCannotControlRivalCards() {
        val input = collected.input
        assertFailsWith<IllegalArgumentException> {
            input.copy(
                origins =
                    listOf(
                        input.origins
                            .single()
                            .copy(opponentUsesCandidate = listOf(false, true, false, false))
                    )
            )
        }
    }

    @Test
    fun diagnosticManifestBindsTheTailSeparatelyFromTheFBackbone() {
        val input = collected.input
        val manifest = input.manifest()

        assertThat(manifest["kind"]).isEqualTo("card-continuation-diagnostic")
        assertThat(manifest["schemaVersion"]).isEqualTo(3)
        assertThat(manifest["backbonePolicy"]).isEqualTo("F")
        assertThat(manifest["opponentPolicy"]).isEqualTo("F")
        assertThat(manifest["replicates"]).isEqualTo(4)
        assertThat(manifest["maxTailsPerOrigin"]).isEqualTo(16)
        assertThat(manifest["continuation"])
            .isEqualTo(
                mapOf(
                    "path" to input.priority.path.toString(),
                    "sha256" to input.priority.sha256,
                )
            )
        assertThat(input.backboneInput.continuation).isNull()
    }

    @Test
    fun diagnosticCannotBeReadAsAnOldTrainingCollection() {
        CardAdvantageFixture(preferPass = true).use { fixture ->
            val input = source(fixture)

            assertFailsWith<IllegalArgumentException> {
                CardAdvantageCollectionInput.read(input.source)
            }
        }
    }

    @Test
    fun changingOnlyTheOwnTailModelChangesTheDiagnosticSignature() {
        val input = collected.input
        val changed = input.copy(priority = input.priority.copy(sha256 = "0".repeat(64)))

        assertThat(changed.signature()).isNotEqualTo(input.signature())
        assertThat(changed.backboneInput.signature()).isEqualTo(input.backboneInput.signature())
    }

    @Test
    fun completedDiagnosticBatchResumeDoesNotRewriteItsOrigin() {
        CardAdvantageFixture(preferPass = true).use { fixture ->
            val input = source(fixture)
            val original = collected.origin.copy(signature = input.signature())
            val directory = fixture.directory.resolve("out")
            Files.createDirectories(directory)
            val path = directory.resolve("origin-0.json.gz")
            CardTrajectoryFiles.save(path, PurchaseJson.mapper.valueToTree<JsonNode>(original))
            val bytes = Files.readAllBytes(path)
            val time = Files.getLastModifiedTime(path)

            CardAdvantageMain.run(
                listOf(
                    "--mode",
                    "card-advantage-collect",
                    "--source",
                    input.source.toString(),
                    "--output",
                    directory.toString(),
                    "--indices",
                    "0",
                    "--hours",
                    ".05",
                )
            )

            assertThat(Files.readAllBytes(path)).isEqualTo(bytes)
            assertThat(Files.getLastModifiedTime(path)).isEqualTo(time)
        }
    }

    private data class Collected(
        val input: CardContinuationDiagnosticInput,
        val origin: CardContinuationOrigin,
        val document: JsonNode,
        val capture: CardAdvantageCapture,
    )

    companion object {
        private val collected by lazy {
            CardAdvantageFixture(preferPass = true).use { fixture ->
                val input = source(fixture)
                val origin = CardContinuationCollector(input, Long.MAX_VALUE).collect(0)
                val document = PurchaseJson.mapper.valueToTree<JsonNode>(origin)
                CardContinuationArtifacts.validate(document, input, 0)
                val executor =
                    CardAdvantageExecutor(
                        input.backboneInput,
                        input.origins.single(),
                        Long.MAX_VALUE,
                    )
                val capture =
                    CardAdvantageRootCapture(input.backboneInput, Long.MAX_VALUE)
                        .capture(0, executor)
                Collected(input, origin, document, capture)
            }
        }

        private fun source(fixture: CardAdvantageFixture): CardContinuationDiagnosticInput {
            fixture.artifacts.write(
                "source.json",
                fixture.source(listOf(fixture.request), true) +
                    mapOf(
                        "schemaVersion" to 3,
                        "collection" to
                            mapOf("kind" to "own-continuation-crossover-1", "replicates" to 4),
                    ),
            )
            return CardContinuationDiagnosticInput.read(fixture.source)
        }
    }
}
