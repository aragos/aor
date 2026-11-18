package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Compact portable receipts are rehashed before either admitted actor is decoded. */
internal class TokenPairedAdmission
private constructor(
    val source: TokenPairedSubject,
    val protocol: TokenPairedSubject,
    val protocolRecord: JsonNode,
    val originalActor: TokenPairedSubject,
    val finalActor: TokenPairedSubject,
    val scopeSha256: String,
    val ecologySha256: String,
    val iterations: List<TokenPairedSubject>,
    private val subjects: List<TokenPairedSubject>,
) {
    fun verify() = subjects.forEach(TokenPairedSubject::verify)

    companion object {
        const val VERSION = "token-paired-final-screen-admission-1"
        private val ARTIFACTS =
            setOf("identity.json", "policy.json", "checkpoint.json", "history.json")

        fun read(source: TokenPairedSubject): TokenPairedAdmission {
            val subjects = mutableListOf(source)
            fun subject(owner: Path, node: JsonNode): TokenPairedSubject =
                TokenPairedSubject.read(node, owner.parent).also(subjects::add)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "protocol",
                    "scopeSha256",
                    "iterations",
                    "finalActor",
                    "originalActor",
                    "ecologySha256",
                    "completedIterations",
                    "learnerHost",
                    "operationallyComplete",
                    "validationEffectsUsedForSelection",
                ),
            )
            require(
                node["kind"]?.asText() == VERSION &&
                    node["completedIterations"]?.intValue() == 2 &&
                    node["learnerHost"]?.asText() == "DESKTOP" &&
                    node["operationallyComplete"]?.booleanValue() == true &&
                    node["validationEffectsUsedForSelection"]?.booleanValue() == false
            ) {
                "Only the predeclared completed second Desktop actor may enter the final screen."
            }
            val protocol = subject(source.path, node.required("protocol"))
            val p = PurchaseJson.read(protocol.path)
            require(p["kind"]?.asText() == "token-paired-campaign-1") {
                "The original finite token campaign protocol is required."
            }
            val original = subject(source.path, node.required("originalActor"))
            val final = subject(source.path, node.required("finalActor"))
            val scope = sha(node["scopeSha256"], "scope")
            val ecology = sha(node["ecologySha256"], "ecology")
            require(
                original.sha256 == p["originalActorSha256"]?.asText() &&
                    ecology == p["ecologySha256"]?.asText()
            ) {
                "Original actor and complete frozen ecology must match the scientific protocol."
            }
            val iterations =
                PurchaseJson.array(node["iterations"], "iterations").map {
                    subject(source.path, it)
                }
            require(iterations.size == 2 && iterations.map { it.sha256 }.distinct().size == 2) {
                "Exactly two distinct chronological iteration admissions are required."
            }
            var previous: JsonNode? = null
            var previousSubject: TokenPairedSubject? = null
            iterations.forEachIndexed { offset, input ->
                val iteration = offset + 1
                val record = PurchaseJson.read(input.path)
                PurchaseJson.fields(
                    record,
                    setOf(
                        "kind",
                        "iteration",
                        "scopeSha256",
                        "scienceSha256",
                        "protocolSha256",
                        "currentActor",
                        "outputActor",
                        "originalActor",
                        "corpus",
                        "fitCompletion",
                        "validationProbabilitySeal",
                        "previousAdmission",
                        "ready",
                        "acceptedEpochs",
                        "actorChanged",
                    ),
                )
                require(
                    record["kind"]?.asText() == "token-paired-iteration-admission-1" &&
                        record["iteration"]?.intValue() == iteration &&
                        record["scopeSha256"]?.asText() == scope &&
                        record["protocolSha256"]?.asText() == protocol.sha256 &&
                        record["actorChanged"]?.booleanValue() == true &&
                        PurchaseJson.integer(record["acceptedEpochs"], "accepted epochs") in 1..20
                ) {
                    "Each fixed iteration must retain its protocol, scope, actual learning and finite completion."
                }
                val ready = record.required("ready")
                PurchaseJson.fields(ready, setOf("TRAIN", "VALIDATION"))
                require(
                    PurchaseJson.integer(ready["TRAIN"], "TRAIN exposure") in 256..512 &&
                        PurchaseJson.integer(ready["VALIDATION"], "VALIDATION exposure") in 64..128
                ) {
                    "Both outcome-blind branching exposure floors are required."
                }
                val current = subject(input.path, record.required("currentActor"))
                val output = subject(input.path, record.required("outputActor"))
                val initial = subject(input.path, record.required("originalActor"))
                val corpus = subject(input.path, record.required("corpus"))
                val fit = subject(input.path, record.required("fitCompletion"))
                val seal = subject(input.path, record.required("validationProbabilitySeal"))
                require(
                    initial.sha256 == original.sha256 &&
                        output.sha256 != current.sha256 &&
                        current.sha256 ==
                            (previous?.get("outputActor")?.get("sha256")?.asText()
                                ?: original.sha256)
                ) {
                    "The only actor chain is the original initializer to iteration one to iteration two."
                }
                val corpusRecord = PurchaseJson.read(corpus.path)
                val done = PurchaseJson.read(fit.path)
                val probabilities = PurchaseJson.read(seal.path)
                val identity = done.required("identity")
                val context = identity.required("context")
                require(
                    corpusRecord["kind"]?.asText() == "token-paired-corpus-1" &&
                        corpusRecord["complete"]?.booleanValue() == true &&
                        corpusRecord["exposurePassed"]?.booleanValue() == true &&
                        corpusRecord["origins"]?.intValue() == 640 &&
                        corpusRecord["trainOrigins"]?.intValue() == 512 &&
                        corpusRecord["validationOrigins"]?.intValue() == 128 &&
                        corpusRecord["iteration"]?.intValue() == iteration &&
                        corpusRecord["scopeSha256"]?.asText() == scope &&
                        corpusRecord["scienceSha256"] == record["scienceSha256"] &&
                        corpusRecord["currentActorSha256"]?.asText() == current.sha256 &&
                        corpusRecord["originalActorSha256"]?.asText() == original.sha256 &&
                        corpusRecord["ready"] == ready &&
                        corpusRecord["historicalReproductions"]?.intValue() == 4 &&
                        corpusRecord["semanticValidationLabelsDecoded"]?.booleanValue() == false &&
                        corpusRecord["chunks"]?.size() == 10 &&
                        corpusRecord["scienceIdentity"]["origins"] ==
                            p["iterations"][offset]["origins"]
                ) {
                    "The complete public corpus must bind all fixed worlds, four reproductions and unopened validation labels."
                }
                require(
                    done["kind"]?.asText() == "token-paired-fit-complete-1" &&
                        done["complete"]?.booleanValue() == true &&
                        done["operationalPassed"]?.booleanValue() == true &&
                        done["actorChanged"]?.booleanValue() == true &&
                        done["outputModelSha256"]?.asText() == output.sha256 &&
                        done["acceptedEpochs"] == record["acceptedEpochs"] &&
                        context["iteration"]?.intValue() == iteration &&
                        context["source_sha256"]?.asText() == corpus.sha256 &&
                        context["scope_sha256"]?.asText() == scope &&
                        context["current_model_sha256"]?.asText() == current.sha256 &&
                        context["original_model_sha256"]?.asText() == original.sha256 &&
                        context["dictionary_sha256"]?.asText() == p["dictionarySha256"]?.asText() &&
                        identity["epochSeeds"] == p["iterations"][offset]["shuffleSeeds"] &&
                        identity["publicCoverage"]["trainingReady"] == ready["TRAIN"] &&
                        identity["publicCoverage"]["validationReady"] == ready["VALIDATION"]
                ) {
                    "The actual completed fit must bind its exact current actor, corpus, dictionary, shuffle recipe and exposure."
                }
                val files = done.required("artifacts")
                require(files.fieldNames().asSequence().toSet() == ARTIFACTS) {
                    "Retain the exact fixed-fit transaction artifacts."
                }
                ARTIFACTS.forEach { name ->
                    val file = fit.path.parent.resolve(name).toRealPath()
                    val artifact = TokenPairedSubject(file, sha(files[name], name), file.toString())
                    artifact.verify()
                    subjects += artifact
                }
                require(
                    PurchaseJson.read(fit.path.parent.resolve("identity.json")) == identity &&
                        files["policy.json"].asText() == output.sha256
                ) {
                    "Fit identity and selected policy must be the original transaction bytes."
                }
                require(
                    probabilities["kind"]?.asText() == "token-paired-validation-probabilities-1" &&
                        probabilities["complete"]?.booleanValue() == true &&
                        probabilities["semanticLabelsDecoded"]?.booleanValue() == false &&
                        probabilities["context"] == context &&
                        probabilities["fitFinishedSha256"]?.asText() == fit.sha256 &&
                        probabilities["modelSha256"]?.asText() == output.sha256 &&
                        probabilities["originalModelSha256"]?.asText() == original.sha256 &&
                        probabilities["currentModelSha256"]?.asText() == current.sha256 &&
                        probabilities["rows"]?.map { it["index"].intValue() } ==
                            (512 until 640).toList() &&
                        probabilities["counts"]["READY"] == ready["VALIDATION"]
                ) {
                    "All validation probabilities must be durably sealed for this exact model before semantic labels."
                }
                probabilities["rows"].forEach { row ->
                    validateProbabilityRow(row)
                    val index = row["index"].intValue()
                    require(
                        row["futureSeeds"] == p["iterations"][offset]["origins"][index]["future"]
                    ) {
                        "Validation seals must retain every original paired future stream."
                    }
                }
                require(
                    listOf("READY", "SINGLETON", "MISSING").all { status ->
                        probabilities["counts"][status]?.intValue() ==
                            probabilities["rows"].count { it["status"].asText() == status }
                    }
                ) {
                    "Validation probability counts must describe the complete sealed rows."
                }
                if (iteration == 1) {
                    require(record["previousAdmission"].isNull && identity["predecessor"].isNull) {
                        "The first iteration cannot import an earlier fit or optimizer."
                    }
                } else {
                    val predecessor = subject(input.path, record.required("previousAdmission"))
                    require(
                        predecessor.sha256 == previousSubject?.sha256 &&
                            identity["predecessor"]["finishedSha256"] ==
                                previous?.get("fitCompletion")?.get("sha256") &&
                            identity["predecessor"]["modelSha256"] ==
                                previous?.get("outputActor")?.get("sha256")
                    ) {
                        "Iteration two must consume the sole completed first iteration, without reselection."
                    }
                }
                previous = record
                previousSubject = input
            }
            require(final.sha256 == previous?.get("outputActor")?.get("sha256")?.asText()) {
                "The final screen actor must be exactly iteration two."
            }
            return TokenPairedAdmission(
                    source,
                    protocol,
                    p,
                    original,
                    final,
                    scope,
                    ecology,
                    iterations,
                    subjects.toList(),
                )
                .also(TokenPairedAdmission::verify)
        }

        private fun sha(value: JsonNode?, name: String): String =
            PurchaseJson.text(requireNotNull(value) { "$name SHA is required." }, "$name SHA")
                .also {
                    require(it.matches(Regex("[0-9a-f]{64}"))) {
                        "$name requires an exact SHA-256."
                    }
                }

        private fun validateProbabilityRow(row: JsonNode) {
            PurchaseJson.fields(
                row,
                setOf(
                    "index",
                    "status",
                    "publicSha256",
                    "requestSha256",
                    "futureSeeds",
                    "proposedIndices",
                    "inclusionProbability",
                    "currentProbabilities",
                    "probabilities",
                ),
            )
            sha(row["publicSha256"], "public row")
            sha(row["requestSha256"], "request")
            val status = row["status"].asText()
            require(status in setOf("READY", "SINGLETON", "MISSING")) {
                "A validation probability row requires its original support status."
            }
            val current = row.required("currentProbabilities")
            val next = row.required("probabilities")
            if (status == "MISSING") {
                require(current.isEmpty && next.isNull && row["proposedIndices"].isEmpty) {
                    "Missing worlds cannot invent actor probabilities."
                }
                return
            }
            require(
                current.isArray &&
                    current.size() in 1..75 &&
                    next.isArray &&
                    next.size() == current.size() &&
                    (current.size() > 1) == (status == "READY")
            ) {
                "Validation probabilities must cover the exact complete offered support."
            }
            for (values in listOf(current, next)) {
                require(
                    values.all {
                        it.isNumber &&
                            it.doubleValue().isFinite() &&
                            it.doubleValue() > 0.0 &&
                            it.doubleValue() <= 1.0
                    } && kotlin.math.abs(values.sumOf(JsonNode::doubleValue) - 1.0) < 1e-9
                ) {
                    "Sealed probabilities require finite full support and unit mass."
                }
            }
        }
    }
}
