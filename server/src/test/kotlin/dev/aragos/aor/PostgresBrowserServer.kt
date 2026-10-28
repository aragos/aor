package dev.aragos.aor

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Isolated browser backend exercises the production PostgreSQL factory without public auth. */
fun main() {
    val fixture = PostgresFixture()
    val service = createConfiguredGameService(fixture.environment)
    val port =
        checkNotNull(System.getenv("AOR_HTTP_PORT")?.toIntOrNull()) {
            "The browser runner must supply its isolated HTTP port."
        }
    val server =
        embeddedServer(Netty, port = port, host = "127.0.0.1") { module(service, hosted = null) }
    Runtime.getRuntime()
        .addShutdownHook(
            Thread {
                server.stop(100, 2000)
                runCatching { fixture.close() }
            }
        )
    server.start(wait = true)
}
