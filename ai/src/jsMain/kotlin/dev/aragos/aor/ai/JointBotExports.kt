@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

/**
 * Atomic optional card/purchase actor. Both components must parse under one exact scope and
 * ordinary-model binding. Invalid assets or projections retain the existing qualified selector; a
 * valid half of a broken bundle is never installed alone. Sampling uses the native seed owners.
 */
@JsExport
class JointBotSelector(
    baselineModelJson: String,
    incumbentPurchaseModelJson: String,
    jointModelJson: String,
) {
    private val baselineJson = baselineModelJson
    private val incumbent = PurchaseBotSelector(baselineModelJson, incumbentPurchaseModelJson)
    private val actor = readQualifiedJointAsset(jointModelJson)

    fun choose(
        observationJson: String,
        purchaseJson: String?,
        cardJson: String?,
        difficulty: String,
        seed: Int,
    ): String {
        val selected = actor
        val observation = readBotObservation(observationJson)
        val level = BotDifficulty.valueOf(difficulty.uppercase())
        if (
            selected == null ||
                !JointPolicyScope.supports(observation, level) ||
                selected.ordinaryBaseline != readBotModel(baselineJson, observation)
        ) {
            return incumbent.choose(observationJson, purchaseJson, difficulty, seed)
        }
        return try {
            if (observation.decision == "play-cards") {
                val projection =
                    readCardProjection(
                        requireNotNull(cardJson) { "Card actor facts are required." }
                    )
                requireOfferedCommands(observation, projection.actions.map(CardAction::command))
                val prior = BotPolicy.choose(observation, level, seed, selected.cardBaseline)
                val bound = projection.bindBaseline(prior)
                val actorSeed = CardPolicy.actorSeed(seed)
                if (selected.support == null) {
                    CardPolicy.choose(bound, selected.card, actorSeed).command
                } else {
                    KnownOwnChaosSupport.card(
                            bound,
                            CardPolicy.evaluate(bound, selected.card),
                            CardPolicy.uniform(actorSeed),
                        )
                        .command
                }
            } else {
                val projection =
                    readPurchaseProjection(
                        requireNotNull(purchaseJson) { "Purchase actor facts are required." }
                    )
                requireOfferedCommands(observation, projection.actions.map(PurchaseAction::command))
                val prior = BotPolicy.choose(observation, level, seed, selected.purchaseBaseline)
                val bound = projection.bindBaseline(prior)
                val actorSeed = PurchasePolicy.actorSeed(seed)
                if (selected.support == null) {
                    PurchasePolicy.choose(bound, selected.purchase, actorSeed).command
                } else {
                    KnownOwnChaosSupport.purchase(
                            bound,
                            PurchasePolicy.evaluate(bound, selected.purchase),
                            PurchasePolicy.uniform(actorSeed),
                        )
                        .command
                }
            }
        } catch (_: Throwable) {
            incumbent.choose(observationJson, purchaseJson, difficulty, seed)
        }
    }

    private fun requireOfferedCommands(observation: BotObservation, commands: List<String>) {
        require(commands == observation.candidates.map(BotCandidate::command).sorted()) {
            "Joint actor facts must describe exactly the ordinary offered commands."
        }
    }

    /** Additional E inference cannot be attached to a broken or differently supported bundle. */
    internal fun acceptsExpansionReference(
        observation: BotObservation,
        ordinary: BotModel,
        supportProfile: String?,
    ): Boolean {
        val selected = actor ?: return false
        return selected.ordinaryBaseline == ordinary &&
            readBotModel(baselineJson, observation) == ordinary &&
            selected.support?.version == supportProfile
    }
}

private data class QualifiedJointAsset(
    val ordinaryBaseline: BotModel,
    val cardBaseline: BotModel,
    val purchaseBaseline: BotModel,
    val card: CardPolicyModel,
    val purchase: PurchasePolicyModel,
    val support: JointSupportProfile?,
)

/** Optional inference identity, separate from the immutable trained actor qualification. */
private enum class JointSupportProfile(val version: String) {
    KNOWN_OWN_CHAOS_1(KnownOwnChaosSupport.VERSION);

    companion object {
        fun read(raw: dynamic): JointSupportProfile? {
            if (jsTypeOf(raw) == "undefined") return null
            val version = purchaseString(raw)
            return requireNotNull(entries.singleOrNull { it.version == version }) {
                "Unknown joint actor support profile."
            }
        }
    }
}

private fun readQualifiedJointAsset(json: String): QualifiedJointAsset? =
    try {
        require(json.isNotBlank() && json.length <= 48_000_000) {
            "The complete joint actor must fit its transport bound."
        }
        val raw = JSON.parse<dynamic>(json)
        require(
            purchaseInteger(raw.schemaVersion) == 1 &&
                purchaseString(raw.kind) == "qualified-joint-card-purchase-1" &&
                purchaseString(raw.rulesVersion) == JointPolicyScope.RULES_VERSION &&
                purchaseString(raw.dataVersion) == JointPolicyScope.DATA_VERSION &&
                readConfiguration(raw.configuration) == JointPolicyScope.configuration &&
                purchaseString(raw.cardActorVersion) == CardPolicy.ACTOR_VERSION &&
                purchaseString(raw.purchaseActorVersion) == PurchasePolicy.ACTOR_VERSION
        ) {
            "Joint actors require their exact qualified versions and configuration."
        }
        QualifiedJointAsset(
            ordinaryBaseline = readPurchaseBaseline(raw.ordinaryBaseline),
            cardBaseline = readPurchaseBaseline(raw.cardBaseline),
            purchaseBaseline = readPurchaseBaseline(raw.purchaseBaseline),
            card = readCardModel(raw.cardModel),
            purchase = readPurchaseModel(raw.purchaseModel),
            support = JointSupportProfile.read(raw.supportProfile),
        )
    } catch (_: Throwable) {
        null
    }
