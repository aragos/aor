package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** Invented complete admission shapes only; no simulation, scientific model, RNG or fit. */
internal class CoordinatedWinFixture(retained: Path? = null) : AutoCloseable {
    val toy = CoordinatedEsFixture()
    val root: Path =
        retained?.also { target ->
            require(!Files.exists(target)) { "Constructed retained fixture output must be absent." }
            Files.walk(toy.files.directory).use { paths ->
                paths.forEach { path ->
                    val output = target.resolve(toy.files.directory.relativize(path))
                    if (Files.isDirectory(path)) Files.createDirectories(output)
                    else Files.copy(path, output)
                }
            }
        } ?: toy.files.directory.toRealPath()
    val originArena = CoordinatedEsWire.source(root.resolve("arena.json"))
    private val rawArena = ArenaSpecification.read(originArena.path)
    val original =
        CoordinatedEsBundle.read(
            CoordinatedEsWire.source(root.resolve("coordinated-bundle.json")),
            CoordinatedEsBundle.dictionaries(rawArena.configurations.single()),
        )
    private val baselinePath =
        root
            .resolve(PurchaseJson.read(originArena.path)["jointPolicy"]["baseline"].asText())
            .toRealPath()
    val scope =
        CoordinatedEsScope(
            originArena,
            rawArena,
            original,
            PurchaseBaselineInput(
                baselinePath,
                PurchaseJson.hash(baselinePath),
                TrainingArtifacts.readModel(baselinePath),
            ),
        )
    val oldSubjects = linkedMapOf<String, TokenPairedSubject>()
    val scopeMap: Map<String, String>
    val proof: TokenPairedSubject
    val warm: TokenPairedSubject
    val protocol: TokenPairedSubject
    val plan: CoordinatedWinProtocol
    val arena: TokenPairedSubject
    val inherited: TokenPairedSubject
    val preparation: TokenPairedSubject
    val historical: TokenPairedSubject
    val centers: List<TokenPairedSubject>
    val updates: List<TokenPairedSubject>
    val directions: List<TokenPairedSubject>
    val populations: List<TokenPairedSubject>
    val finalAdmission: TokenPairedSubject

    init {
        val template =
            requireNotNull(
                    javaClass.getResourceAsStream("/coordinated-win-protocol-template.json")
                ) {
                    "Protocol fixture resource."
                }
                .use { String(it.readAllBytes(), Charsets.UTF_8) }
        val templateRecord = PurchaseJson.mapper.readTree(template)
        oldSubjects["originalBundle"] = original.source
        oldSubjects["projection"] =
            save(
                "parent/projection.json",
                mapOf(
                    "kind" to "coordinated-es-projection-1",
                    "scopeSha256" to scope.sha256,
                    "originalBundleSha256" to original.source.sha256,
                ),
            )
        scopeMap =
            mapOf(
                "scopeSha256" to scope.sha256,
                "originalBundleSha256" to original.source.sha256,
                "projectionSha256" to old("projection").sha256,
            )
        oldSubjects["protocol"] =
            save(
                "parent/protocol.json",
                mapOf(
                    "kind" to "coordinated-es-campaign-1",
                    "calibration" to templateRecord["calibration"]["recipe"],
                ),
                lf = true,
            )
        oldSubjects["calibration"] = calibration()
        oldSubjects["center"] =
            save(
                "parent/center.json",
                mapOf(
                    "kind" to "coordinated-es-center-1",
                    "scope" to scopeMap,
                    "generation" to 16,
                    "coordinates" to List(256) { .125 },
                ),
            )
        oldSubjects["finalBundle"] = original.source
        oldSubjects["generationAudit"] =
            save("parent/generation-audit.json", mapOf("constructedLineage" to 16))
        oldSubjects["historical"] =
            save("parent/historical.json", mapOf("constructedControls" to 2))
        oldSubjects["centerMaterialization"] =
            save("parent/materialization.json", mapOf("constructedMaterialization" to true))
        val parent =
            save(
                "parent/admission.json",
                mapOf(
                    "kind" to "coordinated-es-final-admission-1",
                    "complete" to true,
                    "completedGenerations" to 16,
                    "scopeSha256" to scope.sha256,
                    "ecologySha256" to scope.ecologySha256,
                ) + oldSubjects.mapValues { ref(it.value) },
            )
        val runtime =
            CoordinatedQualificationOrigin.RUNTIME_NAMES.associateWith {
                save("invented-runtime/$it", mapOf("inventedJarName" to it))
            }
        proof =
            save(
                "parent/proof.json",
                mapOf(
                    "kind" to "coordinated-win-origin-proof-1",
                    "host" to "DESKTOP",
                    "passed" to true,
                    "complete" to true,
                    "admission" to ref(parent),
                    "arena" to ref(originArena),
                    "runtime" to runtime.mapValues { ref(it.value) },
                    "scopeIdentity" to scope.identity,
                    "scopeSha256" to scope.sha256,
                    "ecologySha256" to scope.ecologySha256,
                    "subjects" to oldSubjects.mapValues { ref(it.value) },
                    "inputPins" to
                        (scope.inputPins() +
                            (oldSubjects.values + runtime.values + parent).associate {
                                it.path.toString() to it.sha256
                            }),
                    "execution" to mapOf("newGames" to 0, "policyCalls" to 0, "actualFits" to 0),
                ),
            )
        warm =
            save(
                "warm-start.json",
                mapOf(
                    "kind" to "coordinated-es-warm-start-1",
                    "parameterSpace" to scopeMap,
                    "originCenterSha256" to old("center").sha256,
                    "originBundleSha256" to original.source.sha256,
                    "originProtocolSha256" to old("protocol").sha256,
                    "originAdmissionSha256" to parent.sha256,
                    "calibrationSha256" to old("calibration").sha256,
                    "coordinates" to List(256) { .125 },
                ),
            )
        arena = mixedArena()
        val supported =
            ArenaSpecification.read(arena.path)
                .copy(
                    coordinatedEs =
                        ArenaCoordinatedPolicy.supported(null, original, scope.baseline, null)
                )
        val replacements =
            scopeMap +
                mapOf(
                    "proofSha256" to proof.sha256,
                    "warm_identitySha256" to warm.sha256,
                    "warm_protocolSha256" to old("protocol").sha256,
                    "warm_admissionSha256" to parent.sha256,
                    "warm_centerSha256" to old("center").sha256,
                    "warm_bundleSha256" to original.source.sha256,
                    "warm_materializationSha256" to old("centerMaterialization").sha256,
                    "originProtocolSha256" to old("protocol").sha256,
                    "originCalibrationSha256" to old("calibration").sha256,
                    "ecologySha256" to
                        CpePairedDigest.json(
                            TokenPairedEcology.identity(
                                supported,
                                TokenPairedEcology.ordinaryHash(arena.path),
                            )
                        ),
                    "reference_qualifiedOrdinarySha256" to "1".repeat(64),
                    "reference_cardM8Sha256" to TokenPairedEcology.ordinaryHash(arena.path),
                    "reference_purchaseF0Sha256" to scope.baseline.sha256,
                    "reference_expansionESha256" to
                        scope.policyBindings["expansionImitation"]["candidateModel"]["sha256"]
                            .asText(),
                ) +
                CoordinatedEsHead.entries
                    .flatMap { head ->
                        listOf(
                            "${head.name}_actorSha256" to original.subject(head).sha256,
                            "${head.name}_dictionarySha256" to
                                requireNotNull(scope.dictionaries[head]) { "Fixture dictionary." }
                                    .sha256(),
                        )
                    }
                    .toMap()
        val seal = PurchaseJson.read(old("calibration").path)
        var protocolText =
            replacements.entries.fold(template) { text, (key, value) ->
                text.replace("__${key}__", value)
            }
        listOf("EXPANSION", "TOKEN").forEach {
            protocolText = protocolText.replace("\"__scale_${it}__\"", seal["scales"][it].asText())
        }
        require(!protocolText.contains("__")) {
            "All constructed protocol placeholders must be replaced."
        }
        Files.writeString(root.resolve("protocol.json"), protocolText)
        protocol = CoordinatedEsWire.source(root.resolve("protocol.json"))
        plan = CoordinatedWinProtocol.read(protocol)
        Files.createDirectories(root.resolve("calibration/origin"))
        listOf("protocol", "calibration").forEach {
            Files.copy(old(it).path, root.resolve("calibration/origin/$it.json"))
        }
        listOf("EXPANSION", "TOKEN").forEach {
            Files.copy(
                root.resolve("parent/gap-$it.json"),
                root.resolve("calibration/origin/gap-$it.json"),
            )
        }
        inherited =
            save(
                "calibration/inherited-calibration.json",
                mapOf(
                    "kind" to "coordinated-es-inherited-calibration-1",
                    "protocolSha256" to protocol.sha256,
                    "parameterSpace" to scopeMap,
                    "warmStartSha256" to warm.sha256,
                    "originProtocol" to
                        mapOf("path" to "origin/protocol.json", "sha256" to old("protocol").sha256),
                    "originCalibration" to
                        mapOf(
                            "path" to "origin/calibration.json",
                            "sha256" to old("calibration").sha256,
                        ),
                    "recipe" to plan.record["calibration"]["recipe"],
                    "scales" to seal["scales"],
                    "recalibrated" to false,
                ),
            )
        centers = (0..16).map { save("center-$it.json", center(it)) }
        directions =
            (1..16).map { number ->
                save(
                    "preparation/directions/generation-${pad(number)}.json",
                    mapOf(
                        "kind" to "coordinated-es-directions-1",
                        "scope" to scopeMap,
                        "generation" to number,
                        "vectors" to List(16) { List(256) { 0.0 } },
                    ),
                )
            }
        val generated =
            (1..16).map { number ->
                save(
                    "preparation/directions/generation-${pad(number)}-generation.json",
                    mapOf(
                        "kind" to "coordinated-es-direction-generation-1",
                        "generation" to number,
                        "seed" to plan.generation(number)["directionSeed"],
                        "generator" to "numpy-PCG64",
                        "distribution" to "independent-standard-normal",
                        "shape" to listOf(16, 256),
                        "renormalized" to false,
                        "numpyVersion" to "CONSTRUCTED",
                        "directionsSha256" to directions[number - 1].sha256,
                    ),
                )
            }
        preparation =
            save(
                "preparation/finished.json",
                mapOf(
                    "kind" to "coordinated-win-numeric-complete-1",
                    "verb" to "prepare",
                    "passed" to true,
                    "complete" to true,
                    "protocolSha256" to protocol.sha256,
                    "execution" to mapOf("newGames" to 0, "actualFits" to 0, "optimizerSteps" to 0),
                    "artifacts" to
                        ((directions + generated).associate {
                            root.resolve("preparation").relativize(it.path).toString() to it.sha256
                        } +
                            mapOf(
                                "warm-start.json" to warm.sha256,
                                "projection.json" to old("projection").sha256,
                                "warm-bundle/bundle.json" to original.source.sha256,
                                "initial-center.json" to centers[0].sha256,
                                "calibration/inherited-calibration.json" to inherited.sha256,
                            )),
                ),
            )
        historical =
            save(
                "historical.json",
                mapOf(
                    "kind" to "coordinated-win-historical-admission-1",
                    "passed" to true,
                    "complete" to true,
                    "protocolSha256" to protocol.sha256,
                    "scopeSha256" to scope.sha256,
                    "ecologySha256" to plan.ecologySha256,
                    "originalBundleSha256" to original.source.sha256,
                    "warmStartSha256" to warm.sha256,
                    "scientificTerminalExecutions" to 2,
                    "attemptedGames" to 2,
                    "completedGames" to 2,
                    "zeroFailures" to true,
                    "controls" to
                        plan.record["historicalControls"].map { row ->
                            mapOf(
                                "globalIndex" to row["globalIndex"],
                                "index" to row["index"],
                                "side" to "candidate",
                                "purpose" to "RAW_REFERENCE_REGRESSION_ONLY",
                                "originalSourceSha256" to row["sourceSha256"],
                                "originalPairSha256" to row["pairSha256"],
                                "originalReplaySha256" to row["replaySha256"],
                                "replayFile" to "CONSTRUCTED",
                                "replaySha256" to "a".repeat(64),
                                "canonicalVerified" to true,
                                "execution" to "ZERO_REFERENCE",
                            )
                        },
                ),
            )
        populations = (1..16).map(::population)
        updates =
            (1..16).map { number ->
                save(
                    "update-$number.json",
                    mapOf(
                        "kind" to "coordinated-es-continuation-update-1",
                        "complete" to true,
                        "objective" to CoordinatedWinProtocol.OBJECTIVE,
                        "mathRecipe" to CoordinatedWinProtocol.MATH_RECIPE,
                        "protocolSha256" to protocol.sha256,
                        "warmStartSha256" to warm.sha256,
                        "previousCenterSha256" to centers[number - 1].sha256,
                        "directionsSha256" to directions[number - 1].sha256,
                        "populationSha256" to populations[number - 1].sha256,
                        "center" to center(number),
                    ),
                )
            }
        finalAdmission = finalPublication()
    }

    fun old(name: String) = requireNotNull(oldSubjects[name]) { "Parent fixture $name required." }

    fun ref(subject: TokenPairedSubject): Map<String, String> =
        mapOf("path" to subject.path.toString(), "sha256" to subject.sha256)

    fun pad(number: Int) = number.toString().padStart(2, '0')

    fun center(step: Int) =
        mapOf(
            "kind" to "coordinated-es-continuation-center-1",
            "parameterSpace" to scopeMap,
            "warmStartSha256" to warm.sha256,
            "step" to step,
            "coordinates" to List(256) { .125 + step / 64.0 },
        )

    fun save(name: String, value: Any, lf: Boolean = false): TokenPairedSubject {
        val path = root.resolve(name)
        Files.createDirectories(path.parent)
        fun encode(node: JsonNode): String =
            when {
                node.isObject ->
                    node
                        .properties()
                        .sortedBy { it.key }
                        .joinToString(",", "{", "}") {
                            PurchaseJson.mapper.writeValueAsString(it.key) + ":" + encode(it.value)
                        }
                node.isArray -> node.joinToString(",", "[", "]", transform = ::encode)
                node.isFloatingPointNumber -> {
                    val value = node.doubleValue()
                    require(value.isFinite()) { "Constructed metadata is finite." }
                    if (kotlin.math.abs(value) in 1e-4..<1e16) {
                        java.math.BigDecimal.valueOf(value).toPlainString().let {
                            if ('.' in it) it else "$it.0"
                        }
                    } else {
                        val text = value.toString().lowercase()
                        if ('e' !in text) text
                        else {
                            val pieces = text.split('e')
                            val exponent = pieces[1].toInt()
                            pieces[0].removeSuffix(".0") +
                                "e" +
                                (if (exponent < 0) "-" else "+") +
                                kotlin.math.abs(exponent).toString().padStart(2, '0')
                        }
                    }
                }
                else -> PurchaseJson.mapper.writeValueAsString(node)
            }
        Files.writeString(path, encode(ExpansionOnPolicyStore.wire(value)) + if (lf) "\n" else "")
        return CoordinatedEsWire.source(path)
    }

    private fun calibration(): TokenPairedSubject {
        val template =
            requireNotNull(
                    javaClass.getResourceAsStream("/coordinated-es-calibration-v2-head.json")
                ) {
                    "V2 constructed receipt."
                }
                .use { PurchaseJson.mapper.readTree(it) }
        val heads =
            listOf("EXPANSION", "TOKEN").associateWith { name ->
                template.deepCopy<ObjectNode>().also { head ->
                    head.put("head", name)
                    val gap =
                        save(
                            "parent/gap-$name.json",
                            mapOf("constructedOnly" to true, "head" to name),
                        )
                    head.put("panelSha256", gap.sha256)
                    (head["subjects"] as ObjectNode).also { bindings ->
                        bindings.put("protocolSha256", old("protocol").sha256)
                        bindings.put("projectionSha256", old("projection").sha256)
                        bindings.put(
                            "originalModelSha256",
                            original.subject(CoordinatedEsHead.valueOf(name)).sha256,
                        )
                    }
                }
            }
        val bindings = requireNotNull(heads["TOKEN"]) { "Token head." }["subjects"]
        return save(
            "parent/calibration.json",
            mapOf(
                "kind" to "coordinated-es-calibration-2",
                "passed" to true,
                "complete" to true,
                "protocolSha256" to old("protocol").sha256,
                "originalBundleSha256" to original.source.sha256,
                "projectionSha256" to old("projection").sha256,
                "panelSha256" to bindings["publicPanelSha256"],
                "probePopulationSha256" to "1".repeat(64),
                "directionsSha256" to bindings["directionsSha256"],
                "prescribedMeanSha256" to bindings["prescribedMeanSha256"],
                "scales" to heads.mapValues { it.value["scale"] },
                "heads" to heads,
                "semanticFitnessDecoded" to false,
                "calibrationAttempts" to 1,
            ),
        )
    }

    private fun mixedArena(): TokenPairedSubject {
        val value = PurchaseJson.read(originArena.path) as ObjectNode
        value.put("purpose", "SCREENING").put("pairsPerConfiguration", 256).put("seed", 101)
        value.putArray("opponents").also { array ->
            repeat(4) { array.add(value["candidate"].asText()) }
        }
        val expansion = value["expansionImitation"] as ObjectNode
        val model = expansion["candidateModel"].asText()
        expansion.putArray("opponentModels").addNull().add(model).addNull().add(model)
        (value["tokenOrder"] as ObjectNode)
            .putArray("opponentSelectors")
            .add("ORDINARY")
            .add("ORDINARY")
            .add("FIXED_16_TO_15")
            .add("FIXED_16_TO_15")
        return save("win-arena.json", value)
    }

    private fun population(number: Int): TokenPairedSubject {
        val folder = "population-$number"
        val members =
            (0..31).map { index ->
                val role = CoordinatedEsPopulation.role(index)
                val directory = root.resolve("$folder/members/$role")
                val descriptor =
                    mapOf(
                        "kind" to CoordinatedEsBundle.VERSION,
                        "heads" to
                            original.subjects
                                .map { (head, subject) ->
                                    head.name to
                                        mapOf(
                                            "path" to directory.relativize(subject.path).toString(),
                                            "sha256" to subject.sha256,
                                            "dictionarySha256" to
                                                requireNotNull(scope.dictionaries[head]) {
                                                        "Fixture dictionary."
                                                    }
                                                    .sha256(),
                                        )
                                }
                                .toMap(),
                    )
                val bundle = save("$folder/members/$role/bundle.json", descriptor)
                mapOf(
                    "direction" to index / 2,
                    "sign" to if (index % 2 == 0) 1 else -1,
                    "path" to "members/$role/bundle.json",
                    "coordinatesSha256" to "b".repeat(64),
                    "bundleSha256" to bundle.sha256,
                )
            }
        val base =
            mapOf(
                "kind" to "coordinated-es-continuation-materialization-1",
                "parameterSpace" to scopeMap,
                "warmStartSha256" to warm.sha256,
                "step" to number,
                "centerSha256" to centers[number - 1].sha256,
                "directionsSha256" to directions[number - 1].sha256,
                "worldBankSha256" to
                    CoordinatedEsBank.read(plan.generation(number)["worldBank"], 256).sha256,
                "worldIds" to (0..255).toList(),
                "members" to members,
            )
        return save(
            "$folder/population.json",
            base +
                mapOf(
                    "kind" to "coordinated-es-continuation-population-1",
                    "protocolSha256" to protocol.sha256,
                    "inheritedCalibrationSha256" to inherited.sha256,
                    "materializationSha256" to CpePairedDigest.json(base),
                ),
        )
    }

    fun source(
        number: Int = 1,
        stage: CoordinatedEsStage = CoordinatedEsStage.CONTROLS,
        start: Int = 32,
    ): TokenPairedSubject {
        val subjects =
            mapOf(
                "protocol" to protocol,
                "arena" to arena,
                "originalBundle" to original.source,
                "projection" to old("projection"),
                "warmStart" to warm,
                "originProof" to proof,
                "center" to centers[number - 1],
                "directions" to directions[number - 1],
                "population" to
                    if (stage == CoordinatedEsStage.POPULATION) populations[number - 1] else null,
                "inheritedCalibration" to inherited,
                "previousUpdate" to if (number == 1) null else updates[number - 2],
                "historical" to historical,
                "numericalPreparation" to preparation,
            )
        return save(
            "source-$number-$stage-$start.json",
            mapOf(
                "kind" to CoordinatedWinInput.VERSION,
                "host" to "DESKTOP",
                "stage" to stage.name,
                "generation" to number,
                "globalStart" to start,
                "count" to 32,
                "bank" to plan.generation(number)["worldBank"],
            ) + subjects.mapValues { it.value?.let(::ref) },
        )
    }

    private fun portable(subject: TokenPairedSubject) =
        mapOf("path" to root.relativize(subject.path).toString(), "sha256" to subject.sha256)

    private fun finalPublication(): TokenPairedSubject {
        val audit =
            save(
                "generation-audit.json",
                mapOf(
                    "kind" to "coordinated-win-generation-audit-1",
                    "passed" to true,
                    "complete" to true,
                    "completedGenerations" to 16,
                    "protocolSha256" to protocol.sha256,
                    "scopeSha256" to scope.sha256,
                    "ecologySha256" to plan.ecologySha256,
                    "warmStartSha256" to warm.sha256,
                    "objective" to CoordinatedWinProtocol.OBJECTIVE,
                    "originalBundleSha256" to original.source.sha256,
                    "projectionSha256" to old("projection").sha256,
                    "inheritedCalibrationSha256" to inherited.sha256,
                    "calibrationSha256" to old("calibration").sha256,
                    "numericalPreparationSha256" to preparation.sha256,
                    "finalCenterSha256" to centers[16].sha256,
                    "generations" to
                        (1..16).map { number ->
                            mapOf(
                                "generation" to number,
                                "inputCenterSha256" to centers[number - 1].sha256,
                                "outputCenterSha256" to centers[number].sha256,
                                "outputCenter" to portable(centers[number]),
                                "update" to portable(updates[number - 1]),
                                "populationSha256" to populations[number - 1].sha256,
                                "controls" to 256,
                                "mutants" to 8192,
                                "reproductions" to 2,
                            )
                        },
                ),
            )
        val materialized =
            save(
                "center-materialization.json",
                mapOf(
                    "kind" to "coordinated-es-continuation-center-materialization-1",
                    "protocolSha256" to protocol.sha256,
                    "centerSha256" to centers[16].sha256,
                    "step" to 16,
                    "warmStartSha256" to warm.sha256,
                    "projectionSha256" to old("projection").sha256,
                    "originalBundleSha256" to original.source.sha256,
                    "inheritedCalibrationSha256" to inherited.sha256,
                    "bundleSha256" to original.source.sha256,
                    "finalCandidate" to true,
                ),
            )
        val preparationFolder = root.resolve("portable-preparation")
        Files.createDirectories(preparationFolder.resolve("directions"))
        Files.copy(preparation.path, preparationFolder.resolve("finished.json"))
        (1..16).forEach { number ->
            val file = "directions/generation-${pad(number)}-generation.json"
            Files.copy(root.resolve("preparation/$file"), preparationFolder.resolve(file))
        }
        val portablePreparation =
            CoordinatedEsWire.source(preparationFolder.resolve("finished.json"))
        val subjects =
            mapOf(
                "protocol" to protocol,
                "originalBundle" to original.source,
                "projection" to old("projection"),
                "warmStart" to warm,
                "startBundle" to original.source,
                "inheritedCalibration" to inherited,
                "calibration" to old("calibration"),
                "numericalPreparation" to portablePreparation,
                "center" to centers[16],
                "finalBundle" to original.source,
                "centerMaterialization" to materialized,
                "generationAudit" to audit,
                "historical" to historical,
            )
        return save(
            "final-admission.json",
            mapOf(
                "kind" to "coordinated-win-final-admission-1",
                "complete" to true,
                "completedGenerations" to 16,
                "scopeSha256" to scope.sha256,
                "ecologySha256" to plan.ecologySha256,
                "warmStartSha256" to warm.sha256,
                "validationEffectsUsedForSelection" to false,
            ) + subjects.mapValues { portable(it.value) },
        )
    }

    override fun close() = toy.close()
}
