package dev.aragos.aor

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Locally admitted Hard model with the independently tested same-phase advance guard. */
internal class BroadPreviewPolicy private constructor(private val model: BotModel) :
    ServerBotPolicy {
    override val profile = ServerBotProfile.BROAD_HARD_1
    override val assetSha256 = MODEL_SHA256
    override val finalAdvanceGuard = FinalAdvanceGuard(currentLead = 100, projectedLead = 250)

    override fun choose(view: GameView, observation: BotObservation, seed: Int): String =
        BotPolicy.choose(observation, BotDifficulty.HARD, seed, model)

    companion object {
        private const val MODEL_SHA256 =
            "a479db4b3c7f580ce9979049fe407cbde4db22bf75b76d3cc54ac6f2039ce500"

        fun read(path: Path): BroadPreviewPolicy {
            require(Files.size(path) in 1..1_000_000L) {
                "The broad Hard asset must fit the ordinary model size limit."
            }
            val bytes = Files.readAllBytes(path)
            val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
            require(hash == MODEL_SHA256) {
                "The broad Hard asset must match the model validated with the advance guard."
            }
            val root = ObjectMapper().readTree(bytes)
            require(
                root["rulesVersion"]?.asText() == RULES_VERSION &&
                    root["dataVersion"]?.asText() == DATA_VERSION
            ) {
                "The broad Hard asset requires the current rules and catalog."
            }
            val weights =
                root["weights"].properties().associate { (name, value) ->
                    name to value.doubleValue()
                }
            return BroadPreviewPolicy(
                BotModels.fromSerializedWeights(
                    weights,
                    schemaVersion = root["schemaVersion"].intValue(),
                )
            )
        }
    }
}
