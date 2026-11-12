package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path

/** Two separate invocations: public proposals first, sealed prediction admission before tails. */
internal object PurchasePlanMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Plan-ranking options use --name value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in
                        setOf(
                            "--mode",
                            "--source",
                            "--output",
                            "--workers",
                            "--hours",
                            "--describe",
                            "--public",
                            "--predictions",
                        )
                }
        ) {
            "Plan-ranking options must be unique and supported."
        }
        val tails = fields["--mode"] == "purchase-plan-tails"
        require(tails || fields["--mode"] == "purchase-plan-public") {
            "The plan stage must be explicit."
        }
        require(
            tails == (fields.containsKey("--public") && fields.containsKey("--predictions")) &&
                (tails || !fields.containsKey("--public") && !fields.containsKey("--predictions"))
        ) {
            "Only the tail stage accepts both public evidence and sealed predictions."
        }
        val describe =
            fields["--describe"]?.let {
                require(it == "true") { "Describe must be true when present." }
                true
            } ?: false
        val ordinary =
            fields.filterKeys { it !in setOf("--describe", "--public", "--predictions") } +
                ("--mode" to "joint-collect")
        val options = JointCollectionOptions.parse(ordinary.flatMap { listOf(it.key, it.value) })
        require(options.hours <= 2.0) {
            "A plan stage cannot exceed the external two-hour campaign ceiling."
        }
        val input = PurchasePlanInput.read(options.source)
        require(
            !Files.exists(options.output) && input.files.none { it.startsWith(options.output) }
        ) {
            "Plan output must be new and separate from every immutable input."
        }
        val admission =
            if (tails) {
                PurchasePlanTailAdmission(
                    input,
                    Path.of(requireNotNull(fields["--public"]) { "Public evidence required." })
                        .toRealPath(),
                    Path.of(
                            requireNotNull(fields["--predictions"]) {
                                "Sealed predictions required."
                            }
                        )
                        .toRealPath(),
                )
            } else {
                null
            }
        require(admission == null || !options.output.startsWith(admission.publicDirectory)) {
            "Tail outputs must not mutate the frozen public bank."
        }
        if (describe) {
            TrainingArtifacts(options.output).write("manifest.json", input.metadata())
            println(PurchaseJson.mapper.writeValueAsString(input.metadata()))
            return
        }
        Files.createDirectories(options.output.parent)
        val output = Files.createDirectory(options.output).toRealPath()
        val deadline = System.nanoTime() + (options.hours * 3_600_000_000_000).toLong()
        if (admission == null) {
            collectPublic(input, output, options.workers, deadline)
        } else {
            collectTails(input, admission, output, options.workers, deadline)
        }
    }

    private fun collectPublic(
        input: PurchasePlanInput,
        output: Path,
        workers: Int,
        deadline: Long,
    ) {
        val store = PurchasePlanPublicStore(input, output)
        val collector = PurchasePlanPublicCollector(input.study, deadline)
        var completed = 0
        Tournaments(workers, deadline).use { pool ->
            pool.run(
                (0 until 256).map { index ->
                    {
                        store.save(collector.collect(index))
                        index
                    }
                }
            ) {
                completed++
                println("Public plan origin $completed/256 complete.")
            }
        }
        store.finish()
    }

    private fun collectTails(
        input: PurchasePlanInput,
        admission: PurchasePlanTailAdmission,
        output: Path,
        workers: Int,
        deadline: Long,
    ) {
        val artifacts = TrainingArtifacts(output)
        artifacts.write("manifest.json", input.metadata())
        artifacts.write(
            "admission.json",
            mapOf(
                "publicDirectory" to admission.publicDirectory.toString(),
                "publicFinishedSha256" to PurchaseJson.hash(admission.finishedPath),
                "predictions" to admission.predictions.toString(),
                "predictionsSha256" to PurchaseJson.hash(admission.predictions),
                "inputSha256" to admission.pins.mapKeys { it.key.toString() },
            ),
        )
        val collector = PurchasePlanTails(input, admission, artifacts, deadline)
        var completed = 0
        Tournaments(workers, deadline).use { pool ->
            pool.run(
                (0 until 256).map { index ->
                    {
                        artifacts.write("result-$index.json", collector.collect(index))
                        index
                    }
                }
            ) {
                completed++
                println("Plan continuation origin $completed/256 complete.")
            }
        }
        admission.unchanged()
        val names = mutableListOf("manifest.json", "admission.json")
        var terminalGames = 0
        for (index in 0 until 256) {
            val name = "result-$index.json"
            names.add(name)
            val result = PurchaseJson.read(output.resolve(name))
            require(result.required("originIndex").intValue() == index) {
                "Every assigned origin must complete exactly once."
            }
            val public = admission.public(index)
            val supported =
                if (public.status == PurchasePlanOriginStatus.READY) {
                    public.plans.filter { it.status == PurchasePlanStatus.SUPPORTED }
                } else {
                    emptyList()
                }
            val executions = supported.associate { it.index to (it.aliasOf ?: it.index) }
            val expected =
                supported
                    .filter { it.aliasOf == null }
                    .flatMap { plan ->
                        (0 until 4).map { replica -> plan.index to replica }
                    }
            val tails = PurchaseJson.array(result.required("tails"), "completed tails")
            require(
                result.required("status").asText() == public.status.name &&
                    result.required("logicalPlanExecutions") == PurchasePlanWire.wire(executions) &&
                    tails.map {
                        PurchaseJson.integer(it.required("planIndex"), "planIndex") to
                            PurchaseJson.integer(it.required("replica"), "replica")
                    } == expected
            ) {
                "Completion requires all and only the prospectively supported paired plan executions."
            }
            for (tail in result.required("tails")) {
                val file = tail.required("canonicalFile").asText()
                val replica = tail.required("replica").intValue()
                require(
                    file.matches(Regex("tail-$index-[0-7]-[0-3]\\.json")) &&
                        PurchaseJson.hash(output.resolve(file)) ==
                            tail.required("canonicalSha256").asText() &&
                        tail.required("continuationSeed").longValue() ==
                            input.study.streams[index].futures[replica] &&
                        tail.required("outcome").required("continuationSeed").longValue() ==
                            input.study.streams[index].futures[replica]
                ) {
                    "A terminal return requires its exact verified canonical suffix."
                }
                names.add(file)
                terminalGames++
            }
        }
        require(terminalGames <= 8192 && names.distinct().size == names.size) {
            "The complete sibling bank exceeded its fixed terminal cap or reused a suffix path."
        }
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "purchase-plan-tails-complete-1",
                "signature" to input.signature,
                "origins" to 256,
                "terminalGames" to terminalGames,
                "actorFits" to 0,
                "predictionsSha256" to PurchaseJson.hash(admission.predictions),
                "artifactSha256" to names.associateWith { PurchaseJson.hash(output.resolve(it)) },
            ),
        )
    }
}
