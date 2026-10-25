package dev.aragos.aor

enum class CardChoiceKind {
    NONE,
    COMMODITY,
    PLAYER,
    PROVINCE,
    PROVINCES,
    AREA,
    CATEGORY,
    PROTECTION,
}

/** Command is an authorized complete choice; null denotes a bounded user-edited choice. */
data class CardChoiceView(val kind: CardChoiceKind, val value: String?, val command: String?)

/** Potential credit from playing this hand card; never an already-authorized purchase credit. */
data class LeaderDiscountView(
    val advance: AdvanceId,
    val printedAmount: Int,
    val conditionalAmount: Int,
    val writtenRecordBonus: Int,
    val effectiveDiscount: Int,
    val condition: LeaderDiscountCondition?,
)

enum class CardOfferBlocker {
    WRONG_PHASE,
    WRONG_PLAYER,
    MISERY_BURDEN,
    INVALID_WAR,
    INVALID_CHOICE,
}

data class CardOfferView(
    val reference: String,
    val playable: Boolean,
    val blockers: List<CardOfferBlocker>,
    val choices: List<CardChoiceView>,
    val commodities: Set<Commodity>,
    val leaderDiscounts: List<LeaderDiscountView>,
    val provinceTargets: Set<ProvinceId>,
    val requiredTargetCount: Int?,
    val protectionMax: Int?,
)

internal object CardProjection {
    fun offers(state: GameState, viewer: PlayerState): List<CardOfferView> {
        if (state.turnNumber == null) return emptyList()
        val context = state.cardContext()
        return viewer.hand.map { card -> offer(state, viewer, card, context) }
    }

    private fun offer(
        state: GameState,
        viewer: PlayerState,
        card: HandCard,
        context: CardContext,
    ): CardOfferView {
        val spec = CardEffectData.forCard(card.definition.id)
        val block = blocker(state, viewer, card, context)
        val legal =
            if (block != null) {
                emptyList()
            } else {
                CardGame.choices(context, spec).filter { choice ->
                    try {
                        CardRules.resolve(
                            context,
                            viewer.player.id,
                            CardPlay(card.definition.id, choice),
                        )
                        true
                    } catch (_: CardRuleViolation) {
                        false
                    }
                }
            }
        val pirate = spec is CardEffectSpec.Event && spec.event == EventId.PIRATES_VIKINGS
        val targets = if (pirate && block == null) CardRules.pirateTargets(context) else emptySet()
        val required =
            if (pirate && targets.isNotEmpty()) CardRules.piratesRequiredCount(context) else null
        val protection =
            if (
                spec is CardEffectSpec.Leader &&
                    legal.isNotEmpty() &&
                    state.rules.patronage == PatronageRule.PROTECTION_MONEY &&
                    context.effects.leaders.isNotEmpty()
            ) {
                val played = LeaderRules.play(context, viewer.player.id, spec.leader)
                (viewer.cash.toLong() + played.rebate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else null
        val choices =
            legal
                .filterNot { choice -> pirate && choice != CardChoice.None }
                .map { choice ->
                    choiceView(card.reference, choice)
                } +
                listOfNotNull(
                    CardChoiceView(CardChoiceKind.PROVINCES, null, null).takeIf {
                        required != null
                    },
                    CardChoiceView(CardChoiceKind.PROTECTION, null, null).takeIf {
                        protection != null
                    },
                )
        return CardOfferView(
            card.reference,
            block == null && choices.isNotEmpty(),
            listOfNotNull(block ?: CardOfferBlocker.INVALID_CHOICE.takeIf { choices.isEmpty() }),
            choices,
            (spec as? CardEffectSpec.CommodityChoices)?.choices.orEmpty(),
            discounts(context, viewer, spec),
            targets,
            required,
            protection,
        )
    }

    private fun blocker(
        state: GameState,
        viewer: PlayerState,
        card: HandCard,
        context: CardContext,
    ): CardOfferBlocker? {
        if (CardRules.isBurden(context, card.definition.id)) return CardOfferBlocker.MISERY_BURDEN
        return try {
            CardGame.requirePlayWindow(state, viewer.player.id)
            CardGame.requirePlayableCard(state, card.definition.id)
            null
        } catch (failure: RuleViolation) {
            when (failure.code) {
                ViolationCode.WRONG_PHASE -> CardOfferBlocker.WRONG_PHASE
                ViolationCode.WRONG_PLAYER -> CardOfferBlocker.WRONG_PLAYER
                else -> CardOfferBlocker.INVALID_CHOICE
            }
        } catch (failure: CardRuleViolation) {
            if (failure.code == CardRuleCode.INVALID_WAR) {
                CardOfferBlocker.INVALID_WAR
            } else {
                CardOfferBlocker.INVALID_CHOICE
            }
        }
    }

    private fun discounts(
        context: CardContext,
        viewer: PlayerState,
        spec: CardEffectSpec,
    ): List<LeaderDiscountView> {
        if (spec !is CardEffectSpec.Leader) return emptyList()
        val bonus = LeaderRules.writtenRecordBonus(viewer.advances.owned, context.rules)
        return LeaderData.definition(spec.leader).discounts.map { discount ->
            val amount = discount.amountFor(context.effects.crusadesPlayed, context.voids.crusades)
            LeaderDiscountView(
                discount.advance,
                discount.amount,
                amount,
                bonus,
                amount + bonus,
                discount.condition,
            )
        }
    }

    private fun choiceView(reference: String, choice: CardChoice): CardChoiceView {
        val kind =
            when (choice) {
                CardChoice.None -> CardChoiceKind.NONE
                is CardChoice.CommodityChoice -> CardChoiceKind.COMMODITY
                is CardChoice.PlayerChoice -> CardChoiceKind.PLAYER
                is CardChoice.ProvinceChoice -> CardChoiceKind.PROVINCE
                is CardChoice.ProvincesChoice -> CardChoiceKind.PROVINCES
                is CardChoice.AreaChoice -> CardChoiceKind.AREA
                is CardChoice.CategoryChoice -> CardChoiceKind.CATEGORY
                is CardChoice.LeaderProtection -> CardChoiceKind.PROTECTION
            }
        val value =
            when (choice) {
                CardChoice.None -> null
                is CardChoice.CommodityChoice -> choice.commodity.name
                is CardChoice.PlayerChoice -> choice.player.value
                is CardChoice.ProvinceChoice -> choice.province.value
                is CardChoice.ProvincesChoice -> null
                is CardChoice.AreaChoice -> choice.area.name
                is CardChoice.CategoryChoice -> choice.category?.name
                is CardChoice.LeaderProtection -> choice.cash.toString()
            }
        return CardChoiceView(kind, value, CardCommand.PlayCard(reference, choice).toText())
    }
}
