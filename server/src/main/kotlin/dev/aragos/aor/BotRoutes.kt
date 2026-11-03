package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotDifficulty
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun JsonNode.automatedPlayers(): List<AutomatedPlayerRequest> {
    val requests = this["bots"]?.array().orEmpty()
    if (requests.size > 5) throw ServiceFailure(400, "INVALID_GAME")
    return requests.mapIndexed { index, request ->
        request.fieldsOnly("name", "difficulty")
        AutomatedPlayerRequest(
            name = request["name"]?.string()?.trim() ?: "Automated ${index + 1}",
            difficulty = BotDifficulty.valueOf(request.field("difficulty").string()),
        )
    }
}

internal fun Route.botRoutes(service: GameService, hosted: HostedServices?) {
    post("/api/games/{id}/bots/runner") {
        call.api {
            val access = call.gameAccess(hosted)
            val body = call.body()
            body.fieldsOnly("operation", "runnerId", "expectedEpoch")
            val result =
                withContext(Dispatchers.IO) {
                    BotAutomation.control(
                        service = service,
                        id =
                            checkNotNull(call.parameters["id"]) {
                                "A bot route requires a game ID."
                            },
                        access = access,
                        operation = body.field("operation").string(),
                        runnerId = body.field("runnerId").string(),
                        expectedEpoch = body["expectedEpoch"]?.integer(),
                    )
                }
            call.respondJson(result)
        }
    }
    get("/api/games/{id}/bots/work") {
        call.api {
            val access = call.gameAccess(hosted)
            val runnerId =
                call.request.queryParameters["runnerId"]
                    ?: throw ServiceFailure(400, "INVALID_REQUEST")
            val epoch =
                call.request.queryParameters["epoch"]?.toIntOrNull()
                    ?: throw ServiceFailure(400, "INVALID_REQUEST")
            val result =
                withContext(Dispatchers.IO) {
                    BotAutomation.read(
                        service = service,
                        id =
                            checkNotNull(call.parameters["id"]) {
                                "A bot route requires a game ID."
                            },
                        access = access,
                        runnerId = runnerId,
                        epoch = epoch,
                    )
                }
            call.respondJson(result)
        }
    }
    post("/api/games/{id}/bots/commands") {
        call.api {
            val access = call.gameAccess(hosted)
            val body = call.body()
            body.fieldsOnly(
                "expectedRevision",
                "expectedSubmissionVersion",
                "epoch",
                "runnerId",
                "operationId",
                "seat",
                "decisionId",
                "command",
            )
            val result =
                withContext(Dispatchers.IO) {
                    BotAutomation.submit(
                        service = service,
                        id =
                            checkNotNull(call.parameters["id"]) {
                                "A bot route requires a game ID."
                            },
                        access = access,
                        runnerId = body.field("runnerId").string(),
                        epoch = body.field("epoch").integer(),
                        expectedRevision = body.field("expectedRevision").integer(),
                        expectedSubmissionVersion =
                            body["expectedSubmissionVersion"]
                                ?.takeUnless(JsonNode::isNull)
                                ?.string(),
                        seat = PlayerId(body.field("seat").string()),
                        decisionId = body.field("decisionId").string(),
                        commandText = body.field("command").string(),
                        operationId = body.field("operationId").string(),
                    )
                }
            call.respondJson(result)
        }
    }
}
