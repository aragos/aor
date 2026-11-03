package dev.aragos.aor

/**
 * Audited policy-only upgrade. The archive callback runs under the storage lock, so even private
 * writes which leave the public revision unchanged are included in the archived source.
 */
internal object LinearGameMigration {
    fun candidate(source: StoredGame, archive: String): StoredGame {
        if (source.undoPolicy != UndoPolicy.FRIENDLY_GAME) return source
        GameDocuments.validate(source)
        val upgraded =
            GameAbandonment.reconcile(
                source,
                source.copy(
                    revisions =
                        source.revisions +
                            Revision(
                                id = source.revision + 1,
                                parent = source.revision,
                                author = source.header.creatorId,
                                command = "upgrade-linear-game",
                                entries = emptyList(),
                                undoPolicy = UndoPolicy.LINEAR_GAME,
                                adminAuthor = "system-linear-game",
                                at = System.currentTimeMillis(),
                                storageArchive = archive,
                            )
                ),
            )
        GameDocuments.validate(upgraded)
        check(
            upgraded.effectiveEntries() == source.effectiveEntries() &&
                upgraded.state() == source.state()
        ) {
            "A Linear game policy upgrade must preserve the exact effective history and state."
        }
        return upgraded
    }

    fun run(store: ManagedGameStore): List<String> =
        store.ids().mapNotNull { id ->
            repeat(10) {
                val root = json.readTree(store.document(id))
                val policy =
                    root["undoPolicy"]?.asText()
                        ?: root["revisions"]
                            ?.lastOrNull { it["undoPolicy"]?.isTextual == true }
                            ?.get("undoPolicy")
                            ?.asText()
                if (policy != UndoPolicy.FRIENDLY_GAME.name) return@mapNotNull null
                val source = store.read(id)
                if (source.undoPolicy != UndoPolicy.FRIENDLY_GAME) return@mapNotNull null
                try {
                    store.checkpoint(id, source.revision) { contents, archive ->
                        candidate(GameDocuments.read(id, contents), archive)
                    }
                    return@mapNotNull id
                } catch (failure: ServiceFailure) {
                    if (failure.code != "STALE_REVISION") throw failure
                }
            }
            throw ServiceFailure(409, "POLICY_UPGRADE_CONFLICT")
        }
}
