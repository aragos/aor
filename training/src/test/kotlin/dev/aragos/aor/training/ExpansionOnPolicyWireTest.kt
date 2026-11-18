package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** Retained native wire fixture with constructed terminal records and zero simulator calls. */
class ExpansionOnPolicyWireTest {
    @Test
    fun publishCompletedSyntheticCollectionIncludingEmptyPhaseWorld() {
        ExpansionOnPolicyFixture().use { fixture ->
            val directory = Path.of("build/test-fixtures/expansion-collection").toAbsolutePath()
            // Only this test's generated fixture is replaced; every source below is synthetic.
            directory.toFile().deleteRecursively()
            val inputs = Files.createDirectories(directory.resolve("inputs"))
            Files.list(fixture.files.directory).use { sources ->
                sources.forEach { source ->
                    require(Files.isRegularFile(source)) { "Fixture inputs must be regular files." }
                    Files.copy(source, inputs.resolve(source.fileName))
                }
            }
            val input = ExpansionOnPolicyInput.read(inputs.resolve("on-policy-source.json"))
            val output = directory.resolve("collection")
            val store = ExpansionOnPolicyStore(input, output)
            val rows = mutableListOf<TerritoryDecisionRecord>()
            requireNotNull(input.controllers(0, rows::add)[0]) { "Synthetic actor is required." }
                .choose(fixture.decision(input))
            store.prepare()

            store.finish(
                listOf(
                    store.save(fixture.game(input, rows, index = 0)),
                    store.save(fixture.game(input, emptyList(), index = 1)),
                )
            )

            val completed = PurchaseJson.read(output.resolve("finished.json"))
            val empty =
                requireNotNull(
                    CardTrajectoryFiles.readIfPresent(output.resolve("game-1.json.gz"))
                ) {
                    "The empty-phase world must remain in the native fixture."
                }
            assertThat(completed["complete"].booleanValue()).isTrue()
            assertThat(completed["gameSha256"].size()).isEqualTo(2)
            assertThat(empty["expansionDecisions"].size()).isEqualTo(0)
        }
    }
}
