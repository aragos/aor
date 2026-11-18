package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** A three-result paired world. Each role is stored once and shared across the paired contrasts. */
internal data class ExpansionBaselineWorld(
    val kind: String,
    val signature: String,
    val replica: ExpansionBaselineReplica,
    val index: Int,
    val seed: Int,
    val seat: Int,
    val configuration: String,
    val opponentIndices: List<Int>,
    val roles: Map<ExpansionBaselineRole, ArenaGame>,
)

internal object ExpansionBaselineScreen {
    /**
     * Every scientific role uses the same immutable world/lineup and fresh complete controllers.
     */
    fun play(
        input: ExpansionBaselineInput,
        index: Int,
        deadline: Long,
        canonical: (ExpansionBaselineRole, PurchaseCanonicalReplay) -> Unit,
    ): ExpansionBaselineWorld {
        val lineup = StrategyArena.lineup(input.arena, index)
        val roles =
            ExpansionBaselineRole.entries.associateWith { role ->
                val trace =
                    if (index in ExpansionBaselineInput.REPLAYS) PurchaseCanonicalTrace(lineup.seat)
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
        return ExpansionBaselineWorld(
            ExpansionBaselineInput.VERSION,
            input.signature,
            input.admission.replica,
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
    fun historical(input: ExpansionBaselineInput, ordinal: Int, deadline: Long): StrategyReplay {
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
        ExpansionFinalScreen.verifyHistorical(expected, ExpansionOnPolicyStore.wire(replay))
        control.source.verify()
        control.pair.verify()
        control.replay.verify()
        return replay
    }

    fun reproduce(
        input: ExpansionBaselineInput,
        index: Int,
        role: ExpansionBaselineRole,
        world: JsonNode,
        canonical: JsonNode,
        deadline: Long,
    ): StrategyReplay {
        validateWorld(input, index, world)
        require(index in ExpansionBaselineInput.REPLAYS) {
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

    fun validateWorld(input: ExpansionBaselineInput, index: Int, world: JsonNode) {
        val lineup = StrategyArena.lineup(input.arena, index)
        require(
            world["kind"]?.asText() == ExpansionBaselineInput.VERSION &&
                world["signature"]?.asText() == input.signature &&
                world["replica"]?.asText() == input.admission.replica.name &&
                world["index"]?.intValue() == index &&
                world["seed"]?.intValue() == lineup.seed &&
                world["seat"]?.intValue() == lineup.seat &&
                world["configuration"]?.asText() == lineup.configuration.id &&
                world["opponentIndices"]?.map(JsonNode::intValue) == lineup.opponentIndices &&
                world["roles"]?.fieldNames()?.asSequence()?.toSet() ==
                    ExpansionBaselineRole.entries.map { it.name }.toSet()
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
        val replicas = worlds.map { it["replica"]?.asText() }.distinct()
        require(
            replicas.size == 1 &&
                replicas.single() in ExpansionBaselineReplica.entries.map { it.name }
        ) {
            "Every world must belong to one declared independent replica."
        }
        fun contrast(
            first: ExpansionBaselineRole,
            second: ExpansionBaselineRole,
        ): Map<String, PilotInterval> =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                pilotInterval(
                    worlds.map { world ->
                        val seat = world["seat"].intValue()
                        fun value(role: ExpansionBaselineRole): Double {
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
        val raw = contrast(ExpansionBaselineRole.TREATMENT, ExpansionBaselineRole.RAW_G3)
        val mechanism = contrast(ExpansionBaselineRole.TREATMENT, ExpansionBaselineRole.CONTROL)
        val control = contrast(ExpansionBaselineRole.CONTROL, ExpansionBaselineRole.RAW_G3)
        fun positive(interval: PilotInterval?) = interval?.lower95?.let { it > 0 } == true
        return mapOf(
            "kind" to "expansion-baseline-screen-report-1",
            "worlds" to 1024,
            "replica" to requireNotNull(replicas.single()) { "Replica is required." },
            "treatmentMinusG3" to raw,
            "treatmentMinusControl" to mechanism,
            "controlMinusG3" to control,
            "primaryMechanismPassed" to positive(mechanism["rewards"]),
            "developmentGatePassed" to
                (positive(raw["wins"]) &&
                    positive(raw["rewards"]) &&
                    positive(mechanism["rewards"])),
            "intervalMethod" to
                "Normal 95% across independent paired worlds; each role shared across contrasts",
            "qualification" to false,
            "activated" to false,
            "automaticSuccessor" to false,
        )
    }
}
