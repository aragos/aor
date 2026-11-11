package dev.aragos.aor.ai

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionPositionInputTest {
    @Test
    fun optionalPositionRestoresShippingRetainedBasesAndCathedralUsage() {
        val input = readBotObservation(observation(position()))
        val restored =
            requireNotNull(input.expansionPosition) {
                    "The fixture supplies an expansion position."
                }
                .toPosition()

        assertEquals(3, restored.context.shipped[PlayerId("p1")]?.let { it[ProvinceId("rome")] })
        assertTrue(ProvinceId("florence") in restored.context.bases(PlayerId("p1")))
        assertEquals(setOf(PlayerId("p1")), restored.cathedralUsedAgainst[PlayerId("p2")])
    }

    @Test
    fun absentAndNullPositionsRetainLegacyDecoding() {
        val absent = readBotObservation(observation())
        val explicitNull = readBotObservation(observation("null"))

        assertNull(absent.expansionPosition)
        assertEquals(absent, explicitNull)
    }

    @Test
    fun publicPositionDoesNotChangeAnyCurrentDifficultyChoiceOrScore() {
        val absent = readBotObservation(observation())
        val present = readBotObservation(observation(position()))

        assertEquals(absent, present.copy(expansionPosition = null))
        for (difficulty in BotDifficulty.entries) {
            assertEquals(
                BotPolicy.choose(absent, difficulty, 17),
                BotPolicy.choose(present, difficulty, 17),
            )
        }
        assertEquals(
            absent.candidates.map { BotPolicy.score(it) },
            present.candidates.map { BotPolicy.score(it) },
        )
    }

    @Test
    fun retainedBaseOnAColouredMarkerIsRejectedByCoreConversion() {
        assertFailsWith<IllegalArgumentException> {
            readBotObservation(observation(position().replace("WHITE", "COLOURED")))
        }
    }

    @Test
    fun publicPositionForAnotherActorIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            readBotObservation(
                observation(position().replace("\"actor\":\"p1\"", "\"actor\":\"p2\""))
            )
        }
    }

    private fun observation(position: String? = null): String =
        """{
        "schemaVersion":1,"playerId":"p1","decision":"expand","context":{},
        "availableCash":20,"availableTokens":8,"rulesVersion":"$RULES_VERSION",
        "dataVersion":"$DATA_VERSION","candidates":[
        {"command":"pass","features":{"progress":1},"cashCost":0,"tokenCost":0,
        "group":"pass","family":"pass"}]
        ${if (position == null) "" else ",\"expansionPosition\":$position"}}
        """

    private fun position(): String =
        """{
        "playerCount":3,"rules":{"deckModification":false,"initialCard":"ANY",
        "immediateMasterArtDiscard":false,"map":"STANDARD","drawThreeKeepTwo":false},
        "catalogVersion":"CATALOG_16","board":[{"id":"florence","marker":
        {"owner":"p1","side":"WHITE"},"tokens":[]}],"retainedColouredBases":["florence"],
        "players":[
        {"player":"p1","capital":"venice","advances":[],"ships":{"fleet":"NONE","level":0},"chaos":false},
        {"player":"p2","capital":"genoa","advances":["CATHEDRAL"],"ships":{"fleet":"NONE","level":0},"chaos":false},
        {"player":"p3","capital":"barcelona","advances":[],"ships":{"fleet":"NONE","level":0},"chaos":false}],
        "shipped":{"p1":{"rome":3}},"militaryPlays":[],"actor":"p1","poolTokens":8,
        "stockTokens":{"p1":28,"p2":36,"p3":36},"orderBox":1,"turnOrder":["p1","p2","p3"],
        "cathedralUsedAgainst":{"p2":["p1"]}}
        """
}
