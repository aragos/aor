package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Cross-language fixture from constructed records only; no Simulation.play invocation. */
class TripleCollectionWireTest {
    @Test
    fun publishConstructedCompleteTripleCollection() {
        TripleCollectionFixture().use { fixture ->
            val root = Path.of("build/test-fixtures/triple-collection").toAbsolutePath()
            root.toFile().deleteRecursively()
            val inputs = Files.createDirectories(root.resolve("inputs"))
            Files.list(fixture.files.directory).use { files ->
                files.forEach { source ->
                    require(Files.isRegularFile(source)) { "Constructed inputs must be files." }
                    Files.copy(source, inputs.resolve(source.fileName))
                }
            }
            val input = TripleCollectionInput.read(inputs.resolve("triple-source.json"))
            val store = TripleCollectionStore(input, root.resolve("collection"))
            store.prepare()
            val rows = fixture.rows(input)
            store.finish(
                listOf(
                    store.save(fixture.game(input, rows)),
                    store.save(fixture.game(input, emptyList(), index = 1)),
                )
            )
            assertThat(
                    PurchaseJson.read(root.resolve("collection/finished.json"))["complete"]
                        .booleanValue()
                )
                .isTrue()
        }
    }
}
