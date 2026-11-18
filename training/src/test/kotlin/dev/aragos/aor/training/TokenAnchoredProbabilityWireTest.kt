package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.TokenAnchorDistribution
import dev.aragos.aor.ai.TokenAnchoredPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test

/** Constructed probability arithmetic only: no model inference, states, games or random draws. */
class TokenAnchoredProbabilityWireTest {
    @Test
    fun exportsSharedOwnerResultsForIndependentPythonParity() {
        val configuration = TokenAnchorDistribution(.2, 1.0)
        val cases =
            listOf(
                listOf(0.0, 1.0, 0.0) to listOf(0.0, 0.0, 0.0),
                listOf(0.0, 1.0) to listOf(4.0, 0.0),
                listOf(.1, .6, .3) to listOf(-1.0, .25, 2.0),
                listOf(.5, .5) to listOf(21.0, 22.0),
                listOf(1.0) to listOf(-31.0),
            )
        val rows = cases.map { (anchor, residual) ->
            val value =
                TokenAnchoredPolicy.probabilities(
                    anchor,
                    residual,
                    configuration,
                    anchor.indexOfFirst { it == anchor.max() },
                )
            mapOf(
                "anchor94Probabilities" to anchor,
                "rawResiduals" to residual,
                "initialProbabilities" to value.initialProbabilities,
                "probabilities" to value.probabilities,
                "logits" to value.logits,
            )
        }
        val bytes =
            PurchaseJson.mapper.writeValueAsBytes(
                mapOf(
                    "kind" to "token-anchor-constructed-probability-fixture-1",
                    "distribution" to
                        mapOf(
                            "kind" to TokenAnchorDistribution.VERSION,
                            "epsilon" to .2,
                            "temperature" to 1.0,
                            "support" to configuration.support.name,
                        ),
                    "modelForwards" to 0,
                    "games" to 0,
                    "randomDraws" to 0,
                    "cases" to rows,
                )
            )
        val output = Path.of("build/test-fixtures/token-anchored-probabilities.json")
        Files.createDirectories(output.parent)
        if (Files.exists(output)) {
            assertThat(Files.readAllBytes(output)).isEqualTo(bytes)
        } else {
            Files.write(output, bytes, StandardOpenOption.CREATE_NEW)
        }
        assertThat(rows).hasSize(5)
    }
}
