package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Each case changes one immutable boundary; no game or numerical fit is executed. */
class CoordinatedWinContractsTest {
    @Test
    fun exactWarmFirstControlUsesSupportedAllSeatReference() =
        CoordinatedWinFixture().use { fixture ->
            val input = CoordinatedWinInput.read(fixture.source().path)
            assertThat(input.roles).containsExactly("CONTROL")
            assertThat(input.bundleSha256("CONTROL")).isNull()
            assertThat(input.panelFiles).isFalse()
            assertThat(input.arena.pairsPerConfiguration).isEqualTo(256)
            assertThat(
                    ExpansionOnPolicyStore.wire(input.arena.overlayMetadata())["coordinatedEs"][
                            "supportRoles"]
                        .asText()
                )
                .isEqualTo("ALL_SEATS_CP")
        }

    @Test
    fun secondGenerationKeepsSameArenaAndUsesItsOwnFullBank() =
        CoordinatedWinFixture().use { fixture ->
            val input = CoordinatedWinInput.read(fixture.source(number = 2).path)
            assertThat(input.arenaSource.sha256).isEqualTo(fixture.arena.sha256)
            assertThat(input.arena.seed)
                .isEqualTo(fixture.plan.generation(2)["worldBank"]["nativeOffset"].asInt())
            assertThat(input.preparation.directionHashes[2]).isEqualTo(fixture.directions[1].sha256)
        }

    @Test
    fun changedPreparedDirectionSeedCannotBecomeNewGeneration() =
        CoordinatedWinFixture().use { fixture ->
            val path = fixture.root.resolve("preparation/directions/generation-02-generation.json")
            val row = PurchaseJson.read(path) as ObjectNode
            row.put("seed", 123)
            val changed = fixture.save(fixture.root.relativize(path).toString(), row)
            val completed = PurchaseJson.read(fixture.preparation.path) as ObjectNode
            (completed["artifacts"] as ObjectNode).put(
                "directions/generation-02-generation.json",
                changed.sha256,
            )
            val preparation = fixture.save("preparation/finished.json", completed)
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinPreparation.read(preparation, fixture.plan)
                }
            assertThat(error.message).contains("declared stream")
        }

    @Test
    fun resealedEarlierPredecessorCannotProduceCurrentCenter() =
        CoordinatedWinFixture().use { fixture ->
            val update = PurchaseJson.read(fixture.updates[0].path) as ObjectNode
            update.set<ObjectNode>(
                "center",
                PurchaseJson.read(fixture.centers[0].path) as ObjectNode,
            )
            val changed = fixture.save("changed-update.json", update)
            val source = PurchaseJson.read(fixture.source(number = 2).path) as ObjectNode
            source.set<ObjectNode>(
                "previousUpdate",
                PurchaseJson.mapper.valueToTree(fixture.ref(changed)),
            )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinInput.read(fixture.save("changed-source.json", source).path)
                }
            assertThat(error.message).contains("predecessor")
        }

    @Test
    fun inheritedSealCannotBeRelabelledAsCurrentProtocol() =
        CoordinatedWinFixture().use { fixture ->
            val envelope = PurchaseJson.read(fixture.inherited.path) as ObjectNode
            envelope.set<ObjectNode>(
                "originProtocol",
                PurchaseJson.mapper.valueToTree(
                    mapOf("path" to "origin/protocol.json", "sha256" to fixture.protocol.sha256)
                ),
            )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinCalibration.read(
                        fixture.save("calibration/changed-envelope.json", envelope),
                        fixture.plan,
                        fixture.original,
                    )
                }
            assertThat(error.message).contains("changed")
        }

    @Test
    fun otherHostProofRejectedBeforeAnyOriginalModelDecode() =
        CoordinatedWinFixture().use { fixture ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinOrigin.read(fixture.proof, fixture.plan, QualificationHost.MAC)
                }
            assertThat(error.message).contains("zero-execution proof")
        }

    @Test
    fun finalSixteenLineageEntersThreeRoleIndependentScreen() =
        CoordinatedWinFixture().use { fixture ->
            val source =
                fixture.save(
                    "screen.json",
                    mapOf(
                        "kind" to "coordinated-win-screen-1",
                        "host" to "DESKTOP",
                        "originProof" to fixture.ref(fixture.proof),
                        "arena" to fixture.ref(fixture.arena),
                        "admission" to fixture.ref(fixture.finalAdmission),
                        "historical" to fixture.ref(fixture.historical),
                        "globalStart" to 128,
                        "count" to 128,
                        "bank" to fixture.plan.record["screenBank"],
                    ),
                )
            val input = CoordinatedWinScreenInput.read(source.path)
            assertThat(input.roles).containsExactly("FINAL", "START", "CONTROL").inOrder()
            assertThat(input.raw.pairsPerConfiguration).isEqualTo(2048)
            assertThat(input.expectedOpponents(511)).containsExactly(3, 0, 1, 2).inOrder()
            assertThat(input.executionReceipt(0)["completedGames"]).isEqualTo(0)
        }

    @Test
    fun resealedFinalMaterializationCannotUseEarlierMean() =
        CoordinatedWinFixture().use { fixture ->
            val admission = PurchaseJson.read(fixture.finalAdmission.path) as ObjectNode
            val materialization =
                PurchaseJson.read(fixture.root.resolve("center-materialization.json")) as ObjectNode
            materialization.put("centerSha256", fixture.centers[15].sha256)
            val changed = fixture.save("changed-materialization.json", materialization)
            admission.set<ObjectNode>(
                "centerMaterialization",
                PurchaseJson.mapper.valueToTree(fixture.ref(changed)),
            )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinAdmission.read(
                        fixture.save("changed-admission.json", admission),
                        fixture.proof,
                        QualificationHost.DESKTOP,
                    )
                }
            assertThat(error.message).contains("final16 coordinate")
        }

    @Test
    fun missingCompleteWorldCannotPassFinalLineage() =
        CoordinatedWinFixture().use { fixture ->
            val admission = PurchaseJson.read(fixture.finalAdmission.path) as ObjectNode
            val audit =
                PurchaseJson.read(fixture.root.resolve("generation-audit.json")) as ObjectNode
            (audit["generations"][7] as ObjectNode).put("mutants", 8191)
            val changed = fixture.save("changed-audit.json", audit)
            admission.set<ObjectNode>(
                "generationAudit",
                PurchaseJson.mapper.valueToTree(fixture.ref(changed)),
            )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedWinAdmission.read(
                        fixture.save("changed-admission.json", admission),
                        fixture.proof,
                        QualificationHost.DESKTOP,
                    )
                }
            assertThat(error.message).contains("complete update")
        }
}
