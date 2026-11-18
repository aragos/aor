package dev.aragos.aor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented tensors and original-record claims only; no research asset, game or model is read. */
class SearchCoordinatedPreviewPolicyTest {
    @Test
    fun theThreeCompleteStudy92BanksLoadTheSelectedFullPolicy() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.base.manifest)

        assertThat(policy.profile).isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
    }

    @Test
    fun anInventedCountCannotReplaceActualPreparedWorldArrays() = withFixture { fixture ->
        fixture.changeProtocol {
            (it["banks"]["mixed"] as ObjectNode).apply {
                remove("seeds")
                put("count", 512)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun theOld87RegressionBankCannotBeOmitted() = withFixture { fixture ->
        fixture.base.changeQualification { (it["banks"] as ObjectNode).remove("reference") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun strongBaseConcentrationCannotBeHiddenBySuccessfulWinClaims() = withFixture { fixture ->
        fixture.change("report-mixed") {
            (it["cases"]["s00"]["observedBids"]["CANDIDATE"] as ObjectNode).apply {
                set<ObjectNode>("counts", mapper.valueToTree(mapOf("21" to 90, "22" to 10)))
                put("maximumSinglePositiveAmountCount", 90)
                put("maximumSinglePositiveAmountShare", .9)
                put("observedConcentrationPassed", false)
            }
        }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        assertThat(failure).hasMessageThat().contains("concentration gate")
    }

    @Test
    fun aConcentrationFlagCannotReplaceTheRecordedHistogram() = withFixture { fixture ->
        fixture.change("report-homogeneous") {
            (it["cases"]["s00"]["observedBids"]["CANDIDATE"]["counts"] as ObjectNode).put("21", 500)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun anotherSamplerCannotReuseTheSelectedQualification() = withFixture { fixture ->
        fixture.base.changeRobustInference {
            (it["tokenSelector"] as ObjectNode).put("temperature", 1.5)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun onlyTheFrozenEightCandidateConfigurationsAreAdmitted() = withFixture { fixture ->
        fixture.changeProtocol {
            (it["caseConfigs"][7]["selector"] as ObjectNode).put("temperature", 7)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aDifferentDevelopmentNomineeCannotReuseTheOriginalEvidence() = withFixture { fixture ->
        fixture.change("selection") {
            it.set<ObjectNode>("case", mapper.valueToTree(fixture.cases[1]))
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aWeakerConfidenceRuleCannotClaimTheRegisteredSearch() = withFixture { fixture ->
        fixture.changeProtocol { it.put("confidenceMultiplier", 1.96) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun anIncompleteOriginalAuditRejectsBeforeTensorDecode() = withFixture { fixture ->
        fixture.change("completionAudit") { it.put("complete", false) }
        Files.writeString(fixture.root.resolve("candidate/heads/CARD/policy.json"), "not a model")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        assertThat(failure).hasMessageThat().contains("sealed original search audit")
    }

    @Test
    fun aQualified92AnchorRetainsItsSamplerAndSelectedLeaderBonus() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH) { fixture ->
            assertThat(CoordinatedPreviewPolicy.read(fixture.base.manifest).profile)
                .isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        }

    @Test
    fun bothQualified91AndVerified87FallbackLineagesAreSupported() {
        for (origin in listOf("91", "87")) {
            withFixture(RobustQualificationVersion.LEADER_SEARCH, origin) { fixture ->
                assertThat(CoordinatedPreviewPolicy.read(fixture.base.manifest).profile)
                    .isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
            }
        }
    }

    @Test
    fun aLeaderBonusCannotChangeTheQualifiedAnchorSampler() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH) { fixture ->
            fixture.base.changeRobustInference {
                (it["tokenSelector"] as ObjectNode).put("temperature", 8)
            }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        }

    @Test
    fun aQualified92WinnerCannotBeSkippedForAnotherAnchor() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH, "91") { fixture ->
            fixture.change("previousClosure") { it.put("qualified", true) }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        }

    @Test
    fun aDifferentPortableAnchorCertificateCannotReplaceTheOriginal() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH) { fixture ->
            fixture.change("anchorQualification") { it.put("terminalExecutions", 5199) }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        }

    @Test
    fun anotherOriginalProtocolCannotReplaceTheQualifiedAnchorSource() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH) { fixture ->
            fixture.change("previousClosure") { it.put("protocolSha256", "0".repeat(64)) }

            assertFailsWith<IllegalArgumentException> {
                CoordinatedPreviewPolicy.read(fixture.base.manifest)
            }
        }

    @Test
    fun source93HasNoUnapprovedConcentrationGate() =
        withFixture(RobustQualificationVersion.LEADER_SEARCH) { fixture ->
            fixture.change("report-mixed") {
                (it["cases"]["l00"]["observedBids"]["CANDIDATE"] as ObjectNode).apply {
                    set<ObjectNode>("counts", mapper.valueToTree(mapOf("21" to 100)))
                    put("maximumSinglePositiveAmountCount", 100)
                    put("maximumSinglePositiveAmountShare", 1.0)
                    put("observedConcentrationPassed", false)
                }
            }

            assertThat(CoordinatedPreviewPolicy.read(fixture.base.manifest).profile)
                .isEqualTo(ServerBotProfile.COORDINATED_ROBUST_SUPPORT_1)
        }

    private fun withFixture(
        version: RobustQualificationVersion = RobustQualificationVersion.LEARNED_BID_SEARCH,
        anchor: String = "92",
        block: (SearchPreviewFixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("constructed-policy-search-")
        try {
            block(SearchPreviewFixture(directory, version, anchor))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private val mapper = ObjectMapper()
}

/** Producer-shaped records and every tensor/success claim are explicitly invented test data. */
private class SearchPreviewFixture(
    val root: Path,
    private val version: RobustQualificationVersion,
    private val anchorOrigin: String,
) {
    val base = PreviewAssetFixture(root)
    private val mapper = ObjectMapper()
    private val bidSearch = version == RobustQualificationVersion.LEARNED_BID_SEARCH
    private val prefix = if (bidSearch) "learned-bid-search" else "mac-leader-search"
    private val study = if (bidSearch) 92 else 93
    private val method = if (bidSearch) BID_METHOD else LEADER_METHOD
    private val native = "14245cba2d1dbd4e62780371efd84d4416bd91592ceca876449d7f417756463f"
    private val bidCases =
        (0..7).map { index ->
            mapOf(
                "id" to "s0$index",
                "selector" to
                    mapOf(
                        "kind" to "near-optimal-token-softmax-1",
                        "temperature" to listOf(.7, 1.5, 3.0, 8.0)[index % 4],
                        "maxScoreGap" to if (index < 4) 6 else 16,
                        "neighborRadius" to if (index < 4) 4 else 8,
                    ),
            )
        }
    val cases =
        if (bidSearch) bidCases
        else
            (0..7).map { index ->
                mapOf(
                    "id" to "l0$index",
                    "expansionDenial" to
                        mapOf(
                            "kind" to "public-leader-attack-bonus-1",
                            "beta" to listOf(.25, .5, 1.0, 2.0)[index / 2],
                            "minimumWinProbability" to if (index % 2 == 0) .5 else .8,
                        ),
                )
            }
    private val sampler =
        when {
            bidSearch || anchorOrigin == "92" -> bidCases[0]["selector"] as Map<*, *>
            anchorOrigin == "91" ->
                mapOf(
                    "kind" to "near-optimal-token-softmax-1",
                    "temperature" to .35,
                    "maxScoreGap" to 3,
                    "neighborRadius" to 4,
                )
            else -> null
        }
    private val bankNames = listOf("development") + version.banks
    private val evidenceNames =
        setOf(
            "profile",
            "sourceReadiness",
            "selection",
            "developmentReport",
            "completionAudit",
            "physicalClosure",
        ) +
            if (bidSearch) emptySet()
            else {
                setOf(
                    "anchorFreeze",
                    "previousTerminal",
                    "previousClosure",
                    "study91Closure",
                    "anchorQualification",
                ) + if (anchorOrigin == "87") emptySet() else setOf("anchorSourceProtocol")
            }

    init {
        @Suppress("UNCHECKED_CAST")
        base.toRobustQualified(
            sampler = sampler as Map<String, Any>?,
            denial = if (bidSearch) null else cases[0]["expansionDenial"] as Map<String, Any>,
        )
        val bundle = base.hash(root.resolve("candidate/bundle.json"))
        val asset = base.hash(root.resolve("inference-asset.json"))
        val banks =
            bankNames
                .mapIndexed { index, name ->
                    val count =
                        if (name == "development") {
                            if (bidSearch) 128 else 64
                        } else version.pairs
                    val seeds = List(count) { index * 10_000 + it }
                    name to
                        (mapOf(
                            "name" to name,
                            "worlds" to count,
                            "seeds" to seeds,
                            "opponentStyle" to
                                if (name in listOf("development", "mixed")) "BALANCED"
                                else "QUALIFIED",
                            "replayIndices" to
                                when {
                                    name == "development" && bidSearch -> listOf(0, 42, 85, 127)
                                    name == "development" -> listOf(0, 21, 42, 63)
                                    bidSearch -> listOf(0, 170, 341, 511)
                                    else -> listOf(0, 85, 170, 255)
                                },
                        ) +
                            if (bidSearch)
                                mapOf("control" to if (name == "reference") "OLD87" else "LEARNED")
                            else emptyMap())
                }
                .toMap()
        val protocol =
            mapOf(
                "kind" to "$prefix-1",
                "study" to study,
                "methodSha256" to method,
                "maximumTerminalExecutions" to version.maximumTerminalExecutions,
                "chunkSize" to 256,
                "confidenceMultiplier" to 2.5758293035489004,
                "nativeJarSha256" to native,
                "nominationSha256" to "1".repeat(64),
                "inferenceAssetSha256" to asset,
                "caseConfigs" to cases,
                "banks" to banks,
                "reservedSchedule" to
                    mapOf(
                        "kind" to "$prefix-schedule-1",
                        "banks" to
                            banks.mapValues { (_, bank) ->
                                mapOf("count" to bank["worlds"], "seeds" to bank["seeds"])
                            },
                    ),
            )
        base.write(
            "research-protocol.json",
            protocol +
                if (bidSearch) {
                    mapOf(
                        "candidateBundleSha256" to bundle,
                        "learnedControlBundleSha256" to bundle,
                        "learnedControlSelector" to
                            mapOf(
                                "kind" to "near-optimal-token-softmax-1",
                                "temperature" to .35,
                                "maxScoreGap" to 3,
                                "neighborRadius" to 4,
                            ),
                        "oldControlSelector" to null,
                        "oldControlBundleSha256" to bundle,
                        "positiveBidConcentrationMaximum" to .8,
                    )
                } else anchor(bundle, asset),
        )
        write(
            "sourceReadiness",
            mapOf(
                "kind" to "$prefix-runner-source-readiness-1",
                "passed" to true,
                "sourceOnly" to true,
                "nativeJarSha256" to native,
            ) +
                if (bidSearch) {
                    mapOf(
                        "method" to
                            mapOf("path" to "/unopened-original/method.json", "sha256" to method)
                    )
                } else mapOf("methodSha256" to method),
        )
        write(
            "profile",
            mapOf(
                "kind" to "mac-policy-search-host-1",
                "study" to study,
                "candidateBundle" to archived("candidate/bundle.json", bundle),
                "nativeJar" to archived("training.jar", native),
                "inferenceAsset" to archived("inference-asset.json", asset),
                "nomination" to archived("nomination.json", "1".repeat(64)),
                "pins" to emptyMap<String, String>(),
            ),
        )
        write("selection", mapOf("complete" to true, "case" to cases[0]))
        write(
            "developmentReport",
            mapOf(
                "complete" to true,
                "bank" to "development",
                "cases" to
                    cases
                        .mapIndexed { index, case ->
                            case["id"] to
                                statistics(if (bidSearch) 128 else 64, if (index == 0) .3 else .1)
                        }
                        .toMap(),
            ),
        )
        for (name in version.banks) write(
            "report-$name",
            mapOf(
                "complete" to true,
                "bank" to name,
                "cases" to mapOf(cases[0]["id"] to statistics(version.pairs, .3)),
            ),
        )
        write(
            "completionAudit",
            mapOf(
                "kind" to "$prefix-original-audit-1",
                "passed" to true,
                "complete" to true,
                "qualified" to true,
                "terminalExecutions" to version.maximumTerminalExecutions,
                "selectedCase" to cases[0],
                "originalReproductionsVerified" to true,
                "allWin99StatisticsRecomputed" to true,
                "allObservedConcentrationStatisticsRecomputed" to true,
                "clockSha256" to "2".repeat(64),
            ),
        )
        write(
            "physicalClosure",
            mapOf(
                "kind" to "$prefix-physical-closure-1",
                "passed" to true,
                "scienceComplete" to true,
                "qualified" to true,
                "allOwnedProcessesExited" to true,
                "originalClocksRenewed" to false,
                "auditReruns" to 0,
                "actualCompletedLowerBound" to version.maximumTerminalExecutions,
                "actualAttemptedUpperBound" to version.maximumTerminalExecutions,
                "clockSha256" to "2".repeat(64),
            ),
        )
        rebind()
    }

    private fun anchor(bundle: String, asset: String): Map<String, Any?> {
        if (anchorOrigin != "87")
            write(
                "anchorSourceProtocol",
                mapOf(
                    "kind" to
                        if (anchorOrigin == "92") "learned-bid-search-1"
                        else "mac-qualification-campaign-1",
                    "candidateBundleSha256" to bundle,
                    "methodSha256" to BID_METHOD,
                    "rootProspectiveMethodSha256" to
                        "0687dabf2cb25e1a88740be9a8b29ec5a21a4151b1e7f9df73991de5a76b489b",
                    "selector" to sampler,
                ),
            )
        write(
            "anchorQualification",
            mapOf(
                "kind" to
                    if (anchorOrigin == "92") "learned-bid-search-original-audit-1"
                    else "mac-qualification-original-audit-1",
                "passed" to true,
                "complete" to true,
                "qualified" to true,
                "terminalExecutions" to if (anchorOrigin == "92") 5208 else 8208,
                "protocolSha256" to
                    if (anchorOrigin == "87") "3".repeat(64) else hash("anchorSourceProtocol"),
                "selectedCase" to bidCases[0],
            ),
        )
        write(
            "study91Closure",
            mapOf(
                "kind" to "mac-qualification-physical-closure-1",
                "passed" to true,
                "allOwnedProcessesExited" to true,
                "qualified" to (anchorOrigin != "87"),
                "scienceComplete" to (anchorOrigin != "87"),
                "actualCompletedLowerBound" to 8208,
                "protocolSha256" to
                    if (anchorOrigin == "91") hash("anchorSourceProtocol") else "3".repeat(64),
                "artifactPins" to
                    if (anchorOrigin == "91")
                        mapOf("/original/completion-audit.json" to hash("anchorQualification"))
                    else emptyMap(),
            ),
        )
        write(
            "previousTerminal",
            mapOf(
                "complete" to (anchorOrigin == "92"),
                "protocolSha256" to
                    if (anchorOrigin == "92") hash("anchorSourceProtocol") else "4".repeat(64),
                "completionAuditSha256" to hash("anchorQualification"),
                "selectedCase" to bidCases[0],
            ),
        )
        write(
            "previousClosure",
            mapOf(
                "kind" to "learned-bid-search-physical-closure-1",
                "passed" to true,
                "allOwnedProcessesExited" to true,
                "qualified" to (anchorOrigin == "92"),
                "scienceComplete" to (anchorOrigin == "92"),
                "actualCompletedLowerBound" to 5208,
                "protocolSha256" to
                    if (anchorOrigin == "92") hash("anchorSourceProtocol") else "4".repeat(64),
                "artifactPins" to
                    mapOf(
                        "/original/terminal.json" to hash("previousTerminal"),
                        "/original/completion-audit.json" to hash("anchorQualification"),
                    ),
            ),
        )
        val anchor =
            mapOf(
                "origin" to anchorOrigin,
                "bundleSha256" to bundle,
                "selector" to sampler,
                "qualificationSha256" to hash("anchorQualification"),
            )
        write(
            "anchorFreeze",
            mapOf(
                "kind" to "mac-leader-search-anchor-freeze-1",
                "passed" to true,
                "complete" to true,
                "qualified" to true,
                "methodSha256" to LEADER_METHOD,
                "study91Closure" to archived("study91Closure.json", hash("study91Closure")),
                "old87Certificate" to archived("certificate.json", hash("anchorQualification")),
                "old87Lineage" to
                    mapOf(
                        "qualified" to true,
                        "bundleSha256" to bundle,
                        "inferenceAssetSha256" to asset,
                        "nominationSha256" to "1".repeat(64),
                    ),
            ) + anchor,
        )
        return mapOf(
            "anchor" to anchor,
            "anchorFreeze" to archived("anchorFreeze.json", hash("anchorFreeze")),
            "parentTerminal" to archived("previousTerminal.json", hash("previousTerminal")),
            "parentClosure" to archived("previousClosure.json", hash("previousClosure")),
        )
    }

    fun change(name: String, transform: (ObjectNode) -> Unit) {
        val value = read(name)
        transform(value)
        write(name, value)
        rebind()
    }

    fun changeProtocol(transform: (ObjectNode) -> Unit) {
        base.changeResearchProtocol(transform)
        rebind()
    }

    private fun rebind() {
        if (!bidSearch) {
            val protocol =
                mapper.readTree(root.resolve("research-protocol.json").toFile()) as ObjectNode
            for ((key, name) in
                listOf(
                    "anchorFreeze" to "anchorFreeze",
                    "parentTerminal" to "previousTerminal",
                    "parentClosure" to "previousClosure",
                )) {
                (protocol[key] as ObjectNode).put("sha256", hash(name))
            }
            base.write("research-protocol.json", protocol)
        }
        val protocolHash = base.hash(root.resolve("research-protocol.json"))
        val profile = read("profile")
        profile.set<ObjectNode>(
            "pins",
            mapper.valueToTree(
                mapOf(
                    "/original/protocol.json" to protocolHash,
                    "/original/source-readiness.json" to hash("sourceReadiness"),
                )
            ),
        )
        write("profile", profile)
        for (name in
            listOf("selection", "developmentReport") + version.banks.map { "report-$it" }) {
            val value = read(name)
            value.put("protocolSha256", protocolHash)
            write(name, value)
        }
        val pins =
            (listOf("profile", "selection", "developmentReport") +
                    version.banks.map { "report-$it" })
                .associate { "/original/$it.json" to hash(it) } +
                ("/original/protocol.json" to protocolHash)
        val audit = read("completionAudit")
        audit.put("protocolSha256", protocolHash)
        audit.put("profileSha256", hash("profile"))
        audit.set<ObjectNode>("artifactPins", mapper.valueToTree(pins))
        write("completionAudit", audit)
        val closed = read("physicalClosure")
        closed.put("protocolSha256", protocolHash)
        closed.set<ObjectNode>(
            "artifactPins",
            mapper.valueToTree(
                pins + ("/original/completion-audit.json" to hash("completionAudit"))
            ),
        )
        write("physicalClosure", closed)
        base.changeQualification { certificate ->
            certificate.put("evaluationVersion", version.wireValue)
            certificate.set<ObjectNode>(
                "protocol",
                mapper.valueToTree(base.subject(root.resolve("research-protocol.json"))),
            )
            certificate.set<ObjectNode>(
                "searchEvidence",
                mapper.valueToTree(evidenceNames.associateWith { base.subject(path(it)) }),
            )
            certificate.set<ObjectNode>(
                "banks",
                mapper.valueToTree(
                    version.banks.associateWith { name ->
                        mapOf(
                            "complete" to true,
                            "pairs" to version.pairs,
                            "win99Passed" to true,
                            "evidence" to base.subject(path("completionAudit")),
                            "report" to base.subject(path("report-$name")),
                        )
                    }
                ),
            )
        }
    }

    private fun statistics(count: Int, mean: Double): Map<String, Any> =
        mapOf(
            "wins" to mapOf("count" to count, "mean" to mean, "lower99" to .05),
            "observedBids" to
                mapOf(
                    "CANDIDATE" to
                        mapOf(
                            "counts" to mapOf("21" to 50, "22" to 50),
                            "positiveCount" to 100,
                            "maximumSinglePositiveAmountCount" to 50,
                            "maximumSinglePositiveAmountShare" to .5,
                            "observedConcentrationPassed" to true,
                        )
                ),
        )

    private fun path(name: String): Path = root.resolve("search/$name.json")

    private fun hash(name: String): String = base.hash(path(name))

    private fun read(name: String): ObjectNode = mapper.readTree(path(name).toFile()) as ObjectNode

    private fun write(name: String, value: Any) = base.write("search/$name.json", value)

    private fun archived(name: String, sha256: String) =
        mapOf("path" to "/unopened-original/$name", "sha256" to sha256)

    companion object {
        private const val BID_METHOD =
            "82debbe40373aaa5986a7fe79616598e0d9d6e6ec5e3b51959329c7253cbce36"
        private const val LEADER_METHOD =
            "20a3a84a7c03a601fffffe20fe08c691ff22e691b87b992b22a1d5fb17ee79fa"
    }
}
