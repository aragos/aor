package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TokenPairedAdmissionTest {
    @Test
    fun exactlyTwoPortableCompletedIterationsAreAdmittedWithoutModelInference() {
        TokenPairedScreenFixture().use { fixture ->
            val admitted = fixture.readAdmission()
            assertThat(admitted.finalActor.path).isEqualTo(fixture.finalPolicy.toRealPath())
            assertThat(admitted.iterations).hasSize(2)
        }
    }

    @Test
    fun unsupportedFinalActorCannotBeParsedBeforeChainAdmission() {
        TokenPairedScreenFixture().use { fixture ->
            Files.writeString(fixture.finalPolicy, "not a model")
            val failure = assertFailsWith<IllegalArgumentException> { fixture.readAdmission() }
            assertThat(failure.message).contains("subject changed")
        }
    }

    @Test
    fun changedCurrentActorCannotReplaceTheFirstCompletedPredecessor() {
        TokenPairedScreenFixture().use { fixture ->
            fixture.editAdmission(2) {
                set<JsonNode>(
                    "currentActor",
                    PurchaseJson.mapper.valueToTree(
                        fixture.subject(
                            fixture.original,
                            fixture.root.resolve("iteration-2/admission.json"),
                        )
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> { fixture.readAdmission() }
        }
    }

    @Test
    fun insufficientPublicValidationExposureCannotAdvance() {
        TokenPairedScreenFixture().use { fixture ->
            fixture.editAdmission(1) { (this["ready"] as ObjectNode).put("VALIDATION", 63) }
            assertFailsWith<IllegalArgumentException> { fixture.readAdmission() }
        }
    }

    @Test
    fun finalAdmissionCannotSelectIterationOne() {
        TokenPairedScreenFixture().use { fixture ->
            val node = PurchaseJson.read(fixture.finalAdmission) as ObjectNode
            node.set<JsonNode>(
                "finalActor",
                PurchaseJson.mapper.valueToTree(
                    fixture.subject(
                        fixture.root.resolve("iteration-1/fit/policy.json"),
                        fixture.finalAdmission,
                    )
                ),
            )
            fixture.write(fixture.finalAdmission, node)
            assertFailsWith<IllegalArgumentException> { fixture.readAdmission() }
        }
    }

    @Test
    fun rawAndOptionalRolesKeepOneGlobalBankAcrossHostSlices() {
        TokenPairedScreenFixture().use { fixture ->
            val first = TokenPairedScreenInput.read(fixture.source)
            fixture.source(896)
            val last = TokenPairedScreenInput.read(fixture.source)
            assertThat(first.scienceSha256).isEqualTo(last.scienceSha256)
            assertThat(first.signature).isNotEqualTo(last.signature)
            assertThat(first.raw.tokenActor).isNull()
            assertThat(first.specification(TokenPairedScreenRole.RAW_T)).isSameInstanceAs(first.raw)
            assertThat(first.arena.tokenActor?.actor(ArenaSide.CANDIDATE)?.sha256)
                .isEqualTo(first.admission.finalActor.sha256)
            assertThat(last.ownedReplays).containsExactly(1023)
        }
    }

    @Test
    fun absentTokenRolePreservesLegacyIdentityAndWire() {
        TokenPairedFixture().use { fixture ->
            val original = fixture.input.arena
            assertThat(original.copy(tokenActor = null).identity()).isEqualTo(original.identity())
            assertThat(original.overlayMetadata()).doesNotContainKey("tokenActor")
        }
    }

    @Test
    fun historicalAdmissionMustPrecedeFreshWorldsOnBothHosts() {
        TokenPairedScreenFixture().use { fixture ->
            val path = fixture.root.resolve("historical-admission.json")
            val node = PurchaseJson.read(path) as ObjectNode
            node.put("complete", false)
            fixture.write(path, node)
            fixture.source(0)
            assertFailsWith<IllegalArgumentException> {
                TokenPairedScreenInput.read(fixture.source)
            }
        }
    }
}
