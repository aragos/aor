package dev.aragos.aor

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
import java.util.UUID
import kotlin.test.Test

class BotApplicationTest {
    @Test
    fun botWorkSerializesTheExactConfigurationExpectedByTheBrowserModelLoader() {
        val directory = Files.createTempDirectory("aor-bot-configuration-api-test-")
        try {
            testApplication {
                application { module(createGameService(directory, FixedRandom())) }
                val created =
                    client.post("/api/games") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{
                            "players":["Alice"],
                            "bots":[{"difficulty":"HARD"},{"difficulty":"HARD"},{"difficulty":"HARD"}],
                            "options":{
                                "map":"standard",
                                "initial-card":"no-crusades",
                                "deck-modification":"enabled",
                                "immediate-master-art-discard":"enabled"
                            }
                        }"""
                        )
                    }
                val game = json.readTree(created.bodyAsText())
                val token = game["links"].single()["path"].asText().substringAfter("token=")

                val response =
                    client.post("/api/games/${game["gameId"].asText()}/bots/runner") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            json.writeValueAsString(
                                mapOf(
                                    "operation" to "claim",
                                    "runnerId" to UUID.randomUUID().toString(),
                                    "expectedEpoch" to 0,
                                )
                            )
                        )
                    }

                val observation =
                    json.readTree(response.bodyAsText())["observations"].first()["observation"]
                val expected =
                    json.readTree(
                        """{"playerCount":4,"map":"STANDARD","initialCard":"NO_CRUSADES","deckModification":true,"immediateMasterArtDiscard":true,"drawThreeKeepTwo":false}"""
                    )
                assertThat(response.status).isEqualTo(HttpStatusCode.OK)
                assertThat(observation["configuration"]).isEqualTo(expected)
                assertThat(observation["rulesVersion"].asText()).isEqualTo(RULES_VERSION)
                assertThat(observation["dataVersion"].asText()).isEqualTo(DATA_VERSION)
                assertThat(observation["availableCash"].isIntegralNumber).isTrue()
                assertThat(observation["availableTokens"].isIntegralNumber).isTrue()
                assertThat(
                        observation["candidates"].all { candidate ->
                            candidate["features"].properties().all { it.value.isNumber }
                        }
                    )
                    .isTrue()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun privateLinkCreationReturnsOnlyHumanLinksAndPublicBotConfiguration() {
        val directory = Files.createTempDirectory("aor-bot-api-test-")
        try {
            testApplication {
                application { module(createGameService(directory, FixedRandom())) }
                val created =
                    client.post("/api/games") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"players":["Alice"],"bots":[{"difficulty":"EASY"},{"difficulty":"NORMAL"}]}"""
                        )
                    }
                val result = json.readTree(created.bodyAsText())
                val snapshot =
                    client.get("/api/games/${result["gameId"].asText()}") {
                        bearerAuth(
                            result["links"].single()["path"].asText().substringAfter("token=")
                        )
                    }
                val automation = json.readTree(snapshot.bodyAsText())["automation"]

                assertThat(created.status).isEqualTo(HttpStatusCode.Created)
                assertThat(result["links"].size()).isEqualTo(1)
                assertThat(automation["players"].map { it["difficulty"].asText() })
                    .containsExactly("EASY", "NORMAL")
                    .inOrder()
                assertThat(automation.has("runnerId")).isFalse()
                assertThat(automation["players"].any { it.has("seed") }).isFalse()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
