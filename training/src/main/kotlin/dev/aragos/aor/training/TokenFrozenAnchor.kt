package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.JointSupportProfile
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.TokenAnchorDistribution
import dev.aragos.aor.ai.TokenAnchorSupport

/**
 * Frozen qualified94 parameters and its original4a/calibration remain distinct from the learner.
 */
internal class TokenFrozenAnchor
private constructor(
    val source: TokenPairedSubject,
    val asset: CoordinatedInferenceAsset,
    val bundle: CoordinatedEsBundle,
    val sampler: NearOptimalTokenSampler,
    val distribution: TokenAnchorDistribution,
) {
    val pins =
        asset.pins +
            mapOf(source.path.toString() to source.sha256) +
            mapOf(bundle.source.path.toString() to bundle.source.sha256) +
            CoordinatedEsHead.entries.associate { head ->
                bundle.subject(head).let { it.path.toString() to it.sha256 }
            }

    val identity: Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "sourceSha256" to source.sha256,
            "inferenceAssetSha256" to asset.source.sha256,
            "anchorBundleSha256" to bundle.source.sha256,
            "anchorHeads" to bundle.identity(),
            "originalBundleSha256" to asset.original.source.sha256,
            "calibrationSha256" to asset.calibration.source.sha256,
            "sampler" to
                mapOf(
                    "kind" to NearOptimalTokenSampler.VERSION,
                    "temperature" to sampler.temperature,
                    "maxScoreGap" to sampler.maxScoreGap,
                    "neighborRadius" to sampler.neighborRadius,
                ),
            "distribution" to
                mapOf(
                    "kind" to TokenAnchorDistribution.VERSION,
                    "epsilon" to distribution.epsilon,
                    "temperature" to distribution.temperature,
                    "support" to distribution.support.name,
                ),
        )

    fun verify() {
        pins.forEach { (path, hash) ->
            require(PurchaseJson.hash(java.nio.file.Path.of(path)) == hash) {
                "A frozen token anchor input changed: $path."
            }
        }
    }

    fun qualified(reference: SimulationController, ordinary: BotModel): CoordinatedEsController =
        CoordinatedEsController(
            reference,
            ordinary,
            asset.baseline.model,
            bundle.models,
            asset.original.models,
            asset.calibration.selectors,
            JointSupportProfile.KNOWN_OWN_CHAOS_1,
            sampler,
        )

    companion object {
        const val VERSION = "token-frozen-anchor-1"
        const val QUALIFIED_94 = "fca8593a5d43026d1c7146a9bb76cbce5bfd1a725de28ba18382d537710ff63e"
        const val FROZEN_ASSET = "c10357cc5ef3350628076a7b5a66eca06dc6038584bb7b3b2e519ff084874b1a"

        fun read(source: TokenPairedSubject, arena: ArenaSpecification): TokenFrozenAnchor {
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf("kind", "inferenceAsset", "anchorBundle", "sampler", "distribution"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "Use the explicit frozen94 token anchor descriptor."
            }
            val assetSubject =
                TokenPairedSubject.read(node.required("inferenceAsset"), source.path.parent)
            require(assetSubject.sha256 == FROZEN_ASSET) {
                "Version1 preserves the exact qualified94 reference and calibration closure."
            }
            val asset =
                CoordinatedInferenceAsset.read(
                    assetSubject,
                    arena.configurations.single(),
                )
            val bundleSubject =
                TokenPairedSubject.read(node.required("anchorBundle"), source.path.parent)
            require(bundleSubject.sha256 == QUALIFIED_94) {
                "Version1 anchors the exact immutable qualified94 bundle."
            }
            val bundle = CoordinatedEsBundle.read(bundleSubject, asset.original.dictionaries)
            bundle.models.validateFrozenValues(asset.original.models)
            asset.original.validateOriginal(arena, asset.baseline)
            val samplerNode = node.required("sampler")
            PurchaseJson.fields(
                samplerNode,
                setOf("kind", "temperature", "maxScoreGap", "neighborRadius"),
            )
            require(
                samplerNode["kind"]?.asText() == NearOptimalTokenSampler.VERSION &&
                    samplerNode["temperature"]?.doubleValue() == 0.35 &&
                    samplerNode["maxScoreGap"]?.doubleValue() == 3.0 &&
                    PurchaseJson.integer(samplerNode["neighborRadius"], "anchor radius") == 4
            ) {
                "Version1 retains the exact C05 frozen anchor sampler."
            }
            val distributionNode = node.required("distribution")
            PurchaseJson.fields(
                distributionNode,
                setOf("kind", "epsilon", "temperature", "support"),
            )
            require(
                distributionNode["kind"]?.asText() == TokenAnchorDistribution.VERSION &&
                    distributionNode["support"]?.asText() ==
                        TokenAnchorSupport.ORIGINAL_AFFORDABLE_OFFERS.name &&
                    distributionNode["epsilon"].isNumber &&
                    distributionNode["temperature"].isNumber
            ) {
                "Declare the explicit offered-only anchor-floor distribution and numeric units."
            }
            return TokenFrozenAnchor(
                    source,
                    asset,
                    bundle,
                    NearOptimalTokenSampler(0.35, 3.0, 4),
                    TokenAnchorDistribution(
                        epsilon = distributionNode["epsilon"].doubleValue(),
                        temperature = distributionNode["temperature"].doubleValue(),
                    ),
                )
                .also(TokenFrozenAnchor::verify)
        }
    }
}
