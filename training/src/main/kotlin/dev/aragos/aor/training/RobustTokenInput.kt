package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.InitiativePolicyConfig
import dev.aragos.aor.ai.LeadingRivalRetargetingConfig
import dev.aragos.aor.ai.LocalTokenNeighborMixture
import dev.aragos.aor.ai.MidgameBidMix
import dev.aragos.aor.ai.NearOptimalTokenSampler
import dev.aragos.aor.ai.OpeningBidMix
import dev.aragos.aor.ai.PublicLeaderAttackConfig
import dev.aragos.aor.ai.SecondCapitalGeography
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

/** Several independent policies fixed to registration-relative seats before a world starts. */
internal data class InitiativeLineup(val policiesByOffset: Map<Int, InitiativePolicyConfig>) {
    init {
        require(policiesByOffset.isNotEmpty() && policiesByOffset.keys.all { it in 1..3 }) {
            "A heterogeneous lineup needs distinct rival offsets from one through three."
        }
    }

    fun seated(controlledSeat: Int): Map<Int, InitiativePolicyConfig> =
        policiesByOffset.mapKeys { (offset, _) ->
            (controlledSeat + offset) % 4
        }

    companion object {
        const val VERSION = "public-initiative-heterogeneous-1"
    }
}

/**
 * Setup bids in dollars. [rivalBids] maps registration offsets from the measured actor to fixed
 * public-training rival bids. A null [fixedBid] and empty [mixedBids] observe the baseline actor.
 */
internal data class CapitalAuctionConfig(
    val fixedBid: Int?,
    val mixedBids: List<Int> = emptyList(),
    val rivalBids: Map<Int, Int> = emptyMap(),
) {
    init {
        require(fixedBid == null || fixedBid in 0..40) {
            "A capital auction bid must be between zero and 40 dollars."
        }
        require(
            mixedBids.isEmpty() ||
                fixedBid == null &&
                    mixedBids.size in 2..8 &&
                    mixedBids.distinct().size == mixedBids.size &&
                    mixedBids.all { it in 0..40 }
        ) {
            "A capital mixture requires two through eight distinct legal bids and no fixed bid."
        }
        require(rivalBids.all { (offset, bid) -> offset in 1..3 && bid in 0..40 }) {
            "Each capital study rival needs an opponents' registration offset and an affordable bid."
        }
    }

    fun bidsFor(offset: Int): List<Int> =
        if (offset == 0 && mixedBids.isNotEmpty()) mixedBids
        else listOfNotNull(if (offset == 0) fixedBid else rivalBids[offset])

    fun science(): Map<String, Any?> =
        mapOf("kind" to VERSION, "bid" to fixedBid) +
            (if (mixedBids.isEmpty()) emptyMap() else mapOf("mixedBids" to mixedBids)) +
            (if (rivalBids.isEmpty()) emptyMap() else mapOf("rivals" to rivalBids.toSortedMap()))

    companion object {
        const val VERSION = "capital-auction-study-1"
    }
}

/** Explicit relative seats that share an independently declared attack-only rival policy. */
internal data class RivalTargetingLineup(
    val offsets: List<Int>,
    val policy: LeadingRivalRetargetingConfig,
) {
    init {
        require(
            offsets.isNotEmpty() && offsets.distinct() == offsets && offsets.all { it in 1..3 }
        ) {
            "Each targeted opponent needs one distinct relative seat offset from one through three."
        }
    }

    fun seated(actor: Int): Set<Int> = offsets.map { (actor + it) % 4 }.toSet()

    companion object {
        const val VERSION = "leading-rival-table-1"
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
    val seriesOpeningPriors: List<CapitalOrderOpeningBid>,
    val seriesOpeningResponses: List<CapitalOrderOpeningBid>,
    val seriesMidgamePriors: List<Int>,
    val midgameBidMix: MidgameBidMix?,
    val candidateTokenAnchoredPolicy: TokenAnchoredPolicyInput?,
    val initiative: InitiativePolicyConfig?,
    val initiativeRival: InitiativeRival?,
    val initiativeLineup: InitiativeLineup?,
    val capitalAuction: CapitalAuctionConfig?,
    val secondCapitalGeography: Boolean,
    val leadingRivalRetargeting: LeadingRivalRetargetingConfig?,
    val rivalTargetingLineup: RivalTargetingLineup?,
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
            (if (seriesOpeningPriors.isEmpty()) emptyMap()
            else mapOf("seriesOpeningPriors" to seriesOpeningPriors)) +
            (if (seriesOpeningResponses.isEmpty()) emptyMap()
            else mapOf("seriesOpeningResponses" to seriesOpeningResponses)) +
            (if (seriesMidgamePriors.isEmpty()) emptyMap()
            else mapOf("seriesMidgamePriors" to seriesMidgamePriors)) +
            (midgameBidMix?.let {
                mapOf("midgameBidMix" to mapOf("kind" to MidgameBidMix.VERSION, "policy" to it))
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
            (initiativeLineup?.let {
                mapOf(
                    "initiativeLineup" to mapOf("kind" to InitiativeLineup.VERSION, "rivals" to it)
                )
            } ?: emptyMap()) +
            (capitalAuction?.let {
                mapOf("capitalAuction" to it.science())
            } ?: emptyMap()) +
            (if (secondCapitalGeography)
                mapOf("secondCapitalGeography" to SecondCapitalGeography.VERSION)
            else emptyMap()) +
            (leadingRivalRetargeting?.let {
                mapOf(
                    "leadingRivalRetargeting" to
                        mapOf(
                            "kind" to LeadingRivalRetargetingConfig.VERSION,
                            "configuration" to it,
                        )
                )
            } ?: emptyMap()) +
            (rivalTargetingLineup?.let {
                mapOf(
                    "rivalTargetingLineup" to
                        mapOf("kind" to RivalTargetingLineup.VERSION, "policy" to it)
                )
            } ?: emptyMap()) +
            (initiative?.let {
                mapOf(
                    "initiative" to
                        mapOf("kind" to InitiativePolicyConfig.VERSION, "configuration" to it)
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
                optional =
                    setOf(
                        "historyDecay",
                        "bidScoreTolerance",
                        "openingSpread",
                        "maximumExtraTokens",
                        "planWeight",
                        "extraCashPenalty",
                        "counterAnticipation",
                        "respectRivalCash",
                        "openingMix",
                    ),
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
                maximumExtraTokens =
                    node["maximumExtraTokens"]?.let {
                        PurchaseJson.integer(it, "maximum extra tokens")
                    } ?: 0,
                planWeight =
                    node["planWeight"]?.let { PurchaseJson.number(it, "expansion plan weight") }
                        ?: 0.0,
                extraCashPenalty =
                    node["extraCashPenalty"]?.let {
                        PurchaseJson.number(it, "extra token cash penalty")
                    } ?: 0.15,
                counterAnticipation =
                    node["counterAnticipation"]?.let {
                        PurchaseJson.number(it, "counter anticipation weight")
                    } ?: 0.0,
                respectRivalCash =
                    node["respectRivalCash"]?.let { setting ->
                        require(setting.isBoolean) { "The rival cash constraint must be boolean." }
                        setting.booleanValue()
                    } ?: false,
                openingMix = node["openingMix"]?.let(::openingMix),
            )
        }

        private fun openingMix(node: JsonNode): OpeningBidMix {
            PurchaseJson.fields(
                node,
                setOf("bids"),
                optional =
                    setOf(
                        "laterCapitalBids",
                        "retainOrdinaryForLaterCapital",
                        "participation",
                        "weights",
                        "laterCapitalWeights",
                    ),
            )
            fun integers(field: String): List<Int> =
                PurchaseJson.array(node[field], field).map { PurchaseJson.integer(it, field) }
            return OpeningBidMix(
                bids = integers("bids"),
                laterCapitalBids =
                    node["laterCapitalBids"]?.let { integers("laterCapitalBids") } ?: emptyList(),
                retainOrdinaryForLaterCapital =
                    node["retainOrdinaryForLaterCapital"]?.let { setting ->
                        require(setting.isBoolean) {
                            "Keeping regular later-capital bids requires a JSON boolean."
                        }
                        setting.booleanValue()
                    } ?: false,
                participation =
                    node["participation"]?.let {
                        PurchaseJson.number(it, "opening mix participation")
                    } ?: 1.0,
                weights = node["weights"]?.let { integers("weights") } ?: emptyList(),
                laterCapitalWeights =
                    node["laterCapitalWeights"]?.let { integers("laterCapitalWeights") }
                        ?: emptyList(),
            )
        }

        private fun initiativeLineup(node: JsonNode): InitiativeLineup {
            PurchaseJson.fields(node, setOf("kind", "rivals"))
            require(node["kind"]?.asText() == InitiativeLineup.VERSION) {
                "Declare the exact heterogeneous initiative lineup version."
            }
            val policies = buildMap {
                for (rival in PurchaseJson.array(node["rivals"], "heterogeneous rivals")) {
                    PurchaseJson.fields(rival, setOf("offset", "policy"))
                    val offset = PurchaseJson.integer(rival["offset"], "heterogeneous rival offset")
                    require(offset !in this) {
                        "A heterogeneous rival seat may be declared only once."
                    }
                    put(offset, initiative(rival["policy"]))
                }
            }
            return InitiativeLineup(policies.toSortedMap())
        }

        private fun readCapitalAuction(node: JsonNode): CapitalAuctionConfig {
            val bid =
                node["bid"]?.takeUnless(JsonNode::isNull)?.let {
                    PurchaseJson.integer(it, "capital bid")
                }
            val mix =
                node["mixedBids"]
                    ?.let { value ->
                        PurchaseJson.array(value, "candidate capital bids").map {
                            PurchaseJson.integer(it, "candidate capital bid")
                        }
                    }
                    .orEmpty()
            val rivals =
                node["rivals"]
                    ?.let { value ->
                        buildMap {
                            for (rival in PurchaseJson.array(value, "capital bid rivals")) {
                                PurchaseJson.fields(rival, setOf("offset", "bid"))
                                val offset =
                                    PurchaseJson.integer(rival["offset"], "rival capital offset")
                                require(offset !in this) {
                                    "Capital bid rivals need distinct offsets."
                                }
                                put(offset, PurchaseJson.integer(rival["bid"], "rival capital bid"))
                            }
                        }
                    }
                    .orEmpty()
            return CapitalAuctionConfig(bid, mix, rivals)
        }

        private fun retargeting(rule: JsonNode): LeadingRivalRetargetingConfig {
            PurchaseJson.fields(
                rule,
                setOf("kind", "maximumTrailingPoints", "requireGreaterCommodityDenial"),
                optional = setOf("maximumAdditionalTokens", "maximumOddsDrop", "earliestTurn"),
            )
            require(
                rule["kind"]?.asText() == LeadingRivalRetargetingConfig.VERSION &&
                    rule["requireGreaterCommodityDenial"].isBoolean
            ) {
                "Declare the exact retargeting policy kind and Boolean commodity condition."
            }
            return LeadingRivalRetargetingConfig(
                maximumTrailingPoints =
                    PurchaseJson.integer(rule["maximumTrailingPoints"], "maximum trailing points"),
                requireGreaterCommodityDenial =
                    rule["requireGreaterCommodityDenial"].booleanValue(),
                maximumAdditionalTokens =
                    rule["maximumAdditionalTokens"]?.let {
                        PurchaseJson.integer(it, "additional attack tokens")
                    } ?: 2,
                maximumOddsDrop =
                    rule["maximumOddsDrop"]?.let {
                        PurchaseJson.number(it, "maximum odds drop")
                    } ?: .15,
                earliestTurn =
                    rule["earliestTurn"]?.let {
                        PurchaseJson.integer(it, "earliest retargeting turn")
                    } ?: 4,
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
                    "seriesOpeningPriors",
                    "seriesOpeningResponses",
                    "seriesMidgamePriors",
                    "midgameBidMix",
                    "candidateTokenAnchoredPolicy",
                    "controlBundle",
                    "controlSelector",
                    "expansionDenial",
                    "leadingRivalRetargeting",
                    "rivalTargetingLineup",
                    "initiative",
                    "initiativeRival",
                    "initiativeLineup",
                    "capitalAuction",
                    "secondCapitalGeography",
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
            val leadingRivalRetargeting = node["leadingRivalRetargeting"]?.let(::retargeting)
            val rivalTargetingLineup =
                node["rivalTargetingLineup"]?.let { lineup ->
                    PurchaseJson.fields(lineup, setOf("kind", "offsets", "policy"))
                    require(lineup["kind"]?.asText() == RivalTargetingLineup.VERSION) {
                        "Select the exact versioned retargeting rival lineup."
                    }
                    val offsets =
                        PurchaseJson.array(lineup["offsets"], "retargeting rival offsets").map {
                            PurchaseJson.integer(it, "retargeting rival offset")
                        }
                    RivalTargetingLineup(offsets, retargeting(lineup["policy"]))
                }
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
            val adaptiveOpponent =
                node["adaptiveOpponent"]?.takeUnless(JsonNode::isNull)?.let {
                    RevealedTokenCounterConfig.read(it)
                }
            require(style.isRevealedCounter == (adaptiveOpponent != null)) {
                "Public-history rival styles require their explicit identity-bound adaptiveOpponent config."
            }
            fun seriesOpeningBids(field: String): List<CapitalOrderOpeningBid> {
                val definition = node[field] ?: return emptyList()
                require(adaptiveOpponent != null) {
                    "Series opening profiles require an explicit public-history opponent."
                }
                val profiles =
                    PurchaseJson.array(definition, field).map { opening ->
                        PurchaseJson.fields(opening, setOf("earlyBid", "lateBid"))
                        CapitalOrderOpeningBid(
                            earlyBid =
                                PurchaseJson.integer(
                                    opening["earlyBid"],
                                    "earlier chooser opening bid",
                                ),
                            lateBid =
                                PurchaseJson.integer(
                                    opening["lateBid"],
                                    "later chooser opening bid",
                                ),
                        )
                    }
                require(profiles.isEmpty() || profiles.size == seeds.size) {
                    "The series study needs one frozen opening profile per declared world."
                }
                return profiles
            }
            val seriesOpeningPriors = seriesOpeningBids("seriesOpeningPriors")
            val seriesOpeningResponses = seriesOpeningBids("seriesOpeningResponses")
            val seriesMidgamePriors =
                node["seriesMidgamePriors"]
                    ?.let { prior ->
                        require(adaptiveOpponent != null) {
                            "A midgame series prior requires an explicit public-history rival."
                        }
                        val amounts =
                            PurchaseJson.array(prior, "midgame series prior").map {
                                PurchaseJson.integer(it, "prior midgame bid")
                            }
                        require(amounts.size == seeds.size && amounts.all { it in -40..100 }) {
                            "Every declared game needs its own frozen midgame signed amount."
                        }
                        amounts
                    }
                    .orEmpty()
            val midgameBidMix =
                node["midgameBidMix"]?.let { mix ->
                    PurchaseJson.fields(
                        mix,
                        required = setOf("kind", "bids", "weights", "respectPublicOrder"),
                        optional = setOf("requireRepeatedEvidence", "focusScoreLeader"),
                    )
                    require(
                        candidateOnly &&
                            mix["kind"]?.asText() == MidgameBidMix.VERSION &&
                            mix["respectPublicOrder"].isBoolean
                    ) {
                        "Use the versioned candidate-only midgame mix with a Boolean order setting."
                    }
                    fun integers(field: String): List<Int> =
                        PurchaseJson.array(mix[field], field).map {
                            PurchaseJson.integer(it, field)
                        }
                    fun flag(field: String): Boolean =
                        mix[field]?.let {
                            require(it.isBoolean) { "$field must be a JSON Boolean." }
                            it.booleanValue()
                        } ?: false
                    MidgameBidMix(
                        bids = integers("bids"),
                        weights = integers("weights"),
                        respectPublicOrder = mix["respectPublicOrder"].booleanValue(),
                        requireRepeatedEvidence = flag("requireRepeatedEvidence"),
                        focusScoreLeader = flag("focusScoreLeader"),
                    )
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
            val initiativeLineup = node["initiativeLineup"]?.let(::initiativeLineup)
            val capitalAuction =
                node["capitalAuction"]?.let { setting ->
                    PurchaseJson.fields(
                        setting,
                        setOf("kind", "bid"),
                        optional = setOf("mixedBids", "rivals"),
                    )
                    require(
                        candidateOnly && setting["kind"]?.asText() == CapitalAuctionConfig.VERSION
                    ) {
                        "Use the typed capital auction study on the declared candidate-only world."
                    }
                    readCapitalAuction(setting)
                }
            val secondCapitalGeography =
                node["secondCapitalGeography"]?.let { setting ->
                    require(
                        setting.isTextual && setting.asText() == SecondCapitalGeography.VERSION
                    ) {
                        "Select the versioned second-capital geography policy."
                    }
                    true
                } ?: false
            require(
                initiativeLineup == null ||
                    initiativeRival == null &&
                        (style == RobustOpponentStyle.QUALIFIED || style.isRevealedCounter)
            ) {
                "Use one fixed initiative lineup with ordinary or explicitly learning opponents."
            }
            require(
                initiativeLineup == null ||
                    adaptiveOpponent == null ||
                    initiativeLineup.policiesByOffset.keys.all {
                        it > adaptiveOpponent.targetedRivalCount
                    }
            ) {
                "Fixed rivals must occupy different seats from the public-history counter."
            }
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
                    seriesOpeningPriors,
                    seriesOpeningResponses,
                    seriesMidgamePriors,
                    midgameBidMix,
                    anchored,
                    initiative,
                    initiativeRival,
                    initiativeLineup,
                    capitalAuction,
                    secondCapitalGeography,
                    leadingRivalRetargeting,
                    rivalTargetingLineup,
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
