package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import kotlin.test.Test

class RegretProbeTest {
    private val probe =
        RegretProbe(
            BotObservation(
                playerId = "p1",
                decision = "play-cards",
                context = emptyMap(),
                availableCash = 0,
                availableTokens = 0,
                candidates =
                    listOf(
                        BotCandidate("pass", mapOf("cash" to 3.0)),
                        BotCandidate("play hand-1", mapOf("points" to 7.0)),
                        BotCandidate("play hand-2", mapOf("cash" to -1.0)),
                    ),
                rulesVersion = RULES_VERSION,
                dataVersion = DATA_VERSION,
            )
        )

    @Test
    fun changingCandidateModelsKeepsTheSamePriorReference() {
        val cashModel = BotModel(weights = mapOf("cash" to 10.0))
        val pointsModel = BotModel(weights = mapOf("points" to 10.0))

        val regrets = listOf(cashModel, pointsModel, cashModel).map(probe::regret)

        assertThat(regrets).containsExactly(4.0, 0.0, 4.0).inOrder()
    }

    @Test
    fun selectingANegativePriorValueIncludesTheWholeLoss() {
        val model = BotModel(weights = mapOf("cash" to -1.0))

        val regret = probe.regret(model)

        assertThat(regret).isEqualTo(8.0)
    }
}
