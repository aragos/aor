package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ApplicationTest {
    @Test
    fun requestsRequireAValidPlayerCredential() = withGame {
        val missing = client.get(url)
        val invalid = client.get(url) { bearerAuth("x".repeat(43)) }
        val wrongPlayer = command(1, 1, "start")

        assertThat(missing.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(invalid.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(wrongPlayer.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun playerViewsExposeOnlyTheCallersCard() = withGame {
        val started = command(0, 1, "start")
        val raw = started.bodyAsText()
        val other = json.readTree(view(1).bodyAsText())

        assertThat(started.status).isEqualTo(HttpStatusCode.OK)
        assertThat(started.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(raw).contains("stone-1")
        assertThat(raw).doesNotContain("stone-2")
        assertThat(raw).doesNotContain("wool-1")
        assertThat(raw).doesNotContain("wool-2")
        assertThat(raw).doesNotContain("deck")
        assertThat(other["game"]["hand"][0]["definition"]["id"].asText()).isEqualTo("stone-2")
    }

    @Test
    fun staleCommandsReturnConflict() = withGame {
        command(0, 1, "start")

        val response = command(0, 1, "bid-capital 12")

        assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
    }

    @Test
    fun invalidCommandsReturnRuleDiagnostics() = withGame {
        command(0, 1, "start")
        val commands =
            listOf(
                "p2: bid-capital 12",
                "!roll capital-tie p1=6 p2=1",
                "set-option setup improved",
                "bid-capital -1",
                "bid-capital 41",
                "set-option epochs 2",
            )

        val responses = commands.map { text -> command(0, 2, text) }

        assertThat(responses.map { response -> response.status })
            .containsExactlyElementsIn(List(commands.size) { HttpStatusCode.UnprocessableEntity })
    }

    @Test
    fun multilineCommandsReturnBadRequest() = withGame {
        val response = command(0, 1, "start\nstart")

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun pendingBidsRemainPrivateUntilTheBatchIsComplete() = withGame {
        command(0, 1, "start")
        command(0, 2, "bid-capital 12")

        val other = json.readTree(view(1).bodyAsText())

        assertThat(other["pendingBid"].isNull).isTrue()
        assertThat(other["game"]["revealedBids"].size()).isEqualTo(0)
        assertThat(other["game"]["players"][0]["cash"].asInt()).isEqualTo(40)
        assertThat(other["history"].none { entry -> entry.asText().contains("bid-capital") })
            .isTrue()
    }

    @Test
    fun outOfTurnAndDuplicateCapitalChoicesAreRejected() = withGame {
        revealBids()
        val outOfTurn = command(0, 3, "choose-capital venice")
        command(1, 3, "choose-capital venice")
        val duplicate = command(0, 4, "choose-capital venice")

        assertThat(outOfTurn.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
        assertThat(duplicate.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun capitalSelectionStartsTurnOneTokenPurchaseThroughTheHttpApi() = withGame {
        revealBids()
        command(1, 3, "choose-capital venice")
        command(0, 4, "choose-capital genoa")

        val response = command(2, 5, "choose-capital barcelona")
        val complete = json.readTree(response.bodyAsText())

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(complete["game"]["stage"].asText()).isEqualTo("TOKEN_PURCHASE")
        assertThat(complete["game"]["turnNumber"].asInt()).isEqualTo(1)
        assertThat(complete["prompt"]["messageId"].asText()).isEqualTo("prompt.buy-tokens")
        assertThat(complete["game"]["players"].map { player -> player["cash"].asInt() })
            .containsExactly(28, 28, 40)
            .inOrder()
        assertThat(complete["game"]["capitalOrder"].map { player -> player.asText() })
            .containsExactly("p2", "p1", "p3")
            .inOrder()
        assertThat(complete["game"]["players"].map { player -> player["stockMarkers"].asInt() })
            .containsExactly(24, 24, 24)
        assertThat(complete["game"]["players"].map { player -> player["boardMarkers"].asInt() })
            .containsExactly(1, 1, 1)
    }

    @Test
    fun signedTokenPurchasesRemainPrivateThroughTheHttpApi() = withGame {
        selectCapitals()

        val submitted = command(0, 6, "buy-tokens -7")
        val own = json.readTree(submitted.bodyAsText())
        val other = json.readTree(view(1).bodyAsText())

        assertThat(submitted.status).isEqualTo(HttpStatusCode.OK)
        assertThat(own["pendingBid"].asInt()).isEqualTo(-7)
        assertThat(other["pendingBid"].isNull).isTrue()
        assertThat(other["game"]["revealedTokenPurchases"].size()).isEqualTo(0)
        assertThat(other["history"].none { entry -> entry.asText().contains("buy-tokens") })
            .isTrue()
        assertThat(other["game"]["players"][0]["cash"].asInt()).isEqualTo(28)
    }

    @Test
    fun tokenPurchasesReachTheDrawCardBoundaryThroughTheHttpApi() = withGame {
        selectCapitals()
        command(0, 6, "buy-tokens -7")
        command(1, 6, "buy-tokens 5")

        val response = command(2, 6, "buy-tokens 1")
        val snapshot = json.readTree(response.bodyAsText())

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(snapshot["game"]["stage"].asText()).isEqualTo("DRAW_CARD")
        assertThat(snapshot["game"]["turnNumber"].asInt()).isEqualTo(1)
        assertThat(snapshot["game"]["turnOrder"].size()).isEqualTo(3)
        assertThat(snapshot["game"]["orderBoxes"].size()).isEqualTo(3)
        assertThat(snapshot["game"]["revealedTokenPurchases"]["p1"].asInt()).isEqualTo(-7)
        assertThat(snapshot["submitted"].size()).isEqualTo(0)
        assertThat(snapshot["pendingBid"].isNull).isTrue()
    }

    @Test
    fun playerCredentialsAreStoredAsHashes() = withGame {
        val disk = Files.readString(directory.resolve("$id.json"))

        tokens.forEach { token -> assertThat(disk).doesNotContain(token) }
    }

    @Test
    fun malformedJsonReturnsStructuredErrors() = testApplication {
        val directory = Files.createTempDirectory("aor-input-test-")
        try {
            application { module(createGameService(directory)) }
            for (body in
                listOf(
                    "null",
                    "[]",
                    "{",
                    "{}",
                    """{"players":"Alice"}""",
                    """{"players":["Alice","Bob","Carol"],"epochs":1.2}""",
                )) {
                val response = client.post("/api/games") { setBody(body) }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(json.readTree(response.bodyAsText())["code"].asText())
                    .isEqualTo("INVALID_REQUEST")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidRegistrationsReturnStructuredErrors() = testApplication {
        val directory = Files.createTempDirectory("aor-input-test-")
        try {
            application { module(createGameService(directory)) }
            for (names in listOf(listOf("A", "B"), listOf("A", "a", "C"), listOf("A", " ", "C"))) {
                val response =
                    client.post("/api/games") {
                        setBody(json.writeValueAsString(mapOf("players" to names)))
                    }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(json.readTree(response.bodyAsText())["code"].asText())
                    .isEqualTo("INVALID_GAME")
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun withGame(block: suspend ApiGame.() -> Unit) = testApplication {
        val directory = Files.createTempDirectory("aor-api-test-")
        try {
            application { module(createGameService(directory, FixedRandom())) }
            val response =
                client.post("/api/games") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"players":["Alice","Bob","Carol"],"epochs":3}""")
                }
            val created = json.readTree(response.bodyAsText())
            ApiGame(
                    client = client,
                    directory = directory,
                    id = created["gameId"].asText(),
                    tokens =
                        created["links"].map { link ->
                            link["path"].asText().substringAfter("token=")
                        },
                )
                .block()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private class ApiGame(
    val client: HttpClient,
    val directory: Path,
    val id: String,
    val tokens: List<String>,
) {
    val url = "/api/games/$id"

    suspend fun view(player: Int) = client.get(url) { bearerAuth(tokens[player]) }

    suspend fun command(player: Int, revision: Int, text: String) =
        client.post("$url/commands") {
            bearerAuth(tokens[player])
            contentType(ContentType.Application.Json)
            setBody(
                json.writeValueAsString(mapOf("expectedRevision" to revision, "command" to text))
            )
        }

    suspend fun selectCapitals() {
        revealBids()
        command(1, 3, "choose-capital venice")
        command(0, 4, "choose-capital genoa")
        command(2, 5, "choose-capital barcelona")
    }

    suspend fun revealBids() {
        command(0, 1, "start")
        command(0, 2, "bid-capital 12")
        command(1, 2, "bid-capital 12")
        command(2, 2, "bid-capital 0")
    }
}
