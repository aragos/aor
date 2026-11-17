package dev.aragos.aor

import dev.aragos.aor.ai.BotConfiguration
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.JointPolicyScope

/** An explicit stored controller identity, separate from the legacy browser policy version. */
enum class ServerBotProfile(val version: String) {
    COORDINATED_83_SUPPORT_1("aor-coordinated-83-support-1"),
    COORDINATED_WIN_SUPPORT_1("aor-coordinated-win-support-1"),
    COORDINATED_ROBUST_SUPPORT_1("aor-coordinated-robust-support-1");

    fun supports(playerCount: Int, rules: RuleOptions): Boolean =
        BotConfiguration(
            playerCount = playerCount,
            map = rules.map,
            initialCard = rules.initialCard,
            deckModification = rules.deckModification,
            immediateMasterArtDiscard = rules.immediateMasterArtDiscard,
            drawThreeKeepTwo = rules.drawThreeKeepTwo,
        ) == JointPolicyScope.configuration

    fun supports(observation: BotObservation, difficulty: BotDifficulty): Boolean =
        difficulty == BotDifficulty.HARD &&
            observation.rulesVersion == JointPolicyScope.RULES_VERSION &&
            observation.dataVersion == JointPolicyScope.DATA_VERSION &&
            observation.configuration == JointPolicyScope.configuration

    companion object {
        fun fromVersion(version: String): ServerBotProfile? = entries.singleOrNull {
            it.version == version
        }
    }
}

/**
 * Immutable cached inference. Inputs contain only the acting seat's view and existing policy seed.
 */
interface ServerBotPolicy {
    val profile: ServerBotProfile
    val assetSha256: String

    fun choose(view: GameView, observation: BotObservation, seed: Int): String
}

/** Creator-only proposal; submission still uses the ordinary fenced authoritative command path. */
data class BotPolicyProposal(
    val profile: String,
    val assetSha256: String,
    val command: String,
) {
    init {
        require(ServerBotProfile.fromVersion(profile) != null) {
            "A server proposal requires an explicit supported profile."
        }
        require(assetSha256.matches(Regex("[0-9a-f]{64}"))) {
            "A server proposal requires its exact immutable asset hash."
        }
        require(
            command.isNotBlank() && command.length <= 200 && '\n' !in command && '\r' !in command
        ) {
            "A server proposal requires one bounded canonical command."
        }
    }
}
