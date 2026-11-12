package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Separately authorized public projection, never a collection/replay/inference entry point. */
object CardCalibrationPublicMain {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 4 && arguments[0] == "--source" && arguments[2] == "--output") {
            "Use --source pinned-cohort.json --output NEW_DIRECTORY."
        }
        val source = Path.of(arguments[1]).toRealPath()
        val sourceSha = PurchaseJson.hash(source)
        val input = PurchaseJson.read(source)
        PurchaseJson.fields(
            input,
            setOf(
                "kind",
                "receipt",
                "storage",
                "finished",
                "collection",
                "terminalExport",
                "games",
            ),
        )
        require(input["kind"]?.asText() == "card-calibration-cohort-1") {
            "An explicit calibration cohort is required."
        }
        fun subject(node: JsonNode): Path {
            PurchaseJson.fields(node, setOf("path", "sha256"))
            val path =
                source.parent.resolve(PurchaseJson.text(node["path"], "subject path")).toRealPath()
            require(PurchaseJson.hash(path) == node["sha256"].asText()) {
                "Original TRAIN subject bytes changed."
            }
            return path
        }
        val receiptPath = subject(input.required("receipt"))
        val receipt = PurchaseJson.read(receiptPath)
        require(
            receipt["kind"]?.asText() == "joint-credit-trial-batch-1" &&
                receipt["completed"]?.booleanValue() == true &&
                receipt["arm"]?.asText() == "chronological" &&
                receipt["batch"]?.intValue() == 32
        ) {
            "Only the preselected completed53 chronological TRAIN batch32 is admitted."
        }
        val exportPath = subject(input.required("terminalExport"))
        validateTerminalExport(receipt, PurchaseJson.read(exportPath))
        val storagePath = subject(input.required("storage"))
        val storage = PurchaseJson.read(storagePath)
        require(
            PurchaseJson.hash(storagePath) == receipt["storageManifestSha256"].asText() &&
                storage["archiveSha256"].asText() == receipt["archiveSha256"].asText()
        ) {
            "Original lossless TRAIN storage binding changed."
        }
        val finishedPath = subject(input.required("finished"))
        val finished = PurchaseJson.read(finishedPath)
        require(
            PurchaseJson.hash(finishedPath) ==
                storage["files"]["collection/finished.json"]["sha256"].asText() &&
                finished["games"].intValue() == 256 &&
                finished["signature"].asText() == receipt["signature"].asText()
        ) {
            "Original completed256-world TRAIN evidence is required."
        }
        val collectionPath = subject(input.required("collection"))
        val collection = PurchaseJson.read(collectionPath)
        require(
            PurchaseJson.hash(collectionPath) ==
                storage["files"]["collection/collection.json"]["sha256"].asText() &&
                collection["signature"].asText() == receipt["signature"].asText() &&
                collection["games"].intValue() == 256 &&
                collection["baseline"]["sha256"].asText() == M8 &&
                collection["sharedPurchaseBaseline"]["sha256"].asText() == F0 &&
                collection["bundle"]["baselineSha256"].asText() == F0
        ) {
            "The original own observations must bind the exact separate M8/F0 priors."
        }
        val games = input.required("games")
        require(games.isArray && games.size() == 64) {
            "Use exactly preselected worlds0..63, without refill."
        }
        val output = Path.of(arguments[3]).toAbsolutePath()
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val rowsPath = output.resolve("rows.jsonl")
        val counts = linkedMapOf("card" to 0, "purchase" to 0)
        val hashes = linkedMapOf<String, String>()
        Files.newBufferedWriter(rowsPath).use { writer ->
            games.forEachIndexed { index, node ->
                val path = subject(node)
                val hash = PurchaseJson.hash(path)
                require(
                    hash == finished["gameSha256"][index].asText() &&
                        hash ==
                            storage["files"]["collection/game-$index.json.gz"]["sha256"].asText()
                ) {
                    "Calibration membership is the first64 original completed TRAIN files."
                }
                val rows =
                    Files.newInputStream(path).use { raw ->
                        GZIPInputStream(raw).use { stream ->
                            CardCalibrationPublic.rows(
                                stream,
                                index,
                                hash,
                                receipt["signature"].asText(),
                            )
                        }
                    }
                for (row in rows) {
                    val phase = row["phase"].toString()
                    counts[phase] =
                        requireNotNull(counts[phase]) { "A known phase is required." } + 1
                    writer.write(PurchaseJson.mapper.writeValueAsString(row))
                    writer.newLine()
                }
                require(PurchaseJson.hash(path) == hash) {
                    "Original game bytes changed during projection."
                }
                hashes[path.toString()] = hash
            }
        }
        for (name in listOf("receipt", "storage", "finished", "collection", "terminalExport")) {
            subject(input.required(name))
        }
        require(PurchaseJson.hash(source) == sourceSha) { "The fixed cohort source changed." }
        TrainingArtifacts(output)
            .write(
                "receipt.json",
                mapOf(
                    "kind" to "card-calibration-public-1",
                    "passed" to true,
                    "cohort" to "53/chronological/batch-32/worlds-0-63",
                    "sourceSha256" to sourceSha,
                    "sourceReceiptSha256" to PurchaseJson.hash(receiptPath),
                    "terminalExportSha256" to PurchaseJson.hash(exportPath),
                    "storageSha256" to PurchaseJson.hash(storagePath),
                    "finishedSha256" to PurchaseJson.hash(finishedPath),
                    "gameSha256" to hashes,
                    "originalGameSeeds" to collection["seeds"].take(64).map { it.intValue() },
                    "originalGameSha256" to finished["gameSha256"].take(64).map { it.asText() },
                    "collectionSha256" to PurchaseJson.hash(collectionPath),
                    "phaseDictionaries" to
                        listOf("card", "purchase").associateWith { phase ->
                            val head = collection["bundle"][phase]
                            mapOf(
                                "encoderVersion" to head["encoderVersion"],
                                "actorVersion" to head["actorVersion"],
                                "stateFeatureNames" to head["stateFeatureNames"],
                                "actionFeatureNames" to head["actionFeatureNames"],
                            )
                        },
                    "rowsSha256" to PurchaseJson.hash(rowsPath),
                    "rows" to counts,
                    "maximumRowsPerHeadWorld" to 32,
                    "terminalLabelsDecoded" to false,
                    "behaviorChoicesDecoded" to false,
                    "behaviorValuesDecoded" to false,
                    "newGames" to 0,
                    "policyEvaluations" to 0,
                    "actualFits" to 0,
                ),
            )
    }

    internal fun validateTerminalExport(receipt: JsonNode, exported: JsonNode) {
        require(
            exported["kind"]?.asText() == "joint-credit-terminal-export-1" &&
                exported["newFits"]?.intValue() == 0 &&
                exported["sha256"]?.asText() == G3 &&
                exported["originalBundle"]["sha256"].asText() ==
                    receipt["outputBundleSha256"].asText() &&
                exported["components"]["card"]["sha256"].asText() == CARD &&
                exported["components"]["purchase"]["sha256"].asText() == PURCHASE
        ) {
            "Original batch32 output and the path-only portable G3 export must bind the same heads."
        }
    }

    private const val CARD = "916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf"
    private const val PURCHASE = "6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79"
    private const val M8 = "b7c42a6f347a77c9bd9ff5d29778ea74650a9affe5fdb16738a8bea8d75d17d4"
    private const val F0 = "c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0"
    private const val G3 = "65481635981a22e9a80672a684419ebf8d3342a29830712ccf51bf48c8da936d"
}
