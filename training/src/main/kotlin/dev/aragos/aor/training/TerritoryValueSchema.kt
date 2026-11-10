package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Explicit, no-game dictionary export for fresh typed Q initialization. Never upgrades a model. */
object TerritoryValueSchema {
    fun run(arguments: List<String>) {
        require(arguments.size == 8) {
            "Use --mode territory-value-schema --configuration ID --encoder VERSION --output FILE."
        }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.keys == setOf("--mode", "--configuration", "--encoder", "--output") &&
                fields["--mode"] == "territory-value-schema"
        ) {
            "Schema export requires four distinct explicit options."
        }
        val configuration =
            requireNotNull(
                TrainingMatrix.configurations.singleOrNull { it.id == fields["--configuration"] }
            ) {
                "Schema export requires a supported complete training configuration."
            }
        val encoder =
            TerritoryEncoder.fromVersion(
                requireNotNull(fields["--encoder"]) { "An explicit encoder version is required." }
            )
        val catalog = PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
        val dictionary =
            when (encoder.phase) {
                TerritoryPhase.TOKEN -> TokenObservationEncoder.dictionary(catalog, encoder)
                TerritoryPhase.EXPANSION -> ExpansionObservationEncoder.dictionary(catalog, encoder)
            }
        val output =
            Path.of(requireNotNull(fields["--output"]) { "A new schema output file is required." })
                .toAbsolutePath()
                .normalize()
        Files.createDirectories(output.parent)
        Files.write(
            output,
            PurchaseJson.mapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(
                    mapOf(
                        "encoderVersion" to encoder.version,
                        "stateFeatureNames" to dictionary.stateFeatureNames,
                        "actionFeatureNames" to dictionary.actionFeatureNames,
                    )
                ),
            StandardOpenOption.CREATE_NEW,
        )
    }
}
