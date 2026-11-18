package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotConfiguration
import dev.aragos.aor.ai.BotObservation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionImitationInputTest {
    @Test
    fun observationRoundTripPreservesExactCandidateInputs() {
        ArenaPurchaseFixture().use { fixture ->
            val observation = observation(fixture)

            val parsed =
                ExpansionImitationInput.observation(PurchaseJson.mapper.valueToTree(observation))

            assertThat(parsed).isEqualTo(observation)
        }
    }

    @Test
    fun authorityOrTerminalFieldsAreRejectedAtObservationBoundary() {
        ArenaPurchaseFixture().use { fixture ->
            val node = PurchaseJson.mapper.valueToTree<ObjectNode>(observation(fixture))
            node.put("terminalScore", 5000)

            assertFailsWith<IllegalArgumentException> { ExpansionImitationInput.observation(node) }
        }
    }

    @Test
    fun sourceRejectsFutureOutcomeFieldsInSanitizedRows() {
        ArenaPurchaseFixture().use { fixture ->
            val source = source(fixture, leaked = true)

            assertFailsWith<IllegalArgumentException> { ExpansionImitationInput.read(source) }
        }
    }

    @Test
    fun sourceRejectsChangedDatasetBytes() {
        ArenaPurchaseFixture().use { fixture ->
            val source = source(fixture)
            Files.writeString(fixture.directory.resolve("human.jsonl"), "changed")

            val failure =
                assertFailsWith<IllegalArgumentException> { ExpansionImitationInput.read(source) }

            assertThat(failure).hasMessageThat().contains("frozen byte hashes")
        }
    }

    @Test
    fun fixedFitsFreezeSelectionBeforeDescriptiveAudit() {
        ArenaPurchaseFixture().use { fixture ->
            val source = source(fixture)
            val output = fixture.directory.resolve("fit")

            ExpansionImitationMain.run(
                listOf(
                    "--mode",
                    "expansion-imitation-fit",
                    "--source",
                    source.toString(),
                    "--output",
                    output.toString(),
                )
            )

            val selection = PurchaseJson.read(output.resolve("selection.json"))
            val audit = PurchaseJson.read(output.resolve("audit.json"))
            val run = PurchaseJson.read(output.resolve("run.json"))
            assertThat(selection["candidates"].size()).isEqualTo(3)
            assertThat(selection["auditConsulted"].booleanValue()).isFalse()
            assertThat(audit["selectionSha256"].asText())
                .isEqualTo(PurchaseJson.hash(output.resolve("selection.json")))
            assertThat(audit["descriptiveOnly"].booleanValue()).isTrue()
            assertThat(run["trainEntries"].map(JsonNode::intValue))
                .containsExactlyElementsIn(0 until 30)
                .inOrder()
            assertThat(run["validationEntries"].map(JsonNode::intValue))
                .containsExactlyElementsIn(30 until 40)
                .inOrder()
            assertThat(run["auditEntries"].map(JsonNode::intValue))
                .containsExactlyElementsIn(40 until 50)
                .inOrder()
            assertThat(PurchaseJson.read(output.resolve("finished.json"))["phase"].asText())
                .isEqualTo("complete")
        }
    }

    @Test
    fun existingOutputCannotRenewTheAttempt() {
        ArenaPurchaseFixture().use { fixture ->
            val source = source(fixture)
            val output = Files.createDirectory(fixture.directory.resolve("fit"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    ExpansionImitationMain.run(
                        listOf(
                            "--mode",
                            "expansion-imitation-fit",
                            "--source",
                            source.toString(),
                            "--output",
                            output.toString(),
                        )
                    )
                }

            assertThat(failure).hasMessageThat().contains("no adaptive retries")
        }
    }

    private fun source(fixture: ArenaPurchaseFixture, leaked: Boolean = false): java.nio.file.Path {
        val expansion = observation(fixture)
        for (entry in 0 until 186) {
            val observation =
                if (entry < 50) expansion else expansion.copy(decision = "fixture-other")
            fixture.artifacts.append(
                "human.jsonl",
                mapOf(
                    "gameId" to "fixture",
                    "sourceSha256" to "a".repeat(64),
                    "entry" to entry,
                    "turn" to 1,
                    "epoch" to "ONE",
                    "decision" to observation.decision,
                    "selectedCommand" to "place a 1",
                    "baselineSeed" to 0,
                    "ordinaryBaselineCommand" to "place a 1",
                    "observation" to observation,
                ) + if (leaked) mapOf("terminalScores" to listOf(1000, 0, 0, 0)) else emptyMap(),
            )
        }
        fixture.artifacts.write(
            "imitation-source.json",
            mapOf(
                "schemaVersion" to 1,
                "observations" to "human.jsonl",
                "observationsSha256" to PurchaseJson.hash(fixture.directory.resolve("human.jsonl")),
                "baseline" to "baseline.json",
                "baselineSha256" to PurchaseJson.hash(fixture.directory.resolve("baseline.json")),
                "gameId" to "fixture",
                "gameSourceSha256" to "a".repeat(64),
            ),
        )
        return fixture.directory.resolve("imitation-source.json")
    }

    private fun observation(fixture: ArenaPurchaseFixture) =
        BotObservation(
            playerId = "p1",
            decision = "expand",
            context = emptyMap(),
            availableCash = 100,
            availableTokens = 16,
            candidates =
                listOf(
                    BotCandidate(
                        "place a 1",
                        mapOf("income" to 5.0, "tokens" to -1.0, "action.expansion" to 1.0),
                        family = "expansion",
                    ),
                    BotCandidate("pass", mapOf("action.pass" to 1.0), family = "pass"),
                ),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            configuration =
                BotConfiguration(
                    4,
                    fixture.configuration.rules.map,
                    fixture.configuration.rules.initialCard,
                    true,
                    true,
                ),
        )
}
