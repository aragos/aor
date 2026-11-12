package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionPairedInputTest {
    @Test
    fun completeHomogeneousRolesKeepSeparatePriorAndBothHeads() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            val roles = PurchaseJson.mapper.valueToTree<JsonNode>(input.arena.overlayMetadata())

            assertThat(input.controllers(0).keys).containsExactly(0, 1, 2, 3)
            assertThat(roles["jointPolicy"]["sharedBundle"]["card"].isNull).isFalse()
            assertThat(roles["jointPolicy"]["sharedBundle"]["purchase"].isNull).isFalse()
            assertThat(roles["jointPolicy"]["baseline"]["sha256"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.files.directory.resolve("baseline.json")))
            assertThat(input.origins).hasSize(1024)
        }
    }

    @Test
    fun aRawCardRivalCannotMasqueradeAsHomogeneousJointEcology() {
        ExpansionPairedFixture().use { fixture ->
            fixture.editArena {
                (this["jointPolicy"] as ObjectNode).put("sharedBundle", "rival.json")
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun differentTokenSelectorsAreRejected() {
        ExpansionPairedFixture().use { fixture ->
            fixture.editArena {
                (this["tokenOrder"] as ObjectNode).put("incumbentSelector", "ORDINARY")
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun proposalStreamsCannotOverlapAnotherOriginsGameSeed() {
        ExpansionPairedFixture().use { fixture ->
            val node = PurchaseJson.read(fixture.source) as ObjectNode
            (node["origins"][0] as ObjectNode).put("proposalSeed", 1L)
            fixture.files.write("expansion-source.json", node)

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun aChangedSourceCannotBeSnapshottedUnderTheOriginalIdentity() {
        ExpansionPairedFixture().use { fixture ->
            val input = fixture.input
            fixture.files.write("expansion-source.json", mapOf("changed" to true))

            assertFailsWith<IllegalArgumentException> {
                input.snapshot(TrainingArtifacts(fixture.base.output))
            }
        }
    }

    @Test
    fun preparationEmitsExactScheduleAndDictionaryWithoutOrigins() {
        ExpansionPairedFixture().use { fixture ->
            ExpansionPairedMain.main(
                arrayOf(
                    "--mode",
                    "prepare",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    fixture.base.output.toString(),
                )
            )
            val prepared = PurchaseJson.read(fixture.base.output.resolve("prepared.json"))

            assertThat(prepared["maximumTerminalGames"].intValue()).isEqualTo(17408)
            assertThat(prepared["requests"].size()).isEqualTo(1024)
            assertThat(prepared["fits"].intValue()).isEqualTo(0)
            assertThat(prepared["dictionarySha256"].asText())
                .isEqualTo(PurchaseJson.hash(fixture.base.output.resolve("dictionary.json")))
            Files.list(fixture.base.output).use { paths ->
                assertThat(paths.noneMatch { it.fileName.toString().startsWith("origin-") })
                    .isTrue()
            }
        }
    }

    @Test
    fun preparationCannotOverwriteAnExistingOutput() {
        ExpansionPairedFixture().use { fixture ->
            Files.createDirectory(fixture.base.output)

            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                ExpansionPairedMain.main(
                    arrayOf(
                        "--mode",
                        "prepare",
                        "--source",
                        fixture.source.toString(),
                        "--output",
                        fixture.base.output.toString(),
                    )
                )
            }
        }
    }
}
