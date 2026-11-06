package dev.aragos.aor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() {
    val port =
        (System.getenv("AOR_HTTP_PORT") ?: System.getenv("PORT"))?.let { configured ->
            requireNotNull(configured.toIntOrNull()) {
                "AOR_HTTP_PORT must be a whole-number port."
            }
        } ?: 8080
    require(port in 1..65535) { "AOR_HTTP_PORT must be between one and 65535." }
    val host = System.getenv("AOR_HTTP_HOST") ?: "127.0.0.1"
    require(
        host in setOf("127.0.0.1", "localhost", "::1") ||
            System.getenv("AOR_AUTH_MODE") == "firebase"
    ) {
        "Public listening requires Firebase authentication; local mode must stay on loopback."
    }
    embeddedServer(Netty, port = port, host = host, module = { module() }).start(wait = true)
}

fun Application.module(
    service: GameService = createConfiguredGameService(),
    hosted: HostedServices? = createHostedServices(service),
) {
    val gameService = hosted?.games ?: service
    install(DefaultHeaders) {
        header("Server", "aor")
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "same-origin")
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' https://apis.google.com; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.googleapis.com; frame-src https://*.firebaseapp.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
        )
        if (hosted != null) header("Strict-Transport-Security", "max-age=31536000")
    }
    install(RequestBodyLimit) {
        bodyLimit { call ->
            when {
                call.request.path().startsWith("/api/admin/games/") &&
                    call.request.path().endsWith("/log") -> 8L * 1024 * 1024
                call.request.path().endsWith("/history") -> 524288L
                call.request.path().contains("/plans") -> 65536L
                else -> 16384L
            }
        }
    }
    install(Compression) {
        mode = CompressionConfig.Mode.CompressResponse
        gzip {
            minimumSize(1024)
            condition {
                !request.path().startsWith("/api/") ||
                    request.headers["Sec-Fetch-Site"] == "same-origin"
            }
        }
    }
    val planWorker =
        launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    gameService.recoverPlans { id, failure ->
                        log.warn(
                            "Plan recovery failed for {} ({})",
                            id,
                            (failure as? ServiceFailure)?.code ?: failure.javaClass.simpleName,
                            failure,
                        )
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    log.warn(
                        "Plan recovery directory unavailable ({})",
                        (failure as? ServiceFailure)?.code ?: failure.javaClass.simpleName,
                        failure,
                    )
                }
                delay(5000)
            }
        }
    monitor.subscribe(ApplicationStopping) { planWorker.cancel() }
    monitor.subscribe(ApplicationStopped) {
        planWorker.cancel()
        (hosted?.verifier as? AutoCloseable)?.close()
        (gameService.store as? AutoCloseable)?.close()
    }

    routing {
        get("/api/config") {
            call.respondJson(hosted?.publicConfig ?: PublicApplicationConfig("local", null, 1000))
        }
        get("/api/reference") {
            call.respondJson(
                mapOf(
                    "catalog" to PublicCatalog.forPlayers(6),
                    "cards" to CardData.all,
                    "leaders" to LeaderData.definitions,
                    "rules" to RuleOptions(),
                    "ruleOptions" to RuleOptionView.from(RuleOptions()),
                )
            )
        }
        if (hosted != null) hostedRoutes(hosted)
        botRoutes(gameService, hosted)
        get("/api/health") {
            call.api {
                withContext(Dispatchers.IO) {
                    (gameService.store as? PostgresGameStore)?.database?.read { connection ->
                        connection.createStatement().use { it.execute("SELECT 1") }
                    }
                }
                call.respondJson(mapOf("status" to "ok"))
            }
        }
        post("/api/games") {
            call.api {
                val body = call.body()
                body.fieldsOnly("players", "bots", "options", "undoPolicy", "name")
                val bots = body.automatedPlayers()
                val name =
                    try {
                        body["name"]?.let { GameName.parse(it.string()) } ?: GameName.DEFAULT
                    } catch (_: IllegalArgumentException) {
                        throw ServiceFailure(400, "INVALID_GAME_NAME")
                    }
                val undoPolicy =
                    body["undoPolicy"]?.let { UndoPolicy.parse(it.string()) }
                        ?: UndoPolicy.REGULAR_TURN
                if (undoPolicy == UndoPolicy.FRIENDLY_GAME)
                    throw ServiceFailure(400, "INVALID_UNDO_POLICY")
                val options = body["options"]
                val rules =
                    if (options == null) {
                        RuleOptions()
                    } else {
                        if (!options.isObject || options.size() > 4) {
                            throw ServiceFailure(400, "INVALID_REQUEST")
                        }
                        options.properties().fold(RuleOptions()) { configured, (key, value) ->
                            RuleOptionCommand(key, value.string()).resolve(configured)
                        }
                    }
                val result =
                    withContext(Dispatchers.IO) {
                        val players = body.field("players").array().map(JsonNode::string)
                        if (hosted == null) {
                            gameService.create(players, rules, undoPolicy, name, bots)
                        } else {
                            hosted.create(
                                hosted.identity(call.token()),
                                players,
                                rules,
                                undoPolicy,
                                name,
                                bots,
                            )
                        }
                    }
                call.respondJson(result, HttpStatusCode.Created)
            }
        }
        get("/api/games/{id}") {
            call.api {
                val result =
                    withContext(Dispatchers.IO) {
                        gameService.read(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            call.request.headers[HttpHeaders.IfNoneMatch],
                        )
                    }
                call.response.header(HttpHeaders.ETag, result.etag)
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.Vary, "Authorization")
                if (result.snapshot == null) {
                    call.respond(HttpStatusCode.NotModified)
                } else {
                    call.respondJson(result.snapshot)
                }
            }
        }
        post("/api/games/{id}/abandonment") {
            call.api {
                val body = call.body()
                body.fieldsOnly("operation", "expectedRevision", "operationId", "requestId")
                val operation = runCatching {
                    AbandonmentOperation.valueOf(body.field("operation").string().uppercase())
                }
                    .getOrElse { throw ServiceFailure(400, "INVALID_REQUEST") }
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.abandonment(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            body.field("expectedRevision").integer(),
                            body.field("operationId").string(),
                            operation,
                            body["requestId"]?.takeUnless(JsonNode::isNull)?.string(),
                        )
                    }
                )
            }
        }
        get("/api/games/{id}/rewind-targets") {
            call.api {
                val expected =
                    call.request.queryParameters["expectedRevision"]?.toIntOrNull()
                        ?: throw ServiceFailure(400, "INVALID_REQUEST")
                val before =
                    call.request.queryParameters["before"]?.let {
                        it.toIntOrNull() ?: throw ServiceFailure(400, "INVALID_REQUEST")
                    }
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.rewindTargets(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            expected,
                            before,
                        )
                    }
                )
            }
        }
        get("/api/games/{id}/revisions/{revision}") {
            call.api {
                val revision =
                    call.parameters["revision"]?.toIntOrNull()
                        ?: throw ServiceFailure(400, "INVALID_REQUEST")
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.getRevision(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            revision,
                        )
                    }
                )
            }
        }
        post("/api/games/{id}/history") {
            call.api {
                val body = call.body(524288)
                body.fieldsOnly("expectedRevision", "fromEntry", "lines")
                val lines = body.field("lines").array().map(JsonNode::string)
                if (
                    lines.size > 10000 || lines.any { it.length > 200 || '\n' in it || '\r' in it }
                ) {
                    throw ServiceFailure(400, "INVALID_HISTORY_EDIT")
                }
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.editHistory(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            body.field("expectedRevision").integer(),
                            body.field("fromEntry").integer(),
                            lines,
                        )
                    }
                )
            }
        }
        post("/api/games/{id}/quote-token-purchase") {
            call.api {
                val body = call.body()
                body.fieldsOnly("expectedRevision", "amount")
                val result =
                    withContext(Dispatchers.IO) {
                        gameService.quoteTokenPurchase(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
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
                body.fieldsOnly(
                    "expectedRevision",
                    "command",
                    "expectedSubmissionVersion",
                    "operationId",
                )
                val command = body.field("command").string()
                if (command.length > 200 || command.contains('\n') || command.contains('\r')) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                val result =
                    withContext(Dispatchers.IO) {
                        gameService.submit(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            body.field("expectedRevision").integer(),
                            command,
                            body["expectedSubmissionVersion"]?.takeUnless { it.isNull }?.string(),
                            body["operationId"]?.takeUnless { it.isNull }?.string(),
                        )
                    }
                call.respondJson(result)
            }
        }
        post("/api/games/{id}/plans/preview") {
            call.api {
                val body = call.body(65536)
                body.fieldsOnly("expectedRevision", "actions")
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.previewPlan(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            body.field("expectedRevision").integer(),
                            body.plannedActions(),
                        )
                    }
                )
            }
        }
        post("/api/games/{id}/plans") {
            call.api {
                val body = call.body(65536)
                body.fieldsOnly(
                    "expectedRevision",
                    "expectedVersion",
                    "operationId",
                    "operation",
                    "actions",
                    "previewToken",
                )
                call.respondJson(
                    withContext(Dispatchers.IO) {
                        gameService.writePlan(
                            call.parameters["id"]!!,
                            call.gameAccess(hosted),
                            body.field("expectedRevision").integer(),
                            body["expectedVersion"]?.takeUnless(JsonNode::isNull)?.string(),
                            body.field("operationId").string(),
                            PlanOperation.valueOf(body.field("operation").string()),
                            body.plannedActions(),
                            body["previewToken"]?.takeUnless(JsonNode::isNull)?.string(),
                        )
                    }
                )
            }
        }
        System.getenv("AOR_WEB_DIR")?.let { directory ->
            val web = File(directory)
            require(File(web, "index.html").isFile) {
                "AOR_WEB_DIR must contain the built web client."
            }
            staticFiles("/assets", File(web, "assets")) {
                modify { _, call ->
                    call.response.header(
                        HttpHeaders.CacheControl,
                        "public, max-age=31536000, immutable",
                    )
                }
            }
            get("/") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondFile(File(web, "index.html"))
            }
            get("/games/{id}") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondFile(File(web, "index.html"))
            }
            get("/help") {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondFile(File(web, "index.html"))
            }
        }
    }
}

private fun JsonNode.plannedActions(): List<PlannedAction> {
    val actions = get("actions")?.array().orEmpty()
    if (actions.size > 100) throw ServiceFailure(400, "INVALID_REQUEST")
    return actions.map { action ->
        action.fieldsOnly("decision", "command")
        PlannedAction(action.field("decision").string(), action.field("command").string())
    }
}

internal fun ApplicationCall.token(): String =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ") ?: throw ServiceFailure(401, "UNAUTHORIZED")

internal suspend fun ApplicationCall.body(maxLength: Int = 16_384) =
    receiveText().let { text ->
        if (text.length > maxLength) throw ServiceFailure(400, "INVALID_REQUEST")
        json.readTree(text) ?: throw ServiceFailure(400, "INVALID_REQUEST")
    }

internal suspend fun ApplicationCall.respondJson(
    value: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(json.writeValueAsString(value), ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.api(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: RuleViolation) {
        respondJson(
            error.diagnostic,
            HttpStatusCode.UnprocessableEntity,
        )
    } catch (error: ServiceFailure) {
        respondJson(
            mapOf(
                "code" to error.code,
                "entryIndex" to error.entryIndex,
                "entityIds" to error.diagnostic?.entityIds.orEmpty(),
                "entities" to error.diagnostic?.entities.orEmpty(),
                "parameters" to error.diagnostic?.parameters.orEmpty(),
                "diagnostic" to error.diagnostic,
            ),
            HttpStatusCode.fromValue(error.status),
        )
    } catch (_: IllegalArgumentException) {
        respondJson(mapOf("code" to "INVALID_REQUEST"), HttpStatusCode.BadRequest)
    } catch (_: JsonProcessingException) {
        respondJson(mapOf("code" to "INVALID_REQUEST"), HttpStatusCode.BadRequest)
    } catch (_: IOException) {
        respondJson(mapOf("code" to "STORAGE_UNAVAILABLE"), HttpStatusCode.ServiceUnavailable)
    }
}
