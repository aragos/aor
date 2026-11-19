package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.InitiativePolicyConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented complete model subjects; no game, neural forward or fresh scientific stream. */
class RobustTokenInputTest {
    private val publicCounter =
        mapOf(
            "kind" to RevealedTokenCounterConfig.VERSION,
            "minimumObservations" to 2,
            "lookbackTurns" to 8,
            "targetedRivalCount" to 1,
        )

    @Test
    fun independentOpponentUsesItsOwnSeatAndChangesScienceIdentity() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val lineup = mapOf("opponentStyle" to "QUALIFIED")
            val control = RobustTokenInput.read(source(fixture, lineup).path)
            val rival =
                mapOf(
                    "kind" to InitiativeRival.VERSION,
                    "offset" to 1,
                    "policy" to
                        mapOf(
                            "kind" to InitiativePolicyConfig.VERSION,
                            "maximumTokenLoss" to 3,
                            "tokenPenalty" to 0.2,
                            "participation" to 1.0,
                            "requireAffordableContest" to true,
                            "attackBonus" to 3.0,
                        ),
                )
            val test =
                RobustTokenInput.read(source(fixture, lineup + ("initiativeRival" to rival)).path)

            assertThat(test.initiative).isNull()
            assertThat(test.initiativeRival?.seats(3)).containsExactly(0)
            assertThat(test.scienceSha256).isNotEqualTo(control.scienceSha256)
        }
    }

    @Test
    fun publicCounterConfigurationIsExplicitAndIdentityBound() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "opponentStyle" to "REVEALED_UNDERCUT",
                                "adaptiveOpponent" to publicCounter,
                            ),
                        )
                        .path
                )

            assertThat(input.scienceIdentity["adaptiveOpponent"]).isEqualTo(publicCounter)
            assertThat(input.adaptiveOpponent?.targetedRivalCount).isEqualTo(1)
        }
    }

    @Test
    fun historyStylesCannotSilentlyChooseLearningParameters() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(fixture, mapOf("opponentStyle" to "REVEALED_UNDERCUT")).path
                )
            }
        }
    }

    @Test
    fun ordinaryStylesCannotAccidentallyEnablePublicHistory() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(fixture, mapOf("adaptiveOpponent" to publicCounter)).path
                )
            }
        }
    }

    @Test
    fun impossibleObservationMinimumIsRejected() {
        assertFailsWith<IllegalArgumentException> { RevealedTokenCounterConfig(3, 2, 1) }
    }

    @Test
    fun moreThanThreeTargetedRivalsIsRejected() {
        assertFailsWith<IllegalArgumentException> { RevealedTokenCounterConfig(2, 8, 4) }
    }

    @Test
    fun oldBalancedRotationRetainsItsOriginalFourEcologies() {
        val original =
            listOf(
                RobustOpponentStyle.QUALIFIED,
                RobustOpponentStyle.UNDERCUT,
                RobustOpponentStyle.OVERBID,
                RobustOpponentStyle.MIXED,
            )
        for (index in 0..15) {
            for (seat in 0..3) {
                assertThat(RobustOpponentStyle.BALANCED.at(index, seat))
                    .isEqualTo(original[(index / 4 + seat) % 4])
            }
        }
    }

    @Test
    fun revealedBalancedRotationCoversEachStyleEquallyInEveryControlledSeat() {
        for (seat in 0..3) {
            val counts =
                (0..511)
                    .groupingBy { RobustOpponentStyle.REVEALED_BALANCED.at(it, seat) }
                    .eachCount()
            assertThat(counts.keys)
                .containsExactly(
                    RobustOpponentStyle.REVEALED_UNDERCUT,
                    RobustOpponentStyle.REVEALED_OVERBID,
                    RobustOpponentStyle.REVEALED_TIE_UNDERCUT,
                    RobustOpponentStyle.REVEALED_TIE_OVERBID,
                )
            assertThat(counts.values).containsExactly(128, 128, 128, 128)
        }
    }

    @Test
    fun mixedTrainingEcologyRetainsHalfOrdinaryAndCrossedSeatStyleBalance() {
        for (seat in 0..3) {
            val counts =
                (0..287)
                    .filter { it % 4 == seat }
                    .groupingBy { RobustOpponentStyle.QUALIFIED_REVEALED_BALANCED.at(it, seat) }
                    .eachCount()
            assertThat(counts[RobustOpponentStyle.QUALIFIED]).isEqualTo(36)
            for (style in
                listOf(
                    RobustOpponentStyle.REVEALED_UNDERCUT,
                    RobustOpponentStyle.REVEALED_OVERBID,
                    RobustOpponentStyle.REVEALED_TIE_UNDERCUT,
                    RobustOpponentStyle.REVEALED_TIE_OVERBID,
                )) {
                assertThat(counts[style]).isEqualTo(9)
            }
        }
    }

    @Test
    fun theOrdinaryFractionIsPartOfTheMixedEcologyScienceIdentity() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "opponentStyle" to "QUALIFIED_REVEALED_BALANCED",
                                "adaptiveOpponent" to publicCounter,
                            ),
                        )
                        .path
                )

            assertThat(input.scienceIdentity["adaptiveOrdinaryFraction"]).isEqualTo(.5)
        }
    }

    @Test
    fun anAnchoredCandidateCannotSilentlyReplaceAnUnqualifiedToyBase() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val policy =
                fixture.save(
                    "not-a-qualified-anchor.json",
                    mapOf("kind" to TokenAnchoredPolicyInput.VERSION),
                )

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    RobustTokenInput.read(
                        source(
                                fixture,
                                mapOf("candidateTokenAnchoredPolicy" to fixture.ref(policy)),
                            )
                            .path
                    )
                }

            assertThat(failure).hasMessageThat().contains("requires frozen94")
        }
    }

    @Test
    fun typedLocalMixtureIsBoundOnlyWhenExplicitlyOffered() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val ordinary = RobustTokenInput.read(source(fixture).path)
            assertThat(ordinary.scienceIdentity).doesNotContainKey("tokenNeighborMixture")
            val mixture =
                mapOf(
                    "kind" to "local-token-neighbor-mixture-1",
                    "departureProbability" to .5,
                    "radius" to 2,
                    "admission" to "QUOTED_LEGAL_NEIGHBORS",
                )
            val experimental =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf("tokenNeighborMixture" to mixture),
                        )
                        .path
                )
            assertThat(experimental.scienceIdentity["tokenNeighborMixture"]).isEqualTo(mixture)
            assertThat(experimental.scienceSha256).isNotEqualTo(ordinary.scienceSha256)
        }
    }

    @Test
    fun localMixtureRejectsUnknownAdmissionInsteadOfSilentlyUsingOfferedActions() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "tokenNeighborMixture" to
                                    mapOf(
                                        "kind" to "local-token-neighbor-mixture-1",
                                        "departureProbability" to .5,
                                        "radius" to 2,
                                        "admission" to "ANY_LEGAL_ACTION",
                                    )
                            ),
                        )
                        .path
                )
            }
        }
    }

    private val denial =
        mapOf(
            "kind" to "public-leader-attack-bonus-1",
            "beta" to 1.0,
            "minimumWinProbability" to .5,
        )

    private fun alternateActor(fixture: CoordinatedWinQualificationFixture): TokenPairedSubject {
        val token =
            PurchaseJson.read(fixture.toy.original.subject(CoordinatedEsHead.TOKEN).path)
                as ObjectNode
        token.putArray("actorWeights").apply { repeat(64) { add(if (it == 0) .1 else 0.0) } }
        val changed = fixture.save("alternate-token.json", token)
        val bundle = PurchaseJson.read(fixture.toy.original.source.path) as ObjectNode
        (bundle["heads"]["TOKEN"] as ObjectNode)
            .put("path", "alternate-token.json")
            .put("sha256", changed.sha256)
        return fixture.save("alternate-control.json", bundle)
    }

    private fun source(
        fixture: CoordinatedWinQualificationFixture,
        extra: Map<String, Any?> = emptyMap(),
    ): TokenPairedSubject {
        val previous = PurchaseJson.read(fixture.source("homogeneous", fixture.confirmation()).path)
        return fixture.save(
            "robust-source.json",
            mapOf(
                "kind" to "robust-token-chunk-1",
                "protocolSha256" to "a".repeat(64),
                "inferenceAsset" to fixture.ref(fixture.asset),
                "nomination" to fixture.ref(fixture.nomination),
                "arena" to previous.required("arena"),
                "selector" to
                    mapOf(
                        "kind" to "near-optimal-token-softmax-1",
                        "temperature" to .7,
                        "maxScoreGap" to 1.25,
                        "neighborRadius" to 2,
                    ),
                "opponentStyle" to "BALANCED",
                "seeds" to listOf(11, 13),
                "globalStart" to 0,
                "count" to 2,
                "replayIndices" to listOf(0),
            ) + extra,
        )
    }

    @Test
    fun portablePrepareDecodesCompleteAssetWithoutExecutingAnyGames() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = RobustTokenInput.read(source(fixture).path)
            val record = input.prepared()
            assertThat(record["newGames"]).isEqualTo(0)
            assertThat(record["modelForwards"]).isEqualTo(0)
            assertThat(record["maximumTerminalExecutions"]).isEqualTo(6)
            assertThat(input.asset.current.source.sha256).isEqualTo(input.candidate.source.sha256)
            assertThat(input.scienceIdentity).doesNotContainKey("candidateTokenAnchoredPolicy")
        }
    }

    @Test
    fun shardCannotExtendPastItsFrozenWorldBank() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(source(fixture, mapOf("count" to 3)).path)
            }
        }
    }

    @Test
    fun candidateOnlyCollectionHasOneRoleAndAnExactReducedExecutionBound() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = RobustTokenInput.read(source(fixture, mapOf("candidateOnly" to true)).path)
            assertThat(input.roles).containsExactly("CANDIDATE")
            assertThat(input.prepared()["maximumTerminalExecutions"]).isEqualTo(3)
            assertThat(input.prepared()["qualificationClaimed"]).isEqualTo(false)
        }
    }

    @Test
    fun candidateOnlyFlagCannotBeAnAmbiguousString() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(source(fixture, mapOf("candidateOnly" to "true")).path)
            }
        }
    }

    @Test
    fun leaderDenialRequiresMatchedTokenPolicies() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(source(fixture, mapOf("expansionDenial" to denial)).path)
            }
        }
    }

    @Test
    fun leaderDenialCanUseAMatchedExperimentalControlBundle() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val model = alternateActor(fixture)
            val input =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "candidateBundle" to fixture.ref(model),
                                "controlBundle" to fixture.ref(model),
                                "selector" to null,
                                "controlSelector" to null,
                                "expansionDenial" to denial,
                            ),
                        )
                        .path
                )
            assertThat(input.control.source.sha256).isEqualTo(model.sha256)
            assertThat(input.candidate.source.sha256).isEqualTo(model.sha256)
            assertThat(input.scienceIdentity["controlAndRivalsBundleSha256"])
                .isEqualTo(model.sha256)
            assertThat(input.prepared()["qualificationClaimed"]).isEqualTo(false)
        }
    }

    @Test
    fun leaderDenialCannotConfoundDifferentCandidateAndControlModels() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val model = alternateActor(fixture)
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "candidateBundle" to fixture.ref(model),
                                "selector" to null,
                                "expansionDenial" to denial,
                            ),
                        )
                        .path
                )
            }
        }
    }

    @Test
    fun neutralOptionalSettingsPreserveThePreviousCompleteScientificIdentity() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val original = RobustTokenInput.read(source(fixture).path)
            val explicit =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf(
                                "controlBundle" to fixture.ref(fixture.toy.original.source),
                                "controlSelector" to null,
                                "expansionDenial" to null,
                            ),
                        )
                        .path
                )
            assertThat(original.scienceIdentity).isEqualTo(explicit.scienceIdentity)
            assertThat(original.scienceSha256).isEqualTo(explicit.scienceSha256)
        }
    }

    @Test
    fun shardBoundariesDoNotChangeScientificIdentity() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val first = RobustTokenInput.read(source(fixture, mapOf("count" to 1)).path)
            val second =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf("globalStart" to 1, "count" to 1),
                        )
                        .path
                )
            assertThat(first.scienceSha256).isEqualTo(second.scienceSha256)
        }
    }

    @Test
    fun experimentalBundleCannotChangeFrozenValueParameters() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val token =
                PurchaseJson.read(fixture.toy.original.subject(CoordinatedEsHead.TOKEN).path)
                    as ObjectNode
            token.put("valueBias", token["valueBias"].doubleValue() + 1.0)
            val changed = fixture.save("bad-token.json", token)
            val bundle = PurchaseJson.read(fixture.toy.original.source.path) as ObjectNode
            (bundle["heads"]["TOKEN"] as ObjectNode)
                .put("path", "bad-token.json")
                .put("sha256", changed.sha256)
            val candidate = fixture.save("bad-candidate.json", bundle)
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf("candidateBundle" to fixture.ref(candidate)),
                        )
                        .path
                )
            }
        }
    }
}
