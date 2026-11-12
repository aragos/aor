package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** UNIT JSON fixtures deliberately contain no real training history or return. */
class PurchaseMacroExportTest {
    @Test
    fun rootProjectionSkipsEntireOutcomeAndIntermediateStepTrees() {
        val path = Files.createTempFile("macro-root-unit", ".json")
        try {
            Files.writeString(
                path,
                """
                {
                                "index":0,"origin":{"seat":0},"missingRoot":false,"exclusions":{},
                                "backboneEvidence":{"file":"unit.json","sha256":"unit"},
                                "root":{"number":2,"observation":{},"projection":{},"proposals":{},
                                    "tails":[{"arm":"COMPLETE_PREFIX","outcome":{"unit-secret":9},
                                        "steps":[{"unit-future":"must-not-be-a-feature"}]}]}}
                """
                    .trimIndent(),
            )
            val result = PurchaseMacroExport.rootFields(path)
            assertThat(result.required("root").properties().map { it.key })
                .containsExactly("number", "observation", "projection", "proposals")
            assertThat(result.toString()).doesNotContain("unit-secret")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun prefixReadsOnlyPreRootCommandsAndTheActualNextPass() {
        val path = Files.createTempFile("macro-prefix-unit", ".json")
        try {
            Files.writeString(
                path,
                """{"header":{},"entries":[
                "p1: start","authority: unit","p1: unit-before-root","p1: pass",
                {"unit-future":"not a decoded canonical command"}],
                "rootPlayerDecision":1,"rootEntryCount":3} """,
            )
            val result = PurchaseMacroExport.prefix(path)
            assertThat(result.entries)
                .containsExactly("p1: start", "authority: unit", "p1: unit-before-root")
                .inOrder()
            assertThat(result.next).isEqualTo("p1: pass")
            assertThat(result.number).isEqualTo(1)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun absolutePlayerIndexMismatchIsRejected() {
        val path = Files.createTempFile("macro-offset-unit", ".json")
        try {
            Files.writeString(
                path,
                """{"header":{},"entries":["p1: start","p2: unit","p1: pass"],
                "rootPlayerDecision":0,"rootEntryCount":2}""",
            )
            assertFailsWith<IllegalArgumentException> { PurchaseMacroExport.prefix(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun truncatedCanonicalNextActionIsRejected() {
        val path = Files.createTempFile("macro-truncated-unit", ".json")
        try {
            Files.writeString(
                path,
                """{"header":{},"entries":["p1: start"],
                "rootPlayerDecision":0,"rootEntryCount":1}""",
            )
            assertFailsWith<IllegalArgumentException> { PurchaseMacroExport.prefix(path) }
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
