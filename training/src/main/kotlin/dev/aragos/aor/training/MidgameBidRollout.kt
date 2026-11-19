package dev.aragos.aor.training

import dev.aragos.aor.Command
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation

/** An executor-only replay reconstructs the original sealed batch after the ordinary game ends. */
internal class MidgameBidRollout(
    override val seat: Int,
    private val turn: Int,
    private val deadline: Long,
    private val adaptiveRival: RevealedTokenCounterLeague? = null,
) : SimulationTrace {
    private data class Position(
        val player: PlayerId,
        val entryCount: Int,
        val decision: Int,
        val amount: Int,
        val cash: Int,
        val availableStock: Int,
        val rival: PlayerId?,
        val rivalForecast: Int?,
        val offered: Set<Int>,
        val peacefulMarkets: Int,
        val contestedMarkets: Int,
    )

    private data class Root(
        val state: GameState,
        val commands: Map<PlayerId, String>,
        val position: Position,
    )

    private var position: Position? = null
    private var root: Root? = null
    private var learnedPrefix: RevealedTokenCounterLeague? = null

    override fun decision(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
    ) {
        if (view.turnNumber != turn || observation.decision != "buy-tokens") return
        val amount = (TextCodec.parseCommand(chosen.command) as Command.BuyTokens).amount
        val offered = view.tokenPurchaseOffers.map { it.amount }.toSet()
        if (amount < 3 || amount - 2 !in offered || amount + 2 !in offered) return
        val player = PlayerId(observation.playerId)
        val owner = view.players.single { it.id == player }
        val challenger = adaptiveRival?.let { league ->
            view.players.single { league.isTargeted(it.id) }
        }
        if (challenger?.misery == MiseryStep.CHAOS) return
        val rival = challenger?.id
        val forecast = rival?.let { view.pastTokenPurchases.lastOrNull()?.let { row -> row[it] } }
        learnedPrefix = adaptiveRival?.fork()
        position =
            Position(
                player = player,
                entryCount = snapshotEntryCount,
                decision = number - (commandEntryIndex - snapshotEntryCount),
                amount = amount,
                cash = owner.cash,
                availableStock = owner.stockTokens,
                rival = rival,
                rivalForecast = forecast,
                offered = offered,
                peacefulMarkets = view.expansionForecast?.peacefulMarkets?.size ?: 0,
                contestedMarkets = view.expansionForecast?.contestedMarkets?.size ?: 0,
            )
    }

    override fun verified(header: GameHeader, entries: List<String>) {
        val selected = position ?: return
        val state =
            entries.take(selected.entryCount).fold(GameState(header)) { current, entry ->
                checkTokenValueDeadline(deadline)
                current.step(TextCodec.parse(entry))
            }
        val prompt = state.prompts().single() as? Prompt.MultiPlayer
        check(
            state.stage == Stage.TOKEN_PURCHASE &&
                prompt != null &&
                selected.player in prompt.players
        ) {
            "The trace must reconstruct the untouched sealed token-purchase batch."
        }
        val parsed =
            entries.drop(selected.entryCount).take(prompt.players.size).map {
                TextCodec.parse(it) as Entry.Action
            }
        check(
            parsed.map { it.actor } == prompt.players &&
                parsed.all { it.command is Command.BuyTokens }
        ) {
            "The replayed prefix must retain every original sealed purchase."
        }
        val commands = parsed.associate { it.actor to it.command.toText() }
        check(commands[selected.player] == Command.BuyTokens(selected.amount).toText()) {
            "The sealed command must confirm the candidate's actual original bid."
        }
        root = Root(state, commands, selected)
    }

    fun outcomes(
        configuration: String,
        seed: Int,
        models: List<BotModel>,
        controllers: (RevealedTokenCounterLeague?) -> Map<Int, SimulationController>,
    ): Map<String, Any> {
        val captured = root ?: return mapOf("status" to "no-eligible-root", "requestedTurn" to turn)
        val position = captured.position
        val actualRivalBid =
            position.rival?.let { rival ->
                (TextCodec.parseCommand(
                        checkNotNull(captured.commands[rival]) {
                            "The identified opponent needs its sealed token bid."
                        }
                    ) as Command.BuyTokens)
                    .amount
            }
        val amounts = buildMap {
            put("ordinary", position.amount)
            put("earlier", position.amount - 2)
            put("larger-pool", position.amount + 2)
            position.rivalForecast?.let { forecast ->
                preferredUndercut(position, forecast)?.let { put("public-response", it) }
            }
            actualRivalBid?.let { observed ->
                preferredUndercut(position, observed)?.let { put("sealed-oracle", it) }
            }
        }
        val results =
            amounts.values.distinct().associateWith { amount ->
                (0..1).map { replicate ->
                    val future = CardAdvantageExecutor.chanceSeed(seed, replicate)
                    val session = ExperimentSession(captured.state, future, recordEntries = true)
                    val continuation =
                        FrozenPolicyContinuation(
                            models,
                            controllers(learnedPrefix?.fork()),
                            seed,
                            deadline,
                            decisionOffset = position.decision,
                        )
                    session.apply(
                        captured.commands + (position.player to Command.BuyTokens(amount).toText()),
                        deadline,
                    )
                    while (session.state.stage == Stage.TURN_ORDER_TIE) {
                        continuation.check(session)
                        session.apply(
                            continuation.commands(
                                session,
                                session.actors().associateWith(session::observation),
                            ),
                            deadline,
                        )
                    }
                    val box =
                        requireNotNull(session.state.orderBoxes[position.player]) {
                            "A completed bid batch must resolve the actor's printed order box."
                        }
                    val own = session.state.playerState(position.player)
                    val bidOrder = session.state.turnOrder
                    mapOf(
                        "amount" to amount,
                        "orderBox" to box,
                        "poolAfter" to own.poolTokens,
                        "cashAfter" to own.cash,
                        "terminal" to continuation.finish(session, seat, configuration, future),
                        "canonicalSha256" to session.verifiedContinuation(captured.state, deadline),
                    ) +
                        (position.rival?.let { rival ->
                            mapOf(
                                "beforeCounteringRival" to
                                    (bidOrder.indexOf(position.player) < bidOrder.indexOf(rival))
                            )
                        } ?: emptyMap())
                }
            }
        return mapOf(
            "status" to "paired",
            "requestedTurn" to turn,
            "availableCash" to position.cash,
            "availableStock" to position.availableStock,
            "originalBid" to position.amount,
            "tails" to
                amounts.mapValues { (_, amount) ->
                    checkNotNull(results[amount]) {
                        "Each distinct bid requires completed futures."
                    }
                },
        ) +
            if (actualRivalBid != null) {
                buildMap {
                    put("actualRivalBid", actualRivalBid)
                    put("peacefulMarkets", position.peacefulMarkets)
                    put("contestedMarkets", position.contestedMarkets)
                    position.rivalForecast?.let { put("publicRivalForecast", it) }
                }
            } else emptyMap()
    }

    /** Largest affordable amount beneath the forecast, within eight tokens of the original. */
    private fun preferredUndercut(position: Position, forecast: Int): Int? =
        position.offered
            .filter { amount ->
                amount in (position.amount - 8).coerceAtLeast(0) until position.amount &&
                    amount < forecast
            }
            .maxOrNull()
}
