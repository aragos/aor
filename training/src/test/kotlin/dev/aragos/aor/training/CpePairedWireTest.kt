package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Retained cross-owner wire fixture; all outcomes/canonical receipts are invented, zero play. */
class CpePairedWireTest {
    @Test
    fun exportConstructedAllHeadPublicAndLabelBoundaries() {
        val target = Path.of("build", "test-fixtures", "cpe-wire").toAbsolutePath()
        target.toFile().deleteRecursively()
        Files.createDirectories(target)
        for (head in CpeHead.entries) {
            CpePairedFixture(nonzeroCardPurchase = true).use { fixture ->
                fixture.source(head)
                val input = fixture.input
                val directory = target.resolve(head.name)
                val store = CpePairedStore(input, directory)
                store.prepare()
                val source = CpePairedStoreTest()
                val receipts =
                    listOf(
                        store.save(source.origin(input, 0, true)),
                        store.save(source.origin(input, 1, false)),
                    )
                assertThat(receipts.map { it.status })
                    .containsExactly(CpePairedStatus.READY, CpePairedStatus.MISSING)
                    .inOrder()
                TrainingArtifacts(directory)
                    .write(
                        "CONSTRUCTED-FIXTURE.json",
                        mapOf(
                            "kind" to "cpe-native-wire-fixture-1",
                            "head" to head,
                            "newGames" to 0,
                            "scientificFits" to 0,
                            "retainedModelsRead" to 0,
                            "scientificAdmission" to false,
                            "terminalAndCanonicalReceiptsInvented" to true,
                            "receipts" to receipts,
                        ),
                    )
            }
        }
    }
}
