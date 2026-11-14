package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.JointPolicySelector
import dev.aragos.aor.ai.JointSupportProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented receipts and own/public positions only; no whole-game execution or scientific files. */
class CoordinatedQualificationTest {
    @Test
    fun fixedProtocolPreservesBothCompleteDisjointBanks() {
        CpePairedFixture().use { fixture ->
            val plan = plan(fixture)
            assertThat(plan.banks.keys).containsExactly("mixed", "homogeneous")
            assertThat(plan.banks["mixed"]?.seeds).hasSize(4096)
        }
    }

    @Test
    fun overlappingQualificationBanksAreRejected() {
        CpePairedFixture().use { fixture ->
            val value = record()
            (value["banks"] as ObjectNode).set<JsonNode>("homogeneous", value["banks"]["mixed"])
            assertFailsWith<IllegalArgumentException> { plan(fixture, value) }
        }
    }

    @Test
    fun changedNinetyNineMultiplierIsRejected() {
        CpePairedFixture().use { fixture ->
            val value = record()
            (value["interval"] as ObjectNode).put("multiplier", 1.96)
            assertFailsWith<IllegalArgumentException> { plan(fixture, value) }
        }
    }

    @Test
    fun missingConfirmationCannotOpenHomogeneousBank() {
        CpePairedFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                plan(fixture).confirmation(null, "homogeneous")
            }
        }
    }

    @Test
    fun anotherHostsRawOriginIsRejectedBeforeAdmissionModelAccess() {
        CpePairedFixture().use { fixture ->
            val absent = fixture.files.directory.resolve("must-not-read.json")
            val arena = TokenPairedSubject(absent, "e".repeat(64), absent.toString())
            val admission = TokenPairedSubject(absent, "d".repeat(64), absent.toString())
            val error =
                assertFailsWith<IllegalArgumentException> {
                    plan(fixture).bindInputs(QualificationHost.MAC, arena, admission, admission)
                }
            assertThat(error.message).contains("exact original83 arena")
        }
    }

    @Test
    fun fractionalBudgetCannotBeTruncatedIntoTheFixedClock() {
        CpePairedFixture().use { fixture ->
            val value = record().put("maximumSeconds", 28800.5)
            assertFailsWith<IllegalArgumentException> { plan(fixture, value) }
        }
    }

    @Test
    fun mixedLineupUsesOriginalGlobalIndices() {
        assertThat(CoordinatedQualificationInput.opponents("mixed", 4095))
            .containsExactly(3, 0, 1, 2)
            .inOrder()
        assertThat(CoordinatedQualificationInput.opponents("mixed", 128))
            .containsExactly(0, 1, 2, 3)
            .inOrder()
        assertThat(CoordinatedQualificationInput.opponents("homogeneous", 4095))
            .containsExactly(0, 0, 0, 0)
            .inOrder()
    }

    @Test
    fun supportedControlWrapsEveryRivalCardPurchaseWithTheSameCommonKernel() {
        CoordinatedEsFixture().use { fixture ->
            val raw = fixture.input.arena
            val supported =
                raw.copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.supported(
                            null,
                            fixture.original,
                            fixture.input.purchaseBaseline,
                            null,
                        )
                )
            val controllers =
                supported.controllers(4, 0, ArenaSide.CANDIDATE, raw.candidate, List(4) { 0 })
            val joint =
                JointPolicySelector(
                    raw.candidate,
                    fixture.input.purchaseBaseline.model,
                    fixture.original.models.card,
                    fixture.original.models.purchase,
                    JointSupportProfile.KNOWN_OWN_CHAOS_1,
                )
            for (head in listOf(CoordinatedEsHead.CARD, CoordinatedEsHead.PURCHASE)) {
                val decision = fixture.decision(head)
                val expected = joint.choose(decision.view, decision.observation, decision.seed)
                for (seat in 0..3) {
                    assertThat(controllers[seat]?.choose(decision)).isEqualTo(expected)
                }
            }
            val binding = ExpansionOnPolicyStore.wire(supported.overlayMetadata())["coordinatedEs"]
            assertThat(binding["version"].asText()).isEqualTo("coordinated-support-arena-1")
            assertThat(binding["supportRoles"].asText()).isEqualTo("ALL_SEATS_CP")
        }
    }

    @Test
    fun supportedControlLeavesTheOriginalExpansionAndTokenReferenceExact() {
        CoordinatedEsFixture().use { fixture ->
            val raw = fixture.input.arena
            val supported =
                raw.copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.supported(
                            null,
                            fixture.original,
                            fixture.input.purchaseBaseline,
                            null,
                        )
                )
            val old = raw.controllers(4, 0, ArenaSide.CANDIDATE, raw.candidate, List(4) { 0 })
            val next =
                supported.controllers(4, 0, ArenaSide.CANDIDATE, raw.candidate, List(4) { 0 })
            for (head in listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)) {
                val decision = fixture.decision(head)
                assertThat(next[0]?.choose(decision)).isEqualTo(old[0]?.choose(decision))
            }
        }
    }

    @Test
    fun zeroSupportOmissionRetainsLegacyBinding() {
        CoordinatedEsFixture().use { fixture ->
            val binding =
                ExpansionOnPolicyStore.wire(
                    ArenaCoordinatedPolicy.zero(
                            fixture.original,
                            fixture.input.purchaseBaseline,
                        )
                        .binding()
                )
            assertThat(binding["version"].asText()).isEqualTo(ArenaCoordinatedPolicy.VERSION)
            assertThat(binding.has("supportProfile")).isFalse()
            assertThat(binding.has("supportRoles")).isFalse()
        }
    }

    @Test
    fun completePositiveBankProducesStrictNinetyNineGates() {
        val report = ExpansionOnPolicyStore.wire(CoordinatedQualificationReport.report(worlds()))
        CoordinatedQualificationReport.requirePassed(report)
        assertThat(report["candidateMinusControl"]["rewards"]["mean"].doubleValue())
            .isWithin(1e-12)
            .of(.2)
        assertThat(report["intervalMultiplier"].doubleValue()).isEqualTo(2.5758293035489004)
        assertThat(report["qualification"].booleanValue()).isFalse()
    }

    @Test
    fun zeroImprovementDoesNotPassStrictGates() {
        val report = ExpansionOnPolicyStore.wire(CoordinatedQualificationReport.report(worlds(.4)))
        assertThat(report["gates"]["passed"].booleanValue()).isFalse()
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationReport.requirePassed(report)
        }
    }

    @Test
    fun partialBankCannotOpenEffects() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationReport.report(worlds().dropLast(1))
        }
    }

    @Test
    fun relabeledNinetyFiveIntervalCannotPass() {
        val report =
            ExpansionOnPolicyStore.wire(CoordinatedQualificationReport.report(worlds()))
                as ObjectNode
        report.put("intervalMultiplier", 1.96)
        assertFailsWith<IllegalArgumentException> {
            CoordinatedQualificationReport.requirePassed(report)
        }
    }

    private fun plan(
        fixture: CpePairedFixture,
        value: JsonNode = record(),
    ): CoordinatedQualificationProtocol {
        fixture.files.write("qualification-protocol.json", value)
        return CoordinatedQualificationProtocol(
            CoordinatedEsWire.source(fixture.files.directory.resolve("qualification-protocol.json"))
        )
    }

    private fun record(): ObjectNode =
        ExpansionOnPolicyStore.wire(
            mapOf(
                "kind" to CoordinatedQualificationProtocol.VERSION,
                "profile" to CoordinatedQualificationProtocol.PROFILE,
                "supportProfile" to JointSupportProfile.KNOWN_OWN_CHAOS_1.version,
                "candidateBundleSha256" to "a".repeat(64),
                "originalBundleSha256" to "b".repeat(64),
                "calibrationSha256" to "c".repeat(64),
                "originAdmissionSha256" to "d".repeat(64),
                "originArenaSha256" to mapOf("DESKTOP" to "e".repeat(64), "MAC" to "f".repeat(64)),
                "originProofSha256" to mapOf("DESKTOP" to "a".repeat(64), "MAC" to "b".repeat(64)),
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "configuration" to CoordinatedQualificationProtocol.CONFIGURATION,
                "maximumSeconds" to 28800,
                "auditSeconds" to 900,
                "maximumTerminalExecutions" to 16400,
                "references" to
                    mapOf(
                        "qualifiedOrdinarySha256" to "1".repeat(64),
                        "cardM8Sha256" to "2".repeat(64),
                        "purchaseF0Sha256" to "3".repeat(64),
                        "expansionESha256" to "4".repeat(64),
                        "tokenControlVersion" to "FIXED_16_TO_15",
                    ),
                "interval" to
                    mapOf(
                        "confidence" to .99,
                        "multiplier" to CoordinatedQualificationProtocol.MULTIPLIER,
                        "requiredMetrics" to listOf("wins", "rewards"),
                        "lowerBoundStrictlyPositive" to true,
                    ),
                "banks" to mapOf("mixed" to bank(41), "homogeneous" to bank(42)),
            )
        ) as ObjectNode

    private fun bank(offset: Int) =
        mapOf(
            "nativeOffset" to offset,
            "count" to 4096,
            "seeds" to List(4096) { StrategyArena.seed(offset, it) },
        )

    private fun worlds(candidate: Double = .6): List<JsonNode> =
        (0..4095).map { index ->
            fun game(value: Double) =
                mapOf(
                    "wins" to List(4) { value },
                    "rewards" to List(4) { value },
                    "ranks" to List(4) { value },
                    "scores" to List(4) { mapOf("total" to 1) },
                    "decisions" to 1,
                )
            ExpansionOnPolicyStore.wire(
                mapOf(
                    "index" to index,
                    "seat" to index % 4,
                    "roles" to mapOf("CANDIDATE" to game(candidate), "CONTROL" to game(.4)),
                )
            )
        }
}
