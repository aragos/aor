package dev.aragos.aor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.JointPolicyScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Constructed tensors, views and transport success claims only; never research qualification. */
class RobustCoordinatedPreviewPolicyTest {
    @Test
    fun aCompleteSixGenerationCertificateLoadsTheExplicitRobustProfile() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        assertThat(policy.assetSha256).isEqualTo(fixture.hash(fixture.manifest))
    }

    @Test
    fun bothRegisteredDenialEvaluationSizesAreExplicitlySupported() {
        for (version in
            listOf(
                RobustQualificationVersion.DENIAL_FULL,
                RobustQualificationVersion.DENIAL_SMALL,
            )) {
            withFixture(version) { fixture ->
                assertThat(CoordinatedPreviewPolicy.read(fixture.manifest).profile)
                    .isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
            }
        }
    }

    @Test
    fun denialQualificationRequiresItsOriginalDataMode() =
        withFixture(RobustQualificationVersion.DENIAL_FULL) { fixture ->
            fixture.changeResearchProtocol { it.put("dataMode", "SMALL") }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }
        }

    @Test
    fun denialQualificationRequiresItsApprovedMethodBytes() =
        withFixture(RobustQualificationVersion.DENIAL_FULL) { fixture ->
            fixture.changeResearchProtocol { it.put("methodSha256", "0".repeat(64)) }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }
        }

    @Test
    fun denialQualificationCountsOriginalSeedArraysRatherThanAnInventedCount() =
        withFixture(RobustQualificationVersion.DENIAL_FULL) { fixture ->
            fixture.changeResearchProtocol {
                (it["banks"]["mixed"] as ObjectNode).apply {
                    remove("seeds")
                    put("count", 2048)
                }
            }

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedPreviewPolicy.read(fixture.manifest)
                }

            assertThat(failure).hasMessageThat().contains("registered mixed world prefix")
        }

    @Test
    fun denialQualificationCannotWeakenTheOriginalConfidenceRule() =
        withFixture(RobustQualificationVersion.DENIAL_SMALL) { fixture ->
            fixture.changeResearchProtocol { it.put("confidenceMultiplier", 1.96) }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }
        }

    @Test
    fun aSmallCertificateCannotReuseFullProtocolEvidence() = withFixture { fixture ->
        fixture.changeQualification { it.put("evaluationVersion", "study90-SMALL1") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anUnregisteredEvaluationCannotChooseItsOwnPairCount() = withFixture { fixture ->
        fixture.changeQualification { it.put("evaluationVersion", "custom-32-pairs") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun incompleteHomogeneousQualificationRejectsBeforeTensorDecode() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["homogeneous"] as ObjectNode).put("complete", false)
        }
        Files.writeString(fixture.root.resolve("candidate/heads/CARD/policy.json"), "not a model")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.manifest)
            }

        assertThat(failure).hasMessageThat().contains("99% win gate")
    }

    @Test
    fun eitherFailedWinGateRejectsTheWholePolicy() = withFixture { fixture ->
        fixture.changeQualification {
            (it["banks"]["mixed"] as ObjectNode).put("win99Passed", false)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun actualProtocolCountsMustMatchTheRegisteredSize() = withFixture { fixture ->
        fixture.changeResearchProtocol { (it["banks"]["mixed"] as ObjectNode).put("count", 1024) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun eightGenerationTrainingCannotClaimTheSixGenerationCertificate() = withFixture { fixture ->
        fixture.changeResearchProtocol { it.put("activeGenerations", 8) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun anotherSamplerCannotReuseAnExistingCertificate() = withFixture { fixture ->
        val file = fixture.root.resolve("robust-inference-asset.json")
        val value = ObjectMapper().readTree(file.toFile()) as ObjectNode
        (value["tokenSelector"] as ObjectNode).put("temperature", 0.8)
        fixture.write("robust-inference-asset.json", value)
        fixture.changeManifest {
            (it["inferenceAsset"] as ObjectNode).put("sha256", fixture.hash(file))
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun unknownSamplerSemanticsAreRejected() = withFixture { fixture ->
        fixture.changeRobustInference {
            (it["tokenSelector"] as ObjectNode).put("kind", "arbitrary-jitter")
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun invalidSamplerRangesAreOwnedByTheSharedConfiguration() = withFixture { fixture ->
        fixture.changeRobustInference {
            (it["tokenSelector"] as ObjectNode).put("temperature", 0.0)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun unknownDenialSemanticsAreRejected() = withFixture { fixture ->
        fixture.changeRobustInference {
            it.set<ObjectNode>(
                "expansionDenial",
                ObjectMapper()
                    .valueToTree(
                        mapOf(
                            "kind" to "always-attack",
                            "beta" to 1.0,
                            "minimumWinProbability" to 0.5,
                        )
                    ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.manifest)
        }
    }

    @Test
    fun nullOptionsRetainOldWinProfileTokenAndExpansionChoices() =
        withFixture(sampler = null) { fixture ->
            val old = PreviewAssetFixture(fixture.root.resolve("legacy"))
            old.toWinQualified()
            val legacy = CoordinatedPreviewPolicy.read(old.manifest)
            val robust = CoordinatedPreviewPolicy.read(fixture.manifest)

            for (view in listOf(tokenView(), expansionView())) {
                val observation = observation(view)
                assertThat((0..7).map { robust.choose(view, observation, it) })
                    .isEqualTo((0..7).map { legacy.choose(view, observation, it) })
            }
        }

    @Test
    fun theCachedSamplerUsesTheExistingSeedAndOfferedAmounts() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        val view = tokenView()
        val observation = observation(view)

        val commands = (0..31).map { policy.choose(view, observation, it) }

        assertThat(commands.toSet().size).isGreaterThan(1)
        assertThat(commands.all { it in setOf("buy-tokens 14", "buy-tokens 15", "buy-tokens 16") })
            .isTrue()
    }

    @Test
    fun theSameDecisionSeedRecomputesTheSameStochasticProposal() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
        val view = tokenView()
        val observation = observation(view)

        assertThat(policy.choose(view, observation, 1234))
            .isEqualTo(policy.choose(view, observation, 1234))
    }

    @Test
    fun cachedDenialUsesTheSharedLeadingRivalAdjustment() =
        withFixture(
            sampler = null,
            denial =
                mapOf(
                    "kind" to "public-leader-attack-bonus-1",
                    "beta" to 2.0,
                    "minimumWinProbability" to 0.5,
                ),
        ) { fixture ->
            val policy = CoordinatedPreviewPolicy.read(fixture.manifest)
            val view = expansionView()

            assertThat(policy.choose(view, observation(view), 7)).isEqualTo("attack toledo")
        }

    @Test
    fun oldWinQualificationStillRequiresItsOriginal4096Pairs() {
        val directory = Files.createTempDirectory("constructed-old-win-size-")
        try {
            val fixture = PreviewAssetFixture(directory)
            fixture.toWinQualified()
            fixture.changeQualification { (it["banks"]["mixed"] as ObjectNode).put("pairs", 2048) }

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    CoordinatedPreviewPolicy.read(fixture.manifest)
                }

            assertThat(failure).hasMessageThat().contains("4096-pair")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /**
     * Generated only for isolated production-StrictMode transport tests, never strength evidence.
     */
    @Test
    fun exportsExplicitlyConstructedRobustTransportFixture() {
        val fixture =
            PreviewAssetFixture(
                Path.of("build/test-fixtures/robust-coordinated-preview").toAbsolutePath()
            )
        fixture.toRobustQualified()
        fixture.write(
            "constructed-fixture.json",
            mapOf(
                "kind" to "constructed-robust-coordinated-preview-fixture-1",
                "constructedOnly" to true,
                "realModels" to 0,
                "scientificGames" to 0,
                "qualificationClaimsInventedForTransportTest" to true,
            ),
        )

        assertThat(CoordinatedPreviewPolicy.read(fixture.manifest).profile)
            .isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
    }

    private fun withFixture(
        version: RobustQualificationVersion = RobustQualificationVersion.LEAGUE_SIX,
        sampler: Map<String, Any>? = defaultSampler,
        denial: Map<String, Any>? = null,
        block: (PreviewAssetFixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("constructed-robust-qualified-")
        try {
            val fixture = PreviewAssetFixture(directory)
            fixture.toRobustQualified(version, sampler, denial)
            block(fixture)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun observation(view: GameView): BotObservation =
        if (view.stage == Stage.TOKEN_PURCHASE) {
            BotObservations.from(
                view,
                LegalChoices(view.tokenPurchaseOffers.map { "buy-tokens ${it.amount}" }),
                actor,
                "buy-tokens",
            )
        } else {
            BotObservation(
                playerId = actor.value,
                decision = "expand",
                context = emptyMap(),
                availableCash = 30,
                availableTokens = 10,
                candidates =
                    listOf("attack basel", "attack toledo", "pass").map {
                        BotCandidate(it, emptyMap(), family = "expansion")
                    },
                rulesVersion = JointPolicyScope.RULES_VERSION,
                dataVersion = JointPolicyScope.DATA_VERSION,
                configuration = JointPolicyScope.configuration,
            )
        }

    private fun tokenView(): GameView =
        view(Stage.TOKEN_PURCHASE)
            .copy(
                tokenPurchaseOffers =
                    (14..16).map { TokenPurchaseOfferView(it, it, it, 30 - it, 36 - it, it) }
            )

    private fun expansionView(): GameView =
        view(Stage.EXPANSION)
            .copy(
                board =
                    listOf(
                        ProvinceView(
                            ProvinceId("basel"),
                            MarkerView(PlayerId("p3"), PieceSide.COLOURED),
                        ),
                        ProvinceView(
                            ProvinceId("toledo"),
                            MarkerView(PlayerId("p2"), PieceSide.COLOURED),
                        ),
                    ),
                scores =
                    listOf(40, 100, 80, 60).mapIndexed { index, score ->
                        ScoreView(PlayerId("p${index + 1}"), 0, score, 0, score, false)
                    },
                expansionOffers =
                    listOf("basel", "toledo").map {
                        ExpansionOfferView(
                            destination = ProvinceId(it),
                            primaryAction = ExpansionAction.ATTACK,
                            requiredTokens = 2,
                            availablePool = 10,
                            resultingPool = 8,
                            establishesMarker = true,
                            uncertain = true,
                            blockers = emptyList(),
                            partialPlacements = emptyList(),
                            shipping = null,
                            incomeBefore = 0,
                            incomeAfter = null,
                            reachable = true,
                            competitionWinProbability = 0.9,
                        )
                    },
            )

    private fun view(stage: Stage): GameView {
        val rules =
            RuleOptions(
                deckModification = true,
                initialCard = InitialCardRule.NO_CRUSADES,
                immediateMasterArtDiscard = true,
            )
        val ids = (1..4).map { PlayerId("p$it") }
        return GameView(
            stage = stage,
            currentEpoch = 1,
            rules = rules,
            ruleOptions = emptyList(),
            rebuiltMarkerBonus = RebuiltMarkerBonus.EXCLUDE,
            players =
                ids.map {
                    PlayerView(
                        id = it,
                        name = it.value,
                        cash = 30,
                        writtenCash = 30,
                        capital = null,
                        handCount = 0,
                        poolTokens = if (stage == Stage.EXPANSION) 10 else 0,
                        boardMarkers = 0,
                        poolAvailable = stage == Stage.EXPANSION,
                    )
                },
            hand = emptyList(),
            capitalOrder = ids,
            revealedBids = emptyMap(),
            turnNumber = 3,
            turnOrder = ids,
            orderBoxes = emptyMap(),
            revealedTokenPurchases = emptyMap(),
            provisionalTieOrder = emptyList(),
            catalog = PublicCatalog.forPlayers(4, rules),
        )
    }

    companion object {
        private val actor = PlayerId("p1")
        private val defaultSampler: Map<String, Any> =
            mapOf(
                "kind" to "near-optimal-token-softmax-1",
                "temperature" to 0.7,
                "maxScoreGap" to 3.0,
                "neighborRadius" to 4,
            )
    }
}
