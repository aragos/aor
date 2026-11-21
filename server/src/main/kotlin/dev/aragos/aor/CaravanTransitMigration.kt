package dev.aragos.aor

/** Make the new reach rule available after the last Expansion with recorded play. */
internal object CaravanTransitMigration {
    fun run(store: ManagedGameStore): List<String> =
        store.ids().mapNotNull { id ->
            repeat(10) {
                val root = json.readTree(store.document(id))
                val header = root["header"]
                val rulesVersion = header?.get("rulesVersion")?.asText()
                val dataVersion = header?.get("dataVersion")?.asText()
                if (
                    root["fileVersion"]?.asInt() != 7 ||
                        rulesVersion !in setOf(RULES_VERSION, LEGACY_RULES_VERSION) ||
                        CatalogVersion.entries.none { it.wireValue == dataVersion } ||
                        header?.hasNonNull("darkCaravanFromTurn") == true
                ) {
                    return@mapNotNull null
                }
                val source = store.read(id)
                if (source.header.darkCaravanFromTurn != null) return@mapNotNull null
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

    private fun candidate(source: StoredGame, archive: String): StoredGame {
        val header =
            source.header.copy(
                darkCaravanFromTurn =
                    source.latestRecordedTurn { before, _ ->
                        before.turnNumber.takeIf { before.stage == Stage.EXPANSION }
                    } + 1
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
                            command = "upgrade-caravan-transit",
                            entries = emptyList(),
                            adminAuthor = "system-caravan-transit",
                            at = System.currentTimeMillis(),
                            storageArchive = archive,
                        ),
            )
        GameDocuments.validate(upgraded)
        check(upgraded.state() == source.state().copy(header = header)) {
            "The Caravan upgrade must preserve recorded gameplay."
        }
        return upgraded
    }
}
