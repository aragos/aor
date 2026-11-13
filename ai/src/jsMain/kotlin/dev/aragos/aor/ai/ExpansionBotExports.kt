@file:OptIn(ExperimentalJsExport::class)

package dev.aragos.aor.ai

import kotlin.js.ExperimentalJsExport
import kotlin.js.JSON
import kotlin.js.JsExport

/**
 * Optional outer selector around the already cached C/P controller. The worker verifies the three
 * exact constructor-input SHA-256 bindings before construction; this boundary additionally owns
 * scope, support profile, ordinary reference, full offers and original E sampling semantics.
 */
@JsExport
class ExpansionBotSelector(
    private val incumbent: JointBotSelector,
    expansionModelJson: String,
) {
    private val actor = readQualifiedExpansionAsset(expansionModelJson)

    fun choose(
        observationJson: String,
        purchaseJson: String?,
        cardJson: String?,
        expansionJson: String?,
        difficulty: String,
        seed: Int,
    ): String {
        val reference = incumbent.choose(observationJson, purchaseJson, cardJson, difficulty, seed)
        val selected = actor ?: return reference
        if (expansionJson == null) return reference
        return try {
            val observation = readBotObservation(observationJson)
            val level = BotDifficulty.valueOf(difficulty.uppercase())
            if (
                !ExpansionPolicyScope.supports(observation, level) ||
                    !incumbent.acceptsExpansionReference(
                        observation,
                        selected.ordinary,
                        selected.supportProfile,
                    )
            ) {
                return reference
            }
            val projection = readExpansionProjection(expansionJson)
            require(
                projection.actions.map(TerritoryAction::command) ==
                    observation.candidates.map(BotCandidate::command).sorted()
            ) {
                "Expansion facts must contain exactly the ordinary offered commands."
            }
            TerritoryPolicy.choose(
                    projection.bindBaseline(reference),
                    selected.model,
                    TerritoryPolicy.actorSeed(TerritoryPhase.EXPANSION, seed),
                )
                .command
        } catch (_: Throwable) {
            reference
        }
    }
}

private data class QualifiedExpansionAsset(
    val ordinary: BotModel,
    val model: TerritoryPolicyModel,
    val supportProfile: String?,
)

private fun readQualifiedExpansionAsset(json: String): QualifiedExpansionAsset? =
    try {
        require(json.isNotBlank() && json.length <= 26_000_000) {
            "The expansion asset must fit its transport bound."
        }
        val raw = JSON.parse<dynamic>(json)
        requireExpansionFields(
            raw,
            setOf(
                "schemaVersion",
                "kind",
                "actorVersion",
                "encoderVersion",
                "rulesVersion",
                "dataVersion",
                "configuration",
                "reference",
                "ordinaryBaseline",
                "model",
            ),
        )
        requireExpansionFields(
            raw.configuration,
            setOf(
                "playerCount",
                "map",
                "initialCard",
                "deckModification",
                "immediateMasterArtDiscard",
                "drawThreeKeepTwo",
            ),
        )
        require(
            purchaseInteger(raw.schemaVersion) == 1 &&
                purchaseString(raw.kind) == "qualified-expansion-actor-1" &&
                purchaseString(raw.actorVersion) == ExpansionPolicyScope.ACTOR_VERSION &&
                purchaseString(raw.encoderVersion) == TerritoryEncoder.EXPANSION_2.version &&
                purchaseString(raw.rulesVersion) == ExpansionPolicyScope.RULES_VERSION &&
                purchaseString(raw.dataVersion) == ExpansionPolicyScope.DATA_VERSION &&
                readConfiguration(raw.configuration) == ExpansionPolicyScope.configuration
        ) {
            "Expansion actors require their exact declared versions and setup."
        }
        requireExpansionFields(
            raw.reference,
            setOf("kind", "ordinarySha256", "purchaseSha256", "jointSha256", "supportProfile"),
        )
        require(purchaseString(raw.reference.kind) == "browser-policy-reference-1") {
            "Expansion actors require an exact browser constructor-input reference."
        }
        for (name in listOf("ordinarySha256", "purchaseSha256", "jointSha256")) {
            require(purchaseString(raw.reference[name]).matches(Regex("[0-9a-f]{64}"))) {
                "Expansion browser reference hashes require canonical SHA-256 values."
            }
        }
        val support =
            if (raw.reference.supportProfile == null) null
            else purchaseString(raw.reference.supportProfile)
        require(support == null || support == KnownOwnChaosSupport.VERSION) {
            "Expansion actors must name the exact supported C/P behavior profile."
        }
        requireExpansionFields(raw.ordinaryBaseline, setOf("schemaVersion", "weights"))
        QualifiedExpansionAsset(
            readPurchaseBaseline(raw.ordinaryBaseline),
            readExpansionModel(raw.model),
            support,
        )
    } catch (_: Throwable) {
        null
    }
