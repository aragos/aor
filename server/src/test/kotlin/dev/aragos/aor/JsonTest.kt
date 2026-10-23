package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class JsonTest {
    @Test
    fun playerIdentifiersRetainStringValuesAndObjectKeys() {
        val encoded = json.writeValueAsString(mapOf(PlayerId("p2") to PlayerId("p1")))

        assertThat(encoded).isEqualTo("""{"p2":"p1"}""")
    }

    @Test
    fun diceRetainNumericValues() {
        val encoded = json.writeValueAsString(mapOf(PlayerId("p1") to DieRoll(6)))

        assertThat(encoded).isEqualTo("""{"p1":6}""")
    }

    @Test
    fun cardKindsRetainLowercaseNames() {
        assertThat(json.writeValueAsString(CardKind.COMMODITY)).isEqualTo("\"commodity\"")
        assertThat(json.writeValueAsString(CardKind.EVENT)).isEqualTo("\"event\"")
        assertThat(json.writeValueAsString(CardKind.LEADER)).isEqualTo("\"leader\"")
    }

    @Test
    fun startingResourcesRetainNumericRepresentations() {
        val encoded =
            json.writeValueAsString(
                mapOf("misery" to MiseryStep.ZERO, "ships" to ShippingCapability.NONE)
            )

        assertThat(encoded).isEqualTo("""{"misery":0,"ships":0}""")
    }

    @Test
    fun chaosHasNoNumericMiseryValue() {
        assertThat(json.writeValueAsString(MiseryStep.CHAOS)).isEqualTo("null")
    }
}
