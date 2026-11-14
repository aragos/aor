package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path

/** Fixed supported G3 C/P roles with the four predeclared E/T rival combinations. */
internal object CoordinatedWinEcology {
    fun read(
        source: TokenPairedSubject,
        protocol: CoordinatedWinProtocol,
        origin: CoordinatedWinOrigin,
        bank: CoordinatedEsBank,
    ): ArenaSpecification {
        source.verify()
        val arena = ArenaSpecification.read(source.path)
        require(
            arena.configurations == origin.scope.arena.configurations &&
                arena.opponents.size == 4 &&
                arena.purpose == ArenaPurpose.SCREENING &&
                arena.expansionActor == null &&
                arena.tokenActor == null &&
                arena.cpeBundle == null &&
                arena.coordinatedEs == null
        ) {
            "The new evaluation must retain its full bank, fixed configuration and four raw rival references."
        }
        val references = protocol.record["evaluation"]["references"]
        val ordinary = TokenPairedEcology.ordinaryHash(source.path)
        require(
            ordinary == references["cardM8Sha256"].asText() &&
                references["tokenControlVersion"]?.asText() == "FIXED_16_TO_15"
        ) {
            "Every ordinary role and the raw token reference must retain their manifest-bound identities."
        }
        val expected =
            ExpansionOnPolicyStore.wire(
                TokenPairedEcology.portableBindings(origin.scope.policyBindings)
            ) as ObjectNode
        val expansion = expected["expansionImitation"] as ObjectNode
        val candidate = expansion.required("candidateModel")
        expansion.putArray("opponentModels").also {
            it.addNull()
            it.add(candidate)
            it.addNull()
            it.add(candidate)
        }
        (expected["tokenOrder"] as ObjectNode).putArray("opponentSelectors").also { array ->
            listOf("ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15").forEach(array::add)
        }
        val actual =
            ExpansionOnPolicyStore.wire(
                TokenPairedEcology.portableBindings(
                    ExpansionOnPolicyStore.wire(arena.overlayMetadata())
                )
            )
        require(
            actual == expected &&
                actual["jointPolicy"]["baseline"]["sha256"].asText() ==
                    references["purchaseF0Sha256"].asText() &&
                candidate["sha256"].asText() == references["expansionESha256"].asText()
        ) {
            "Only the fixed E-off/T-off, E-on/T-off, E-off/T-on and E-on/T-on rival lineup is admitted."
        }
        val supported =
            arena.copy(
                pairsPerConfiguration = bank.count,
                seed = bank.nativeOffset,
                coordinatedEs =
                    ArenaCoordinatedPolicy.supported(
                        null,
                        origin.scope.original,
                        origin.scope.baseline,
                        null,
                    ),
            )
        val identity = TokenPairedEcology.identity(supported, ordinary)
        require(CpePairedDigest.json(identity) == protocol.ecologySha256) {
            "The supported mixed execution ecology must match its own protocol identity, distinct from the parameter origin."
        }
        return supported
    }

    fun pins(source: TokenPairedSubject, arena: ArenaSpecification): Map<String, String> =
        buildMap {
            fun pin(path: Path, hash: String) {
                val actual = path.toRealPath()
                require(PurchaseJson.hash(actual) == hash) {
                    "An immutable evaluation reference changed: $actual."
                }
                val previous = put(actual.toString(), hash)
                require(previous == null || previous == hash) {
                    "One evaluation input cannot have two raw identities."
                }
            }
            fun metadata(value: JsonNode) {
                if (value.isObject) {
                    if (value["path"]?.isTextual == true && value["sha256"]?.isTextual == true) {
                        pin(Path.of(value["path"].asText()), value["sha256"].asText())
                    }
                    value.elements().forEachRemaining(::metadata)
                } else if (value.isArray) value.forEach(::metadata)
            }
            pin(source.path, source.sha256)
            metadata(ExpansionOnPolicyStore.wire(arena.overlayMetadata()))
            val value = PurchaseJson.read(source.path)
            (listOf(value.required("candidate"), value.required("incumbent")) +
                    value.required("opponents").toList())
                .forEach {
                    val path =
                        source.path.parent
                            .resolve(PurchaseJson.text(it, "ordinary model"))
                            .toRealPath()
                    pin(path, PurchaseJson.hash(path))
                }
        }
}
