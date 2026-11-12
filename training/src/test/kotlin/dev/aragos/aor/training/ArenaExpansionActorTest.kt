package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ArenaExpansionActorTest {
    @Test
    fun candidateReusesExactCollectionSamplerAndLeavesOtherSeatsAlone() {
        ExpansionOnPolicyFixture().use { fixture ->
            val input = fixture.input
            val arena = input.arena.copy(expansionActor = actor(fixture))
            for (number in 1..20) {
                val decision = fixture.decision(input, number)
                val expected = input.controllers(0)
                val actual =
                    arena.controllers(4, 0, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
                for (seat in 0..3) {
                    assertThat(actual[seat]?.choose(decision))
                        .isEqualTo(expected[seat]?.choose(decision))
                }
            }
        }
    }

    @Test
    fun missingIncumbentActorPreservesExactOrdinaryComposition() {
        ExpansionOnPolicyFixture().use { fixture ->
            val base = fixture.input.arena
            val wrapped = base.copy(expansionActor = actor(fixture))
            val decision = fixture.decision()
            val expected =
                base.controllers(4, 0, ArenaSide.INCUMBENT, base.incumbent, List(4) { 0 })
            val actual =
                wrapped.controllers(4, 0, ArenaSide.INCUMBENT, base.incumbent, List(4) { 0 })
            assertThat((0..3).map { actual[it]?.choose(decision) })
                .isEqualTo((0..3).map { expected[it]?.choose(decision) })
        }
    }

    @Test
    fun nullActorHasNoWireFieldOrMetadataIdentityChange() {
        ExpansionOnPolicyFixture().use { fixture ->
            val base = fixture.input.arena
            assertThat(base.copy(expansionActor = null).identity()).isEqualTo(base.identity())
            assertThat(base.overlayMetadata()).doesNotContainKey("expansionActor")
            assertThat(
                    ExpansionOnPolicyStore.wire(StrategyArena.pair(base, 0, terminal(), terminal()))
                        .has("expansionActor")
                )
                .isFalse()
        }
    }

    @Test
    fun explicitActorRejectsOrdinarySideAmbiguity() {
        ExpansionOnPolicyFixture().use { fixture ->
            val base = fixture.input.arena.copy(expansionActor = actor(fixture))
            assertFailsWith<IllegalArgumentException> {
                base.controllers(4, 0, null, base.candidate, List(4) { 0 })
            }
        }
    }

    @Test
    fun changedModelBytesRejectBeforeInference() {
        ExpansionOnPolicyFixture().use { fixture ->
            val source = declaration(fixture).deepCopy()
            (source["candidate"] as ObjectNode).put("sha256", "0".repeat(64))
            assertFailsWith<IllegalArgumentException> {
                ArenaExpansionActor.read(fixture.source, source)
            }
        }
    }

    @Test
    fun schemaOneActorCannotEnterTheNewProfile() {
        ExpansionOnPolicyFixture().use { fixture ->
            val source = declaration(fixture)
            val model = PurchaseJson.read(fixture.actorPath) as ObjectNode
            model.put("schemaVersion", 1)
            fixture.files.write("expansion-actor.json", model)
            (source["candidate"] as ObjectNode).put("sha256", PurchaseJson.hash(fixture.actorPath))
            assertFailsWith<IllegalArgumentException> {
                ArenaExpansionActor.read(fixture.source, source)
            }
        }
    }

    @Test
    fun replayRejectsMissingExpansionRoleBinding() {
        ExpansionOnPolicyFixture().use { fixture ->
            val arena = fixture.input.arena.copy(expansionActor = actor(fixture))
            val pair =
                ExpansionOnPolicyStore.wire(StrategyArena.pair(arena, 0, terminal(), terminal()))
                    as ObjectNode
            pair.remove("expansionActor")
            fixture.files.write("pair.json", pair)
            assertFailsWith<IllegalArgumentException> {
                StrategyReplayPair.read(fixture.files.directory.resolve("pair.json"))
                    .opponentIndices(arena, 0)
            }
        }
    }

    @Test
    fun replayAcceptsExactActorRole() {
        ExpansionOnPolicyFixture().use { fixture ->
            val arena = fixture.input.arena.copy(expansionActor = actor(fixture))
            fixture.files.write("pair.json", StrategyArena.pair(arena, 0, terminal(), terminal()))
            assertThat(
                    StrategyReplayPair.read(fixture.files.directory.resolve("pair.json"))
                        .opponentIndices(arena, 0)
                )
                .containsExactly(0, 0, 0, 0)
        }
    }

    @Test
    fun snapshotPreservesExactActorBytes() {
        ExpansionOnPolicyFixture().use { fixture ->
            val arena = fixture.input.arena.copy(expansionActor = actor(fixture))
            arena.snapshotOverlays(TrainingArtifacts(fixture.output))
            assertThat(PurchaseJson.hash(fixture.output.resolve("expansion-actor-candidate.json")))
                .isEqualTo(PurchaseJson.hash(fixture.actorPath))
        }
    }

    private fun declaration(fixture: ExpansionOnPolicyFixture): ObjectNode =
        ExpansionOnPolicyStore.wire(
            mapOf(
                "version" to ArenaExpansionActor.VERSION,
                "candidate" to
                    mapOf(
                        "path" to fixture.actorPath.toString(),
                        "sha256" to PurchaseJson.hash(fixture.actorPath),
                    ),
            )
        ) as ObjectNode

    private fun actor(fixture: ExpansionOnPolicyFixture) =
        ArenaExpansionActor.read(fixture.source, declaration(fixture))

    private fun terminal() =
        ArenaGame(List(4) { .25 }, List(4) { .5 }, List(4) { .5 }, emptyList(), 0, 0)
}
