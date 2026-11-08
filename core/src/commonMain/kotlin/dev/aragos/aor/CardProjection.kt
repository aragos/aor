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

/**
 * Command is an authorized complete choice; null denotes a bounded user-edited choice. Known
 * [miseryChanges] use printed box endpoints; [miseryUncertainPlayers] identifies participants whose
 * destinations require unresolved dice or decisions instead of inventing an outcome.
 */
data class CardChoiceView(
    val kind: CardChoiceKind,
    val value: String?,
    val command: String?,
    val miseryChanges: List<MiseryChangeView> = emptyList(),
    val miseryUncertainPlayers: List<PlayerId> = emptyList(),
    val cashChanges: List<CashChangeView> = emptyList(),
    /** The card can be consumed without an effect because it has no legal target. */
    val noEffect: Boolean = false,
)

/** Viewer-specific credit; authorization to use a played leader is projected separately. */
data class LeaderDiscountView(
    val advance: AdvanceId,
    val printedAmount: Int,
    val conditionalAmount: Int,
    val writtenRecordBonus: Int,
    val effectiveDiscount: Int,
    val condition: LeaderDiscountCondition?,
    /** Immediate Printed Word cash for an already owned advance; excludes Written Record. */
    val rebate: Int = 0,
) {
    companion object {
        internal fun forLeader(
            leader: LeaderId,
            advances: Set<AdvanceId>,
            crusadesPlayed: Boolean,
            mongolEnhanced: Boolean,
        ): List<LeaderDiscountView> {
            val bonus = LeaderRules.writtenRecordBonus(advances)
            return LeaderData.definition(leader).discounts.map { discount ->
                val amount = discount.amountFor(crusadesPlayed, mongolEnhanced)
                LeaderDiscountView(
                    discount.advance,
                    discount.amount,
                    amount,
                    bonus,
                    amount + bonus,
                    discount.condition,
                    if (AdvanceId.PRINTED_WORD in advances && discount.advance in advances) amount
                    else 0,
                )
            }
        }
    }
}

/**
 * Played credits retain their play-time enhancement and the viewer's current Written Record bonus.
 */
data class PlayedLeaderDiscountView(
    val leader: LeaderId,
    val usable: Boolean,
    val discounts: List<LeaderDiscountView>,
) {
    companion object {
        internal fun from(state: GameState, viewer: PlayerState?): List<PlayedLeaderDiscountView> =
            state.effects.leaders.map { played ->
                PlayedLeaderDiscountView(
                    played.leader,
                    viewer != null &&
                        (played.owner == viewer.player.id ||
                            viewer.player.id in played.patronageClaimants),
                    LeaderDiscountView.forLeader(
                        played.leader,
                        viewer?.advances?.owned.orEmpty(),
                        state.effects.crusadesPlayed,
                        played.mongolEnhanced,
                    ),
                )
            }
    }
}

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
    /** Consequences identical across all authorized choices, known before selecting a target. */
    val miseryChanges: List<MiseryChangeView> = emptyList(),
    val miseryUncertainPlayers: List<PlayerId> = emptyList(),
)

/** Public card facts with current viewer credits; never identifies any private card location. */
data class CardInspectionView(
    val definition: CardDefinition,
    val commodities: Set<Commodity>,
    val leaderDiscounts: List<LeaderDiscountView>,
)

internal object CardProjection {
    private data class ResolvedChoice(
        val choice: CardChoice,
        val preview: CardMiseryPreview,
        val noEffect: Boolean,
    )

    fun inspections(state: GameState, viewer: PlayerState?): List<CardInspectionView> {
        val played = PlayedLeaderDiscountView.from(state, viewer).associateBy { it.leader.cardId }
        return CardData.all.map { definition ->
            val spec = CardEffectData.forCard(definition.id)
            CardInspectionView(
                definition,
                (spec as? CardEffectSpec.CommodityChoices)?.choices.orEmpty(),
                played[definition.id]?.discounts ?: discounts(state, viewer, spec),
            )
        }
    }

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
                CardGame.choices(context, spec).mapNotNull { choice ->
                    try {
                        val resolution =
                            CardRules.resolve(
                                context,
                                viewer.player.id,
                                CardPlay(card.definition.id, choice),
                            )
                        ResolvedChoice(
                            choice,
                            CardMiseryPreview.from(context, resolution),
                            noEffect = resolution is CardResolution.NoEffect,
                        )
                    } catch (_: CardRuleViolation) {
                        null
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
                    context.effects.leaders.isNotEmpty()
            ) {
                val played = LeaderRules.play(context, viewer.player.id, spec.leader)
                (viewer.cash.toLong() + played.rebate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else null
        val choices =
            legal
                .filterNot { (choice) -> pirate && choice != CardChoice.None }
                .map { (choice, preview, noEffect) ->
                    choiceView(card.reference, choice)
                        .copy(
                            miseryChanges = preview.changes,
                            miseryUncertainPlayers = preview.uncertainPlayers,
                            cashChanges = preview.cashChanges,
                            noEffect = noEffect,
                        )
                } +
                listOfNotNull(
                    CardChoiceView(CardChoiceKind.PROVINCES, null, null).takeIf {
                        required != null
                    },
                    CardChoiceView(CardChoiceKind.PROTECTION, null, null).takeIf {
                        protection != null
                    },
                )
        val commonMisery = legal.map(ResolvedChoice::preview).distinct().singleOrNull()
        return CardOfferView(
            card.reference,
            block == null && choices.isNotEmpty(),
            listOfNotNull(block ?: CardOfferBlocker.INVALID_CHOICE.takeIf { choices.isEmpty() }),
            choices,
            (spec as? CardEffectSpec.CommodityChoices)?.choices.orEmpty(),
            discounts(state, viewer, spec),
            targets,
            required,
            protection,
            commonMisery?.changes.orEmpty(),
            commonMisery?.uncertainPlayers.orEmpty(),
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
        state: GameState,
        viewer: PlayerState?,
        spec: CardEffectSpec,
    ): List<LeaderDiscountView> {
        if (spec !is CardEffectSpec.Leader) return emptyList()
        return LeaderDiscountView.forLeader(
            spec.leader,
            viewer?.advances?.owned.orEmpty(),
            state.effects.crusadesPlayed,
            state.voids.crusades,
        )
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
