package dev.aragos.aor

/**
 * Preserves historical phase timing and enables Draw Card swaps after the latest recorded turn. The
 * exact source, including private pending work, is archived under the storage lock before
 * publication.
 */
internal object DrawRenaissanceMigration {
    fun candidate(source: StoredGame, archive: String): StoredGame {
        if (source.header.rulesVersion != LEGACY_RULES_VERSION) return source
        GameDocuments.validate(source)
        val before = source.state()
        val header =
            source.header.copy(
                rulesVersion = RULES_VERSION,
                drawRenaissanceFromTurn =
                    source.latestRecordedTurn { _, next -> next.turnNumber } + 1,
            )
        val upgraded =
            source.copy(
                header = header,
                revisions =
                    source.revisions +
                        Revision(
                            id = source.revision + 1,
                            parent = source.revision,
                            author = header.creatorId,
                            command = "upgrade-draw-renaissance",
                            entries = emptyList(),
                            adminAuthor = "system-draw-renaissance",
                            at = System.currentTimeMillis(),
                            storageArchive = archive,
                        ),
            )
        GameDocuments.validate(upgraded)
        check(
            upgraded.effectiveEntries() == source.effectiveEntries() &&
                upgraded.state() == before.copy(header = header)
        ) {
            "A Draw Card Renaissance upgrade must preserve exact history and current gameplay."
        }
        return upgraded
    }

    fun run(store: ManagedGameStore): List<String> =
        store.ids().mapNotNull { id ->
            repeat(10) {
                val root = json.readTree(store.document(id))
                if (
                    root["fileVersion"]?.asInt() != 7 ||
                        root["header"]?.get("rulesVersion")?.asText() != LEGACY_RULES_VERSION
                ) {
                    return@mapNotNull null
                }
                val source = store.read(id)
                if (source.header.rulesVersion != LEGACY_RULES_VERSION) return@mapNotNull null
                try {
                    store.checkpoint(id, source.revision) { contents, archive ->
                        candidate(GameDocuments.read(id, contents), archive)
                    }
                    return@mapNotNull id
                } catch (failure: ServiceFailure) {
                    if (failure.code != "STALE_REVISION") throw failure
                }
            }
            throw ServiceFailure(409, "RULES_UPGRADE_CONFLICT")
        }
}
