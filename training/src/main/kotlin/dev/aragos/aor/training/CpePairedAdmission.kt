package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Rehashes both atomic three-head publications before any policy tensor is parsed. */
internal class CpePairedAdmission
private constructor(
    val source: TokenPairedSubject,
    val protocol: TokenPairedSubject,
    val protocolRecord: JsonNode,
    val originalBundle: TokenPairedSubject,
    val finalBundle: TokenPairedSubject,
    val bundleScopeSha256: String,
    val ecologySha256: String,
    private val subjects: List<TokenPairedSubject>,
) {
    fun verify() = subjects.forEach(TokenPairedSubject::verify)

    companion object {
        const val VERSION = "cpe-paired-final-screen-admission-1"

        fun read(source: TokenPairedSubject): CpePairedAdmission = Reader(source).read()
    }

    private class Reader(private val source: TokenPairedSubject) {
        private val subjects = mutableListOf(source)
        private val heads = CpeHead.entries.map { it.name }.toSet()
        private val children =
            setOf(
                "identity.json",
                "policy.json",
                "checkpoint.json",
                "history.json",
                "finished.json",
            )

        private fun subject(owner: Path, node: JsonNode): TokenPairedSubject =
            TokenPairedSubject.read(node, owner.parent).also(subjects::add)

        private fun artifact(directory: Path, name: String, hash: JsonNode): TokenPairedSubject {
            val path = directory.resolve(name).toRealPath()
            return TokenPairedSubject(path, sha(hash, name), path.toString()).also {
                it.verify()
                subjects += it
            }
        }

        fun read(): CpePairedAdmission {
            source.verify()
            val value = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                value,
                setOf(
                    "kind",
                    "protocol",
                    "bundleScopeSha256",
                    "ecologySha256",
                    "iterations",
                    "finalBundle",
                    "originalBundle",
                    "completedIterations",
                    "learnerHost",
                    "operationallyComplete",
                    "validationEffectsUsedForSelection",
                ),
            )
            require(
                value["kind"]?.asText() == VERSION &&
                    value["completedIterations"]?.intValue() == 2 &&
                    value["learnerHost"]?.asText() == "DESKTOP" &&
                    value["operationallyComplete"]?.booleanValue() == true &&
                    value["validationEffectsUsedForSelection"]?.booleanValue() == false
            ) {
                "Only the complete predeclared second three-head bundle may enter the final screen."
            }
            val protocol = subject(source.path, value.required("protocol"))
            val plan = PurchaseJson.read(protocol.path)
            require(plan["kind"]?.asText() == "cpe-paired-campaign-1") {
                "The exact original C/P/E campaign protocol is required."
            }
            val scope = sha(value["bundleScopeSha256"], "bundle scope")
            val ecology = sha(value["ecologySha256"], "ecology")
            val original = subject(source.path, value.required("originalBundle"))
            val final = subject(source.path, value.required("finalBundle"))
            require(
                plan["originalBundleSha256"]?.asText() == original.sha256 &&
                    plan["bundleScopeSha256"]?.asText() == scope &&
                    plan["ecologySha256"]?.asText() == ecology
            ) {
                "Original complete bundle, scope and ecology must match the original protocol."
            }
            val iterations =
                PurchaseJson.array(value["iterations"], "iterations").map {
                    subject(source.path, it)
                }
            require(iterations.size == 2 && iterations.map { it.sha256 }.distinct().size == 2) {
                "Exactly two distinct chronological atomic admissions are required."
            }
            var previous: Iteration? = null
            iterations.forEachIndexed { offset, input ->
                previous =
                    iteration(input, offset + 1, protocol, plan, original, scope, ecology, previous)
            }
            require(final.sha256 == previous?.output?.sha256) {
                "The final bundle must be exactly the sole completed second publication."
            }
            return CpePairedAdmission(
                    source,
                    protocol,
                    plan,
                    original,
                    final,
                    scope,
                    ecology,
                    subjects.toList(),
                )
                .also(CpePairedAdmission::verify)
        }

        private data class Iteration(
            val source: TokenPairedSubject,
            val output: TokenPairedSubject,
            val fit: TokenPairedSubject,
            val childHashes: Map<String, String>,
            val models: JsonNode,
        )

        private fun bundle(input: TokenPairedSubject): JsonNode {
            val value = PurchaseJson.read(input.path)
            PurchaseJson.fields(value, setOf("kind", "heads"))
            require(value["kind"]?.asText() == CpePairedBundle.VERSION) {
                "A complete explicit C/P/E descriptor is required."
            }
            PurchaseJson.fields(value.required("heads"), heads)
            heads.forEach { head ->
                val item = value["heads"].required(head)
                PurchaseJson.fields(item, setOf("path", "sha256", "dictionarySha256"))
                sha(item["dictionarySha256"], "$head dictionary")
                val path = Path.of(PurchaseJson.text(item["path"], "$head path"))
                require(
                    !path.isAbsolute &&
                        path.normalize() == path &&
                        path.none { it.toString() == ".." }
                ) {
                    "A published bundle requires confined relative component paths."
                }
                val component = PurchaseJson.mapper.createObjectNode()
                component.set<JsonNode>("path", item["path"])
                component.set<JsonNode>("sha256", item["sha256"])
                subject(input.path, component)
            }
            return value["heads"]
        }

        private fun iteration(
            input: TokenPairedSubject,
            number: Int,
            protocol: TokenPairedSubject,
            plan: JsonNode,
            original: TokenPairedSubject,
            scope: String,
            ecology: String,
            previous: Iteration?,
        ): Iteration {
            val row = PurchaseJson.read(input.path)
            PurchaseJson.fields(
                row,
                setOf(
                    "kind",
                    "iteration",
                    "protocolSha256",
                    "bundleScopeSha256",
                    "ecologySha256",
                    "currentBundle",
                    "outputBundle",
                    "originalBundle",
                    "corpus",
                    "cohorts",
                    "fitCompletion",
                    "validationProbabilitySeal",
                    "previousAdmission",
                    "ready",
                    "acceptedEpochs",
                    "allHeadsChanged",
                ),
            )
            require(
                row["kind"]?.asText() == "cpe-paired-iteration-admission-1" &&
                    row["iteration"]?.intValue() == number &&
                    row["protocolSha256"]?.asText() == protocol.sha256 &&
                    row["bundleScopeSha256"]?.asText() == scope &&
                    row["ecologySha256"]?.asText() == ecology &&
                    row["allHeadsChanged"]?.booleanValue() == true
            ) {
                "Each atomic admission must retain the exact protocol, iteration, scope and all-head learning."
            }
            val current = subject(input.path, row.required("currentBundle"))
            val output = subject(input.path, row.required("outputBundle"))
            val initial = subject(input.path, row.required("originalBundle"))
            require(
                initial.sha256 == original.sha256 &&
                    current.sha256 == (previous?.output?.sha256 ?: original.sha256) &&
                    output.sha256 != current.sha256
            ) {
                "Only the original to first to second complete-bundle chain is permitted."
            }
            val currentHeads = bundle(current)
            val initialHeads = bundle(initial)
            val outputHeads = bundle(output)
            val corpus = subject(input.path, row.required("corpus"))
            val fit = subject(input.path, row.required("fitCompletion"))
            val seal = subject(input.path, row.required("validationProbabilitySeal"))
            val data = PurchaseJson.read(corpus.path)
            val done = PurchaseJson.read(fit.path)
            val identity = done.required("identity")
            val probabilities = PurchaseJson.read(seal.path)
            require(
                data["kind"]?.asText() == "cpe-paired-iteration-corpus-1" &&
                    data["complete"]?.booleanValue() == true &&
                    data["exposurePassed"]?.booleanValue() == true &&
                    data["iteration"]?.intValue() == number &&
                    data["independentOrigins"]?.intValue() == 1920
            ) {
                "All three independent complete cohorts must precede any final admission."
            }
            require(
                done["kind"]?.asText() == "cpe-paired-bundle-complete-1" &&
                    done["complete"]?.booleanValue() == true &&
                    done["operationalPassed"]?.booleanValue() == true &&
                    done["outputBundleSha256"]?.asText() == output.sha256 &&
                    identity["kind"]?.asText() == "cpe-paired-bundle-identity-1" &&
                    identity["iteration"]?.intValue() == number
            ) {
                "Every output must be one successful atomic three-head transaction."
            }
            for ((key, expected) in
                mapOf(
                    "originalBundleSha256" to original.sha256,
                    "currentBundleSha256" to current.sha256,
                    "bundleScopeSha256" to scope,
                    "ecologySha256" to ecology,
                )) {
                require(data[key]?.asText() == expected && identity[key]?.asText() == expected) {
                    "The corpus and atomic identity differ at $key."
                }
            }
            val protocolWire = Files.readString(protocol.path)
            require(
                protocolWire.endsWith("\n") &&
                    !protocolWire.endsWith("\n\n") &&
                    data["protocolDigest"]?.asText() ==
                        CpePairedDigest.bytes(protocolWire.dropLast(1))
            ) {
                "The corpus must bind the exact canonical protocol bytes before their one LF frame."
            }
            val files = done.required("artifacts")
            val expected =
                setOf("identity.json", "bundle.json") +
                    heads.flatMap { head -> children.map { "heads/$head/$it" } }
            PurchaseJson.fields(files, expected)
            expected.forEach { artifact(fit.path.parent, it, files.required(it)) }
            require(
                PurchaseJson.read(fit.path.parent.resolve("identity.json")) == identity &&
                    files["bundle.json"]?.asText() == output.sha256
            ) {
                "The publication must preserve its original root identity and complete descriptor bytes."
            }
            require(
                probabilities["kind"]?.asText() == "cpe-paired-validation-probabilities-1" &&
                    probabilities["complete"]?.booleanValue() == true &&
                    probabilities["semanticLabelsDecoded"]?.booleanValue() == false &&
                    probabilities["iteration"]?.intValue() == number &&
                    probabilities["fitFinishedSha256"]?.asText() == fit.sha256 &&
                    probabilities["bundleSha256"]?.asText() == output.sha256 &&
                    probabilities["originalBundleSha256"]?.asText() == original.sha256 &&
                    probabilities["currentBundleSha256"]?.asText() == current.sha256 &&
                    probabilities["bundleScopeSha256"]?.asText() == scope
            ) {
                "All-head full probability seals must belong to this exact atomic transaction before validation labels."
            }
            for (field in listOf("cohorts", "ready", "acceptedEpochs")) PurchaseJson.fields(
                row.required(field),
                heads,
            )
            for (field in
                listOf("contexts", "trainingSha256", "epochSeeds", "predecessors")) PurchaseJson
                .fields(identity.required(field), heads)
            PurchaseJson.fields(probabilities.required("heads"), heads)
            val childHashes = heads.associateWith { head ->
                validateHead(
                    input,
                    row,
                    head,
                    number,
                    plan,
                    data,
                    identity,
                    files,
                    fit,
                    probabilities,
                    currentHeads,
                    initialHeads,
                    outputHeads,
                    previous,
                )
            }
            if (previous == null) {
                require(
                    row["previousAdmission"].isNull && identity["previousFinishedSha256"].isNull
                ) {
                    "Iteration one cannot import an earlier publication."
                }
            } else {
                val predecessor = subject(input.path, row.required("previousAdmission"))
                require(
                    predecessor.sha256 == previous.source.sha256 &&
                        identity["previousFinishedSha256"]?.asText() == previous.fit.sha256
                ) {
                    "Iteration two must consume the sole first atomic publication."
                }
            }
            return Iteration(input, output, fit, childHashes, outputHeads)
        }

        private fun validateHead(
            input: TokenPairedSubject,
            row: JsonNode,
            head: String,
            number: Int,
            plan: JsonNode,
            data: JsonNode,
            identity: JsonNode,
            files: JsonNode,
            fit: TokenPairedSubject,
            probabilities: JsonNode,
            current: JsonNode,
            initial: JsonNode,
            output: JsonNode,
            previous: Iteration?,
        ): String {
            val cohort = subject(input.path, row["cohorts"].required(head))
            val value = PurchaseJson.read(cohort.path)
            val ready = row["ready"].required(head)
            PurchaseJson.fields(ready, setOf("TRAIN", "VALIDATION"))
            require(
                PurchaseJson.integer(ready["TRAIN"], "TRAIN roots") in 256..512 &&
                    PurchaseJson.integer(ready["VALIDATION"], "VALIDATION roots") in 64..128
            ) {
                "Every head must meet both outcome-blind branching exposure floors."
            }
            val context = identity["contexts"].required(head)
            val declaration = plan["heads"].required(head)
            val bank = plan["iterations"][number - 1]["heads"].required(head)
            require(
                value["kind"]?.asText() == "cpe-paired-corpus-1" &&
                    value["head"]?.asText() == head &&
                    value["iteration"]?.intValue() == number &&
                    value["complete"]?.booleanValue() == true &&
                    value["exposurePassed"]?.booleanValue() == true &&
                    value["origins"]?.intValue() == 640 &&
                    value["ready"] == ready &&
                    value["protocolDigest"] == data["protocolDigest"] &&
                    data["cohorts"][head]["sha256"]?.asText() == cohort.sha256
            ) {
                "Each head requires its original complete 640-world cohort and exposure receipt."
            }
            for (key in
                listOf(
                    "originalBundleSha256",
                    "currentBundleSha256",
                    "bundleScopeSha256",
                    "ecologySha256",
                )) require(value[key] == identity[key]) {
                "Cohort $head differs from its complete bundle at $key."
            }
            require(
                context["head"]?.asText() == head &&
                    context["iteration"]?.intValue() == number &&
                    context["source_sha256"]?.asText() == cohort.sha256 &&
                    context["scope_sha256"] == declaration["scopeSha256"] &&
                    context["scope_sha256"] == value["scopeSha256"] &&
                    context["dictionary_sha256"] == declaration["dictionarySha256"] &&
                    context["dictionary_semantic_sha256"] ==
                        declaration["semanticDictionarySha256"] &&
                    context["original_model_sha256"] == declaration["originalActorSha256"] &&
                    context["original_model_sha256"] == initial[head]["sha256"] &&
                    context["current_model_sha256"] == current[head]["sha256"] &&
                    initial[head]["dictionarySha256"] == current[head]["dictionarySha256"] &&
                    output[head]["dictionarySha256"] == current[head]["dictionarySha256"] &&
                    context["dictionary_semantic_sha256"] == output[head]["dictionarySha256"] &&
                    identity["epochSeeds"][head] == bank["shuffleSeeds"] &&
                    context["request_bank_sha256"]?.asText() ==
                        CpePairedDigest.json(bank.required("origins"))
            ) {
                "The head must bind exact dictionaries, full bundle components, prospective request table and shuffles."
            }
            for ((key, parent) in
                mapOf(
                    "bundle_scope_sha256" to "bundleScopeSha256",
                    "ecology_sha256" to "ecologySha256",
                    "original_bundle_sha256" to "originalBundleSha256",
                    "current_bundle_sha256" to "currentBundleSha256",
                )) require(context[key] == identity[parent]) {
                "Head $head must retain the atomic $parent."
            }
            val directory = fit.path.parent.resolve("heads/$head")
            val done = PurchaseJson.read(directory.resolve("finished.json"))
            val child = done.required("identity")
            val accepted = PurchaseJson.integer(done["acceptedEpochs"], "accepted head epochs")
            require(
                done["kind"]?.asText() == "cpe-paired-head-complete-1" &&
                    done["complete"]?.booleanValue() == true &&
                    done["operationalPassed"]?.booleanValue() == true &&
                    done["actorChanged"]?.booleanValue() == true &&
                    accepted in 1..20 &&
                    done["acceptedEpochs"] == row["acceptedEpochs"][head] &&
                    done["outputModelSha256"] == output[head]["sha256"] &&
                    child["kind"]?.asText() == "cpe-paired-head-identity-1" &&
                    child["context"] == context &&
                    child["epochSeeds"] == identity["epochSeeds"][head] &&
                    child["trainingSha256"] == identity["trainingSha256"][head] &&
                    child["predecessor"] == identity["predecessors"][head] &&
                    child["recipe"] == identity["recipe"] &&
                    child["sourceSha256"] == identity["sourceSha256"] &&
                    child["originalDeadline"] == identity["originalDeadline"] &&
                    child == PurchaseJson.read(directory.resolve("identity.json"))
            ) {
                "Every successful child must belong to the same atomic recipe, sources, clock, corpus and component publication."
            }
            val childFiles = done.required("artifacts")
            PurchaseJson.fields(childFiles, children - "finished.json")
            (children - "finished.json").forEach { name ->
                require(childFiles[name] == files["heads/$head/$name"]) {
                    "Child and atomic artifact hashes differ for $head/$name."
                }
            }
            require(
                child["publicCoverage"]["trainingReady"] == ready["TRAIN"] &&
                    child["publicCoverage"]["validationReady"] == ready["VALIDATION"]
            ) {
                "Child public coverage must equal its admitted cohort."
            }
            if (previous == null)
                require(child["predecessor"].isNull) {
                    "The first head cannot use an earlier optimizer or policy."
                }
            else
                require(
                    child["predecessor"]["bundleFinishedSha256"]?.asText() == previous.fit.sha256 &&
                        child["predecessor"]["headFinishedSha256"]?.asText() ==
                            previous.childHashes[head] &&
                        child["predecessor"]["modelSha256"] == previous.models[head]["sha256"]
                ) {
                    "Each second head must consume its exact first atomic predecessor."
                }
            val seal = probabilities["heads"].required(head)
            val rows = PurchaseJson.array(seal["rows"], "sealed validation rows")
            require(
                seal["context"] == context &&
                    seal["modelSha256"] == output[head]["sha256"] &&
                    rows.map { it["index"].intValue() } == (512 until 640).toList() &&
                    seal["counts"]["READY"] == ready["VALIDATION"]
            ) {
                "Every head requires all 128 ordered validation probability rows for its exact output."
            }
            for (status in listOf("READY", "MISSING")) require(
                seal["counts"][status]?.intValue() == rows.count { it["status"].asText() == status }
            ) {
                "Sealed public counts must describe all validation positions."
            }
            rows.forEach { probabilityRow(it, head, bank) }
            return files["heads/$head/finished.json"].asText()
        }

        private fun probabilityRow(row: JsonNode, head: String, bank: JsonNode) {
            PurchaseJson.fields(
                row,
                setOf(
                    "index",
                    "status",
                    "publicSha256",
                    "requestSha256",
                    "futureSeeds",
                    "referenceIndex",
                    "proposedIndices",
                    "inclusionProbability",
                    "currentProbabilities",
                    "probabilities",
                ),
            )
            sha(row["publicSha256"], "public row")
            sha(row["requestSha256"], "request")
            require(row["futureSeeds"] == bank["origins"][row["index"].intValue()]["future"]) {
                "Every probability seal must preserve the original two paired future streams."
            }
            if (row["status"]?.asText() == "MISSING") {
                require(
                    row["referenceIndex"].isNull &&
                        row["proposedIndices"].isNull &&
                        row["inclusionProbability"].isNull &&
                        row["currentProbabilities"].isNull &&
                        row["probabilities"].isNull
                ) {
                    "Missing roots cannot invent references or predictions."
                }
                return
            }
            val current = row.required("currentProbabilities")
            val next = row.required("probabilities")
            val limit = if (head == "PURCHASE") 128 else 1024
            require(
                row["status"]?.asText() == "READY" &&
                    current.isArray &&
                    current.size() in 2..limit &&
                    next.isArray &&
                    next.size() == current.size()
            ) {
                "A selected reservoir root requires exact full branching support."
            }
            for (values in listOf(current, next)) require(
                values.all {
                    it.isNumber &&
                        it.doubleValue().isFinite() &&
                        it.doubleValue() > 0.0 &&
                        it.doubleValue() <= 1.0
                } && abs(values.sumOf(JsonNode::doubleValue) - 1.0) < 1e-9
            ) {
                "Sealed full-support probabilities must be finite, positive and sum to one."
            }
            val reference = PurchaseJson.integer(row["referenceIndex"], "sampled reference")
            val proposed =
                PurchaseJson.array(row["proposedIndices"], "proposals").map {
                    PurchaseJson.integer(it, "proposal")
                }
            require(
                reference in 0 until current.size() &&
                    proposed.size == minOf(9, current.size()) &&
                    proposed.distinct().size == proposed.size &&
                    proposed.first() == reference &&
                    proposed.all { it in 0 until current.size() } &&
                    row["inclusionProbability"].isNumber &&
                    abs(
                        row["inclusionProbability"].doubleValue() -
                            minOf(8, current.size() - 1).toDouble() / (current.size() - 1)
                    ) < 1e-12
            ) {
                "Validation proposals must retain the sampled reference and original uniform nonreference inclusion."
            }
        }

        private fun sha(value: JsonNode?, name: String): String =
            PurchaseJson.text(requireNotNull(value) { "$name SHA is required." }, name).also {
                require(it.matches(Regex("[0-9a-f]{64}"))) { "$name requires an exact SHA-256." }
            }
    }
}
