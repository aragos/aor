package dev.aragos.aor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() {
    val port =
        System.getenv("AOR_HTTP_PORT")?.let { configured ->
            requireNotNull(configured.toIntOrNull()) {
                "AOR_HTTP_PORT must be a whole-number port."
            }
        } ?: 8080
    require(port in 1..65535) { "AOR_HTTP_PORT must be between one and 65535." }
    embeddedServer(Netty, port = port, host = "127.0.0.1", module = { module() }).start(wait = true)
}

fun Application.module(service: GameService = createGameService()) {
    routing {
        get("/api/health") { call.respondText("{\"status\":\"ok\"}", ContentType.Application.Json) }
        post("/api/games") {
            call.api {
                val body = call.body()
                body.fieldsOnly("players")
                val result =
                    withContext(Dispatchers.IO) {
                        service.create(names = body.field("players").array().map(JsonNode::string))
                    }
                call.respondJson(result, HttpStatusCode.Created)
            }
        }
        get("/api/games/{id}") {
            call.api {
                val result =
                    withContext(Dispatchers.IO) {
                        service.get(call.parameters["id"]!!, call.token())
                    }
                call.respondJson(result)
            }
        }
        post("/api/games/{id}/quote-token-purchase") {
            call.api {
                val body = call.body()
                body.fieldsOnly("expectedRevision", "amount")
                val result =
                    withContext(Dispatchers.IO) {
                        service.quoteTokenPurchase(
                            call.parameters["id"]!!,
                            call.token(),
                            body.field("expectedRevision").integer(),
                            body.field("amount").integer(),
                        )
                    }
                call.respondJson(result)
            }
        }
        post("/api/games/{id}/commands") {
            call.api {
                val body = call.body()
                body.fieldsOnly("expectedRevision", "command")
                val command = body.field("command").string()
                if (command.length > 200 || command.contains('\n') || command.contains('\r')) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                val result =
                    withContext(Dispatchers.IO) {
                        service.submit(
                            call.parameters["id"]!!,
                            call.token(),
                            body.field("expectedRevision").integer(),
                            command,
                        )
                    }
                call.respondJson(result)
            }
        }
    }
}

private fun ApplicationCall.token(): String =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ") ?: throw ServiceFailure(401, "UNAUTHORIZED")

private suspend fun ApplicationCall.body() =
    receiveText().let { text ->
        if (text.length > 16_384) throw ServiceFailure(400, "INVALID_REQUEST")
        json.readTree(text) ?: throw ServiceFailure(400, "INVALID_REQUEST")
    }

private suspend fun ApplicationCall.respondJson(
    value: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(json.writeValueAsString(value), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.api(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: RuleViolation) {
        respondJson(
            mapOf("code" to error.code.name, "entityIds" to error.entityIds),
            HttpStatusCode.UnprocessableEntity,
        )
    } catch (error: ServiceFailure) {
        respondJson(mapOf("code" to error.code), HttpStatusCode.fromValue(error.status))
    } catch (_: JsonProcessingException) {
        respondJson(mapOf("code" to "INVALID_REQUEST"), HttpStatusCode.BadRequest)
    }
}
