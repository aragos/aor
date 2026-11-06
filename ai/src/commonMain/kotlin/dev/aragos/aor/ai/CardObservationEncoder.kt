package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardChoice
import dev.aragos.aor.CardChoiceKind
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardData
import dev.aragos.aor.CardKind
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.GeographicalArea
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/**
 * Raw public/own card inputs. Every currently offered card command survives, including pass only
 * when actually offered. References select the physical card but never become feature identities.
 * Normal and final card windows are explicit; playing another card leaves retained hand facts to
 * the next ordinary projection. Nested war, discard and replacement decisions remain outside this
 * actor. No authored BotCandidate score or estimated future purchase utility is read.
 *
 * State scales are the unchanged raw purchase scales. Card-only handPlayable and handBurden counts
 * use stable physical-card definition IDs and authoritative own-hand availability. Action
 * cash/protection use dollars/200, leader credits dollars/100, misery/1000, hand counts/10,
 * markers/25 and tokens/36. Features are clipped to [-16,16], absent names mean zero, and inference
 * rejects names outside the full frozen public-catalog dictionary. Known immediate effects come
 * from the authoritative card quote; unquoted parameterized effects and unresolved misery are
 * explicitly marked, never simulated.
 */
object CardObservationEncoder {
    const val ENCODER_VERSION = "aor-card-observation-1"
    private val roles = (0..5).map { "role.$it" }
    private val amounts =
        listOf(
            "cashAfter",
            "handAfter",
            "protection",
            "targetCount",
            "effect.quoted",
            "effect.unquoted",
            "effect.noEffect",
            "choice.category.none",
        )
    private val roleEffects =
        listOf(
            "cashDelta",
            "miseryDelta",
            "chaos",
            "miseryUncertain",
            "markersLost",
            "tokensLost",
        )
    private val credits = listOf("printed", "conditional", "writtenRecord", "effective")

    fun dictionary(view: GameView): CardFeatureDictionary =
        dictionary(
            requireNotNull(view.catalog) { "Card encoding requires the ordinary public catalog." }
        )

    /**
     * Complete vocabulary is known before collection, including absent cards and public targets.
     */
    fun dictionary(catalog: PublicCatalog): CardFeatureDictionary {
        val state =
            (RawActorState.dictionary(catalog, listOf("play-cards")) +
                    listOf("cardWindow.normal", "cardWindow.final") +
                    CardData.all.flatMap { listOf("handPlayable.${it.id}", "handBurden.${it.id}") })
                .sorted()
        val action = buildSet {
            addAll(amounts)
            add("kind.pass")
            add("kind.play")
            CardData.all.forEach { add("card.${it.id}") }
            CardKind.entries.forEach { add("cardKind.${it.name}") }
            CardChoiceKind.entries.forEach { add("choice.${it.name}") }
            Commodity.entries.forEach { add("target.commodity.${it.name}") }
            GeographicalArea.entries.forEach { add("target.area.${it.name}") }
            AdvanceCategory.entries.forEach { add("target.category.${it.name}") }
            catalog.provinces.forEach { add("target.province.${it.id.value}") }
            roles.forEach { role ->
                add("target.$role")
                roleEffects.forEach { add("effect.$role.$it") }
            }
            AdvanceId.entries.forEach { advance ->
                credits.forEach { add("credit.${advance.name}.$it") }
            }
        }
            .sorted()
        return CardFeatureDictionary(state, action)
    }

    fun from(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        baselineCommand: String,
    ): CardObservation {
        require(
            view.stage in setOf(Stage.PLAY_CARDS, Stage.FINAL_CARD_PLAY) &&
                observation.decision == "play-cards"
        ) {
            "The card actor requires an ordinary or final card-play decision."
        }
        require(observation.playerId == seat.value) {
            "Card inputs must belong to the acting seat."
        }
        val state =
            RawActorState.from(view, seat, observation.decision) +
                handState(view) +
                mapOf(
                    (if (view.stage == Stage.FINAL_CARD_PLAY) "cardWindow.final"
                    else "cardWindow.normal") to 1.0
                )
        val actions =
            observation.candidates.sortedBy(BotCandidate::command).map { candidate ->
                CardAction(candidate.command, action(view, seat, candidate.command))
            }
        return CardObservation(
            stateFeatures = state,
            actions = actions,
            baselineIndex = actions.indexOfFirst { it.command == baselineCommand },
        )
    }

    private fun handState(view: GameView): Map<String, Double> {
        val playable = view.cardOffers.filter { it.playable }.map { it.reference }.toSet()
        val features = Features()
        view.hand.forEach { card ->
            if (card.reference in playable) features.add("handPlayable.${card.definition.id}")
            if (card.reference in view.burdenReferences)
                features.add("handBurden.${card.definition.id}")
        }
        return features.values.toMap()
    }

    private fun action(view: GameView, seat: PlayerId, text: String): Map<String, Double> {
        val self = view.players.single { it.id == seat }
        val command = TextCodec.parseCommand(text)
        val features = Features()
        if (command == TurnCommand.Pass) {
            features.add("kind.pass")
            features.add("cashAfter", self.cash / 200.0)
            features.add("handAfter", self.handCount / 10.0)
            return features.values.toMap()
        }
        require(command is CardCommand.PlayCard) {
            "Card inputs require offered play or pass commands."
        }
        val card = view.hand.single { it.reference == command.reference }
        val offer = view.cardOffers.single { it.reference == command.reference }
        require(offer.playable) { "An offered card action requires an authorized playable card." }
        val quote = offer.choices.firstOrNull { it.command == text }
        val protection = (command.choice as? CardChoice.LeaderProtection)?.cash ?: 0
        // Protected commands are bounded user choices. Their ordinary play owns the same rebate.
        val leaderCash =
            if (card.definition.kind == CardKind.LEADER) {
                BotLeaderCash.from(offer, seat, self.cash, protection)
            } else null
        val commonQuote =
            if (leaderCash != null && quote == null) {
                offer.choices.firstOrNull { it.command == "play ${command.reference}" }
            } else quote
        features.add("kind.play")
        features.add("card.${card.definition.id}")
        features.add("cardKind.${card.definition.kind.name}")
        features.add("handAfter", (self.handCount - 1) / 10.0)
        features.add("protection", protection / 200.0)
        choice(features, view, seat, command.choice)
        features.add(if (commonQuote == null) "effect.unquoted" else "effect.quoted")
        if (commonQuote?.noEffect == true) features.add("effect.noEffect")
        val cash = commonQuote?.cashChanges.orEmpty()
        cash.forEach { change ->
            if (leaderCash == null || change.player != seat) {
                features.add(
                    "effect.${RawActorState.role(view, seat, change.player)}.cashDelta",
                    (change.after - change.before) / 200.0,
                )
            }
        }
        if (leaderCash != null) features.add("effect.role.0.cashDelta", leaderCash.change / 200.0)
        // An absent nonleader quote means an unknown receipt, not a known zero cash change.
        if (leaderCash != null || commonQuote != null) {
            features.add(
                "cashAfter",
                (leaderCash?.after
                    ?: (self.cash +
                        cash.filter { it.player == seat }.sumOf { it.after - it.before })) / 200.0,
            )
        }
        val misery = commonQuote?.miseryChanges ?: offer.miseryChanges
        misery.forEach { change ->
            val prefix = "effect.${RawActorState.role(view, seat, change.player)}"
            val after = change.toValue
            if (after == null) features.add("$prefix.chaos")
            else features.add("$prefix.miseryDelta", (after - (change.fromValue ?: 0)) / 1000.0)
        }
        val uncertain = commonQuote?.miseryUncertainPlayers ?: offer.miseryUncertainPlayers
        uncertain.forEach {
            features.add("effect.${RawActorState.role(view, seat, it)}.miseryUncertain")
        }
        commonQuote?.boardLosses.orEmpty().forEach { loss ->
            val prefix = "effect.${RawActorState.role(view, seat, loss.owner)}"
            if (loss.markerControlLost) features.add("$prefix.markersLost", 1.0 / 25.0)
            features.add("$prefix.tokensLost", loss.tokensRemoved / 36.0)
        }
        offer.leaderDiscounts.forEach { credit ->
            val prefix = "credit.${credit.advance.name}"
            features.add("$prefix.printed", credit.printedAmount / 100.0)
            features.add("$prefix.conditional", credit.conditionalAmount / 100.0)
            features.add("$prefix.writtenRecord", credit.writtenRecordBonus / 100.0)
            features.add("$prefix.effective", credit.effectiveDiscount / 100.0)
        }
        return features.values.toMap()
    }

    private fun choice(features: Features, view: GameView, seat: PlayerId, choice: CardChoice) {
        val kind =
            when (choice) {
                CardChoice.None -> CardChoiceKind.NONE
                is CardChoice.CommodityChoice -> {
                    features.add("target.commodity.${choice.commodity.name}")
                    CardChoiceKind.COMMODITY
                }
                is CardChoice.PlayerChoice -> {
                    features.add("target.${RawActorState.role(view, seat, choice.player)}")
                    CardChoiceKind.PLAYER
                }
                is CardChoice.ProvinceChoice -> {
                    features.add("target.province.${choice.province.value}")
                    features.add("targetCount", 1.0 / 25.0)
                    CardChoiceKind.PROVINCE
                }
                is CardChoice.ProvincesChoice -> {
                    choice.provinces.forEach { features.add("target.province.${it.value}") }
                    features.add("targetCount", choice.provinces.size / 25.0)
                    CardChoiceKind.PROVINCES
                }
                is CardChoice.AreaChoice -> {
                    features.add("target.area.${choice.area.name}")
                    CardChoiceKind.AREA
                }
                is CardChoice.CategoryChoice -> {
                    val category = choice.category
                    if (category == null) features.add("choice.category.none")
                    else features.add("target.category.${category.name}")
                    CardChoiceKind.CATEGORY
                }
                is CardChoice.LeaderProtection -> CardChoiceKind.PROTECTION
            }
        features.add("choice.${kind.name}")
    }

    private class Features {
        val values = linkedMapOf<String, Double>()

        fun add(name: String, value: Double = 1.0) {
            val bounded = ((values[name] ?: 0.0) + value).coerceIn(-16.0, 16.0)
            if (bounded == 0.0) values.remove(name) else values[name] = bounded
        }
    }
}
