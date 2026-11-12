package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotObservations
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardStyleInteractionTest {
    @Test
    fun baselineAndEveryStyleReproduceUnchangedExpansionChoiceAtOriginalOffset() {
        CardStyleInteractionFixture().use { fixture ->
            val original = fixture.old.input
            val input = fixture.input
            val state = expansionPairedState()
            val actor = state.playerIds.first()
            val session = ExperimentSession(state, 71, recordEntries = true)
            val offered =
                session
                    .observation(actor)
                    .candidates
                    .first { it.command.startsWith("place ") }
                    .command
            session.apply(mapOf(actor to offered))
            val observations = session.actors().associateWith(session::observation)
            val expected =
                FrozenPolicyContinuation(
                        List(4) { original.arena.candidate },
                        original.controllers(0),
                        input.origins[0].gameSeed,
                        Long.MAX_VALUE,
                    )
                    .commands(session, observations)
            val collector = CardStyleInteractionCollector(input, Long.MAX_VALUE)

            val actual =
                CardStyleInteractionStyle.entries.map { style ->
                    val fork = ExperimentSession(session.state, 71, recordEntries = true)
                    collector
                        .normal(style, input.origins[0], 0, session.decisions)
                        .commands(fork, observations)
                }

            assertThat(actual).containsExactlyElementsIn(List(5) { expected })
        }
    }

    @Test
    fun persistentVariantsChangeOnlyControlledCardSeatAndKeepAllRivalsExact() {
        CardStyleInteractionFixture().use { fixture ->
            val input = fixture.input
            val state = jointStateFixture()
            val actor = PlayerId("p1")
            val decision =
                SimulationDecision(
                    7,
                    0,
                    71,
                    state.viewForPlayer(actor),
                    BotObservations.from(state, actor),
                )
            val normal = input.controllers(CardStyleInteractionStyle.G3, 0)
            val variant = input.controllers(CardStyleInteractionStyle.D1_PLUS, 0)
            fun commands(controllers: Map<Int, SimulationController>, seat: Int) =
                (0..127).map { seed ->
                    requireNotNull(controllers[seat]) { "Every seat controller is required." }
                        .choose(decision.copy(seed = seed))
                }

            val changed = commands(variant, 0)

            assertThat(changed).isNotEqualTo(commands(normal, 0))
            for (seat in 1..3) {
                assertThat(commands(variant, seat)).isEqualTo(commands(normal, seat))
            }
        }
    }

    @Test
    fun alteredRivalBundleIsRejectedAtTheInputBoundary() {
        CardStyleInteractionFixture().use { fixture ->
            val node =
                PurchaseJson.read(fixture.files.directory.resolve("D1_PLUS-arena.json"))
                    as ObjectNode
            (node["jointPolicy"] as ObjectNode).put("sharedBundle", "D1_PLUS-bundle.json")
            fixture.files.write("D1_PLUS-arena.json", node)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun calibrationCannotClaimADifferentHeadSubject() {
        CardStyleInteractionFixture().use { fixture ->
            val node =
                PurchaseJson.read(fixture.files.directory.resolve("calibration.json")) as ObjectNode
            (node["styles"]["D1_PLUS"]["card"] as ObjectNode).put("modelSha256", "0".repeat(64))
            fixture.files.write("calibration.json", node)
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            (source["calibration"] as ObjectNode).put(
                "sha256",
                PurchaseJson.hash(fixture.files.directory.resolve("calibration.json")),
            )
            fixture.files.write("continuation-source.json", source)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun preparationRetainsFiveExactRolesWithoutCreatingOrigins() {
        CardStyleInteractionFixture().use { fixture ->
            CardStyleInteractionMain.main(
                arrayOf(
                    "--mode",
                    "prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    fixture.old.base.output.toString(),
                )
            )
            val prepared = PurchaseJson.read(fixture.old.base.output.resolve("prepared.json"))

            assertThat(prepared["maximumTerminalGames"].intValue()).isEqualTo(21504)
            assertThat(prepared["policyBindings"].size()).isEqualTo(5)
            Files.list(fixture.old.base.output).use { paths ->
                assertThat(paths.noneMatch { it.fileName.toString().startsWith("origin-") })
                    .isTrue()
            }
        }
    }

    @Test
    fun missingWireReceiptRoundTripsNegativeLongStreamsAndCannotCompletePartialBank() {
        CardStyleInteractionFixture().use { fixture ->
            val input = fixture.input
            val store = CardStyleInteractionStore(input, fixture.old.base.output)
            val origin =
                CardStyleInteractionOrigin(
                    signature = input.signature,
                    request = CardStyleInteractionRequest(0),
                    streams = input.origins[0],
                    status = ExpansionOriginStatus.MISSING,
                    missingReason = CardStyleInteractionMissing.CARD_UNAVAILABLE,
                    firstExpansionTurn = 2,
                    observedWindows = 1,
                    prefix =
                        ExpansionPairedPrefix(
                            expansionPairedState().header,
                            emptyList(),
                            CardAdvantageExecutor.hash(emptyList<String>()),
                            7,
                            true,
                        ),
                    actorInput = null,
                    incumbentCommand = null,
                    exactIncumbentVerified = false,
                    continuations = emptyList(),
                )

            val receipt = store.save(origin)

            assertThat(receipt.tailGames).isEqualTo(0)
            assertFailsWith<IllegalArgumentException> { store.finish(listOf(receipt)) }
        }
    }
}
