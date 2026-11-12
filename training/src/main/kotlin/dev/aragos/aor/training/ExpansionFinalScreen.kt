package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** A three-result paired world. FINAL is stored once, not rerun or counted twice. */
internal data class ExpansionScreenWorld(
    val kind: String,
    val signature: String,
    val index: Int,
    val seed: Int,
    val seat: Int,
    val configuration: String,
    val opponentIndices: List<Int>,
    val roles: Map<ExpansionScreenRole, ArenaGame>,
)

internal object ExpansionFinalScreen {
    /**
     * Every scientific role uses the same immutable world/lineup and fresh complete controllers.
     */
    fun play(
        input: ExpansionFinalInput,
        index: Int,
        deadline: Long,
        canonical: (ExpansionScreenRole, PurchaseCanonicalReplay) -> Unit,
    ): ExpansionScreenWorld {
        val lineup = StrategyArena.lineup(input.arena, index)
        val roles =
            ExpansionScreenRole.entries.associateWith { role ->
                val trace =
                    if (index in ExpansionFinalInput.REPLAYS) PurchaseCanonicalTrace(lineup.seat)
                    else null
                StrategyArena.tracedGame(
                        input.specification(role),
                        index,
                        input.side(role),
                        deadline,
                        trace,
                    )
                    .also {
                        trace?.let { canonical(role, it.result()) }
                    }
            }
        return ExpansionScreenWorld(
            ExpansionFinalInput.VERSION,
            input.signature,
            index,
            lineup.seed,
            lineup.seat,
            lineup.configuration.id,
            lineup.opponentIndices,
            roles,
        )
    }

    /**
     * Historical controls finish before any new bank; original score annotations are not policy
     * choices.
     */
    fun historical(input: ExpansionFinalInput, ordinal: Int, deadline: Long): StrategyReplay {
        val control = input.historical[ordinal]
        val arena = ArenaSpecification.read(control.source.path)
        require(
            arena.expansionActor == null &&
                arena.jointPolicy != null &&
                arena.expansionImitation != null &&
                arena.tokenOrder != null
        ) {
            "Historical control must be the explicit unmodified G3/E/T composition."
        }
        val selected =
            if (control.side == StrategyReplaySide.CANDIDATE) "candidateBundle"
            else "incumbentBundle"
        val old = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
        val current =
            PurchaseJson.mapper.valueToTree<JsonNode>(input.rawComparison.overlayMetadata())
        val roleName = control.side.commandValue
        require(
            old["jointPolicy"][selected]["sha256"] ==
                current["jointPolicy"]["sharedBundle"]["sha256"] &&
                old["jointPolicy"]["baseline"]["sha256"] ==
                    current["jointPolicy"]["baseline"]["sha256"] &&
                old["expansionImitation"]["${roleName}Model"]["sha256"] ==
                    current["expansionImitation"]["candidateModel"]["sha256"] &&
                old["tokenOrder"]["${roleName}Selector"] ==
                    current["tokenOrder"]["candidateSelector"] &&
                control.side.model(arena) == input.arena.candidate
        ) {
            "Historical controlled G3/E/T/F0/ordinary roles must match the raw screen control."
        }
        val expected = control.replay.read()
        val replay =
            StrategyReplays.play(
                arena,
                control.index,
                control.side,
                deadline,
                StrategyReplayPair.read(control.pair.path),
            )
        verifyHistorical(expected, ExpansionOnPolicyStore.wire(replay))
        control.source.verify()
        control.pair.verify()
        control.replay.verify()
        return replay
    }

    internal fun verifyHistorical(expected: JsonNode, actual: JsonNode) {
        require(
            expected["replayVerified"]?.booleanValue() == true &&
                actual["replayVerified"]?.booleanValue() == true
        ) {
            "Both historical traces must have canonical replay verification."
        }
        for (key in
            listOf(
                "index",
                "configuration",
                "seed",
                "seat",
                "side",
                "opponentIndices",
                "header",
                "entries",
                "jointPolicy",
                "expansionActor",
            )) {
            require(expected[key] == actual[key]) { "Historical raw-G3 replay differs at $key." }
        }
        fun commands(value: JsonNode): List<JsonNode> =
            value.required("decisions").map { row ->
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "snapshotEntryCount" to row["snapshotEntryCount"],
                        "commandEntryIndex" to row["commandEntryIndex"],
                        "command" to row["chosen"]["command"],
                    )
                )
            }
        require(commands(expected) == commands(actual)) {
            "Historical controlled commands or absolute offsets changed."
        }
    }

    fun reproduce(
        input: ExpansionFinalInput,
        index: Int,
        role: ExpansionScreenRole,
        world: JsonNode,
        canonical: JsonNode,
        deadline: Long,
    ): StrategyReplay {
        validateWorld(input, index, world)
        require(index in ExpansionFinalInput.REPLAYS) {
            "Only four predeclared worlds have side reproductions."
        }
        val side =
            if (input.side(role) == ArenaSide.CANDIDATE) StrategyReplaySide.CANDIDATE
            else StrategyReplaySide.INCUMBENT
        val replay = StrategyReplays.play(input.specification(role), index, side, deadline)
        val actual = ExpansionOnPolicyStore.wire(replay)
        for (field in listOf("wins", "rewards", "ranks", "scores", "decisions")) {
            require(actual["game"][field] == world["roles"][role.name][field]) {
                "Reproduced $role terminal differs at $field."
            }
        }
        require(
            canonical["header"] == actual["header"] &&
                canonical["entries"] == actual["entries"] &&
                replay.replayVerified
        ) {
            "Reproduced $role canonical history differs from its original scientific game."
        }
        return replay
    }

    fun validateWorld(input: ExpansionFinalInput, index: Int, world: JsonNode) {
        val lineup = StrategyArena.lineup(input.arena, index)
        require(
            world["kind"]?.asText() == ExpansionFinalInput.VERSION &&
                world["signature"]?.asText() == input.signature &&
                world["index"]?.intValue() == index &&
                world["seed"]?.intValue() == lineup.seed &&
                world["seat"]?.intValue() == lineup.seat &&
                world["configuration"]?.asText() == lineup.configuration.id &&
                world["opponentIndices"]?.map(JsonNode::intValue) == lineup.opponentIndices &&
                world["roles"]?.fieldNames()?.asSequence()?.toSet() ==
                    ExpansionScreenRole.entries.map { it.name }.toSet()
        ) {
            "A screen world must retain every fixed role, original lineup and source identity."
        }
    }

    /** Pure completed-bank statistics; no intermediate report or data-dependent role selection. */
    fun report(worlds: List<JsonNode>): Map<String, Any> {
        require(
            worlds.size == 1024 && worlds.map { it["index"].intValue() } == (0 until 1024).toList()
        ) {
            "Only the full ordered 1,024-world bank has a development report."
        }
        fun contrast(
            first: ExpansionScreenRole,
            second: ExpansionScreenRole,
        ): Map<String, PilotInterval> =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                pilotInterval(
                    worlds.map { world ->
                        val seat = world["seat"].intValue()
                        fun value(role: ExpansionScreenRole): Double {
                            val game = world["roles"][role.name]
                            return if (metric == "chaos") {
                                if (game["scores"][seat]["total"].isNull) 1.0 else 0.0
                            } else
                                game[metric][seat].doubleValue().also {
                                    require(it.isFinite() && it in 0.0..1.0) {
                                        "Paired metrics must be finite normalized rewards."
                                    }
                                }
                        }
                        value(first) - value(second)
                    }
                )
            }
        val raw = contrast(ExpansionScreenRole.FINAL, ExpansionScreenRole.RAW_G3)
        val learned = contrast(ExpansionScreenRole.FINAL, ExpansionScreenRole.INITIAL)
        val exploration = contrast(ExpansionScreenRole.INITIAL, ExpansionScreenRole.RAW_G3)
        fun positive(interval: PilotInterval?) = interval?.lower95?.let { it > 0 } == true
        return mapOf(
            "kind" to "expansion-final-screen-report-1",
            "worlds" to 1024,
            "finalMinusG3" to raw,
            "finalMinusInitializer" to learned,
            "initializerMinusG3" to exploration,
            "developmentGatePassed" to
                (positive(raw["wins"]) && positive(raw["rewards"]) && positive(learned["rewards"])),
            "intervalMethod" to
                "Normal 95% across independent paired worlds; final shared across contrasts",
            "qualification" to false,
            "activated" to false,
            "automaticSuccessor" to false,
        )
    }
}
