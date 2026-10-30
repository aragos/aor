package dev.aragos.aor

import java.nio.file.Files
import java.nio.file.Path

/** Generate the public creation/reference metadata consumed by the browser. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Provide the generated rule-options JSON destination." }
    Files.writeString(
        Path.of(args.single()),
        json.writeValueAsString(RuleOptionView.from(RuleOptions())) + "\n",
    )
}
