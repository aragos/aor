package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardAdvantageInputTest {
    @Test
    fun explicitRequestsBindExactModelsAndFullFeatureDictionary() {
        CardAdvantageFixture(continuation = true).use { fixture ->
            val input = fixture.input
            val manifest = input.manifest()

            assertThat(input.environment.dictionary).isEqualTo(fixture.dictionary)
            assertThat(input.origins).containsExactly(fixture.request)
            assertThat(manifest["trajectoryEncoding"]).isEqualTo("gzip-json-v1")
            assertThat(manifest["maxTailsPerOrigin"]).isEqualTo(52)
            assertThat(
                    requireNotNull(input.continuation) { "This fixture has a continuation." }.sha256
                )
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("advantage.json")))
        }
    }

    @Test
    fun duplicateOriginWorldsAreRejectedAcrossSplits() {
        CardAdvantageFixture().use { fixture ->
            fixture.artifacts.write(
                "source.json",
                fixture.source(
                    listOf(
                        fixture.request,
                        fixture.request.copy(index = 1, split = "validation"),
                    )
                ),
            )
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun candidateRivalsRequireTheirDeclaredContinuation() {
        CardAdvantageFixture().use { fixture ->
            fixture.artifacts.write(
                "source.json",
                fixture.source(
                    listOf(
                        fixture.request.copy(
                            opponentUsesCandidate = listOf(false, true, false, false)
                        )
                    )
                ),
            )
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun controlledRoleCannotAlsoBeMarkedAsARival() {
        assertFailsWith<IllegalArgumentException> {
            CardAdvantageRequest(0, "train", 71, 0, 1, "generic", 1, List(4) { true })
        }
    }

    @Test
    fun metadataDescriptionRejectsOutputOrExecutionFlags() {
        CardAdvantageFixture().use { fixture ->
            val metadata =
                listOf(
                    "--mode",
                    "card-advantage-collect",
                    "--source",
                    fixture.source.toString(),
                    "--describe",
                    "true",
                )
            assertThat(CardAdvantageOptions.parse(metadata).describe).isTrue()
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageOptions.parse(
                    metadata + listOf("--output", fixture.directory.resolve("output").toString())
                )
            }
        }
    }

    @Test
    fun nativeOptionsRejectDuplicateFieldsAndNonfiniteAllowance() {
        CardAdvantageFixture().use { fixture ->
            val arguments =
                listOf(
                    "--mode",
                    "card-advantage-collect",
                    "--source",
                    fixture.source.toString(),
                    "--output",
                    fixture.directory.resolve("output").toString(),
                    "--index",
                    "0",
                    "--hours",
                    "NaN",
                )
            assertFailsWith<IllegalArgumentException> { CardAdvantageOptions.parse(arguments) }
            assertFailsWith<IllegalArgumentException> {
                CardAdvantageOptions.parse(arguments + listOf("--index", "1"))
            }
        }
    }

    @Test
    fun evaluationRejectsDuplicateWorldRoleArmCells() {
        CardAdvantageFixture().use { fixture ->
            val job =
                CardAdvantageEvaluationJob(
                    0,
                    71,
                    2,
                    "candidate",
                    "candidate",
                    List(4) { "baseline" },
                )
            assertFailsWith<IllegalArgumentException> {
                fixture.evaluation(listOf(job, job.copy(index = 1)))
            }
        }
    }

    @Test
    fun evaluationRejectsUndeclaredRivalModels() {
        CardAdvantageFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                fixture.evaluation(
                    listOf(
                        CardAdvantageEvaluationJob(
                            0,
                            71,
                            2,
                            "candidate",
                            "candidate",
                            List(4) { "unknown" },
                        )
                    )
                )
            }
        }
    }
}
