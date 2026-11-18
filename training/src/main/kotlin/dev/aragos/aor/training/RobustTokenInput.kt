package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.PublicLeaderAttackConfig
import dev.aragos.aor.ai.TokenNeighborAdmission
import java.nio.file.Path

/** Public-state opponent interventions around each rival's actual qualified token command. */
internal enum class RobustOpponentStyle {
    QUALIFIED,
    UNDERCUT,
    OVERBID,
    MIXED,
    BALANCED;

    fun at(index: Int, seat: Int): RobustOpponentStyle =
        if (this == BALANCED) entries[(index / 4 + seat) % 4] else this
}

/** A bounded paired shard; the source owns all streams and immutable inference subjects. */
internal class RobustTokenInput
private constructor(
    val source: TokenPairedSubject,
    val protocolSha256: String,
    val asset: CoordinatedInferenceAsset,
    val nomination: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val arena: ArenaSpecification,
    val candidate: CoordinatedEsBundle,
    val sampler: NearOptimalTokenSampler?,
    val control: CoordinatedEsBundle,
    val controlSampler: NearOptimalTokenSampler?,
    val expansionDenial: PublicLeaderAttackConfig?,
    val tokenNeighborMixture: LocalTokenNeighborMixture?,
    val opponentStyle: RobustOpponentStyle,
    val seeds: List<Int>,
    val start: Int,
    val count: Int,
    val replayIndices: List<Int>,
    /** Experimental population collection only; paired qualification sources omit this flag. */
    val candidateOnly: Boolean,
    val pins: Map<String, String>,
) {
    val indices = start until start + count
    val ownedReplays = replayIndices.filter { it in indices }
    val roles = if (candidateOnly) listOf("CANDIDATE") else listOf("CANDIDATE", "CONTROL")
    val maximumTerminalExecutions = (count + ownedReplays.size) * roles.size
    val selectorIdentity = samplerIdentity(sampler)
    val scienceIdentity: Map<String, Any?> =
        mapOf(
            "kind" to "robust-token-science-1",
            "protocolSha256" to protocolSha256,
            "inferenceAssetSha256" to asset.source.sha256,
            "nominationSha256" to nomination.sha256,
            "candidateBundleSha256" to candidate.source.sha256,
            "controlAndRivalsBundleSha256" to control.source.sha256,
            "originalBundleSha256" to asset.original.source.sha256,
            "calibrationSha256" to asset.calibration.source.sha256,
            "configuration" to arena.configurations.single().id,
            "selector" to selectorIdentity,
            "opponentStyle" to opponentStyle.name,
            "styleRotation" to "index-div4-plus-seat-mod4-1",
            "seeds" to seeds,
            "replayIndices" to replayIndices,
            "roles" to roles,
        ) +
            (controlSampler?.let { mapOf("controlSelector" to samplerIdentity(it)) }
                ?: emptyMap()) +
            (expansionDenial?.let {
                mapOf(
                    "expansionDenial" to
                        mapOf(
                            "kind" to PublicLeaderAttackConfig.VERSION,
                            "beta" to it.beta,
                            "minimumWinProbability" to it.minimumWinProbability,
                        )
                )
            } ?: emptyMap()) +
            (tokenNeighborMixture?.let {
                mapOf(
                    "tokenNeighborMixture" to
                        mapOf(
                            "kind" to LocalTokenNeighborMixture.VERSION,
                            "departureProbability" to it.departureProbability,
                            "radius" to it.radius,
                            "admission" to it.admission.name,
                        )
                )
            } ?: emptyMap())
    val scienceSha256 = CpePairedDigest.json(scienceIdentity)

    fun verify() {
        pins.forEach { (path, digest) ->
            require(PurchaseJson.hash(Path.of(path)) == digest) {
                "Frozen robust-token input changed: $path."
            }
        }
    }

    fun prepared(): Map<String, Any?> =
        mapOf(
            "kind" to "robust-token-prepared-1",
            "passed" to true,
            "sourceSha256" to source.sha256,
            "scienceIdentity" to scienceIdentity,
            "scienceSha256" to scienceSha256,
            "inputPins" to pins,
            "globalStart" to start,
            "count" to count,
            "ownedReplayIndices" to ownedReplays,
            "maximumTerminalExecutions" to maximumTerminalExecutions,
            "roles" to roles,
            "candidateOnly" to candidateOnly,
            "newGames" to 0,
            "modelForwards" to 0,
            "experimentalCandidateOverride" to
                (candidate.source.sha256 != asset.current.source.sha256),
            "qualificationClaimed" to false,
        )

    companion object {
        private fun neighborMixture(node: JsonNode): LocalTokenNeighborMixture? {
            if (node.isNull) return null
            PurchaseJson.fields(node, setOf("kind", "departureProbability", "radius", "admission"))
            require(
                node["kind"]?.asText() == LocalTokenNeighborMixture.VERSION &&
                    node["departureProbability"].isNumber
            ) {
                "Declare the typed local token mixture and numeric departure probability."
            }
            val admission =
                requireNotNull(
                    TokenNeighborAdmission.entries.singleOrNull {
                        it.name == node["admission"]?.asText()
                    }
                ) {
                    "Declare one typed local token neighbor admission."
                }
            return LocalTokenNeighborMixture(
                node["departureProbability"].doubleValue(),
                PurchaseJson.integer(node["radius"], "local token radius"),
                admission,
            )
        }

        private fun samplerIdentity(value: NearOptimalTokenSampler?): Map<String, Any>? =
            value?.let {
                mapOf(
                    "kind" to NearOptimalTokenSampler.VERSION,
                    "temperature" to it.temperature,
                    "maxScoreGap" to it.maxScoreGap,
                    "neighborRadius" to it.neighborRadius,
                )
            }

        private fun denial(node: JsonNode): PublicLeaderAttackConfig? {
            if (node.isNull) return null
            PurchaseJson.fields(node, setOf("kind", "beta", "minimumWinProbability"))
            require(
                node["kind"]?.asText() == PublicLeaderAttackConfig.VERSION &&
                    node["beta"].isNumber &&
                    node["minimumWinProbability"].isNumber
            ) {
                "Declare the typed public-leader bonus and its numeric probability threshold."
            }
            return PublicLeaderAttackConfig(
                node["beta"].doubleValue(),
                node["minimumWinProbability"].doubleValue(),
            )
        }

        fun sampler(node: JsonNode): NearOptimalTokenSampler? {
            if (node.isNull) return null
            PurchaseJson.fields(node, setOf("kind", "temperature", "maxScoreGap", "neighborRadius"))
            require(node["kind"]?.asText() == NearOptimalTokenSampler.VERSION) {
                "Declare the explicit near-optimal token sampler version."
            }
            require(node["temperature"].isNumber && node["maxScoreGap"].isNumber) {
                "Sampler temperature and score gap require JSON numbers."
            }
            return NearOptimalTokenSampler(
                node["temperature"].doubleValue(),
                node["maxScoreGap"].doubleValue(),
                PurchaseJson.integer(node["neighborRadius"], "token radius"),
            )
        }

        fun read(path: Path): RobustTokenInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "protocolSha256",
                    "inferenceAsset",
                    "nomination",
                    "arena",
                    "selector",
                    "opponentStyle",
                    "seeds",
                    "globalStart",
                    "count",
                    "replayIndices",
                ),
                setOf(
                    "candidateBundle",
                    "candidateOnly",
                    "controlBundle",
                    "controlSelector",
                    "expansionDenial",
                    "tokenNeighborMixture",
                ),
            )
            require(node["kind"]?.asText() == "robust-token-chunk-1") {
                "Use the bounded robust-token paired source contract."
            }
            val protocol = PurchaseJson.text(node["protocolSha256"], "protocol hash")
            require(protocol.matches(Regex("[0-9a-f]{64}"))) {
                "The robust source must bind its prospectively fixed protocol hash."
            }
            fun subject(name: String) =
                TokenPairedSubject.read(node.required(name), source.path.parent)
            val arenaSource = subject("arena")
            val parsed = ArenaSpecification.read(arenaSource.path)
            val asset =
                CoordinatedInferenceAsset.read(
                    subject("inferenceAsset"),
                    parsed.configurations.single(),
                )
            // The old homogeneous descriptor supplies frozen reference composition only. New
            // worlds use the separately admitted source seeds; its historical arena seeds are
            // unused.
            val arena =
                CoordinatedInferenceEcology.read(
                    arenaSource,
                    asset,
                    CoordinatedQualificationBank(
                        parsed.seed,
                        List(4096) { StrategyArena.seed(parsed.seed, it) },
                    ),
                    "homogeneous",
                )
            val nomination = subject("nomination")
            val nominationPins = CoordinatedPolicyNomination.read(nomination, asset.source)
            val candidate =
                node["candidateBundle"]?.let {
                    CoordinatedEsBundle.read(
                        TokenPairedSubject.read(it, source.path.parent),
                        CoordinatedEsBundle.dictionaries(arena.configurations.single()),
                    )
                } ?: asset.current
            candidate.models.validateFrozenValues(asset.original.models)
            val control =
                node["controlBundle"]?.let {
                    CoordinatedEsBundle.read(
                        TokenPairedSubject.read(it, source.path.parent),
                        CoordinatedEsBundle.dictionaries(arena.configurations.single()),
                    )
                } ?: asset.current
            control.models.validateFrozenValues(asset.original.models)
            val candidateSampler = sampler(node.required("selector"))
            val controlSampler = node["controlSelector"]?.let(::sampler)
            val expansionDenial = node["expansionDenial"]?.let(::denial)
            val tokenNeighborMixture = node["tokenNeighborMixture"]?.let(::neighborMixture)
            val seeds = node.required("seeds").map { PurchaseJson.integer(it, "world seed") }
            require(seeds.size in 1..16384 && seeds.distinct().size == seeds.size) {
                "A robust bank requires 1–16384 distinct prospectively fixed world seeds."
            }
            val start = PurchaseJson.integer(node["globalStart"], "global start")
            val count = PurchaseJson.integer(node["count"], "world count")
            require(start >= 0 && count in 1..256 && start.toLong() + count <= seeds.size) {
                "Each robust shard must contain 1–256 original bank indices."
            }
            val replays =
                node.required("replayIndices").map { PurchaseJson.integer(it, "replay index") }
            require(
                replays.size <= 16 &&
                    replays.distinct().size == replays.size &&
                    replays.all { it in seeds.indices }
            ) {
                "At most 16 distinct prescribed original worlds may reproduce."
            }
            val style =
                requireNotNull(
                    RobustOpponentStyle.entries.singleOrNull {
                        it.name == node["opponentStyle"]?.asText()
                    }
                ) {
                    "Declare one typed robust opponent ecology."
                }
            val candidateOnly =
                node["candidateOnly"]?.let {
                    require(it.isBoolean) {
                        "Candidate-only collection requires an explicit JSON boolean."
                    }
                    it.booleanValue()
                } ?: false
            require(
                expansionDenial == null ||
                    !candidateOnly &&
                        candidate.source.sha256 == control.source.sha256 &&
                        candidateSampler == controlSampler
            ) {
                "The leader-denial mechanism requires paired identical model and token policies; only expansion may differ."
            }
            require(tokenNeighborMixture == null || !candidateOnly && expansionDenial == null) {
                "Local token neighbor experiments require paired roles and no expansion intervention."
            }
            val pins =
                asset.pins +
                    nominationPins +
                    CoordinatedWinEcology.pins(arenaSource, arena) +
                    (listOf(candidate.source) + candidate.subjects.values).associate {
                        it.path.toString() to it.sha256
                    } +
                    (listOf(control.source) + control.subjects.values).associate {
                        it.path.toString() to it.sha256
                    } +
                    mapOf(source.path.toString() to source.sha256)
            return RobustTokenInput(
                    source,
                    protocol,
                    asset,
                    nomination,
                    arenaSource,
                    arena,
                    candidate,
                    candidateSampler,
                    control,
                    controlSampler,
                    expansionDenial,
                    tokenNeighborMixture,
                    style,
                    seeds,
                    start,
                    count,
                    replays,
                    candidateOnly,
                    pins,
                )
                .also(RobustTokenInput::verify)
        }
    }
}
