package dev.aragos.aor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented tensors and producer-shaped claims only; no research records or complete games. */
class LocalMixtureCoordinatedPreviewPolicyTest {
    @Test
    fun anotherConsistentlyReboundNativeBuildCannotReuseStudy95() = withFixture { fixture ->
        fixture.substituteNativeBuild("0".repeat(64))

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aCompleteLocalMixtureQualificationLoadsItsFullPolicyIdentity() = withFixture { fixture ->
        val policy = CoordinatedPreviewPolicy.read(fixture.base.manifest)

        assertThat(policy.tokenNeighborMixture?.departureProbability).isEqualTo(.25)
        assertThat(policy.assetSha256).isEqualTo(fixture.base.hash(fixture.base.manifest))
    }

    @Test
    fun anEarlierCertificateCannotAuthorizeNewMixtureSemantics() = withFixture { fixture ->
        fixture.base.changeQualification { it.put("evaluationVersion", "study94-QUAL1") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aDifferentMixtureCannotReuseTheSelectedDevelopmentNominee() = withFixture { fixture ->
        fixture.base.changeRobustInference {
            (it["tokenNeighborMixture"] as ObjectNode).put("departureProbability", .5)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun omissionOfTheReference87BankCannotQualify() = withFixture { fixture ->
        fixture.base.changeQualification { (it["banks"] as ObjectNode).remove("reference") }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun anUnqualifiedBaseline94CannotAuthorizeTheDepartureKernel() = withFixture { fixture ->
        fixture.change("qualifiedBaseline94CompletionAudit") { it.put("qualified", false) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aLivePreceding93ProcessCannotBeIgnored() = withFixture { fixture ->
        fixture.change("previousClosure") { it.put("allOwnedProcessesExited", false) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun aFailedReferenceWinGateCannotBeHiddenByTheTwoOtherBanks() = withFixture { fixture ->
        fixture.change("report-reference") {
            (it["cases"]["n00"]["wins"] as ObjectNode).put("lower99", 0)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun highObservedConcentrationCannotBeHiddenByWinClaims() = withFixture { fixture ->
        fixture.change("report-mixed") {
            val bids = it["cases"]["n00"]["observedBids"]["CANDIDATE"] as ObjectNode
            bids.set<ObjectNode>("counts", mapper.valueToTree(mapOf("21" to 10)))
            bids.put("maximumSinglePositiveAmountCount", 10)
            bids.put("maximumSinglePositiveAmountShare", 1)
            bids.put("observedConcentrationPassed", false)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun newOffersCannotBeSmuggledIntoTheVersionOneDescriptor() = withFixture { fixture ->
        fixture.base.changeRobustInference {
            it.put("kind", "coordinated-robust-inference-asset-1")
            it.put("schemaVersion", 1)
        }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    @Test
    fun anInventedWorldCountCannotReplaceTheReservedSeedArrays() = withFixture { fixture ->
        fixture.change("protocol") { (it["banks"]["mixed"] as ObjectNode).put("worlds", 128) }

        assertFailsWith<IllegalArgumentException> {
            CoordinatedPreviewPolicy.read(fixture.base.manifest)
        }
    }

    private fun withFixture(block: (LocalMixturePreviewFixture) -> Unit) {
        val root = Files.createTempDirectory("constructed-local-mixture-preview-")
        try {
            block(LocalMixturePreviewFixture(root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    companion object {
        private val mapper = ObjectMapper()
    }
}

private class LocalMixturePreviewFixture(private val root: Path) {
    val base = PreviewAssetFixture(root)
    private val mapper = ObjectMapper()
    private val documents = mutableMapOf<String, ObjectNode>()
    private val digest = "4".repeat(64)
    private val native = LOCAL_BID_MIXTURE_NATIVE_JAR_SHA256
    private val sampler =
        mapOf(
            "kind" to "near-optimal-token-softmax-1",
            "temperature" to .35,
            "maxScoreGap" to 3,
            "neighborRadius" to 4,
        )
    private val counts =
        mapOf("development" to 64, "mixed" to 256, "homogeneous" to 256, "reference" to 256)
    private val cases =
        (0..7).map { index ->
            mapOf(
                "id" to "n0$index",
                "tokenNeighborMixture" to
                    mapOf(
                        "kind" to "local-token-neighbor-mixture-1",
                        "departureProbability" to if (index % 2 == 0) .25 else .5,
                        "radius" to if (index % 4 < 2) 1 else 2,
                        "admission" to if (index < 4) "OFFERED_ONLY" else "QUOTED_LEGAL_NEIGHBORS",
                    ),
            )
        }
    private val evidenceNames =
        listOf(
            "profile",
            "sourceReadiness",
            "selection",
            "developmentReport",
            "completionAudit",
            "physicalClosure",
            "previousTerminal",
            "previousClosure",
            "nativeSourceReadiness",
            "qualifiedBaseline94Protocol",
            "qualifiedBaseline94Terminal",
            "qualifiedBaseline94Closure",
            "qualifiedBaseline94CompletionAudit",
        )

    init {
        base.toRobustQualified(sampler = sampler)
        base.changeRobustInference {
            it.put("kind", "coordinated-robust-inference-asset-2")
            it.put("schemaVersion", 2)
            it.set<ObjectNode>(
                "tokenNeighborMixture",
                mapper.valueToTree(cases.first()["tokenNeighborMixture"]),
            )
        }
        val bundle = base.hash(root.resolve("candidate/bundle.json"))
        val inference = base.hash(root.resolve("inference-asset.json"))
        put(
            "nativeSourceReadiness",
            mapOf(
                "kind" to "local-token-mixture-native-source-final-readiness-1",
                "passed" to true,
                "sourceOnly" to true,
                "nativeJar" to mapOf("path" to "constructed/training.jar", "sha256" to native),
            ),
        )
        put(
            "sourceReadiness",
            mapOf(
                "kind" to "local-bid-mixture-source-readiness-1",
                "passed" to true,
                "sourceOnly" to true,
                "methodSha256" to METHOD,
                "nativeJarSha256" to native,
            ),
        )
        put(
            "qualifiedBaseline94Protocol",
            mapOf(
                "kind" to "fixed-policy-qualification-campaign-1",
                "contract" to "final6-c05-small-512-1",
                "maximumTerminalExecutions" to 2064,
                "rootProspectiveMethodSha256" to
                    "22c172eec9af6683cfe8db382154b53440bdc7db89a77d9cf332ac886c4ed377",
                "candidateBundleSha256" to bundle,
                "selector" to sampler,
            ),
        )
        put(
            "qualifiedBaseline94Terminal",
            mapOf(
                "kind" to "fixed-policy-qualification-terminal-1",
                "status" to "completed",
                "qualified" to true,
            ),
        )
        put(
            "qualifiedBaseline94CompletionAudit",
            mapOf(
                "kind" to "fixed-policy-qualification-original-audit-1",
                "passed" to true,
                "complete" to true,
                "qualified" to true,
                "terminalExecutions" to 2064,
            ),
        )
        put("qualifiedBaseline94Closure", closure("mac-qualification-physical-closure-1", 2064))
        put(
            "previousTerminal",
            mapOf(
                "kind" to "mac-leader-search-terminal-1",
                "study" to 93,
                "status" to "completed",
                "protocolSha256" to digest,
            ),
        )
        put(
            "previousClosure",
            closure("mac-leader-search-physical-closure-1", 2128) +
                mapOf("protocolSha256" to digest, "study" to 93),
        )
        val banks =
            counts.entries
                .mapIndexed { index, (name, count) ->
                    name to
                        mapOf(
                            "name" to name,
                            "worlds" to count,
                            "seeds" to List(count) { index * 1000 + it },
                            "opponentStyle" to
                                if (name in setOf("development", "mixed")) {
                                    "BALANCED"
                                } else {
                                    "QUALIFIED"
                                },
                            "control" to if (name == "reference") "QUALIFIED87" else "QUALIFIED94",
                            "replayIndices" to
                                if (name == "development") {
                                    listOf(0, 21, 42, 63)
                                } else {
                                    listOf(0, 85, 170, 255)
                                },
                        )
                }
                .toMap()
        val reserved =
            counts.entries
                .mapIndexed { index, (name, count) ->
                    name to
                        mapOf(
                            "nativeOffset" to index,
                            "count" to count,
                            "seeds" to List(count) { index * 1000 + it },
                        )
                }
                .toMap()
        put(
            "protocol",
            mapOf(
                "kind" to "local-bid-mixture-1",
                "study" to 95,
                "methodSha256" to METHOD,
                "confidenceMultiplier" to 2.5758293035489004,
                "maximumTerminalExecutions" to 2648,
                "fullReservedWorlds" to 832,
                "activeReservedWorlds" to 832,
                "unusedReservedWorlds" to 0,
                "candidateBundleSha256" to bundle,
                "controlAndRivalsBundleSha256" to bundle,
                "reference87BundleSha256" to
                    mapper
                        .readTree(root.resolve("inference-asset.json").toFile())["currentBundle"][
                            "sha256"]
                        .textValue(),
                "baseTokenSelector" to sampler,
                "baseExpansionDenial" to null,
                "positiveBidConcentrationMaximum" to .8,
                "inferenceAssetSha256" to inference,
                "nativeJarSha256" to native,
                "caseConfigs" to cases,
                "banks" to banks,
                "reservedSchedule" to
                    mapOf("kind" to "local-bid-mixture-schedule-1", "banks" to reserved),
            ),
        )
        put(
            "profile",
            mapOf(
                "kind" to "mac-policy-search-host-1",
                "study" to 95,
                "candidateBundle" to mapOf("path" to "candidate/bundle.json", "sha256" to bundle),
                "inferenceAsset" to mapOf("path" to "inference-asset.json", "sha256" to inference),
                "nativeJar" to mapOf("path" to "constructed/training.jar", "sha256" to native),
            ),
        )
        put("selection", mapOf("complete" to true, "case" to cases.first()))
        put(
            "developmentReport",
            mapOf(
                "complete" to true,
                "bank" to "development",
                "cases" to
                    cases
                        .mapIndexed { index, case ->
                            case["id"] to statistics(64, if (index == 0) .1 else -.1)
                        }
                        .toMap(),
            ),
        )
        for (name in listOf("mixed", "homogeneous", "reference")) {
            put(
                "report-$name",
                mapOf(
                    "complete" to true,
                    "bank" to name,
                    "cases" to mapOf("n00" to statistics(256, .1)),
                ),
            )
        }
        put(
            "completionAudit",
            mapOf(
                "kind" to "local-bid-mixture-original-audit-1",
                "passed" to true,
                "complete" to true,
                "qualified" to true,
                "originalReproductionsVerified" to true,
                "allWin99StatisticsRecomputed" to true,
                "allObservedConcentrationStatisticsRecomputed" to true,
                "terminalExecutions" to 2648,
                "clockSha256" to digest,
                "selectedCase" to cases.first(),
            ),
        )
        put("physicalClosure", closure("local-bid-mixture-physical-closure-1", 2648))
        seal()
    }

    fun change(name: String, transform: (ObjectNode) -> Unit) {
        transform(document(name))
        seal()
    }

    fun substituteNativeBuild(sha256: String) {
        document("protocol").put("nativeJarSha256", sha256)
        document("sourceReadiness").put("nativeJarSha256", sha256)
        (document("nativeSourceReadiness")["nativeJar"] as ObjectNode).put("sha256", sha256)
        (document("profile")["nativeJar"] as ObjectNode).put("sha256", sha256)
        seal()
    }

    private fun put(name: String, value: Any) {
        documents[name] = mapper.valueToTree(value)
    }

    private fun document(name: String): ObjectNode =
        checkNotNull(documents[name]) {
            "The constructed fixture must contain $name."
        }

    private fun write(name: String): Path = base.write("local/$name.json", document(name))

    private fun subject(name: String): Map<String, String> = base.subject(write(name))

    private fun seal() {
        val baseline = subject("qualifiedBaseline94Protocol")["sha256"]
        for (name in
            listOf(
                "qualifiedBaseline94Terminal",
                "qualifiedBaseline94CompletionAudit",
                "qualifiedBaseline94Closure",
            )) {
            document(name).put("protocolSha256", baseline)
        }
        document("qualifiedBaseline94Closure")
            .set<ObjectNode>(
                "artifactPins",
                mapper.valueToTree(
                    mapOf("audit" to subject("qualifiedBaseline94CompletionAudit")["sha256"])
                ),
            )
        document("previousClosure")
            .set<ObjectNode>(
                "artifactPins",
                mapper.valueToTree(mapOf("terminal" to subject("previousTerminal")["sha256"])),
            )
        document("protocol").apply {
            set<ObjectNode>(
                "qualifiedBaseline94",
                mapper.valueToTree(
                    mapOf(
                        "protocol" to subject("qualifiedBaseline94Protocol"),
                        "terminal" to subject("qualifiedBaseline94Terminal"),
                        "closure" to subject("qualifiedBaseline94Closure"),
                        "completionAudit" to subject("qualifiedBaseline94CompletionAudit"),
                    )
                ),
            )
            set<ObjectNode>("parentTerminal", mapper.valueToTree(subject("previousTerminal")))
            set<ObjectNode>("parentClosure", mapper.valueToTree(subject("previousClosure")))
            set<ObjectNode>(
                "nativeSourceReadiness",
                mapper.valueToTree(subject("nativeSourceReadiness")),
            )
        }
        val protocol = subject("protocol")["sha256"]
        document("profile")
            .set<ObjectNode>(
                "pins",
                mapper.valueToTree(
                    mapOf(
                        "protocol" to protocol,
                        "source" to subject("sourceReadiness")["sha256"],
                    )
                ),
            )
        for (name in
            listOf(
                "selection",
                "developmentReport",
                "completionAudit",
                "physicalClosure",
                "report-mixed",
                "report-homogeneous",
                "report-reference",
            )) {
            document(name).put("protocolSha256", protocol)
        }
        document("completionAudit").apply {
            put("profileSha256", subject("profile")["sha256"])
            set<ObjectNode>(
                "artifactPins",
                mapper.valueToTree(
                    listOf(
                            "profile",
                            "protocol",
                            "selection",
                            "developmentReport",
                            "report-mixed",
                            "report-homogeneous",
                            "report-reference",
                        )
                        .associateWith {
                            subject(it)["sha256"]
                        }
                ),
            )
        }
        document("physicalClosure")
            .set<ObjectNode>(
                "artifactPins",
                mapper.valueToTree(mapOf("audit" to subject("completionAudit")["sha256"])),
            )
        base.changeQualification {
            it.put("evaluationVersion", "study95-LOCAL1")
            it.set<ObjectNode>("protocol", mapper.valueToTree(subject("protocol")))
            it.set<ObjectNode>(
                "searchEvidence",
                mapper.valueToTree(evidenceNames.associateWith(::subject)),
            )
            it.set<ObjectNode>(
                "banks",
                mapper.valueToTree(
                    listOf("mixed", "homogeneous", "reference").associateWith { bank ->
                        mapOf(
                            "complete" to true,
                            "pairs" to 256,
                            "win99Passed" to true,
                            "evidence" to subject("completionAudit"),
                            "report" to subject("report-$bank"),
                        )
                    }
                ),
            )
        }
    }

    private fun closure(kind: String, executions: Int): Map<String, Any> =
        mapOf(
            "kind" to kind,
            "passed" to true,
            "scienceComplete" to true,
            "qualified" to true,
            "allOwnedProcessesExited" to true,
            "originalClocksRenewed" to false,
            "auditReruns" to 0,
            "actualCompletedLowerBound" to executions,
            "actualAttemptedUpperBound" to executions,
            "clockSha256" to digest,
        )

    private fun statistics(count: Int, mean: Double): Map<String, Any> =
        mapOf(
            "wins" to
                mapOf(
                    "count" to count,
                    "mean" to mean,
                    "lower99" to mean - .01,
                ),
            "observedBids" to
                mapOf(
                    "CANDIDATE" to
                        mapOf(
                            "counts" to mapOf("20" to 5, "21" to 5),
                            "positiveCount" to 10,
                            "maximumSinglePositiveAmountCount" to 5,
                            "maximumSinglePositiveAmountShare" to .5,
                            "observedConcentrationPassed" to true,
                        )
                ),
        )

    companion object {
        private const val METHOD =
            "4c757424a2700de67d2f2dc6ba6b2e7e9bef8c76a96dc714eec158578698179f"
    }
}
