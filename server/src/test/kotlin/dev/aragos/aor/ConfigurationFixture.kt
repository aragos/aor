package dev.aragos.aor

/** Explicit creation defaults used by persistence and history tests. */
internal fun defaultConfigurationText(creator: PlayerId = PlayerId("p1")): List<String> =
    listOf(
            "deck-modification disabled",
            "initial-card any",
            "draw-three-keep-two disabled",
            "immediate-master-art-discard disabled",
            "map standard",
        )
        .map { option -> "$creator: set-option $option" }
