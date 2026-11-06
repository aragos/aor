package dev.aragos.aor.training

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrategyArenaTest {
    @Test
    fun sourceRejectsFractionalIntegerFieldsInsteadOfTruncatingThem() {
        for (field in listOf("schemaVersion", "seed", "pairsPerConfiguration")) {
            val failure = assertFailsWith<IllegalArgumentException> { readSource(field to "1.5") }

            assertThat(failure).hasMessageThat().contains("$field must be a JSON integer")
        }
    }

    @Test
    fun sourceRejectsTextualIntegerFieldsInsteadOfSilentlyReadingZero() {
        for (field in listOf("schemaVersion", "seed", "pairsPerConfiguration")) {
            val failure = assertFailsWith<IllegalArgumentException> { readSource(field to "\"1\"") }

            assertThat(failure).hasMessageThat().contains("$field must be a JSON integer")
        }
    }

    @Test
    fun sourceRejectsOverflowingIntegerFieldsInsteadOfWrappingThem() {
        for (field in listOf("schemaVersion", "seed", "pairsPerConfiguration")) {
            for (value in listOf("4294967297", "-4294967295", "18446744073709551616")) {
                val failure =
                    assertFailsWith<IllegalArgumentException> { readSource(field to value) }

                assertThat(failure).hasMessageThat().contains("signed 32-bit range")
            }
        }
    }

    @Test
    fun sourceRetainsDomainBoundsForRepresentableIntegers() {
        for ((field, value) in
            listOf(
                "schemaVersion" to "0",
                "schemaVersion" to "2",
                "seed" to "-1",
                "seed" to "100000001",
                "pairsPerConfiguration" to "0",
                "pairsPerConfiguration" to "10001",
            )) {
            assertFailsWith<IllegalArgumentException> { readSource(field to value) }
        }
    }

    @Test
    fun sourcePreservesValidIntegerBoundaryValues() {
        val minimum = readSource("seed" to "0", "pairsPerConfiguration" to "1")
        val maximum = readSource("seed" to "100000000", "pairsPerConfiguration" to "10000")

        assertThat(minimum.seed).isEqualTo(0)
        assertThat(minimum.pairsPerConfiguration).isEqualTo(1)
        assertThat(maximum.seed).isEqualTo(100000000)
        assertThat(maximum.pairsPerConfiguration).isEqualTo(10000)
    }

    @Test
    fun identicalModelsHaveIdenticalTerminalResultsOnBothSidesOfAPair() {
        val specification =
            ArenaSpecification(
                purpose = ArenaPurpose.SCREENING,
                configurations = listOf(TrainingMatrix.stratified.first()),
                candidate = BotModels.default,
                incumbent = BotModels.default,
                opponents = listOf(BotModels.default),
                pairsPerConfiguration = 1,
                seed = 71,
            )

        val pair = StrategyArena.play(specification, 0, Long.MAX_VALUE)

        assertThat(pair.candidate.scores).isEqualTo(pair.incumbent.scores)
        assertThat(pair.candidate.wins).isEqualTo(pair.incumbent.wins)
        assertThat(pair.candidate.rewards).isEqualTo(pair.incumbent.rewards)
        assertThat(pair.candidate.decisions).isEqualTo(pair.incumbent.decisions)
    }

    @Test
    fun arenaSeedsStayDistinctAndSeparateFromEarlierPilotRanges() {
        val seeds = (0 until 100000).map { StrategyArena.seed(100_000_000, it) }

        assertThat(seeds.distinct()).hasSize(100000)
        assertThat(seeds.all { it in -1_000_000_000..-1 }).isTrue()
        assertThat(seeds.none { it == diagnosticSeed(100_000_000, 9999, 255) }).isTrue()
    }

    @Test
    fun aggregateUsesTheRecordedControlledSeatForPairedWinDifferences() {
        val mapper = ObjectMapper()
        val pairs =
            listOf(
                mapper.readTree(
                    """{"seat":1,"candidate":{"wins":[0,1],"rewards":[0,1],"ranks":[0,1]},"incumbent":{"wins":[1,0],"rewards":[1,0],"ranks":[1,0]}}"""
                ),
                mapper.readTree(
                    """{"seat":0,"candidate":{"wins":[0,1],"rewards":[0,1],"ranks":[0,1]},"incumbent":{"wins":[0,1],"rewards":[0,1],"ranks":[0,1]}}"""
                ),
            )

        val report = StrategyArena.report(pairs)

        assertThat(report["candidateWinShare"]).isEqualTo(0.5)
        assertThat(report["incumbentWinShare"]).isEqualTo(0.0)
        assertThat((report["winShareDifference"] as PilotInterval).mean).isEqualTo(0.5)
        assertThat(report["gains"]).isEqualTo(1)
        assertThat(report["losses"]).isEqualTo(0)
    }

    @Test
    fun unsupportedArenaSeedIndicesAreRejected() {
        assertFailsWith<IllegalArgumentException> { StrategyArena.seed(0, 100000) }
    }

    @Test
    fun fourSeatsSeeEveryFourOpponentLineupInSixteenPairs() {
        assertCompleteLineups(opponentCount = 4)
    }

    @Test
    fun fourSeatsSeeEveryFiveOpponentLineupInTwentyPairs() {
        assertCompleteLineups(opponentCount = 5)
    }

    @Test
    fun freshArenaRejectsOrphanedPairsWithoutAnIdentityCheckpoint() {
        val directory = Files.createTempDirectory("aor-arena-orphaned-pair-")
        try {
            TrainingArtifacts(directory).write("pair-0.json", mapOf("index" to 0))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    StrategyArenaMain.requireFreshOutput(directory)
                }

            assertThat(failure).hasMessageThat().contains("artifacts from another run")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun freshArenaAllowsOnlyItsSupervisorFilesBeforeInitialization() {
        val directory = Files.createTempDirectory("aor-arena-supervisor-")
        try {
            val artifacts = TrainingArtifacts(directory)
            artifacts.write("supervisor.json", emptyMap<String, Any>())
            Files.writeString(directory.resolve(".supervisor.lock"), "")
            Files.writeString(directory.resolve("trainer.log"), "")

            StrategyArenaMain.requireFreshOutput(directory)

            assertThat(Files.exists(directory.resolve("state.json"))).isFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun readSource(vararg fields: Pair<String, String>): ArenaSpecification {
        val values =
            mapOf("schemaVersion" to "1", "seed" to "71", "pairsPerConfiguration" to "4") +
                fields.toMap()
        val source = Files.createTempFile("aor-arena-integers-", ".json")
        try {
            Files.writeString(
                source,
                """
                {
                  "schemaVersion": ${values["schemaVersion"]},
                  "purpose": "SCREENING",
                  "configurations": ["${TrainingMatrix.stratified.first().id}"],
                  "candidate": null,
                  "incumbent": null,
                  "opponents": [null],
                  "pairsPerConfiguration": ${values["pairsPerConfiguration"]},
                  "seed": ${values["seed"]}
                }
                """
                    .trimIndent(),
            )
            return ArenaSpecification.read(source)
        } finally {
            Files.deleteIfExists(source)
        }
    }

    private fun assertCompleteLineups(opponentCount: Int) {
        val playerCount = 4
        val specification =
            ArenaSpecification(
                purpose = ArenaPurpose.SCREENING,
                configurations =
                    listOf(TrainingMatrix.stratified.first { it.playerCount == playerCount }),
                candidate = BotModels.default,
                incumbent = BotModels.default,
                opponents = List(opponentCount) { BotModels.default },
                pairsPerConfiguration = playerCount * opponentCount,
                seed = 71,
            )

        val actual =
            (0 until specification.pairsPerConfiguration).map { index ->
                val lineup = StrategyArena.lineup(specification, index)
                lineup.seat to lineup.opponentIndices
            }

        val expected =
            (0 until playerCount).flatMap { seat ->
                (0 until opponentCount).map { shift ->
                    seat to List(playerCount) { player -> (shift + player) % opponentCount }
                }
            }
        assertThat(actual).containsExactlyElementsIn(expected)
    }
}
