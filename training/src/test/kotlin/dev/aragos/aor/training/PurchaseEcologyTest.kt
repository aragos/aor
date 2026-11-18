package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PurchaseEcologyTest {
    @Test
    fun omissionRetainsLegacyIdentityAndMetadataWithoutSidecars() {
        PurchaseCollectionFixture(onPolicy = true).use { fixture ->
            val input = fixture.input
            val store = fixture.store(input)

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))

            assertThat(input.ecology).isNull()
            assertThat(manifest.has("continuationProtocol")).isFalse()
            assertThat(manifest.has("expansionImitation")).isFalse()
            assertThat(manifest.has("tokenOrder")).isFalse()
            assertThat(store.completedIndices()).isEmpty()
        }
    }

    @Test
    fun optInArchivesExactExpansionRolesAndProspectiveIndices() {
        PurchaseEcologyFixture().use { fixture ->
            val input = fixture.input

            PurchaseCollectionStore(input, fixture.output, input.signature())

            val manifest = PurchaseJson.read(fixture.output.resolve("collection.json"))
            assertThat(manifest["continuationProtocol"].asText()).isEqualTo("ARENA_OVERLAYS_1")
            assertThat(manifest["replayIndices"].map { it.intValue() }).containsExactly(0)
            val roles = manifest["expansionImitation"]["opponentModels"]
            assertThat(roles[0].isNull).isTrue()
            assertThat(roles[1]["snapshot"].asText())
                .isEqualTo("expansion-imitation-opponent-1.json")
            assertThat(
                    PurchaseJson.hash(fixture.output.resolve("expansion-imitation-candidate.json"))
                )
                .isEqualTo(PurchaseJson.hash(fixture.directory.resolve("expansion.json")))
        }
    }

    @Test
    fun rotatingRivalRolesUseOpponentIndicesAndNeverReplacePurchaseControllers() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit { put("games", 8) }
            val input = fixture.input
            val purchase =
                (0..3).associateWith {
                    object : SimulationController {
                        override fun controls(decision: String) = decision == "purchase-advances"

                        override fun choose(decision: SimulationDecision) = "pass"
                    }
                }

            val ecology = requireNotNull(input.ecology) { "The fixture requires explicit ecology." }
            val controllers = ecology.controllers(input, 4, purchase)

            assertThat(input.opponentIndices(4)).containsExactly(1, 2, 3, 0).inOrder()
            assertThat(controllers[0]?.controls("expand")).isTrue()
            assertThat(controllers[0]?.controls("buy-tokens")).isTrue()
            assertThat(controllers[1]?.controls("expand")).isFalse()
            assertThat(controllers[1]?.controls("buy-tokens")).isTrue()
            assertThat(controllers[2]?.controls("expand")).isTrue()
            assertThat(controllers[2]?.controls("buy-tokens")).isTrue()
            assertThat(controllers[3]?.controls("expand")).isFalse()
            assertThat(controllers[3]?.controls("buy-tokens")).isFalse()
            assertThat(controllers.values.all { it.controls("purchase-advances") }).isTrue()
            assertThat(controllers.values.any { it.controls("play-cards") }).isFalse()
        }
    }

    @Test
    fun originalBytesChangeIdentityEvenWhenExpansionCoefficientsAreUnchanged() {
        PurchaseEcologyFixture().use { fixture ->
            val before = fixture.input
            val path = fixture.directory.resolve("expansion.json")
            Files.writeString(path, Files.readString(path) + "\n")

            val after = fixture.input

            assertThat(before.sourceSha256).isEqualTo(after.sourceSha256)
            assertThat(before.signature()).isNotEqualTo(after.signature())
        }
    }

    @Test
    fun ecologyFieldsWithoutOptInAreRejected() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit { remove("continuationProtocol") }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun unusedIncumbentRoleIsRejected() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit {
                (this["expansionImitation"] as ObjectNode).put("incumbentModel", "expansion.json")
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun learnedTokenModelsCannotEnterPurchaseCollection() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).put("candidateModel", "candidate-policy.json")
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun undeclaredMinusTwoRivalControlIsRejected() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit {
                (this["tokenOrder"] as ObjectNode).set<JsonNode>(
                    "opponentSelectors",
                    PurchaseJson.mapper.valueToTree(List(4) { "ORDINARY_MINUS_TWO" }),
                )
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun rivalRoleCountsMustMatchTheOrdinaryPool() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit {
                (this["expansionImitation"] as ObjectNode).set<JsonNode>(
                    "opponentModels",
                    PurchaseJson.mapper.valueToTree(listOf("expansion.json")),
                )
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun duplicateProspectiveReplayIndicesAreRejected() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit {
                set<JsonNode>("replayIndices", PurchaseJson.mapper.valueToTree(listOf(0, 0)))
            }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }

    @Test
    fun ecologyRequiresAnExplicitSharedPurchasePrior() {
        PurchaseEcologyFixture().use { fixture ->
            fixture.edit { remove("sharedPurchaseBaseline") }

            assertFailsWith<IllegalArgumentException> { fixture.input }
        }
    }
}
