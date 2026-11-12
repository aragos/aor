package dev.aragos.aor.ai

import dev.aragos.aor.PublicCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class AfterstateValueEncoderTest {
    @Test
    fun dictionaryCoordinatesAreIdenticalAcrossTargets() {
        val catalog = PublicCatalog.forPlayers(4)
        val before = AfterstateValueEncoder.legacyDictionary(catalog)
        val after = AfterstateValueEncoder.dictionary(catalog)
        assertEquals(7241, before.size)
        assertEquals(before, after.take(before.size))
        assertEquals(
            listOf(
                "purchase.decision.purchase-owner-exit",
                "purchase.decision.purchase-relief",
                "purchase.decision.purchase-stabilization",
            ),
            after.drop(before.size),
        )
    }
}
