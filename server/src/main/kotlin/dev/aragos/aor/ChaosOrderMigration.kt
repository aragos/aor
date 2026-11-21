package dev.aragos.aor

/** Preserve recorded token reveals and their downstream orders, including rewound history. */
internal object ChaosOrderMigration {
    fun run(store: ManagedGameStore): List<String> =
        store.ids().mapNotNull { id ->
            repeat(10) {
                val root = json.readTree(store.document(id))
                val header = root["header"]
                if (
                    root["fileVersion"]?.asInt() != 7 ||
                        header?.get("rulesVersion")?.asText() !in
                            setOf(RULES_VERSION, LEGACY_RULES_VERSION) ||
                        CatalogVersion.entries.none {
                            it.wireValue == header?.get("dataVersion")?.asText()
                        } ||
                        header?.hasNonNull("automaticChaosOrderFromTurn") == true
                ) {
                    return@mapNotNull null
                }
                val source = store.read(id)
                if (source.header.automaticChaosOrderFromTurn != null) return@mapNotNull null
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
                automaticChaosOrderFromTurn =
                    source.latestRecordedTurn { before, after ->
                        before.turnNumber.takeIf {
                            before.stage == Stage.TOKEN_PURCHASE &&
                                after.stage != Stage.TOKEN_PURCHASE
                        }
                    } + 1
            )
        val checkpoint =
            source.copy(
                header = header,
                revisions =
                    source.revisions +
                        Revision(
                            id = source.revision + 1,
                            parent = source.revision,
                            author = header.creatorId,
                            command = "upgrade-chaos-order",
                            entries = emptyList(),
                            adminAuthor = "system-chaos-order",
                            at = System.currentTimeMillis(),
                            storageArchive = archive,
                        ),
            )
        val upgraded = GameAbandonment.reconcile(source, checkpoint)
        GameDocuments.validate(upgraded)
        check(upgraded.state() == source.state().copy(header = header)) {
            "The Chaos ordering upgrade must preserve recorded gameplay."
        }
        return upgraded
    }
}
