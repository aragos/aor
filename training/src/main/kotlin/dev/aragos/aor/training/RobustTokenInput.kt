package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.CommodityTimingConfig
import dev.aragos.aor.ai.InitiativePolicyConfig
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.PublicLeaderAttackConfig
import dev.aragos.aor.ai.TokenNeighborAdmission
import java.nio.file.Path

/** Own-baseline interventions and explicit learning from another seat's public revealed bids. */
internal enum class RobustOpponentStyle {
    QUALIFIED,
    UNDERCUT,
    OVERBID,
    MIXED,
    BALANCED,
    REVEALED_UNDERCUT,
    REVEALED_OVERBID,
    REVEALED_TIE_UNDERCUT,
    REVEALED_TIE_OVERBID,
    REVEALED_BALANCED,
    QUALIFIED_REVEALED_BALANCED;

    val isRevealedCounter: Boolean
        get() =
            when (this) {
                REVEALED_UNDERCUT,
                REVEALED_OVERBID,
                REVEALED_TIE_UNDERCUT,
                REVEALED_TIE_OVERBID,
                REVEALED_BALANCED,
                QUALIFIED_REVEALED_BALANCED -> true
                else -> false
            }

    val isTieAware: Boolean
        get() = this == REVEALED_TIE_UNDERCUT || this == REVEALED_TIE_OVERBID

    val countersEarlier: Boolean
        get() = this == REVEALED_UNDERCUT || this == REVEALED_TIE_UNDERCUT

    fun at(index: Int, seat: Int): RobustOpponentStyle =
        when (this) {
            BALANCED -> entries[(index / 4 + seat) % 4]
            REVEALED_BALANCED ->
                listOf(
                    REVEALED_UNDERCUT,
                    REVEALED_OVERBID,
                    REVEALED_TIE_UNDERCUT,
                    REVEALED_TIE_OVERBID,
                )[(index / 4 + seat) % 4]
            QUALIFIED_REVEALED_BALANCED -> {
                val cell = (index / 4 + seat) % 8
                if (cell < 4) {
                    QUALIFIED
                } else {
                    listOf(
                        REVEALED_UNDERCUT,
                        REVEALED_OVERBID,
                        REVEALED_TIE_UNDERCUT,
                        REVEALED_TIE_OVERBID,
                    )[cell - 4]
                }
            }
            else -> this
        }
}

/** One frozen independent opponent in a declared registration-relative seat. */
internal data class InitiativeRival(val offsets: List<Int>, val policy: InitiativePolicyConfig) {
    init {
        require(
            offsets.isNotEmpty() &&
                offsets == offsets.distinct().sorted() &&
                offsets.all { it in 1..3 }
        ) {
            "The initiative lineup requires distinct registration-relative rival seats."
        }
    }

    fun seats(targetSeat: Int): List<Int> = offsets.map { (targetSeat + it) % 4 }

    companion object {
        const val VERSION = "public-initiative-rival-1"
        const val LINEUP = "public-initiative-lineup-1"
    }
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
    val adaptiveOpponent: RevealedTokenCounterConfig?,
    val candidateTokenAnchoredPolicy: TokenAnchoredPolicyInput?,
    val initiative: InitiativePolicyConfig?,
    val initiativeRival: InitiativeRival?,
    val commodityTiming: CommodityTimingConfig?,
    val commodityRollouts: Boolean,
    val commodityExpansionRollouts: Boolean,
    val midgameBidRollouts: Boolean,
    val rivalMarketDenialRollouts: Boolean,
    val reserveExpansionCardRollouts: Boolean,
    val capitalChoiceRollouts: Boolean,
    val leaderProtectionRollouts: Boolean,
    val nextTurnBaseRollouts: Boolean,
    val caravanAttackRollouts: Boolean,
    val expansionCardRollouts: Boolean,
    val cardTerritoryRollouts: Boolean,
    val lookaheadCheckpoints: Boolean,
    val cardTerritoryEpochs: List<Int>,
    val chanceAlignmentTrial: Boolean,
    val shortTrainingFutures: Int,
    val resampledDrawStudy: Boolean,
    val reliefUnlockRollouts: Boolean,
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
            "styleRotation" to
                if (opponentStyle == RobustOpponentStyle.QUALIFIED_REVEALED_BALANCED) {
                    "index-div4-plus-seat-mod8-half-qualified-1"
                } else {
                    "index-div4-plus-seat-mod4-1"
                },
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
            } ?: emptyMap()) +
            (adaptiveOpponent?.let {
                mapOf(
                    "adaptiveOpponent" to it.identity,
                    "adaptiveOrdinaryFraction" to
                        if (opponentStyle == RobustOpponentStyle.QUALIFIED_REVEALED_BALANCED) .5
                        else 0.0,
                )
            } ?: emptyMap()) +
            (candidateTokenAnchoredPolicy?.let {
                mapOf("candidateTokenAnchoredPolicy" to it.identity)
            } ?: emptyMap()) +
            (initiativeRival?.let {
                mapOf(
                    "initiativeRival" to
                        mapOf("kind" to InitiativeRival.VERSION, "configuration" to it)
                )
            } ?: emptyMap()) +
            (initiative?.let {
                mapOf(
                    "initiative" to
                        mapOf("kind" to InitiativePolicyConfig.VERSION, "configuration" to it)
                )
            } ?: emptyMap()) +
            (commodityTiming?.let {
                mapOf(
                    "commodityTiming" to
                        mapOf("kind" to CommodityTimingConfig.VERSION, "configuration" to it)
                )
            } ?: emptyMap()) +
            (if (commodityRollouts) mapOf("commodityRollouts" to "play-versus-hold-1")
            else emptyMap()) +
            (if (commodityExpansionRollouts)
                mapOf("commodityExpansionRollouts" to "hold-expand-play-1")
            else emptyMap()) +
            (if (midgameBidRollouts) mapOf("midgameBidRollouts" to "sealed-bid-neighbors-1")
            else emptyMap()) +
            (if (rivalMarketDenialRollouts)
                mapOf("rivalMarketDenialRollouts" to "matched-rival-market-denial-1")
            else emptyMap()) +
            (if (reserveExpansionCardRollouts)
                mapOf("reserveExpansionCardRollouts" to "reserve-market-and-card-1")
            else emptyMap()) +
            (if (capitalChoiceRollouts) mapOf("capitalChoiceRollouts" to "all-offered-capitals-1")
            else emptyMap()) +
            (if (leaderProtectionRollouts)
                mapOf("leaderProtectionRollouts" to "matched-protection-escrow-1")
            else emptyMap()) +
            (if (nextTurnBaseRollouts)
                mapOf("nextTurnBaseRollouts" to "colored-base-followthrough-1")
            else emptyMap()) +
            (if (caravanAttackRollouts)
                mapOf("caravanAttackRollouts" to "caravan-attack-expected-route-3")
            else emptyMap()) +
            (if (expansionCardRollouts) mapOf("expansionCardRollouts" to "buy-versus-pass-1")
            else emptyMap()) +
            (if (cardTerritoryRollouts) mapOf("cardTerritoryRollouts" to "buy-versus-territory-1")
            else emptyMap()) +
            (if (lookaheadCheckpoints) mapOf("lookaheadCheckpoints" to "public-turn-horizons-1")
            else emptyMap()) +
            (if (cardTerritoryEpochs.isNotEmpty())
                mapOf("cardTerritoryEpochs" to cardTerritoryEpochs)
            else emptyMap()) +
            (if (chanceAlignmentTrial) mapOf("chanceAlignmentTrial" to "turn-and-outcome-channel-1")
            else emptyMap()) +
            (if (shortTrainingFutures > 0) mapOf("shortTrainingFutures" to shortTrainingFutures)
            else emptyMap()) +
            (if (resampledDrawStudy) mapOf("resampledDrawStudy" to "hidden-draw-order-1")
            else emptyMap()) +
            (if (reliefUnlockRollouts) mapOf("reliefUnlockRollouts" to "first-tier-relief-1")
            else emptyMap())
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

        private fun initiative(node: JsonNode): InitiativePolicyConfig {
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "maximumTokenLoss",
                    "tokenPenalty",
                    "participation",
                    "requireAffordableContest",
                    "attackBonus",
                ),
                optional = setOf("historyDecay", "bidScoreTolerance", "openingSpread"),
            )
            require(
                node["kind"]?.asText() == InitiativePolicyConfig.VERSION &&
                    node["requireAffordableContest"].isBoolean
            ) {
                "Supply a versioned public initiative policy and a boolean contest condition."
            }
            return InitiativePolicyConfig(
                maximumTokenLoss =
                    PurchaseJson.integer(node["maximumTokenLoss"], "maximum token loss"),
                tokenPenalty = PurchaseJson.number(node["tokenPenalty"], "token penalty"),
                participation = PurchaseJson.number(node["participation"], "participation"),
                requireAffordableContest = node["requireAffordableContest"].booleanValue(),
                attackBonus = PurchaseJson.number(node["attackBonus"], "attack bonus"),
                historyDecay =
                    node["historyDecay"]?.let { PurchaseJson.number(it, "history decay") } ?: 1.0,
                bidScoreTolerance =
                    node["bidScoreTolerance"]?.let {
                        PurchaseJson.number(it, "bid score tolerance")
                    } ?: 0.0,
                openingSpread =
                    node["openingSpread"]?.let { PurchaseJson.integer(it, "opening spread") } ?: 0,
            )
        }

        private fun commodityTiming(node: JsonNode): CommodityTimingConfig {
            PurchaseJson.fields(
                node,
                setOf("kind", "futureWeight", "minimumGrowth", "currentPayoutLimit"),
            )
            require(node["kind"]?.asText() == CommodityTimingConfig.VERSION) {
                "Declare the exact commodity timing policy version."
            }
            return CommodityTimingConfig(
                futureWeight = PurchaseJson.number(node["futureWeight"], "future payout weight"),
                minimumGrowth =
                    PurchaseJson.integer(node["minimumGrowth"], "minimum future growth"),
                currentPayoutLimit =
                    PurchaseJson.integer(node["currentPayoutLimit"], "current payout limit"),
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
                    "adaptiveOpponent",
                    "candidateTokenAnchoredPolicy",
                    "controlBundle",
                    "controlSelector",
                    "expansionDenial",
                    "initiative",
                    "commodityTiming",
                    "commodityRollouts",
                    "commodityExpansionRollouts",
                    "midgameBidRollouts",
                    "rivalMarketDenialRollouts",
                    "reserveExpansionCardRollouts",
                    "capitalChoiceRollouts",
                    "leaderProtectionRollouts",
                    "nextTurnBaseRollouts",
                    "caravanAttackRollouts",
                    "expansionCardRollouts",
                    "cardTerritoryRollouts",
                    "lookaheadCheckpoints",
                    "cardTerritoryEpochs",
                    "chanceAlignmentTrial",
                    "shortTrainingFutures",
                    "resampledDrawStudy",
                    "reliefUnlockRollouts",
                    "initiativeRival",
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
            val expansionCardRollouts =
                node["expansionCardRollouts"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The expansion card rollout switch must be boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val cardTerritoryRollouts =
                node["cardTerritoryRollouts"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The card versus territory rollout switch must be boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val lookaheadCheckpoints =
                node["lookaheadCheckpoints"]?.let { setting ->
                    require(setting.isBoolean) { "Short lookahead checkpoints require a boolean." }
                    setting.booleanValue()
                } ?: false
            val chanceAlignmentTrial =
                node["chanceAlignmentTrial"]?.let { setting ->
                    require(setting.isBoolean) { "The chance alignment trial must be boolean." }
                    setting.booleanValue()
                } ?: false
            val shortTrainingFutures =
                node["shortTrainingFutures"]?.let { setting ->
                    PurchaseJson.integer(setting, "number of short training futures").also { count
                        ->
                        require(count in 2..8) {
                            "Short rollout training requires two through eight additional futures per action."
                        }
                    }
                } ?: 0
            val resampledDrawStudy =
                node["resampledDrawStudy"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The resampled draw study requires a JSON boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val reliefUnlockRollouts =
                node["reliefUnlockRollouts"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The relief unlock study requires a JSON boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val cardTerritoryEpochs =
                node["cardTerritoryEpochs"]
                    ?.let { setting ->
                        val epochs =
                            PurchaseJson.array(setting, "card territory epochs").map {
                                PurchaseJson.integer(it, "card territory epoch")
                            }
                        require(
                            epochs.isNotEmpty() &&
                                epochs.distinct().size == epochs.size &&
                                epochs.all { it in 1..3 }
                        ) {
                            "Card versus territory roots require distinct requested epochs 1 through 3."
                        }
                        epochs
                    }
                    .orEmpty()
            val adaptiveOpponent =
                node["adaptiveOpponent"]?.takeUnless(JsonNode::isNull)?.let {
                    RevealedTokenCounterConfig.read(it)
                }
            require(style.isRevealedCounter == (adaptiveOpponent != null)) {
                "Public-history rival styles require their explicit identity-bound adaptiveOpponent config."
            }
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
            val initiativeRival =
                node["initiativeRival"]?.let { rival ->
                    require(style == RobustOpponentStyle.QUALIFIED) {
                        "The independent initiative lineup requires the frozen base opposition."
                    }
                    val offsets =
                        when (rival["kind"]?.asText()) {
                            InitiativeRival.VERSION -> {
                                PurchaseJson.fields(rival, setOf("kind", "offset", "policy"))
                                listOf(PurchaseJson.integer(rival["offset"], "rival offset"))
                            }
                            InitiativeRival.LINEUP -> {
                                PurchaseJson.fields(rival, setOf("kind", "offsets", "policy"))
                                PurchaseJson.array(rival["offsets"], "rival offsets").map {
                                    PurchaseJson.integer(it, "rival offset")
                                }
                            }
                            else -> error("Supply a versioned public initiative lineup.")
                        }
                    InitiativeRival(offsets, initiative(rival.required("policy")))
                }
            val initiative = node["initiative"]?.let(::initiative)
            val commodityTiming = node["commodityTiming"]?.let(::commodityTiming)
            val commodityRollouts =
                node["commodityRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The commodity rollout switch must be boolean." }
                    setting.booleanValue()
                } ?: false
            val commodityExpansionRollouts =
                node["commodityExpansionRollouts"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The commodity expansion study requires a boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val midgameBidRollouts =
                node["midgameBidRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The midgame bid study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            val rivalMarketDenialRollouts =
                node["rivalMarketDenialRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The rival market study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            val reserveExpansionCardRollouts =
                node["reserveExpansionCardRollouts"]?.let { setting ->
                    require(setting.isBoolean) {
                        "The expansion card reserve study requires a boolean."
                    }
                    setting.booleanValue()
                } ?: false
            val capitalChoiceRollouts =
                node["capitalChoiceRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The capital choice study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            val leaderProtectionRollouts =
                node["leaderProtectionRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The protection escrow study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            val nextTurnBaseRollouts =
                node["nextTurnBaseRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The next-turn base study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            val caravanAttackRollouts =
                node["caravanAttackRollouts"]?.let { setting ->
                    require(setting.isBoolean) { "The Caravan attack study requires a boolean." }
                    setting.booleanValue()
                } ?: false
            require(
                initiative == null ||
                    expansionDenial == null &&
                        tokenNeighborMixture == null &&
                        !node.has("candidateTokenAnchoredPolicy")
            ) {
                "Initiative is a standalone intervention over the declared normal policies."
            }
            val anchoredNode = node["candidateTokenAnchoredPolicy"]?.takeUnless(JsonNode::isNull)
            val anchored = anchoredNode?.let {
                val c05 = NearOptimalTokenSampler(.35, 3.0, 4)
                require(
                    candidate.source.sha256 == TokenFrozenAnchor.QUALIFIED_94 &&
                        control.source.sha256 == TokenFrozenAnchor.QUALIFIED_94 &&
                        candidateSampler == c05 &&
                        controlSampler == c05 &&
                        expansionDenial == null &&
                        tokenNeighborMixture == null
                ) {
                    "An anchored candidate requires frozen94 bundles/C05 controls and no other intervention."
                }
                TokenAnchoredPolicyInput.read(
                        TokenPairedSubject.read(it, source.path.parent),
                        arena,
                    )
                    .also { policy ->
                        require(
                            policy.anchor.asset.source.sha256 == asset.source.sha256 &&
                                policy.anchor.bundle.source.sha256 == candidate.source.sha256
                        ) {
                            "The candidate residual must preserve the exact arena reference and anchor bundle."
                        }
                    }
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
                    mapOf(source.path.toString() to source.sha256) +
                    (anchored?.pins ?: emptyMap())
            require(
                !commodityRollouts ||
                    candidateOnly &&
                        style == RobustOpponentStyle.QUALIFIED &&
                        commodityTiming != null &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Commodity rollouts require a fixed candidate-only league and a declared timing trigger."
            }
            require(!commodityExpansionRollouts || commodityRollouts) {
                "A commodity expansion plan requires a declared ordinary holding opportunity."
            }
            require(
                !(nextTurnBaseRollouts || caravanAttackRollouts) ||
                    !commodityRollouts &&
                        !(nextTurnBaseRollouts && caravanAttackRollouts) &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        !midgameBidRollouts &&
                        !rivalMarketDenialRollouts &&
                        !reserveExpansionCardRollouts &&
                        !capitalChoiceRollouts &&
                        !leaderProtectionRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "An expansion route study requires one fixed mixed candidate-only comparison."
            }
            require(
                !leaderProtectionRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        !midgameBidRollouts &&
                        !rivalMarketDenialRollouts &&
                        !reserveExpansionCardRollouts &&
                        !capitalChoiceRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "The protection study requires one fixed mixed candidate-only comparison."
            }
            require(
                !capitalChoiceRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        !midgameBidRollouts &&
                        !rivalMarketDenialRollouts &&
                        !reserveExpansionCardRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "The opening-capital study requires one fixed mixed candidate-only comparison."
            }
            require(
                !reserveExpansionCardRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        !midgameBidRollouts &&
                        !rivalMarketDenialRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "The expansion card reserve study requires one fixed mixed candidate-only comparison."
            }
            require(
                !rivalMarketDenialRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        !midgameBidRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Rival market denial requires a fixed mixed table and one candidate-only comparison."
            }
            require(
                !midgameBidRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        !reliefUnlockRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.MIXED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Midgame bid rollouts require a fixed mixed table and one candidate-only comparison."
            }
            require(
                !expansionCardRollouts ||
                    !commodityRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.QUALIFIED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Expansion card rollouts require a fixed candidate-only league and one comparison."
            }
            require(
                !cardTerritoryRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.QUALIFIED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Card versus territory rollouts require a fixed candidate-only league and one comparison."
            }
            require(!lookaheadCheckpoints || cardTerritoryRollouts) {
                "Short lookahead checkpoints belong to the declared card versus territory study."
            }
            require(
                !reliefUnlockRollouts ||
                    !commodityRollouts &&
                        !expansionCardRollouts &&
                        !cardTerritoryRollouts &&
                        candidateOnly &&
                        style == RobustOpponentStyle.QUALIFIED &&
                        tokenNeighborMixture == null &&
                        anchored == null
            ) {
                "Relief unlock rollouts require one fixed candidate-only policy in a qualified table."
            }
            require(cardTerritoryEpochs.isEmpty() || cardTerritoryRollouts) {
                "Requested root epochs belong to the declared card versus territory study."
            }
            require(!chanceAlignmentTrial || cardTerritoryRollouts) {
                "The chance alignment trial belongs to the declared card versus territory study."
            }
            require(shortTrainingFutures == 0 || cardTerritoryRollouts) {
                "Short rollout training futures belong to the declared card versus territory study."
            }
            require(
                !resampledDrawStudy ||
                    (shortTrainingFutures > 0 && !lookaheadCheckpoints && !chanceAlignmentTrial)
            ) {
                "Resampled draws compare only the independently declared short training futures."
            }
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
                    adaptiveOpponent,
                    anchored,
                    initiative,
                    initiativeRival,
                    commodityTiming,
                    commodityRollouts,
                    commodityExpansionRollouts,
                    midgameBidRollouts,
                    rivalMarketDenialRollouts,
                    reserveExpansionCardRollouts,
                    capitalChoiceRollouts,
                    leaderProtectionRollouts,
                    nextTurnBaseRollouts,
                    caravanAttackRollouts,
                    expansionCardRollouts,
                    cardTerritoryRollouts,
                    lookaheadCheckpoints,
                    cardTerritoryEpochs,
                    chanceAlignmentTrial,
                    shortTrainingFutures,
                    resampledDrawStudy,
                    reliefUnlockRollouts,
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
