package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindmillProfileTest {
    @Test
    fun burdenCapturesEarlierEpoch() {
        assertThat(captures(1, true)).isTrue()
    }

    @Test
    fun burdenCapturesLaterEpoch() {
        assertThat(captures(3, true)).isTrue()
    }

    @Test
    fun burdenSkipsNoBurden() {
        assertThat(captures(2, false)).isFalse()
    }

    @Test
    fun burdenDoesNotCaptureRival() {
        assertThat(captures(2, true, seat = 0)).isFalse()
    }

    @Test
    fun burdenDoesNotCaptureOtherDecision() {
        assertThat(captures(2, true, decision = "offer-card")).isFalse()
    }

    @Test
    fun assignedStillRequiresEpoch() {
        assertThat(
                WindmillRequest(7)
                    .captures(1, 3, observation(), WindmillRootProfile.ASSIGNED_EPOCH, true)
            )
            .isFalse()
    }

    @Test
    fun oldSourceKeepsExactRequestAndProfileOmission() {
        WindmillFixture().use { fixture ->
            val input = fixture.input
            assertThat(input.declaredProfile).isNull()
            assertThat(input.kind).isEqualTo("windmill-data-1")
            assertThat(input.originKind).isEqualTo("windmill-origin-1")
            assertThat(input.profileMetadata()).isEmpty()
            assertThat(input.requestMetadata(WindmillRequest(7))).isEqualTo(WindmillRequest(7))
        }
    }

    @Test
    fun burdenDeclaresAnyEpoch() {
        WindmillFixture().use { fixture ->
            profile(fixture, "FIRST_BURDEN_WINDMILL")
            val input = fixture.input
            val row =
                PurchaseJson.mapper.valueToTree<JsonNode>(input.requestMetadata(WindmillRequest(7)))
            assertThat(input.kind).isEqualTo("windmill-data-2")
            assertThat(input.originKind).isEqualTo("windmill-origin-2")
            assertThat(row["epoch"].isNull).isTrue()
            assertThat(row["seat"].intValue()).isEqualTo(3)
            assertThat(row["rootProfile"].asText()).isEqualTo("FIRST_BURDEN_WINDMILL")
        }
    }

    @Test
    fun explicitAssignedDeclaresTargetEpoch() {
        WindmillFixture().use { fixture ->
            profile(fixture, "ASSIGNED_EPOCH")
            val row =
                PurchaseJson.mapper.valueToTree<JsonNode>(
                    fixture.input.requestMetadata(WindmillRequest(7))
                )
            assertThat(row["epoch"].intValue()).isEqualTo(2)
            assertThat(row["rootProfile"].asText()).isEqualTo("ASSIGNED_EPOCH")
        }
    }

    @Test
    fun oldVersionRejectsProfile() {
        WindmillFixture().use { fixture ->
            profile(fixture, "FIRST_BURDEN_WINDMILL", "windmill-data-1")
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun newVersionRequiresProfile() {
        WindmillFixture().use { fixture ->
            val source = PurchaseJson.read(fixture.source) as ObjectNode
            source.put("kind", "windmill-data-2")
            fixture.base.artifacts.write("pilot.json", source)
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun unknownProfileRejected() {
        WindmillFixture().use { fixture ->
            profile(fixture, "BEST_RETURN")
            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    private fun captures(
        epoch: Int,
        burden: Boolean,
        seat: Int = 3,
        decision: String = "windmill",
    ) =
        WindmillRequest(7)
            .captures(
                epoch,
                seat,
                observation(decision),
                WindmillRootProfile.FIRST_BURDEN_WINDMILL,
                burden,
            )

    private fun observation(decision: String = "windmill") =
        BotObservation(
            playerId = "p1",
            decision = decision,
            context = emptyMap(),
            availableCash = 10,
            availableTokens = 3,
            candidates = listOf("windmill", "pass").map { BotCandidate(it, emptyMap()) },
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
        )

    private fun profile(fixture: WindmillFixture, name: String, kind: String = "windmill-data-2") {
        val source = PurchaseJson.read(fixture.source) as ObjectNode
        source.put("kind", kind)
        source.put("rootProfile", name)
        fixture.base.artifacts.write("pilot.json", source)
    }
}
