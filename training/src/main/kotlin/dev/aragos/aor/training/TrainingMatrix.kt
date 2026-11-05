package dev.aragos.aor.training

import dev.aragos.aor.InitialCardRule
import dev.aragos.aor.MapRule
import dev.aragos.aor.RuleOptions

/** One complete rules configuration; identifiers are stable training/report keys. */
data class TrainingConfiguration(val playerCount: Int, val rules: RuleOptions) {
    val id: String
        get() =
            listOf(
                    "players-$playerCount",
                    rules.map.commandValue,
                    rules.initialCard.commandValue,
                    "deck-${rules.deckModification}",
                    "art-${rules.immediateMasterArtDiscard}",
                )
                .joinToString("/")
}

/** Exhaustive supported option product, rather than randomly selecting variants. */
object TrainingMatrix {
    val configurations: List<TrainingConfiguration> =
        (3..6).flatMap { count ->
            MapRule.entries.flatMap { map ->
                InitialCardRule.entries.flatMap { initial ->
                    listOf(false, true).flatMap { deck ->
                        listOf(false, true).map { art ->
                            TrainingConfiguration(
                                playerCount = count,
                                rules =
                                    RuleOptions(
                                        deckModification = deck,
                                        initialCard = initial,
                                        immediateMasterArtDiscard = art,
                                        map = map,
                                    ),
                            )
                        }
                    }
                }
            }
        }

    /** Interleave player counts and maps even in short, interrupted training runs. */
    val stratified: List<TrainingConfiguration> = configurations.sortedBy { configuration ->
        val option = configuration.rules
        option.initialCard.ordinal * 32 +
            (if (option.deckModification) 16 else 0) +
            (if (option.immediateMasterArtDiscard) 8 else 0) +
            option.map.ordinal * 4 +
            configuration.playerCount - 3
    }
}

/**
 * A named experimental scope. Promotion establishes strength only within this configuration set.
 */
enum class TrainingProfile(val commandValue: String) {
    FULL("full"),
    STRATEGIC_FOUR_PLAYER("strategic-four-player");

    val configurations: List<TrainingConfiguration>
        get() =
            when (this) {
                FULL -> TrainingMatrix.stratified
                STRATEGIC_FOUR_PLAYER ->
                    listOf(
                        TrainingConfiguration(
                            playerCount = 4,
                            rules =
                                RuleOptions(
                                    deckModification = true,
                                    initialCard = InitialCardRule.NO_CRUSADES,
                                    immediateMasterArtDiscard = true,
                                    map = MapRule.STANDARD,
                                ),
                        )
                    )
            }

    companion object {
        fun fromCommand(value: String): TrainingProfile =
            requireNotNull(entries.firstOrNull { it.commandValue == value }) {
                "Unknown training profile: $value. Use full or strategic-four-player."
            }
    }
}
