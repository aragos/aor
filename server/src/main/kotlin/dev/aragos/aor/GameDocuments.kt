package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotDifficulty

/** Current game-document encoding and replay validation shared by durable storage adapters. */
internal object GameDocuments {
    fun read(id: String, contents: String): StoredGame = read(id, json.readTree(contents))

    fun read(id: String, root: JsonNode): StoredGame {
        try {
            require(root.field("id").string() == id) { "Stored game ID must match its key." }
            val game = decode(root)
            require(root.field("revision").integer() == game.revision) {
                "Active revision must match the final committed revision."
            }
            validate(game)
            return game
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    fun write(game: StoredGame): String {
        try {
            validate(game)
            return json.writeValueAsString(game)
        } catch (_: Exception) {
            throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
        }
    }

    fun requireId(id: String) {
        if (!GAME_ID_PATTERN.matches(id)) throw ServiceFailure(404, "GAME_NOT_FOUND")
    }

    fun requireArchive(name: String) {
        if (!ARCHIVE_PATTERN.matches(name)) throw ServiceFailure(404, "ARCHIVE_NOT_FOUND")
    }

    private fun canonicalUuid(value: String): java.util.UUID {
        require(GAME_ID_PATTERN.matches(value)) { "Lifecycle identifiers must be canonical UUIDs." }
        return java.util.UUID.fromString(value)
    }

    private fun decode(root: JsonNode): StoredGame {
        val node = root.field("header")
        val registeredPlayers =
            node.field("players").array().map { player ->
                Player(
                    id = PlayerId(player.field("id").string()),
                    name = player.field("name").string(),
                )
            }
        val header =
            GameHeader(
                players = registeredPlayers,
                creatorId = PlayerId(node.field("creatorId").string()),
                rulesVersion = node.field("rulesVersion").string(),
                dataVersion = node.field("dataVersion").string(),
            )
        val credentialNode = root.field("credentials")
        require(credentialNode.isObject) {
            "Credentials must be an object keyed by player identifier."
        }
        val pendingNode = root.field("pendingBids")
        require(pendingNode.isObject) {
            "Pending bids must be an object keyed by player identifier."
        }
        return StoredGame(
            fileVersion = root.field("fileVersion").integer(),
            id = root.field("id").string(),
            name = GameName(root.field("name").string()),
            header = header,
            credentials =
                credentialNode.properties().associate { (player, credential) ->
                    PlayerId(player) to credential.string()
                },
            revisions =
                root.field("revisions").array().map { revision ->
                    Revision(
                        id = revision.field("id").integer(),
                        abandonmentCheckpoint =
                            revision["abandonmentCheckpoint"]
                                ?.takeUnless(JsonNode::isNull)
                                ?.string()
                                ?.let(::canonicalUuid),
                        abandonment =
                            revision["abandonment"]?.takeUnless(JsonNode::isNull)?.let { event ->
                                AbandonmentEvent(
                                    requestId = canonicalUuid(event.field("requestId").string()),
                                    operation =
                                        AbandonmentOperation.valueOf(
                                            event.field("operation").string()
                                        ),
                                    operationId =
                                        event["operationId"]
                                            ?.takeUnless(JsonNode::isNull)
                                            ?.string()
                                            ?.let(::canonicalUuid),
                                    baseRevision =
                                        event["baseRevision"]
                                            ?.takeUnless(JsonNode::isNull)
                                            ?.integer(),
                                    closure =
                                        event["closure"]
                                            ?.takeUnless(JsonNode::isNull)
                                            ?.string()
                                            ?.let(AbandonmentClosure::valueOf),
                                )
                            },
                        parent =
                            revision.field("parent").let { parent ->
                                if (parent.isNull) null else parent.integer()
                            },
                        author = PlayerId(revision.field("author").string()),
                        command = revision.field("command").string(),
                        entries = revision.field("entries").array().map(JsonNode::string),
                        replaceFrom =
                            revision.field("replaceFrom").let {
                                if (it.isNull) null else it.integer()
                            },
                        adminAuthor =
                            revision.field("adminAuthor").let {
                                if (it.isNull) null else it.string()
                            },
                        administrativeAction =
                            revision.field("administrativeAction").let {
                                if (it.isNull) null else AdministrativeAction.valueOf(it.string())
                            },
                        at = revision.field("at").longInteger(),
                        storageArchive =
                            revision["storageArchive"]?.takeUnless(JsonNode::isNull)?.string(),
                        undoPolicy =
                            revision.field("undoPolicy").let {
                                if (it.isNull) null else UndoPolicy.valueOf(it.string())
                            },
                    )
                },
            memberships =
                root.field("memberships").properties().associate { (player, uid) ->
                    PlayerId(player) to uid.string()
                },
            createdAt = root.field("createdAt").longInteger(),
            initialStatus = GameStatus.valueOf(root.field("initialStatus").string()),
            baseRevision = root.field("baseRevision").integer(),
            previousArchive =
                root.field("previousArchive").let { if (it.isNull) null else it.string() },
            bots =
                root["bots"]
                    ?.properties()
                    ?.associate { (player, bot) ->
                        PlayerId(player) to
                            AutomatedPlayer(
                                difficulty =
                                    BotDifficulty.valueOf(bot.field("difficulty").string()),
                                policyVersion = bot.field("policyVersion").string(),
                                seed = bot.field("seed").integer(),
                            )
                    }
                    .orEmpty(),
            botRunner =
                root["botRunner"]?.takeUnless(JsonNode::isNull)?.let { runner ->
                    BotRunnerState(
                        epoch = runner.field("epoch").integer(),
                        paused = runner.field("paused").boolean(),
                        runnerId = runner["runnerId"]?.takeUnless(JsonNode::isNull)?.string(),
                        leaseUntil = runner.field("leaseUntil").longInteger(),
                        receipts =
                            runner["receipts"]
                                ?.array()
                                ?.map { receipt ->
                                    BotOperationReceipt(
                                        operationId = receipt.field("operationId").string(),
                                        requestHash = receipt.field("requestHash").string(),
                                        revision = receipt.field("revision").integer(),
                                    )
                                }
                                .orEmpty(),
                    )
                },
            submissionReceipts =
                root["submissionReceipts"]
                    ?.properties()
                    ?.associate { (player, receipts) ->
                        PlayerId(player) to receipts.array().map(JsonNode::string)
                    }
                    .orEmpty(),
            submissionVersions =
                root["submissionVersions"]
                    ?.properties()
                    ?.associate { (player, version) ->
                        PlayerId(player) to version.string()
                    }
                    .orEmpty(),
            pendingBids =
                pendingNode.properties().associate { (player, bid) ->
                    PlayerId(player) to bid.integer()
                },
            plans =
                root["plans"]
                    ?.properties()
                    ?.associate { (player, node) ->
                        PlayerId(player) to
                            SavedActivityPlan(
                                turnNumber = node.field("turnNumber").integer(),
                                stage = Stage.valueOf(node.field("stage").string()),
                                baseRevision = node.field("baseRevision").integer(),
                                baseEntryCount = node.field("baseEntryCount").integer(),
                                authorizationOperationId =
                                    node["authorizationOperationId"]
                                        ?.takeUnless(JsonNode::isNull)
                                        ?.string(),
                                actions =
                                    node.field("actions").array().map { action ->
                                        PlannedAction(
                                            action.field("decision").string(),
                                            action.field("command").string(),
                                        )
                                    },
                                signature =
                                    node["signature"]?.takeUnless(JsonNode::isNull)?.string(),
                                pause =
                                    node["pause"]
                                        ?.takeUnless(JsonNode::isNull)
                                        ?.string()
                                        ?.let(PlanPause::valueOf),
                            )
                    }
                    .orEmpty(),
            planVersions =
                root["planVersions"]
                    ?.properties()
                    ?.associate { (player, node) ->
                        PlayerId(player) to node.string()
                    }
                    .orEmpty(),
            planReceipts =
                root["planReceipts"]
                    ?.properties()
                    ?.associate { (player, node) ->
                        PlayerId(player) to node.array().map(JsonNode::string)
                    }
                    .orEmpty(),
            planExecutions =
                root["planExecutions"]
                    ?.properties()
                    ?.associate { (player, node) ->
                        PlayerId(player) to
                            node.array().map { receipt ->
                                PlanExecution(
                                    receipt.field("version").string(),
                                    receipt.field("fromRevision").integer(),
                                    receipt.field("toRevision").integer(),
                                    receipt.field("operationId").string(),
                                    receipt.field("baseRevision").integer(),
                                    receipt.field("baseEntryCount").integer(),
                                )
                            }
                    }
                    .orEmpty(),
        )
    }

    fun validate(game: StoredGame) {
        require(game.fileVersion == 7) { "Only game file version 7 is supported." }
        require(game.createdAt > 0) { "Game creation time must be positive Unix milliseconds." }
        require(
            game.baseRevision >= 0 && (game.previousArchive != null) == (game.baseRevision > 0)
        ) {
            "Checkpoints require an explicit preceding revision archive."
        }
        require(game.previousArchive == null || game.previousArchive.matches(ARCHIVE_PATTERN)) {
            "Archive references must be canonical revision and SHA-256 filenames."
        }
        val players = game.header.players.map(Player::id).toSet()
        require(game.bots.keys.all { it in players } && game.header.creatorId !in game.bots) {
            "Automated controllers require registered seats and cannot own the creator role."
        }
        require(game.bots.isEmpty() == (game.botRunner == null)) {
            "Automated seats require durable browser runner state."
        }
        val humanPlayers = players - game.bots.keys
        if (game.memberships.isEmpty()) {
            require(game.credentials.keys == humanPlayers) {
                "Local games require one credential per human player."
            }
        } else {
            require(
                game.credentials.isEmpty() &&
                    game.memberships.keys == humanPlayers &&
                    game.memberships.values.distinct().size == humanPlayers.size
            ) {
                "Registered games require unique account membership for every " +
                    "seat and no transferable credentials."
            }
            game.memberships.values.forEach(::AccountId)
        }
        require(game.credentials.values.all(CREDENTIAL_PATTERN::matches)) {
            "Credentials must be SHA-256 hashes encoded as 64 lowercase hexadecimal characters."
        }
        game.botRunner?.let { runner ->
            require(runner.receipts.all { it.revision <= game.revision }) {
                "Automated command receipts cannot name future revisions."
            }
        }
        require(game.credentials.values.distinct().size == game.credentials.size) {
            "Player credentials must be unique."
        }
        require(game.revisions.isNotEmpty()) {
            "A game must have an initial configuration revision."
        }
        val state = validateRevisions(game)
        require(
            players.containsAll(game.plans.keys) &&
                players.containsAll(game.planVersions.keys) &&
                players.containsAll(game.planReceipts.keys) &&
                players.containsAll(game.planExecutions.keys)
        ) {
            "Private planning state requires registered owners."
        }
        require(
            game.planVersions.keys.containsAll(game.plans.keys) &&
                game.planVersions.values.all(GAME_ID_PATTERN::matches)
        ) {
            "Each private plan requires an opaque owner version."
        }
        require(
            game.planReceipts.values.all { receipts ->
                receipts.size <= 16 &&
                    receipts.distinct().size == receipts.size &&
                    receipts.all(GAME_ID_PATTERN::matches)
            }
        ) {
            "Planning operation receipts require at most sixteen distinct UUIDs per owner."
        }
        require(
            game.planExecutions.values.all { receipts ->
                receipts.size <= 16 &&
                    receipts.all { receipt ->
                        receipt.toRevision <= game.revision
                    }
            }
        ) {
            "Plan executions require valid owner versions and committed revision ranges."
        }
        require(
            players.containsAll(game.submissionVersions.keys) &&
                game.submissionVersions.values.all { it.matches(GAME_ID_PATTERN) }
        ) {
            "Private submission receipts require registered owners and opaque UUID values."
        }
        require(
            players.containsAll(game.submissionReceipts.keys) &&
                game.submissionReceipts.values.all { receipts ->
                    receipts.size <= 16 &&
                        receipts.distinct().size == receipts.size &&
                        receipts.all(GAME_ID_PATTERN::matches)
                }
        ) {
            "Private operation receipts retain at most sixteen distinct canonical UUIDs per owner."
        }
        if (game.pendingBids.isNotEmpty()) {
            val participants =
                (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
            require(
                participants.containsAll(game.pendingBids.keys) &&
                    game.pendingBids.size < participants.size
            ) {
                "Pending private decisions must form an incomplete batch of the " +
                    "current participants."
            }
        }
        game.pendingBids.forEach { (player, amount) ->
            val command =
                when (state.stage) {
                    Stage.BIDDING -> Command.BidCapital(amount)
                    Stage.TOKEN_PURCHASE -> Command.BuyTokens(amount)
                    else ->
                        error(
                            "Pending private decisions require a bidding or token-purchase phase."
                        )
                }
            state.step(Entry.Action(actor = player, command = command))
        }
    }

    private fun validateRevisions(game: StoredGame): GameState {
        val prefixes = ReplayPrefixes(game.header, game.revisions)
        return game.revisions.withIndex().fold(GameState(game.header)) { state, (index, revision) ->
            require(
                revision.id == game.baseRevision + index + 1 &&
                    revision.parent ==
                        (if (index == 0 && game.baseRevision == 0) null
                        else game.baseRevision + index)
            ) {
                "Revisions must form a consecutive chain starting with revision 1."
            }
            require(revision.author in state.playerIds) {
                "Revision authors must be registered players."
            }
            require(index != 0 || revision.undoPolicy != null) {
                "Creation must record its undo policy."
            }
            val previous =
                game.copy(
                    revisions = game.revisions.take(index),
                    pendingBids = emptyMap(),
                )
            require(
                revision.abandonmentCheckpoint == null || (index == 0 && game.baseRevision > 0)
            ) {
                "Only an archived administrative checkpoint can record a baseline consent closure."
            }
            val consent = revision.abandonment
            if (consent?.operation == AbandonmentOperation.CLOSE) {
                require(
                    GameAbandonment.open(previous)?.requestId == consent.requestId &&
                        consent.operationId == null &&
                        revision.at > 0 &&
                        consent.baseRevision == null &&
                        GameAbandonment.closure(
                            previous,
                            game.copy(
                                revisions =
                                    game.revisions.take(index) + revision.copy(abandonment = null)
                            ),
                        ) == consent.closure
                ) {
                    "An automatic consent closure must record its exact cause and request."
                }
            } else if (index > 0 && consent == null) {
                require(
                    GameAbandonment.closure(
                        previous,
                        game.copy(revisions = game.revisions.take(index + 1)),
                    ) == null
                ) {
                    "A superseding operation must close its pending consent request."
                }
            }
            when {
                consent != null && consent.operation != AbandonmentOperation.CLOSE -> {
                    require(
                        index > 0 &&
                            consent.operationId != null &&
                            consent.baseRevision == previous.revision &&
                            revision.entries.isEmpty() &&
                            revision.replaceFrom == null &&
                            revision.undoPolicy == null &&
                            revision.adminAuthor == null &&
                            revision.administrativeAction == null &&
                            revision.storageArchive == null &&
                            revision.at > 0 &&
                            revision.command ==
                                "abandonment ${consent.operation.name.lowercase()}" &&
                            previous.revisions.none {
                                it.abandonment?.operationId == consent.operationId
                            } &&
                            GameAbandonment.transition(previous, revision.author, consent) ==
                                consent
                    ) {
                        "Player consent requires a canonical eligible transition and unique operation receipt."
                    }
                    state
                }
                revision.storageArchive != null && revision.command == "upgrade-linear-game" -> {
                    require(
                        index > 0 &&
                            previous.undoPolicy == UndoPolicy.FRIENDLY_GAME &&
                            revision.undoPolicy == UndoPolicy.LINEAR_GAME &&
                            revision.author == game.header.creatorId &&
                            revision.adminAuthor == "system-linear-game" &&
                            revision.administrativeAction == null &&
                            revision.at > 0 &&
                            revision.entries.isEmpty() &&
                            revision.replaceFrom == null &&
                            revision.storageArchive.matches(ARCHIVE_PATTERN)
                    ) {
                        "A Linear game upgrade must audit its source archive and preserve all gameplay."
                    }
                    state
                }
                revision.storageArchive != null -> {
                    require(
                        index > 0 &&
                            revision.author == game.header.creatorId &&
                            revision.command == "upgrade-planning" &&
                            revision.at > 0 &&
                            revision.entries.isEmpty() &&
                            revision.replaceFrom == null &&
                            revision.undoPolicy == null &&
                            revision.adminAuthor == null &&
                            revision.administrativeAction == null &&
                            revision.storageArchive.matches(ARCHIVE_PATTERN)
                    ) {
                        "A storage upgrade must retain history and audit its creator and exact archive."
                    }
                    state
                }
                index == 0 && game.baseRevision > 0 -> {
                    require(
                        revision.adminAuthor != null &&
                            revision.administrativeAction == AdministrativeAction.UPGRADE &&
                            revision.command == "admin-replace-log" &&
                            revision.at > 0 &&
                            revision.replaceFrom == null
                    ) {
                        "A checkpoint must record its administrator, timestamp and " +
                            "replacement operation."
                    }
                    AccountId(revision.adminAuthor)
                    val restored = prefixes.reset(revision.entries)
                    require(
                        revision.entries ==
                            revision.entries.map(TextCodec::parse).map(Entry::toText)
                    ) {
                        "Checkpoint entries must be canonical."
                    }
                    requireCompletedTransaction(restored)
                    restored
                }
                revision.adminAuthor != null -> {
                    AccountId(revision.adminAuthor)
                    val action =
                        checkNotNull(revision.administrativeAction) {
                            "An administrative revision requires its operation."
                        }
                    require(
                        index > 0 &&
                            revision.administrativeAction in
                                setOf(AdministrativeAction.CANCEL, AdministrativeAction.ABANDON) &&
                            revision.at > 0 &&
                            revision.entries.isEmpty() &&
                            revision.replaceFrom == null &&
                            revision.undoPolicy == null &&
                            revision.command == "admin-${action.name.lowercase()}"
                    ) {
                        "Lifecycle revisions require a canonical administrator operation."
                    }
                    state
                }
                revision.replaceFrom != null -> {
                    require(revision.undoPolicy == null) {
                        "A history edit cannot change the undo policy."
                    }
                    require(
                        previous.undoPolicy != UndoPolicy.LINEAR_GAME ||
                            (revision.command == "undo ${revision.replaceFrom}" &&
                                revision.entries.isEmpty())
                    ) {
                        "Linear game accepts only canonical empty-suffix rewinds."
                    }
                    if (previous.undoPolicy == UndoPolicy.LINEAR_GAME) {
                        // The fold has already validated this source state. Validate only the
                        // requested destination instead of replaying every possible target.
                        return@fold LinearHistory.validate(
                            stored = previous,
                            player = revision.author,
                            from = revision.replaceFrom,
                            state = state,
                            prefixes = prefixes,
                        )
                    }
                    val actions =
                        HistoryEdits.retainedLines(
                            previous,
                            revision.author,
                            revision.replaceFrom,
                            revision.entries,
                        )
                    val expected =
                        HistoryEdits.replacement(
                            previous,
                            revision.author,
                            revision.replaceFrom,
                            actions,
                        )
                    require(
                        revision.entries == expected &&
                            revision.command in
                                listOf(
                                    "undo ${revision.replaceFrom}",
                                    "edit-history ${revision.replaceFrom}",
                                )
                    ) {
                        "Edited history must preserve recorded outcomes and its canonical " +
                            "edit command."
                    }
                    prefixes.reset(
                        previous
                            .effectiveEntries()
                            .take(revision.replaceFrom)
                            .map(RecordedEntry::text) + expected
                    )
                }
                revision.undoPolicy != null && index > 0 -> {
                    val configured =
                        HistoryEdits.configure(previous, revision.author, revision.undoPolicy)
                    require(
                        configured.revisions.last() ==
                            revision.copy(
                                abandonment = null,
                                at = if (consent == null) revision.at else 0,
                            )
                    ) {
                        "Undo policy revisions must be canonical."
                    }
                    state
                }
                else -> validateRevision(state, index, revision, prefixes)
            }
        }
    }

    private fun validateRevision(
        state: GameState,
        index: Int,
        revision: Revision,
        prefixes: ReplayPrefixes,
    ): GameState {
        require(revision.author in state.playerIds) {
            "Revision authors must be registered players."
        }
        require(revision.adminAuthor == null && revision.administrativeAction == null) {
            "Ordinary revisions cannot claim administrative authority."
        }
        val entries = revision.entries.map(TextCodec::parse)
        val actions = entries.filterIsInstance<Entry.Action>()
        require(entries.isNotEmpty() && entries.take(actions.size) == actions) {
            "Each revision must contain player actions followed by system outcomes."
        }
        validateActions(state, revision, actions, entries, index)
        require(revision.entries == entries.map(TextCodec::print)) {
            "Stored entries must use canonical text."
        }
        val next = entries.fold(state, prefixes::step)
        requireCompletedTransaction(next)
        return next
    }

    private fun validateActions(
        state: GameState,
        revision: Revision,
        actions: List<Entry.Action>,
        entries: List<Entry>,
        index: Int,
    ) {
        when (revision.command) {
            "reveal-capital-bids" -> {
                require(state.stage == Stage.BIDDING) {
                    "Capital bids can only reveal during bidding."
                }
                validateSealedActions(state, actions) { command -> command is Command.BidCapital }
            }
            "reveal-token-purchases" -> {
                require(state.stage == Stage.TOKEN_PURCHASE) {
                    "Token purchases can only reveal during the token-purchase phase."
                }
                validateSealedActions(state, actions) { command -> command is Command.BuyTokens }
            }
            else -> validateCommandActions(state, revision, actions, entries, index)
        }
    }

    private fun validateCommandActions(
        state: GameState,
        revision: Revision,
        actions: List<Entry.Action>,
        entries: List<Entry>,
        index: Int,
    ) {
        val command = TextCodec.parseCommand(revision.command)
        require(command !is Command.BidCapital && command !is Command.BuyTokens) {
            "private decisions must be committed together under their reveal marker."
        }
        require(revision.command == command.toText()) {
            "Revision commands must use canonical text."
        }
        val action = Entry.Action(actor = revision.author, command = command)
        if (index != 0) {
            val expected = listOf(action)
            require(actions == expected) {
                "A command revision must contain exactly the author's command."
            }
            return
        }
        val configured = entries.fold(state) { current, entry -> current.step(entry) }
        val expected = initialConfiguration(revision.author, configured.rules)
        require(
            revision.author == state.header.creatorId &&
                action == expected.first() &&
                entries == expected
        ) {
            "The initial revision must explicitly configure every resolved option."
        }
    }

    private fun validateSealedActions(
        state: GameState,
        actions: List<Entry.Action>,
        accepts: (Command) -> Boolean,
    ) {
        val participants =
            (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
        require(
            actions.map(Entry.Action::actor) == state.playerIds.filter(participants::contains) &&
                actions.all { action -> accepts(action.command) }
        ) {
            "A revealed batch must include the current private decision for " +
                "every player in registration order."
        }
    }

    val GAME_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val ARCHIVE_PATTERN = Regex("[1-9][0-9]*-[0-9a-f]{64}\\.json")
    val CREDENTIAL_PATTERN = Regex("[0-9a-f]{64}")
}
