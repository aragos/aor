package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CpePairedAdmissionTest {
    @Test
    fun resealedValidationCannotSubstituteOtherFutureStreams() {
        CpePairedAdmissionFixture().use { fixture ->
            val seal =
                PurchaseJson.read(fixture.root.resolve("iteration-1/validation.json")) as ObjectNode
            (seal["heads"]["CARD"]["rows"][0] as ObjectNode).set<JsonNode>(
                "futureSeeds",
                PurchaseJson.mapper.valueToTree(listOf(7, 8)),
            )
            val sealed = fixture.write("iteration-1/validation.json", seal)
            val iteration =
                PurchaseJson.read(fixture.root.resolve("iteration-1/admission.json")) as ObjectNode
            (iteration["validationProbabilitySeal"] as ObjectNode).put("sha256", sealed.sha256)
            val changedIteration = fixture.write("iteration-1/admission.json", iteration)
            val final = PurchaseJson.read(fixture.source.path) as ObjectNode
            (final["iterations"][0] as ObjectNode).put("sha256", changedIteration.sha256)
            val changed = fixture.write("changed.json", final)
            val failure =
                assertFailsWith<IllegalArgumentException> { CpePairedAdmission.read(changed) }
            assertThat(failure.message).contains("paired future streams")
        }
    }

    @Test
    fun completeAtomicChainIsVerifiedWithoutDecodingAnyPolicy() {
        CpePairedAdmissionFixture().use { fixture ->
            val admitted = CpePairedAdmission.read(fixture.source)
            assertThat(admitted.originalBundle.sha256).isEqualTo(fixture.original.sha256)
            assertThat(PurchaseJson.read(admitted.finalBundle.path)["heads"].size()).isEqualTo(3)
            admitted.verify()
        }
    }

    @Test
    fun changedCheckpointRejectsBeforePolicyParsing() {
        CpePairedAdmissionFixture().use { fixture ->
            Files.writeString(
                fixture.root.resolve("iteration-2/fit/heads/CARD/checkpoint.json"),
                "{}",
            )
            assertFailsWith<IllegalArgumentException> { CpePairedAdmission.read(fixture.source) }
        }
    }

    @Test
    fun finalAdmissionCannotSubstituteTheInitializer() {
        CpePairedAdmissionFixture().use { fixture ->
            val record = PurchaseJson.read(fixture.source.path) as ObjectNode
            record.set<JsonNode>("finalBundle", record["originalBundle"])
            val changed = fixture.write("changed.json", record)
            assertFailsWith<IllegalArgumentException> { CpePairedAdmission.read(changed) }
        }
    }

    @Test
    fun duplicateIterationCannotStandInForTheAtomicSecondUpdate() {
        CpePairedAdmissionFixture().use { fixture ->
            val record = PurchaseJson.read(fixture.source.path) as ObjectNode
            record.set<JsonNode>(
                "iterations",
                PurchaseJson.mapper
                    .createArrayNode()
                    .add(record["iterations"][0])
                    .add(record["iterations"][0]),
            )
            val changed = fixture.write("changed.json", record)
            assertFailsWith<IllegalArgumentException> { CpePairedAdmission.read(changed) }
        }
    }
}
