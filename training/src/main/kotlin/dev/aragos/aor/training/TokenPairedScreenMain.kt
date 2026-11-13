package dev.aragos.aor.training

import java.nio.file.Path

/** The original TOKEN six-hour screen dispatch and wire remain unchanged. */
internal object TokenPairedScreenMain {
    private val delegate =
        ThreeRoleScreenMain(
            "token-paired",
            TokenPairedScreenRole.entries.map { it.name },
            "RAW_T",
            "RawT",
            21600,
            TokenPairedScreenInput::read,
            TokenPairedHistorical::run,
        )
    val modes = delegate.modes

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)

    internal fun worldName(index: Int) = delegate.worldName(index)
}
