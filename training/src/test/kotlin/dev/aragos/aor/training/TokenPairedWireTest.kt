package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TerritoryPolicyChoice
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Cross-language boundary fixture; only constructed views/actors and invented outcome rows. */
class TokenPairedWireTest {
    @Test
    fun exportActualNativePublicAndLabelSidecarsWithoutPlayingAnyGame() {
        TokenPairedFixture().use { fixture ->
            val input = fixture.input
            val output = Path.of("build/test-fixtures/token-paired-wire").toAbsolutePath()
            require(!Files.exists(output)) {
                "Preserve an existing constructed wire fixture before regenerating it."
            }
            val store = TokenPairedStore(input, output)
            store.prepare()
            val statuses =
                listOf(
                    TokenPairedStatus.READY,
                    TokenPairedStatus.SINGLETON,
                    TokenPairedStatus.MISSING,
                )
            val receipts = statuses.mapIndexed { index, status ->
                val request = TokenPairedRequest(index)
                val rows = mutableListOf<TokenPairedDecision>()
                val controller =
                    requireNotNull(input.controllers(index, rows::add)[request.seat]) {
                        "Constructed actor is required."
                    }
                val decision =
                    fixture
                        .decision(request.seat)
                        .copy(
                            seed =
                                simulationPolicySeed(
                                    input.origins[index].gameSeed,
                                    19,
                                    request.seat,
                                )
                        )
                controller.choose(decision)
                val actual = rows.single()
                val frame =
                    if (status == TokenPairedStatus.SINGLETON) {
                        val action = actual.observation.actions[actual.observation.baselineIndex]
                        actual.copy(
                            observation =
                                actual.observation.copy(
                                    actions = listOf(action),
                                    baselineIndex = 0,
                                ),
                            currentProbabilities = listOf(1.0),
                            originalProbabilities = listOf(1.0),
                            sampled = TerritoryPolicyChoice(0, action.command, 0.0, .5),
                        )
                    } else actual
                val proposals =
                    TokenPairedProposals.sample(
                        frame.observation.actions.size,
                        frame.observation.baselineIndex,
                        input.origins[index].proposalSeed,
                    )
                val public =
                    if (status == TokenPairedStatus.MISSING) null
                    else
                        TokenPairedPublicRoot(
                            frame.number,
                            frame.batchStartNumber,
                            frame.botSeed,
                            frame.actorSeed,
                            frame.observation,
                            frame.observation.baselineIndex,
                            frame.sampled.index,
                            frame.sampled.logProbability,
                            frame.sampled.value,
                            frame.currentProbabilities,
                            frame.originalProbabilities,
                            proposals.indices,
                            proposals.inclusionProbability,
                        )
                val canonical =
                    TokenPairedCanonical(
                        fixture.state().header,
                        emptyList(),
                        CardAdvantageExecutor.hash(emptyList<String>()),
                        32,
                        true,
                    )
                fun terminal(chance: Long) =
                    TokenValueOutcome(
                        .3,
                        .25,
                        .5,
                        List(4) { PilotReplayScore("p${it + 1}", 0, 1, 0, 1, true) },
                        32,
                        chance,
                    )
                val arms =
                    if (status == TokenPairedStatus.READY)
                        proposals.indices.map { actionIndex ->
                            TokenPairedArm(
                                actionIndex,
                                frame.observation.actions[actionIndex].command,
                                if (actionIndex == frame.observation.baselineIndex) 1.0
                                else proposals.inclusionProbability,
                                input.origins[index].future.mapIndexed { replicate, chance ->
                                    TokenPairedTail(
                                        replicate,
                                        terminal(chance),
                                        emptyList(),
                                        CardAdvantageExecutor.hash(emptyList<String>()),
                                    )
                                },
                            )
                        }
                    else emptyList()
                store.save(
                    TokenPairedOrigin(
                        signature = input.signature,
                        scopeSha256 = input.scopeSha256,
                        scienceSha256 = input.scienceSha256,
                        iteration = 1,
                        request = request,
                        streams = input.origins[index],
                        status = status,
                        missingReason =
                            if (status == TokenPairedStatus.MISSING) "TARGET_WINDOW_NOT_REACHED"
                            else null,
                        observedWindows = if (status == TokenPairedStatus.MISSING) 0 else 1,
                        publicRoot = public,
                        anchors =
                            listOf(
                                TokenPairedAnchor(
                                    1,
                                    index,
                                    frame.number,
                                    frame.batchStartNumber,
                                    frame.observation,
                                    frame.originalProbabilities,
                                )
                            ),
                        prefix = canonical,
                        backbone = canonical,
                        backboneTerminal = terminal(input.origins[index].gameSeed.toLong()),
                        arms = arms,
                    )
                )
            }
            TrainingArtifacts(output)
                .write(
                    "constructed-fixture.json",
                    mapOf(
                        "kind" to "token-paired-wire-unit-1",
                        "constructedOnly" to true,
                        "newGames" to 0,
                        "fits" to 0,
                        "scientificShardComplete" to false,
                        "inventedCanonicalAndTerminalRows" to true,
                        "origins" to receipts,
                        "publicFieldBoundary" to "native exact sidecar projections",
                    ),
                )
            assertThat(receipts.map { it.status }).containsExactlyElementsIn(statuses).inOrder()
            assertThat(
                    PurchaseJson.read(output.resolve("public-0.json"))["publicRoot"]["observation"][
                            "actions"]
                        .size()
                )
                .isGreaterThan(1)
            assertThat(PurchaseJson.read(output.resolve("labels-2.json"))["arms"].isEmpty).isTrue()
        }
    }
}
