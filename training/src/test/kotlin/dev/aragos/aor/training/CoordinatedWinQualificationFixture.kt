package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION

/** Constructed complete inference subjects; no trained weights, random draws or simulation. */
internal class CoordinatedWinQualificationFixture : AutoCloseable {
    val toy = CoordinatedEsFixture()
    val files = toy.files
    val root = files.directory.toRealPath()
    val calibration: TokenPairedSubject
    val asset: TokenPairedSubject
    val nomination: TokenPairedSubject
    val protocol: TokenPairedSubject

    init {
        val ordinary = TrainingArtifacts.readModel(root.resolve("ordinary.json"))
        val previous = TrainingArtifacts.readModel(root.resolve("expansion.json"))
        val expansion =
            previous.copy(
                weights =
                    previous.weights +
                        ExpansionImitationProtocol.features.associateWith {
                            previous.weights[it] ?: 0.0
                        }
            )
        files.model(expansion, "expansion.json")
        files.model(
            ordinary.copy(
                weights =
                    ordinary.weights +
                        mapOf(
                            "planning.expansionModel.enabled" to 1.0,
                            "planning.tokenOrderControl.fixed16To15" to 1.0,
                        ) +
                        ExpansionImitationProtocol.features.associate {
                            "planning.expansionModel.$it" to
                                requireNotNull(expansion.weights[it]) { "Toy coefficient." }
                        }
            ),
            "packed.json",
        )
        val packed = PurchaseJson.read(root.resolve("packed.json")) as ObjectNode
        packed.set<JsonNode>(
            "configuration",
            ExpansionOnPolicyStore.wire(CoordinatedInferenceAsset.CONFIGURATION),
        )
        files.write("packed.json", packed)
        calibration = save("calibration.json", calibrationRecord())
        asset =
            save(
                "inference-asset.json",
                mapOf(
                    "kind" to CoordinatedInferenceAsset.VERSION,
                    "schemaVersion" to 1,
                    "profile" to CoordinatedInferenceAsset.PROFILE,
                    "supportProfile" to CoordinatedInferenceAsset.SUPPORT,
                    "selectorVersion" to "incumbent-residual-margin-1",
                    "rulesVersion" to RULES_VERSION,
                    "dataVersion" to DATA_VERSION,
                    "configuration" to CoordinatedInferenceAsset.CONFIGURATION,
                    "currentBundle" to ref(toy.original.source),
                    "originalBundle" to ref(toy.original.source),
                    "calibration" to ref(calibration),
                    "references" to referenceSubjects(),
                ),
            )
        val audit =
            save(
                "constructed-completion.json",
                mapOf("constructedOnly" to true, "simulationCalls" to 0),
            )
        val report =
            save(
                "constructed-development.json",
                mapOf("constructedOnly" to true, "simulationCalls" to 0),
            )
        nomination =
            save(
                "nomination.json",
                mapOf(
                    "kind" to "coordinated-policy-nomination-1",
                    "schemaVersion" to 1,
                    "passed" to true,
                    "complete" to true,
                    "assetSha256" to asset.sha256,
                    "profile" to CoordinatedInferenceAsset.PROFILE,
                    "completionAudit" to ref(audit),
                    "developmentReport" to ref(report),
                    "learningWin95Passed" to true,
                    "controlWin95Passed" to true,
                    "nomination" to "SOLE_FINAL_MEAN",
                    "candidateAlternatives" to 0,
                ),
            )
        protocol = save("protocol.json", protocolRecord())
    }

    fun subject(name: String) = CoordinatedEsWire.source(root.resolve(name))

    fun ref(value: TokenPairedSubject): Map<String, String> =
        mapOf("path" to root.relativize(value.path).toString(), "sha256" to value.sha256)

    fun save(name: String, value: Any): TokenPairedSubject {
        if (name == "protocol.json") {
            val sorted =
                CpePairedDigest::class.java.getDeclaredMethod("sorted", JsonNode::class.java).also {
                    it.isAccessible = true
                }
            val tree =
                sorted.invoke(CpePairedDigest, ExpansionOnPolicyStore.wire(value)) as JsonNode
            java.nio.file.Files.writeString(
                root.resolve(name),
                PurchaseJson.mapper.writeValueAsString(tree) + "\n",
            )
        } else {
            files.write(name, value)
        }
        return subject(name)
    }

    private fun referenceSubjects(): Map<String, Any> =
        mapOf(
            "qualifiedOrdinary" to ref(subject("packed.json")),
            "cardM8" to ref(subject("ordinary.json")),
            "purchaseF0" to ref(subject("baseline.json")),
            "expansionE" to ref(subject("expansion.json")),
            "tokenControlVersion" to "FIXED_16_TO_15",
        )

    fun references(): Map<String, String> =
        mapOf(
            "qualifiedOrdinarySha256" to subject("packed.json").sha256,
            "cardM8Sha256" to subject("ordinary.json").sha256,
            "purchaseF0Sha256" to subject("baseline.json").sha256,
            "expansionESha256" to subject("expansion.json").sha256,
            "tokenControlVersion" to "FIXED_16_TO_15",
        )

    fun protocolRecord(): ObjectNode =
        PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to CoordinatedWinQualificationProtocol.VERSION,
                "profile" to CoordinatedInferenceAsset.PROFILE,
                "supportProfile" to CoordinatedInferenceAsset.SUPPORT,
                "inferenceAssetSha256" to asset.sha256,
                "nominationSha256" to nomination.sha256,
                "candidateBundleSha256" to toy.original.source.sha256,
                "originalBundleSha256" to toy.original.source.sha256,
                "calibrationSha256" to calibration.sha256,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "configuration" to CoordinatedQualificationProtocol.CONFIGURATION,
                "references" to references(),
                "banks" to mapOf("mixed" to bank(41), "homogeneous" to bank(42)),
                "maximumSeconds" to 28800,
                "auditSeconds" to 900,
                "maximumTerminalExecutions" to 16400,
                "interval" to
                    mapOf(
                        "confidence" to .99,
                        "multiplier" to CoordinatedQualificationProtocol.MULTIPLIER,
                        "requiredMetrics" to listOf("wins"),
                        "lowerBoundStrictlyPositive" to true,
                    ),
            )
        )

    private fun bank(offset: Int) =
        mapOf(
            "nativeOffset" to offset,
            "count" to 4096,
            "seeds" to List(4096) { StrategyArena.seed(offset, it) },
        )

    fun arena(bank: String): TokenPairedSubject {
        val node = PurchaseJson.read(root.resolve("arena.json")) as ObjectNode
        node
            .put("purpose", "CONFIRMATION")
            .put("pairsPerConfiguration", 4096)
            .put("seed", if (bank == "mixed") 41 else 42)
        val ordinary = node["candidate"].asText()
        val model = node["expansionImitation"]["candidateModel"].asText()
        val opponents = node.putArray("opponents")
        repeat(if (bank == "mixed") 4 else 1) { opponents.add(ordinary) }
        val expansions = (node["expansionImitation"] as ObjectNode).putArray("opponentModels")
        val tokens = (node["tokenOrder"] as ObjectNode).putArray("opponentSelectors")
        if (bank == "mixed") {
            expansions.addNull().add(model).addNull().add(model)
            tokens.add("ORDINARY").add("ORDINARY").add("FIXED_16_TO_15").add("FIXED_16_TO_15")
        } else {
            expansions.add(model)
            tokens.add("FIXED_16_TO_15")
        }
        return save("$bank-arena.json", node)
    }

    fun source(
        bank: String = "mixed",
        gate: TokenPairedSubject? = null,
        start: Int = 128,
    ): TokenPairedSubject =
        save(
            "$bank-source.json",
            mapOf(
                "kind" to "coordinated-win-qualification-input-1",
                "host" to "DESKTOP",
                "protocol" to ref(protocol),
                "inferenceAsset" to ref(asset),
                "nomination" to ref(nomination),
                "arena" to ref(arena(bank)),
                "bankName" to bank,
                "globalStart" to start,
                "count" to 128,
                "confirmation" to gate?.let(::ref),
            ),
        )

    fun confirmation(): TokenPairedSubject {
        val report =
            save("mixed-report.json", CoordinatedWinQualificationProtocol.REPORT.report(worlds()))
        val evidence =
            save(
                "mixed-evidence.json",
                mapOf(
                    "kind" to "coordinated-win-qualification-bank-evidence-1",
                    "complete" to true,
                    "bank" to "mixed",
                    "protocolSha256" to protocol.sha256,
                    "candidateBundleSha256" to toy.original.source.sha256,
                    "inferenceAssetSha256" to asset.sha256,
                    "nominationSha256" to nomination.sha256,
                    "scienceSha256" to "c".repeat(64),
                    "worlds" to 4096,
                    "games" to 8192,
                    "reproductions" to 8,
                    "scientificTerminalExecutions" to 8200,
                    "gatePassed" to true,
                    "chunks" to
                        List(32) {
                            mapOf("path" to "invented-$it.json", "sha256" to "d".repeat(64))
                        },
                    "compact" to ref(report),
                    "report" to ref(report),
                    "reportFinished" to ref(report),
                ),
            )
        return save(
            "confirmation.json",
            mapOf(
                "kind" to "coordinated-win-qualification-confirmation-1",
                "passed" to true,
                "complete" to true,
                "protocolSha256" to protocol.sha256,
                "candidateBundleSha256" to toy.original.source.sha256,
                "inferenceAssetSha256" to asset.sha256,
                "nominationSha256" to nomination.sha256,
                "bank" to "mixed",
                "worlds" to 4096,
                "report" to ref(report),
                "evidence" to ref(evidence),
            ),
        )
    }

    private fun calibrationRecord(): ObjectNode {
        val stream =
            requireNotNull(
                javaClass.getResourceAsStream("/coordinated-es-calibration-v2-head.json")
            ) {
                "Constructed V2 resource."
            }
        val head = stream.use { PurchaseJson.mapper.readTree(it) as ObjectNode }
        val subjects = head["subjects"]
        val heads =
            listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN).associate { phase ->
                phase.name to
                    head.deepCopy().also {
                        it.put("head", phase.name)
                        (it["subjects"] as ObjectNode).put(
                            "originalModelSha256",
                            toy.original.subject(phase).sha256,
                        )
                    }
            }
        return PurchaseJson.mapper.valueToTree(
            mapOf(
                "kind" to CoordinatedEsCalibrationVersion.V2.receiptKind,
                "passed" to true,
                "complete" to true,
                "originalBundleSha256" to toy.original.source.sha256,
                "protocolSha256" to subjects["protocolSha256"],
                "projectionSha256" to subjects["projectionSha256"],
                "panelSha256" to subjects["publicPanelSha256"],
                "probePopulationSha256" to "1".repeat(64),
                "directionsSha256" to subjects["directionsSha256"],
                "prescribedMeanSha256" to subjects["prescribedMeanSha256"],
                "scales" to mapOf("EXPANSION" to head["scale"], "TOKEN" to head["scale"]),
                "heads" to heads,
                "semanticFitnessDecoded" to false,
                "calibrationAttempts" to 1,
            )
        )
    }

    override fun close() = toy.close()

    companion object {
        fun worlds(): List<JsonNode> =
            List(4096) { index ->
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "index" to index,
                        "seat" to index % 4,
                        "roles" to
                            mapOf("CANDIDATE" to terminal(.6, .1), "CONTROL" to terminal(.4, .3)),
                    )
                )
            }

        private fun terminal(win: Double, reward: Double) =
            mapOf(
                "wins" to List(4) { win },
                "rewards" to List(4) { reward },
                "ranks" to List(4) { .5 },
                "scores" to List(4) { mapOf("player" to "p${it + 1}", "total" to 1) },
            )
    }
}
