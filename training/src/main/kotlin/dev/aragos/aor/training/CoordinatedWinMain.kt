package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path

/** Explicit new contract dispatch into the unchanged bounded generation executor. */
object CoordinatedWinMain {
    val modes =
        setOf(
            "coordinated-win-prepare",
            "coordinated-win-controls",
            "coordinated-win-population",
            "coordinated-win-reproduce",
        )

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Continuation arguments must be option/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        val mode = requireNotNull(fields["--mode"]) { "An explicit continuation mode is required." }
        val required =
            setOf("--mode", "--source", "--output") +
                when (mode) {
                    "coordinated-win-prepare" -> emptySet()
                    "coordinated-win-reproduce" ->
                        setOf("--seconds", "--original", "--index", "--role")
                    "coordinated-win-controls",
                    "coordinated-win-population" -> setOf("--seconds", "--workers")
                    else -> error("Only explicit continuation generation modes are admitted.")
                }
        require(fields.size * 2 == arguments.size && fields.keys == required) {
            "Only the mode's exact non-repeating options are admitted."
        }
        val seconds =
            fields["--seconds"]?.let {
                requireNotNull(it.toIntOrNull()) { "Remaining original seconds must be integral." }
                    .also { value ->
                        require(value in 1..86400) {
                            "A continuation stage cannot exceed the original24-hour deadline."
                        }
                    }
            } ?: 0
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val source =
            CoordinatedEsWire.source(
                Path.of(requireNotNull(fields["--source"]) { "Source required." })
            )
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Output required." })
                .toAbsolutePath()
                .normalize()
        require(!source.path.startsWith(output)) { "Output cannot contain the immutable source." }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val input = CoordinatedWinInput.read(source.path)
        fields["--workers"]?.let {
            val expected = if (input.host == QualificationHost.DESKTOP) 10 else 6
            require(it.toIntOrNull() == expected) {
                "The source must retain its exact admitted host worker count."
            }
        }
        CoordinatedEsMain.execute(input, mode, fields, output, seconds, deadline)
    }
}
