package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
    fun retiredPlanningUpgradeRouteIsUnavailable() = withGame {
        val response =
            client.post("$url/planning-upgrade") {
                bearerAuth(tokens[0])
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun referenceCatalogIsPublicAndContainsNoGameState() = withGame {
        val response = client.get("/api/reference")
        val reference = json.readTree(response.bodyAsText())

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(reference["cards"].size()).isEqualTo(CardData.all.size)
        assertThat(reference["catalog"]["advances"].size()).isEqualTo(26)
        assertThat(reference.has("players")).isFalse()
        assertThat(reference.has("deck")).isFalse()
    }

    @Test
    fun securityPolicyAllowsFirebaseGooglePopupScripts() = withGame {
        val response = client.get("/api/config")
        val policy =
            requireNotNull(response.headers["Content-Security-Policy"]) {
                "Application responses must include a content security policy."
            }
        val scripts = policy.split(';').map(String::trim).single { it.startsWith("script-src ") }

        assertThat(scripts.split(' '))
            .containsExactly("script-src", "'self'", "https://apis.google.com")
        assertThat(policy).contains("frame-src https://*.firebaseapp.com")
    }

    @Test
    fun tokenQuoteRequiresCredentials() = withGame {
        val response =
            client.post("$url/quote-token-purchase") {
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":1,"amount":8}""")
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun tokenQuoteRejectsFractionalAmounts() = withGame {
        val response =
            client.post("$url/quote-token-purchase") {
                bearerAuth(tokens[0])
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":1,"amount":8.5}""")
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun tokenQuoteRejectsAnEarlierRevision() = withGame {
        val response =
            client.post("$url/quote-token-purchase") {
                bearerAuth(tokens[0])
                contentType(ContentType.Application.Json)
                setBody("""{"expectedRevision":0,"amount":8}""")
            }

        assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
        assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
    }

    @Test
    fun creationAcceptsAllFiveVariantsThroughHttp() = withGame {
        val rules = RuleOptions(initialCard = InitialCardRule.NO_EVENTS, drawThreeKeepTwo = true)
        val options = rules.configurationCommands().associate { it.key to it.value }
        val response =
            client.post("/api/games") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.writeValueAsString(
                        mapOf("players" to listOf("Alice", "Bob", "Carol"), "options" to options)
                    )
                )
            }
        assertThat(response.status).isEqualTo(HttpStatusCode.Created)
        val created = json.readTree(response.bodyAsText())
        val token = created["links"][0]["path"].asText().substringAfter("#token=")
        val view = client.get("/api/games/${created["gameId"].asText()}") { bearerAuth(token) }
        val snapshot = json.readTree(view.bodyAsText())
        assertThat(snapshot["game"]["stage"].asText()).isEqualTo("SETUP_RETURN")
        assertThat(snapshot["game"]["hand"].size()).isEqualTo(3)
        assertThat(snapshot["game"]["ruleOptions"].size()).isEqualTo(5)
    }

    @Test
    fun creationStartsSetupAndFreezesTheSelectedVariants() = withGame {
        val own = json.readTree(view(0).bodyAsText())
        val rejected = command(0, 2, "set-option deck-modification enabled")

        assertThat(own["game"]["stage"].asText()).isEqualTo("BIDDING")
        assertThat(own["game"]["hand"].size()).isEqualTo(1)
        assertThat(own["legalChoices"]["commands"].map { it.asText() }).doesNotContain("start")
        assertThat(rejected.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun rebuiltMarkerBonusDefaultsToExcludedThroughTheHttpApi() = withGame {
        val snapshot = json.readTree(view(0).bodyAsText())

        assertThat(snapshot["game"]["rebuiltMarkerBonus"].asText()).isEqualTo("EXCLUDE")
    }

    @Test
    fun requestsRequireAValidPlayerCredential() = withGame {
        val missing = client.get(url)
        val invalid = client.get(url) { bearerAuth("x".repeat(43)) }
        val wrongPlayer = command(1, 2, "p1: bid-capital 1")

        assertThat(missing.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(invalid.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(wrongPlayer.status).isEqualTo(HttpStatusCode.UnprocessableEntity)
    }

    @Test
    fun playerViewsExposeOnlyTheCallersCard() = withGame {
        val started = view(0)
        val raw = started.bodyAsText()
        val own = json.readTree(raw)
        val privateFields = withoutPublicCardReferences(own).toString()
        val other = json.readTree(view(1).bodyAsText())

        assertThat(started.status).isEqualTo(HttpStatusCode.OK)
        assertThat(started.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(own["game"]["hand"].map { card -> card["definition"]["id"].asText() })
            .containsExactly("stone-1")
        assertThat(own["game"]["cardOffers"].toList()).isEmpty()
        assertThat(privateFields).doesNotContain("stone-2")
        assertThat(privateFields).doesNotContain("wool-1")
        assertThat(privateFields).doesNotContain("wool-2")
        assertThat(own["game"].has("deck")).isFalse()
        assertThat(own["game"]["players"].none { player -> player.has("hand") }).isTrue()
        assertThat(own["game"]["cardInspections"]).isEqualTo(other["game"]["cardInspections"])
        assertThat(own["game"]["unplayedCards"]).isEqualTo(other["game"]["unplayedCards"])
        assertThat(own["game"]["unplayedCards"].map { it["id"].asText() })
            .containsExactlyElementsIn(CardData.epochOne.map(CardDefinition::id))
            .inOrder()
        assertThat(other["game"]["hand"][0]["definition"]["id"].asText()).isEqualTo("stone-2")
    }

    @Test
    fun staleCommandsReturnConflict() = withGame {
        val response = command(0, 1, "bid-capital 12")

        assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
    }

    @Test
    fun invalidCommandsReturnRuleDiagnostics() = withGame {
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
    fun tokenPurchasesResolveDrawsAndReachCardPlayThroughTheHttpApi() = withGame {
        selectCapitals()
        command(0, 6, "buy-tokens -7")
        command(1, 6, "buy-tokens 5")

        val response = command(2, 6, "buy-tokens 1")
        val snapshot = json.readTree(response.bodyAsText())

        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(snapshot["game"]["stage"].asText()).isEqualTo("PLAY_CARDS")
        assertThat(snapshot["game"]["turnNumber"].asInt()).isEqualTo(1)
        assertThat(snapshot["game"]["turnOrder"].size()).isEqualTo(3)
        assertThat(snapshot["game"]["orderBoxes"].size()).isEqualTo(3)
        assertThat(snapshot["game"]["revealedTokenPurchases"]["p1"].asInt()).isEqualTo(-7)
        assertThat(snapshot["submitted"].size()).isEqualTo(0)
        assertThat(snapshot["pendingBid"].isNull).isTrue()
    }

    @Test
    fun phaseDrawsStayPrivateInTheHttpSnapshotAndHistory() = withGame {
        selectCapitals()
        val beforeDraws = json.readTree(view(1).bodyAsText())
        command(0, 6, "buy-tokens 0")
        command(1, 6, "buy-tokens 1")
        command(2, 6, "buy-tokens 2")

        val own = json.readTree(view(0).bodyAsText())
        val other = json.readTree(view(1).bodyAsText())

        assertThat(own["game"]["hand"].map { card -> card["definition"]["id"].asText() })
            .containsExactly("stone-1", "wool-2")
        assertThat(own["history"].map(JsonNode::asText)).contains("!draw p1 hand-4 wool-2")
        assertThat(other["history"].map(JsonNode::asText)).contains("!draw p1 hand-4 <hidden>")
        assertThat(withoutPublicCardReferences(other).toString()).doesNotContain("wool-2")
        assertThat(other["game"]["unplayedCards"]).isEqualTo(beforeDraws["game"]["unplayedCards"])
        assertThat(other["game"]["hand"].none { card -> card["reference"].asText() == "hand-4" })
            .isTrue()
        assertThat(
                other["game"]["cardOffers"].none { offer ->
                    offer["reference"].asText() == "hand-4"
                }
            )
            .isTrue()
        assertThat(other["game"]["cardInspections"])
            .isEqualTo(beforeDraws["game"]["cardInspections"])
        assertThat(other["game"]["deckRemaining"].asInt()).isEqualTo(23)
    }

    @Test
    fun phaseDrawsAndPublicBoardRecoverFromSavedEntriesWithoutRandomness() = withGame {
        selectCapitals()
        command(0, 6, "buy-tokens 0")
        command(1, 6, "buy-tokens 1")
        command(2, 6, "buy-tokens 2")
        val before = json.readTree(view(0).bodyAsText())
        val recovered = createGameService(directory, NoApiRandom).get(id, tokens[0])

        assertThat(json.readTree(json.writeValueAsString(recovered))).isEqualTo(before)
        assertThat(recovered.game.board.map(ProvinceView::id))
            .containsExactly(ProvinceId("venice"), ProvinceId("genoa"), ProvinceId("barcelona"))
    }

    @Test
    fun playerCredentialsAreStoredAsHashes() = withGame {
        val disk = Files.readString(directory.resolve("$id.json"))

        tokens.forEach { token -> assertThat(disk).doesNotContain(token) }
    }

    @Test
    fun malformedJsonReturnsStructuredErrors() {
        val directory = Files.createTempDirectory("aor-input-test-")
        try {
            testApplication {
                application { module(createGameService(directory)) }
                for (body in
                    listOf(
                        "null",
                        "[]",
                        "{",
                        "{}",
                        """{"players":"Alice"}""",
                        """{"players":["Alice","Bob","Carol"],"epochs":1.2}""",
                        """{"players":["Alice","Bob","Carol"],"epochs":3}""",
                    )) {
                    val response = client.post("/api/games") { setBody(body) }
                    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                    assertThat(json.readTree(response.bodyAsText())["code"].asText())
                        .isEqualTo("INVALID_REQUEST")
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun invalidRegistrationsReturnStructuredErrors() {
        val directory = Files.createTempDirectory("aor-input-test-")
        try {
            testApplication {
                application { module(createGameService(directory)) }
                for (names in
                    listOf(listOf("A", "B"), listOf("A", "a", "C"), listOf("A", " ", "C"))) {
                    val response =
                        client.post("/api/games") {
                            setBody(json.writeValueAsString(mapOf("players" to names)))
                        }
                    assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                    assertThat(json.readTree(response.bodyAsText())["code"].asText())
                        .isEqualTo("INVALID_GAME")
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** Retains private-bearing fields, excluding public catalogs and location-free circulation. */
    private fun withoutPublicCardReferences(snapshot: JsonNode): JsonNode =
        snapshot.deepCopy<ObjectNode>().apply {
            (get("game") as ObjectNode).remove(listOf("cardInspections", "unplayedCards"))
        }

    private fun withGame(block: suspend ApiGame.() -> Unit) {
        val directory = Files.createTempDirectory("aor-api-test-")
        try {
            testApplication {
                application { module(createGameService(directory, FixedRandom())) }
                val response =
                    client.post("/api/games") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"players":["Alice","Bob","Carol"]}""")
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
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private object NoApiRandom : SetupRandom {
    override fun shuffle(cards: List<String>): List<String> = error("Replay requested a shuffle")

    override fun roll(count: Int): List<DieRoll> = error("Replay requested dice")
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
        command(0, 2, "bid-capital 12")
        command(1, 2, "bid-capital 12")
        command(2, 2, "bid-capital 0")
    }
}
