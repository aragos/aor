package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Invented complete model subjects; no game, neural forward or fresh scientific stream. */
class RobustTokenInputTest {
    private fun source(
        fixture: CoordinatedWinQualificationFixture,
        extra: Map<String, Any?> = emptyMap(),
    ): TokenPairedSubject {
        val previous = PurchaseJson.read(fixture.source("homogeneous", fixture.confirmation()).path)
        return fixture.save(
            "robust-source.json",
            mapOf(
                "kind" to "robust-token-chunk-1",
                "protocolSha256" to "a".repeat(64),
                "inferenceAsset" to fixture.ref(fixture.asset),
                "nomination" to fixture.ref(fixture.nomination),
                "arena" to previous.required("arena"),
                "selector" to
                    mapOf(
                        "kind" to "near-optimal-token-softmax-1",
                        "temperature" to .7,
                        "maxScoreGap" to 1.25,
                        "neighborRadius" to 2,
                    ),
                "opponentStyle" to "BALANCED",
                "seeds" to listOf(11, 13),
                "globalStart" to 0,
                "count" to 2,
                "replayIndices" to listOf(0),
            ) + extra,
        )
    }

    @Test
    fun portablePrepareDecodesCompleteAssetWithoutExecutingAnyGames() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = RobustTokenInput.read(source(fixture).path)
            val record = input.prepared()
            assertThat(record["newGames"]).isEqualTo(0)
            assertThat(record["modelForwards"]).isEqualTo(0)
            assertThat(record["maximumTerminalExecutions"]).isEqualTo(6)
            assertThat(input.asset.current.source.sha256).isEqualTo(input.candidate.source.sha256)
        }
    }

    @Test
    fun shardCannotExtendPastItsFrozenWorldBank() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(source(fixture, mapOf("count" to 3)).path)
            }
        }
    }

    @Test
    fun candidateOnlyCollectionHasOneRoleAndAnExactReducedExecutionBound() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val input = RobustTokenInput.read(source(fixture, mapOf("candidateOnly" to true)).path)
            assertThat(input.roles).containsExactly("CANDIDATE")
            assertThat(input.prepared()["maximumTerminalExecutions"]).isEqualTo(3)
            assertThat(input.prepared()["qualificationClaimed"]).isEqualTo(false)
        }
    }

    @Test
    fun candidateOnlyFlagCannotBeAnAmbiguousString() {
        CoordinatedWinQualificationFixture().use { fixture ->
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(source(fixture, mapOf("candidateOnly" to "true")).path)
            }
        }
    }

    @Test
    fun shardBoundariesDoNotChangeScientificIdentity() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val first = RobustTokenInput.read(source(fixture, mapOf("count" to 1)).path)
            val second =
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf("globalStart" to 1, "count" to 1),
                        )
                        .path
                )
            assertThat(first.scienceSha256).isEqualTo(second.scienceSha256)
        }
    }

    @Test
    fun experimentalBundleCannotChangeFrozenValueParameters() {
        CoordinatedWinQualificationFixture().use { fixture ->
            val token =
                PurchaseJson.read(fixture.toy.original.subject(CoordinatedEsHead.TOKEN).path)
                    as ObjectNode
            token.put("valueBias", token["valueBias"].doubleValue() + 1.0)
            val changed = fixture.save("bad-token.json", token)
            val bundle = PurchaseJson.read(fixture.toy.original.source.path) as ObjectNode
            (bundle["heads"]["TOKEN"] as ObjectNode)
                .put("path", "bad-token.json")
                .put("sha256", changed.sha256)
            val candidate = fixture.save("bad-candidate.json", bundle)
            assertFailsWith<IllegalArgumentException> {
                RobustTokenInput.read(
                    source(
                            fixture,
                            mapOf("candidateBundle" to fixture.ref(candidate)),
                        )
                        .path
                )
            }
        }
    }
}
