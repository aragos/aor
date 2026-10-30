package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode

/** Engine-derived listing projections may be indexed, and are never used to decide game actions. */
internal object GameDirectory {
    fun list(
        store: ManagedGameStore,
        viewer: AccountId,
        admin: Boolean,
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
                    if (
                        !admin &&
                            root["memberships"]?.properties()?.none {
                                it.value.asText() == viewer.value
                            } != false
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
            )
        }

    fun summary(game: StoredGame, viewer: AccountId?, accounts: AccountStore? = null): GameSummary {
        val state = game.state()
        val seat = game.memberships.entries.singleOrNull { it.value == viewer?.value }?.key
        val prompt = state.prompts().singleOrNull()
        val myMove =
            game.status == GameStatus.ACTIVE &&
                seat != null &&
                when (prompt) {
                    is Prompt.SinglePlayer -> prompt.player == seat
                    is Prompt.MultiPlayer -> seat in prompt.players && seat !in game.pendingBids
                    else -> false
                }
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
        )
    }
}
