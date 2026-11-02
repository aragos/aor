package dev.aragos.aor

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ApplicationCall.gameAccess(hosted: HostedServices?): GameAccess =
    if (hosted == null) {
        GameAccess.SeatToken(token())
    } else {
        withContext(Dispatchers.IO) {
            val identity = hosted.identity(token())
            hosted.account(identity)
            GameAccess.Account(identity.id)
        }
    }

internal fun Route.hostedRoutes(hosted: HostedServices) {
    get("/api/me") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) { hosted.session(hosted.identity(call.token())) }
            )
        }
    }
    post("/api/players/register") {
        call.api {
            val body = call.body()
            body.fieldsOnly("handle")
            call.respondJson(
                withContext(Dispatchers.IO) {
                    val identity = hosted.identity(call.token())
                    if (!identity.emailVerified) throw ServiceFailure(403, "EMAIL_NOT_VERIFIED")
                    hosted.view(
                        hosted.accounts.register(
                            identity.id,
                            body.field("handle").string(),
                        )
                    )
                }
            )
        }
    }
    get("/api/players") {
        call.api {
            val query = call.request.queryParameters["q"].orEmpty().trim()
            if (query.length > 60) throw ServiceFailure(400, "INVALID_REQUEST")
            call.respondJson(
                withContext(Dispatchers.IO) {
                    hosted.account(hosted.identity(call.token()))
                    if (query.length < 2) {
                        emptyList()
                    } else {
                        hosted.accounts.search(query).map(hosted::view)
                    }
                }
            )
        }
    }
    get("/api/games") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) {
                    val account = hosted.account(hosted.identity(call.token()))
                    hosted.administration.list(
                        account.id,
                        when (call.request.queryParameters["scope"]) {
                            null,
                            "own" -> GameListScope.OWN
                            "other" -> GameListScope.OTHER
                            else -> throw ServiceFailure(400, "INVALID_REQUEST")
                        },
                        call.request.queryParameters["status"],
                        call.request.queryParameters["after"],
                        call.pageLimit(),
                        hosted.accounts,
                    )
                }
            )
        }
    }
    get("/api/admin/games") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) {
                    val admin = hosted.requireAdmin(hosted.identity(call.token()))
                    hosted.administration.list(
                        admin.id,
                        GameListScope.ALL,
                        call.request.queryParameters["status"],
                        call.request.queryParameters["after"],
                        call.pageLimit(),
                        hosted.accounts,
                    )
                }
            )
        }
    }
    get("/api/admin/players") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) {
                    hosted.requireAdmin(hosted.identity(call.token()))
                    hosted.accounts.all().sortedBy { it.handle.value }.map(hosted::view)
                }
            )
        }
    }
    post("/api/admin/players/{uid}/creation") {
        call.api {
            val body = call.body()
            body.fieldsOnly("allowed")
            call.respondJson(
                withContext(Dispatchers.IO) {
                    val target =
                        AccountId(
                            checkNotNull(call.parameters["uid"]) {
                                "The route requires an account ID."
                            }
                        )
                    val allowed = body.field("allowed").boolean()
                    hosted.allowCreation(hosted.identity(call.token()), target, allowed)
                }
            )
        }
    }
    get("/api/admin/logs") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) {
                    hosted.requireAdmin(hosted.identity(call.token()))
                    hosted.administration.logs(
                        call.request.queryParameters["after"],
                        call.pageLimit(),
                    )
                }
            )
        }
    }
    get("/api/admin/games/{id}/log") {
        call.api {
            call.respondJson(
                withContext(Dispatchers.IO) {
                    hosted.requireAdmin(hosted.identity(call.token()))
                    hosted.administration.log(
                        call.gameId(),
                        call.request.queryParameters["archive"],
                    )
                }
            )
        }
    }
    post("/api/admin/games/{id}/log") {
        call.api {
            // Authenticate before reading a potentially large administrative payload.
            val admin =
                withContext(Dispatchers.IO) { hosted.requireAdmin(hosted.identity(call.token())) }
            val body = call.body(8 * 1024 * 1024)
            body.fieldsOnly("expectedRevision", "entries", "dryRun")
            call.respondJson(
                withContext(Dispatchers.IO) {
                    val expected = body.field("expectedRevision").integer()
                    val entries = body.field("entries").array().map { it.string() }
                    if (body["dryRun"]?.boolean() == true) {
                        hosted.administration.validateLog(
                            call.gameId(),
                            admin.id,
                            expected,
                            entries,
                        )
                    } else {
                        hosted.administration.replaceLog(call.gameId(), admin.id, expected, entries)
                    }
                }
            )
        }
    }
    post("/api/admin/games/{id}/status") {
        call.api {
            val admin =
                withContext(Dispatchers.IO) { hosted.requireAdmin(hosted.identity(call.token())) }
            val body = call.body()
            body.fieldsOnly("expectedRevision", "action")
            val action =
                when (body.field("action").string()) {
                    "cancel" -> AdministrativeAction.CANCEL
                    "abandon" -> AdministrativeAction.ABANDON
                    else -> throw ServiceFailure(400, "INVALID_REQUEST")
                }
            call.respondJson(
                withContext(Dispatchers.IO) {
                    hosted.administration.changeStatus(
                        call.gameId(),
                        admin.id,
                        body.field("expectedRevision").integer(),
                        action,
                    )
                }
            )
        }
    }
    post("/api/admin/games/{id}/purge") {
        call.api {
            val admin =
                withContext(Dispatchers.IO) { hosted.requireAdmin(hosted.identity(call.token())) }
            val body = call.body()
            body.fieldsOnly("expectedRevision")
            withContext(Dispatchers.IO) {
                hosted.administration.purge(
                    call.gameId(),
                    admin.id,
                    body.field("expectedRevision").integer(),
                )
            }
            call.respondJson(mapOf("purged" to call.gameId()))
        }
    }
}

private fun ApplicationCall.pageLimit(): Int =
    request.queryParameters["limit"]?.let {
        it.toIntOrNull() ?: throw ServiceFailure(400, "INVALID_REQUEST")
    } ?: 50

private fun ApplicationCall.gameId(): String =
    checkNotNull(parameters["id"]) { "The route requires a game ID." }
