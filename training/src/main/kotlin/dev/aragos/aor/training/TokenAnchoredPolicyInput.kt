package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.TokenAnchoredResidual

/** Runtime wrapper keeps all frozen anchor subjects separate from the mutable learned head. */
internal class TokenAnchoredPolicyInput
private constructor(
    val source: TokenPairedSubject,
    val anchor: TokenFrozenAnchor,
    val residualSource: TokenPairedSubject,
    val residual: TokenAnchoredResidual,
) {
    val pins =
        anchor.pins +
            mapOf(
                source.path.toString() to source.sha256,
                residualSource.path.toString() to residualSource.sha256,
            )

    fun controller(reference: SimulationController, ordinary: BotModel): SimulationController =
        TokenAnchoredController(
            reference,
            anchor.qualified(reference, ordinary),
            anchor,
            residual.model,
        )

    companion object {
        const val VERSION = "token-anchored-policy-1"

        fun read(source: TokenPairedSubject, arena: ArenaSpecification): TokenAnchoredPolicyInput {
            source.verify()
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(node, setOf("kind", "anchor", "residual"))
            require(node["kind"]?.asText() == VERSION) {
                "A learned token actor requires its explicit frozen-anchor runtime wrapper."
            }
            val anchor =
                TokenFrozenAnchor.read(
                    TokenPairedSubject.read(node.required("anchor"), source.path.parent),
                    arena,
                )
            val residualSource =
                TokenPairedSubject.read(node.required("residual"), source.path.parent)
            val residual =
                TokenAnchoredResidual(
                    TerritoryPolicyInput.read(residualSource.path),
                    anchor.distribution,
                )
            require(
                residual.model.stateFeatureNames == anchor.bundle.models.token.stateFeatureNames &&
                    residual.model.actionFeatureNames ==
                        anchor.bundle.models.token.actionFeatureNames
            ) {
                "The runtime learned actor must retain the complete frozen TOKEN2 dictionary."
            }
            return TokenAnchoredPolicyInput(source, anchor, residualSource, residual)
        }
    }
}
