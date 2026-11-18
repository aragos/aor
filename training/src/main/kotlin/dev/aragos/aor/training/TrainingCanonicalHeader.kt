package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameHeader
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId

/** Exact native canonical-header boundary, including the historical Renaissance activation turn. */
internal object TrainingCanonicalHeader {
    fun read(node: JsonNode): GameHeader {
        val header =
            GameHeader(
                players =
                    node.required("players").map {
                        Player(
                            PlayerId(it.required("id").required("value").asText()),
                            it.required("name").asText(),
                        )
                    },
                creatorId = PlayerId(node.required("creatorId").required("value").asText()),
                rulesVersion = node.required("rulesVersion").asText(),
                dataVersion = node.required("dataVersion").asText(),
                drawRenaissanceFromTurn =
                    PurchaseJson.integer(
                        node.required("drawRenaissanceFromTurn"),
                        "Renaissance activation turn",
                    ),
            )
        require(
            PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(header)) == node
        ) {
            "All original canonical header fields must survive typed decoding exactly."
        }
        return header
    }
}
