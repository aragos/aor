package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.HARD_AUTOMATA_VERSION
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
import kotlin.test.Test

class BotApplicationTest {
    @Test
    fun privateLinkCreationReturnsOnlyHumanLinksAndPublicBotConfiguration() {
        val directory = Files.createTempDirectory("aor-bot-api-test-")
        try {
            testApplication {
                application { module(createGameService(directory, FixedRandom())) }
                val created =
                    client.post("/api/games") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"players":["Alice"],"bots":[{},{"difficulty":"HARD"}]}""")
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
                    .containsExactly("HARD", "HARD")
                    .inOrder()
                assertThat(automation["players"].map { it["policyVersion"].asText() }.distinct())
                    .containsExactly(HARD_AUTOMATA_VERSION)
                assertThat(automation.has("runnerId")).isFalse()
                assertThat(automation["players"].any { it.has("seed") }).isFalse()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
