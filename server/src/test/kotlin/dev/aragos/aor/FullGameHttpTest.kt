package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class FullGameHttpTest {
    @Test
    fun ordinaryHttpCommandsReachFinalScoresAfterDeckExhaustion() =
        completedGame { completed, _, _, _ ->
            assertThat(completed["game"]["stage"].asText()).isEqualTo("FINISHED")
            assertThat(completed["game"]["deckRemaining"].asInt()).isEqualTo(0)
            assertThat(completed["game"]["endingTriggered"].asBoolean()).isTrue()
            assertThat(completed["game"]["scores"].size()).isEqualTo(3)
            assertThat(completed["game"]["scores"].any { score -> score["winner"].asBoolean() })
                .isTrue()
        }

    @Test
    fun completedHttpGameRecoversTheExactViewWithoutRequestingRandomness() =
        completedGame { completed, directory, id, token ->
            val recovered = createGameService(directory, ReplayOnlyRandom).get(id, token)

            assertThat(json.readTree(json.writeValueAsString(recovered))).isEqualTo(completed)
        }

    private fun completedGame(verify: (JsonNode, Path, String, String) -> Unit) = testApplication {
        val directory = Files.createTempDirectory("aor-full-http-")
        try {
            application { module(createGameService(directory, OrderedRandom)) }
            val created =
                json.readTree(
                    client
                        .post("/api/games") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"players":["Alice","Bob","Carol"],"epochs":1}""")
                        }
                        .bodyAsText()
                )
            val id = created["gameId"].asText()
            val tokens =
                created["links"].map { link -> link["path"].asText().substringAfter("token=") }
            val url = "/api/games/$id"
            suspend fun view(player: Int) =
                json.readTree(client.get(url) { bearerAuth(tokens[player]) }.bodyAsText())
            suspend fun command(player: Int, text: String): JsonNode {
                val before = view(player)
                val response =
                    client.post("$url/commands") {
                        bearerAuth(tokens[player])
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.writeValueAsString(
                                mapOf(
                                    "expectedRevision" to before["revision"].asInt(),
                                    "command" to text,
                                )
                            )
                        )
                    }
                val body = response.bodyAsText()
                check(response.status == HttpStatusCode.OK) {
                    "$text rejected at ${before["game"]["stage"]}: $body"
                }
                return json.readTree(body)
            }
            command(0, "start")
            command(0, "bid-capital 3")
            command(1, "bid-capital 2")
            command(2, "bid-capital 1")
            command(0, "choose-capital venice")
            command(1, "choose-capital genoa")
            command(2, "choose-capital barcelona")
            var snapshot = view(0)
            repeat(1000) {
                if (snapshot["game"]["stage"].asText() == "FINISHED") {
                    verify(view(0), directory, id, tokens[0])
                    return@testApplication
                }
                val actor =
                    snapshot["prompt"]["players"].first().asText().removePrefix("p").toInt() - 1
                val current = view(actor)
                val commands = current["legalChoices"]["commands"].map(JsonNode::asText)
                val command =
                    when {
                        current["game"]["stage"].asText() == "TOKEN_PURCHASE" -> "buy-tokens $actor"
                        commands.any { command -> command.startsWith("choose-turn-position ") } ->
                            commands.first()
                        commands.any { command -> command.startsWith("play-card ") } ->
                            commands.first { command -> command.startsWith("play-card ") }
                        "stabilize cash" in commands -> {
                            val cash = current["game"]["players"][actor]["cash"].asLong()
                            if (current["game"]["stabilizationCost"].asLong() <= cash) {
                                "stabilize cash"
                            } else {
                                "stabilize misery"
                            }
                        }
                        "end-expansion" in commands -> "end-expansion"
                        "pass" in commands -> "pass"
                        else -> commands.first()
                    }
                snapshot = command(actor, command)
            }
            error("One finite epoch must reach final scores.")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private object OrderedRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = cards

    override fun roll(count: Int): List<DieRoll> = (1..count).map { value -> DieRoll(value) }
}

private object ReplayOnlyRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> =
        error("Recovery must replay the recorded shuffle.")

    override fun roll(count: Int): List<DieRoll> = error("Recovery must replay recorded dice.")
}
