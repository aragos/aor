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
                .joinToString("/") + if (rules.drawThreeKeepTwo) "/draw-three-keep-two" else ""
}

/** Exhaustive supported option product, in historical order followed by new opening deals. */
object TrainingMatrix {
    private val singleCardDeal: List<TrainingConfiguration> =
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

    val configurations: List<TrainingConfiguration> =
        singleCardDeal +
            singleCardDeal.mapNotNull { configuration ->
                val rules = configuration.rules.copy(drawThreeKeepTwo = true)
                if (rules.supportsInitialDeal(configuration.playerCount)) {
                    configuration.copy(rules = rules)
                } else {
                    null
                }
            }

    /** Preserve the old seed indices while interleaving player counts and maps in each deal. */
    val stratified: List<TrainingConfiguration> = configurations.sortedBy { configuration ->
        val option = configuration.rules
        (if (option.drawThreeKeepTwo) singleCardDeal.size else 0) +
            option.initialCard.ordinal * 32 +
            (if (option.deckModification) 16 else 0) +
            (if (option.immediateMasterArtDiscard) 8 else 0) +
            option.map.ordinal * 4 +
            configuration.playerCount - 3
    }
}
