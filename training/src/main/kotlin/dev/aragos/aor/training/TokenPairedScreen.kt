package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

internal data class TokenPairedScreenWorld(
    val kind: String,
    val signature: String,
    val scienceSha256: String,
    val index: Int,
    val seed: Int,
    val seat: Int,
    val configuration: String,
    val opponentIndices: List<Int>,
    val roles: Map<TokenPairedScreenRole, ArenaGame>,
)

/** Three results per world; the final actor is shared by both fixed contrasts. */
internal object TokenPairedScreen {
    fun play(
        input: TokenPairedScreenInput,
        index: Int,
        deadline: Long,
        canonical: (TokenPairedScreenRole, PurchaseCanonicalReplay) -> Unit,
    ): TokenPairedScreenWorld {
        require(index in input.indices) {
            "A screen world must belong to its prescribed host range."
        }
        val lineup = StrategyArena.lineup(input.raw, index)
        val roles =
            TokenPairedScreenRole.entries.associateWith { role ->
                val trace =
                    if (index in TokenPairedScreenInput.REPLAYS) PurchaseCanonicalTrace(lineup.seat)
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
        return TokenPairedScreenWorld(
            TokenPairedScreenInput.VERSION,
            input.signature,
            input.scienceSha256,
            index,
            lineup.seed,
            lineup.seat,
            lineup.configuration.id,
            lineup.opponentIndices,
            roles,
        )
    }

    fun reproduce(
        input: TokenPairedScreenInput,
        index: Int,
        role: TokenPairedScreenRole,
        world: JsonNode,
        canonical: JsonNode,
        deadline: Long,
    ): StrategyReplay {
        require(index in input.ownedReplays) {
            "Only the owner shard may execute a fixed side reproduction."
        }
        validateWorld(world, index, input.scienceSha256, StrategyArena.seed(input.raw.seed, index))
        require(world["signature"]?.asText() == input.signature) {
            "A reproduced world must bind its original source."
        }
        val side =
            if (input.side(role) == ArenaSide.CANDIDATE) StrategyReplaySide.CANDIDATE
            else StrategyReplaySide.INCUMBENT
        val replay = StrategyReplays.play(input.specification(role), index, side, deadline)
        val actual = ExpansionOnPolicyStore.wire(replay)
        for (key in listOf("wins", "rewards", "ranks", "scores", "decisions")) {
            require(actual["game"][key] == world["roles"][role.name][key]) {
                "Reproduced $role terminal differs at $key."
            }
        }
        require(
            canonical["header"] == actual["header"] &&
                canonical["entries"] == actual["entries"] &&
                replay.replayVerified
        ) {
            "Every fixed side reproduction must match its complete original canonical history."
        }
        return replay
    }

    fun validateWorld(world: JsonNode, index: Int, science: String, seed: Int) {
        require(
            world["kind"]?.asText() == TokenPairedScreenInput.VERSION &&
                world["scienceSha256"]?.asText() == science &&
                world["index"]?.intValue() == index &&
                world["seed"]?.intValue() == seed &&
                world["seat"]?.intValue() == index % 4 &&
                world["configuration"]?.asText() ==
                    "players-4/standard/no-crusades/deck-true/art-true" &&
                world["opponentIndices"]?.map(JsonNode::intValue) == List(4) { 0 } &&
                world["roles"]?.fieldNames()?.asSequence()?.toSet() ==
                    TokenPairedScreenRole.entries.map { it.name }.toSet()
        ) {
            "Each compact screen world must retain all three roles and its original global lineup."
        }
        for (role in TokenPairedScreenRole.entries) {
            val game = world["roles"][role.name]
            for (name in listOf("wins", "rewards", "ranks")) {
                val values = game.required(name)
                require(
                    values.isArray &&
                        values.size() == 4 &&
                        values.all {
                            it.isNumber &&
                                it.doubleValue().isFinite() &&
                                it.doubleValue() in 0.0..1.0
                        }
                ) {
                    "All role metrics require four finite normalized seat values."
                }
            }
            require(
                game["scores"]?.size() == 4 &&
                    game["scores"].all {
                        it.has("total") && (it["total"].isNull || it["total"].isNumber)
                    }
            ) {
                "Chaos indicators require the actual four public terminal scores, including explicit nulls."
            }
        }
    }

    /**
     * Object-key order is transport detail; number rendering is owned by this same native reader.
     */
    fun semanticHash(value: JsonNode): String =
        trainingSignature(PurchaseJson.mapper.writeValueAsString(sorted(value)))

    private fun sorted(value: JsonNode): JsonNode =
        when {
            value.isObject ->
                PurchaseJson.mapper.createObjectNode().also { output ->
                    value
                        .properties()
                        .sortedBy { it.key }
                        .forEach { output.set<JsonNode>(it.key, sorted(it.value)) }
                }
            value.isArray ->
                PurchaseJson.mapper.createArrayNode().also { output ->
                    value.forEach { output.add(sorted(it)) }
                }
            else -> value
        }

    /** Pure whole-bank effects; neither this owner nor its CLI parses an actor model. */
    fun report(worlds: List<JsonNode>): Map<String, Any> {
        require(worlds.map { it["index"].intValue() } == (0 until 1024).toList()) {
            "A development report requires the complete ordered 1,024-world bank."
        }
        fun contrast(
            first: TokenPairedScreenRole,
            second: TokenPairedScreenRole,
        ): Map<String, PilotInterval> =
            listOf("wins", "rewards", "ranks", "chaos").associateWith { metric ->
                pilotInterval(
                    worlds.map { world ->
                        val seat = world["seat"].intValue()
                        fun value(role: TokenPairedScreenRole): Double {
                            val game = world["roles"][role.name]
                            return if (metric == "chaos") {
                                if (game["scores"][seat]["total"].isNull) 1.0 else 0.0
                            } else game[metric][seat].doubleValue()
                        }
                        value(first) - value(second)
                    }
                )
            }
        val raw = contrast(TokenPairedScreenRole.FINAL, TokenPairedScreenRole.RAW_T)
        val learned = contrast(TokenPairedScreenRole.FINAL, TokenPairedScreenRole.INITIAL)
        fun positive(interval: PilotInterval?) = interval?.lower95?.let { it > 0.0 } == true
        return mapOf(
            "kind" to "token-paired-screen-report-1",
            "worlds" to 1024,
            "finalMinusRawT" to raw,
            "finalMinusInitializer" to learned,
            "initializerMinusRawT" to
                contrast(TokenPairedScreenRole.INITIAL, TokenPairedScreenRole.RAW_T),
            "developmentGatePassed" to
                (positive(raw["wins"]) && positive(raw["rewards"]) && positive(learned["rewards"])),
            "intervalMethod" to
                "Normal 95% across independent paired worlds; final shared across contrasts",
            "intervalMultiplier" to 1.96,
            "qualification" to false,
            "activated" to false,
            "automaticSuccessor" to false,
        )
    }
}
