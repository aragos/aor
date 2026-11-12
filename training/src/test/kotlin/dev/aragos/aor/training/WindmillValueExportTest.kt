package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.GameHeader
import dev.aragos.aor.LEGACY_RULES_VERSION
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindmillValueExportTest {
    @Test
    fun publicPassSkipsEveryTailAndFutureOfferField() {
        val path = Files.createTempFile("windmill-public", ".json")
        try {
            Files.writeString(
                path,
                """{
                "status":"COLLECTED","signature":"subject","request":{"index":1},
                "gameSeed":1,"chanceSeeds":[2,3,4,5],"prefix":{"entries":[]},
                "actorInput":{"own":"only"},"provenance":{"actor":"p1"},
                "arms":[{"terminal":{"secret":999},"immediateOfferCard":{"secret":888}}]
            }""",
            )
            val result = WindmillValueExport.publicFields(path, true)

            assertThat(result.has("arms")).isFalse()
            assertThat(result.toString()).doesNotContain("secret")
            assertThat(result.required("actorInput").required("own").asText()).isEqualTo("only")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingRequestDoesNotReadFinalBackboneOrInventFeatures() {
        val path = Files.createTempFile("windmill-missing", ".json")
        try {
            Files.writeString(
                path,
                """{"status":"MISSING","prefix":{"secret":1},
                "actorInput":{"secret":2},"arms":[],"provenance":{"secret":3}}""",
            )
            val result = WindmillValueExport.publicFields(path, false)

            assertThat(result.toString()).isEqualTo("{\"status\":\"MISSING\"}")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun testIndexIsRejectedBeforeAnyPathAccess() {
        val subject =
            PurchaseJson.mapper.readTree(
                """{
            "chunk":1,"index":224,"split":"TEST","path":"does-not-exist"}"""
            )

        assertFailsWith<IllegalArgumentException> { WindmillValueExport.project(subject) }
    }

    @Test
    fun canonicalHeaderRetainsOriginalVersionsAndRenaissanceTurn() {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player$it") }
        val original =
            GameHeader(
                players,
                players.first().id,
                rulesVersion = LEGACY_RULES_VERSION,
                drawRenaissanceFromTurn = 7,
            )
        val json = PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(original))

        assertThat(TrainingCanonicalHeader.read(json)).isEqualTo(original)
    }

    @Test
    fun canonicalHeaderCannotDropUnknownFields() {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player$it") }
        val json =
            PurchaseJson.mapper.readTree(
                PurchaseJson.mapper.writeValueAsBytes(GameHeader(players, players.first().id))
            ) as com.fasterxml.jackson.databind.node.ObjectNode
        json.put("futureField", 1)

        assertFailsWith<IllegalArgumentException> { TrainingCanonicalHeader.read(json) }
    }
}
