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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FullGameHttpTest(private val postgres: Boolean, private val playerCount: Int) {
    private var sql: PostgresFixture? = null

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "postgres={0}, players={1}")
        fun backends(): List<Array<Any>> =
            listOf(arrayOf<Any>(false, 3)) + (3..6).map { arrayOf<Any>(true, it) }
    }

    private fun service(directory: Path, random: SetupRandom): GameService =
        sql?.reopened(random) ?: createGameService(directory, random)

    @Test
    fun ordinaryHttpCommandsReachFinalScoresAfterDeckExhaustion() =
        completedGame { completed, _, _, _ ->
            assertThat(completed["game"]["stage"].asText()).isEqualTo("FINISHED")
            assertThat(completed["game"]["deckRemaining"].asInt()).isEqualTo(0)
            assertThat(completed["game"]["currentEpoch"].asInt()).isEqualTo(3)
            assertThat(completed["game"]["endingTriggered"].asBoolean()).isTrue()
            assertThat(completed["game"]["scores"].size()).isEqualTo(playerCount)
            assertThat(completed["game"]["scores"].any { score -> score["winner"].asBoolean() })
                .isTrue()
        }

    @Test
    fun completedHttpGameRecoversTheExactViewWithoutRequestingRandomness() =
        completedGame { completed, directory, id, token ->
            val recovered = service(directory, ReplayOnlyRandom).get(id, token)

            assertThat(json.readTree(json.writeValueAsString(recovered))).isEqualTo(completed)
        }

    private fun completedGame(verify: (JsonNode, Path, String, String) -> Unit) = testApplication {
        val directory = Files.createTempDirectory("aor-full-http-")
        try {
            if (postgres) sql = PostgresFixture()
            application { module(service(directory, OrderedRandom)) }
            val created =
                json.readTree(
                    client
                        .post("/api/games") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                json.writeValueAsString(
                                    mapOf(
                                        "players" to
                                            listOf("Alice", "Bob", "Carol", "Dave", "Eve", "Faye")
                                                .take(playerCount)
                                    )
                                )
                            )
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
            for (seat in 0 until playerCount) {
                command(seat, "bid-capital ${playerCount - seat}")
            }
            listOf("venice", "genoa", "barcelona", "paris", "london", "hamburg")
                .take(playerCount)
                .forEachIndexed { seat, capital ->
                    command(seat, "choose-capital $capital")
                }
            var snapshot = view(0)
            repeat(5000) {
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
                        commands.any { command -> command.startsWith("play ") } ->
                            commands.first { command -> command.startsWith("play ") }
                        "buy-card" in commands -> "buy-card"
                        commands.any { command -> command.startsWith("buy-advance ") } ->
                            commands.first { command -> command.startsWith("buy-advance ") }
                        "stabilize cash" in commands -> {
                            val cash = current["game"]["players"][actor]["cash"].asLong()
                            if (current["game"]["stabilizationCost"].asLong() <= cash) {
                                "stabilize cash"
                            } else {
                                "stabilize misery"
                            }
                        }
                        "pass" in commands -> "pass"
                        else -> commands.first()
                    }
                snapshot = command(actor, command)
            }
            error("The three-epoch deck sequence must reach final scores.")
        } finally {
            sql?.close()
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
