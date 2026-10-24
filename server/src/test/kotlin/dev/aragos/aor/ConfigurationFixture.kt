package dev.aragos.aor

/** Expected public creation contract; keep default changes explicit in replay tests. */
internal fun defaultConfigurationText(creator: PlayerId = PlayerId("p1")): List<String> =
    listOf(
            "set-option rebuilt-marker-bonus exclude",
            "set-option setup standard",
            "set-option initial-card any",
            "set-option commerce ah1996",
            "set-option wind-watermill takeover-trade",
            "set-option written-record leader-bonus",
            "set-option income-relief new-world",
            "set-option credits ah1996",
            "set-option misery-relief purchased",
            "set-option patronage protection-money",
            "set-option immediate-master-art-discard disabled",
            "set-option st-gall-support disabled",
        )
        .map { command -> "$creator: $command" }
