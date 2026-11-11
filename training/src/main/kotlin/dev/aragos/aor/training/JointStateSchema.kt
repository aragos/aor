package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Path

/** Export complete public dictionaries without collecting a game or evaluating a policy. */
internal object JointStateSchema {
    fun run(arguments: List<String>) {
        require(arguments.size == 6) {
            "Use --mode joint-state-schema --configuration ID --output DIR."
        }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.keys == setOf("--mode", "--configuration", "--output") &&
                fields["--mode"] == "joint-state-schema"
        ) {
            "Joint schema export requires exactly its mode, public configuration and output."
        }
        val configuration =
            requireNotNull(
                TrainingMatrix.configurations.singleOrNull { it.id == fields["--configuration"] }
            ) {
                "Joint schema export requires a supported configuration."
            }
        val artifacts =
            TrainingArtifacts(
                Path.of(requireNotNull(fields["--output"]) { "Schema output is required." })
            )
        val catalog = PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
        val card = CardObservationEncoder.dictionary(catalog)
        val purchase = PurchaseObservationEncoder.dictionary(catalog)
        artifacts.write(
            "card-schema.json",
            mapOf(
                "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
                "stateFeatureNames" to card.stateFeatureNames,
                "actionFeatureNames" to card.actionFeatureNames,
            ),
        )
        artifacts.write(
            "purchase-schema.json",
            mapOf(
                "encoderVersion" to PurchaseObservationEncoder.ENCODER_VERSION,
                "stateFeatureNames" to purchase.stateFeatureNames,
                "actionFeatureNames" to purchase.actionFeatureNames,
            ),
        )
        artifacts.write(
            "joint-state-dictionary.json",
            JointStateChronology.from(configuration).metadata(),
        )
    }
}
