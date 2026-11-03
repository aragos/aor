package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode

/** Engine-derived listing projections may be indexed, and are never used to decide game actions. */
internal object GameDirectory {
    /** Retain a usable label when an unavailable document cannot be decoded for play. */
    fun displayName(root: JsonNode): GameName = runCatching {
        GameName(root.field("name").string())
    }
        .getOrDefault(GameName.DEFAULT)

    fun list(
        store: ManagedGameStore,
        viewer: AccountId,
        scope: GameListScope,
        status: GameListStatus?,
        after: String?,
        limit: Int,
        accounts: AccountStore? = null,
    ): GamePage {
        val matches =
            store
                .ids(after)
                .asSequence()
                .mapNotNull { id ->
                    val root = json.readTree(store.document(id))
                    val member =
                        root["memberships"]?.properties()?.any {
                            it.value.asText() == viewer.value
                        } == true
                    if (
                        (scope == GameListScope.OWN && !member) ||
                            (scope == GameListScope.OTHER && member)
                    ) {
                        return@mapNotNull null
                    }
                    val candidate = summary(store, id, root, viewer, accounts)
                    candidate.takeIf {
                        status == null ||
                            it.status == status ||
                            (status == GameListStatus.ONGOING &&
                                it.status == GameListStatus.MY_MOVE)
                    }
                }
                .take(limit + 1)
                .toList()
        return GamePage(matches.take(limit), matches.takeIf { it.size > limit }?.get(limit - 1)?.id)
    }

    fun summary(
        store: ManagedGameStore,
        id: String,
        root: JsonNode,
        viewer: AccountId,
        accounts: AccountStore? = null,
    ): GameSummary =
        try {
            summary(store.read(id), viewer, accounts)
        } catch (_: ServiceFailure) {
            val players =
                root["header"]
                    ?.get("players")
                    ?.mapNotNull { player ->
                        runCatching {
                            Player(
                                PlayerId(player.field("id").string()),
                                player.field("name").string(),
                            )
                        }
                            .getOrNull()
                    }
                    .orEmpty()
            GameSummary(
                id,
                root["createdAt"]?.asLong() ?: 0,
                root["revision"]?.asInt() ?: 0,
                GameListStatus.UNAVAILABLE,
                null,
                players.map { player ->
                    player.copy(
                        name =
                            accounts?.playerName(
                                root["memberships"]?.get(player.id.value)?.asText(),
                                player.name,
                            ) ?: player.name
                    )
                },
                false,
                displayName(root),
            )
        }

    fun summary(game: StoredGame, viewer: AccountId?, accounts: AccountStore? = null): GameSummary {
        val state = game.state()
        val seat = game.memberships.entries.singleOrNull { it.value == viewer?.value }?.key
        val prompt = state.prompts().singleOrNull()
        val actionPlayers =
            if (game.status != GameStatus.ACTIVE) {
                emptyList()
            } else {
                when (prompt) {
                    is Prompt.SinglePlayer -> listOf(prompt.player)
                    is Prompt.MultiPlayer -> prompt.players.filter { it !in game.pendingBids }
                    else -> emptyList()
                }
            }
        val responders =
            if (game.status == GameStatus.ACTIVE) GameAbandonment.open(game)?.outstanding.orEmpty()
            else emptyList()
        val consentResponse = seat != null && seat in responders
        val myMove = seat != null && (seat in actionPlayers || consentResponse)
        val status =
            when {
                game.status == GameStatus.CANCELLED -> GameListStatus.CANCELLED
                game.status == GameStatus.ABANDONED -> GameListStatus.ABANDONED
                state.stage == Stage.FINISHED -> GameListStatus.FINISHED
                myMove -> GameListStatus.MY_MOVE
                else -> GameListStatus.ONGOING
            }
        return GameSummary(
            game.id,
            game.createdAt,
            game.revision,
            status,
            state.stage,
            game.header.players.map { player ->
                player.copy(
                    name =
                        accounts?.playerName(game.memberships[player.id], player.name)
                            ?: player.name
                )
            },
            myMove,
            game.name,
            actionPlayers,
            responders,
            consentResponse,
            seat != null && seat in actionPlayers,
        )
    }
}
