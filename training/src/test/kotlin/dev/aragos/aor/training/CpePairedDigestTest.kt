package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CpePairedDigestTest {
    @Test
    fun completeDictionaryMatchesIndependentPythonCanonicalSha256() {
        val dictionary = CpePairedDictionary("UNIT", listOf("state"), listOf("action"))
        assertThat(dictionary.sha256())
            .isEqualTo("ed6de198663957758b2d4555c5177c5e109dbb89426f92e0d3b38e7515f198d3")
    }

    @Test
    fun requestDigestPreservesBothSignedLongExtremesWithoutRounding() {
        val value =
            listOf(mapOf("future" to listOf(Long.MIN_VALUE, Long.MAX_VALUE), "gameSeed" to -1))
        assertThat(CpePairedDigest.json(value))
            .isEqualTo("5a70f04890223d8617384f779868f77aaf7e0a868f4d17d76ad7343d98220f34")
    }
}
