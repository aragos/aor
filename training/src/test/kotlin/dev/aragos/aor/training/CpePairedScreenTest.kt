package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CpePairedScreenTest {
    @Test
    fun completeBundleAndRawG3UseTheSamePairedReportMathAsToken() {
        fun worlds(raw: String) =
            (0 until 1024).map { index ->
                fun game(value: Double) =
                    mapOf(
                        "wins" to List(4) { value },
                        "rewards" to List(4) { value },
                        "ranks" to List(4) { value },
                        "scores" to List(4) { mapOf("total" to 1) },
                    )
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "index" to index,
                        "seat" to index % 4,
                        "roles" to
                            mapOf("FINAL" to game(.6), raw to game(.4), "INITIAL" to game(.5)),
                    )
                )
            }
        val cpe = ThreeRoleScreen.report(worlds("RAW_G3"), "cpe-paired", "RAW_G3", "RawG3")
        val token = TokenPairedScreen.report(worlds("RAW_T"))
        assertThat(cpe["finalMinusRawG3"]).isEqualTo(token["finalMinusRawT"])
        assertThat(cpe["initializerMinusRawG3"]).isEqualTo(token["initializerMinusRawT"])
        assertThat(cpe["finalMinusInitializer"]).isEqualTo(token["finalMinusInitializer"])
        assertThat(cpe["intervalMultiplier"]).isEqualTo(1.96)
        assertThat(cpe["qualification"]).isEqualTo(false)
    }
}
