package dev.aragos.aor.training

import java.nio.file.Path

/** Explicit complete C/P/E screen: separately admitted, sharing only fixed execution mechanics. */
internal object CpePairedScreenMain {
    private val delegate =
        ThreeRoleScreenMain(
            "cpe-paired",
            CpePairedScreenRole.entries.map { it.name },
            "RAW_G3",
            "RawG3",
            64800,
            CpePairedScreenInput::read,
            CpePairedHistorical::run,
        )
    val modes = delegate.modes

    fun run(arguments: List<String>) = delegate.run(arguments)

    internal fun compactReport(source: Path, output: Path) = delegate.compactReport(source, output)
}
