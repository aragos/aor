package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.ai.BOT_POLICY_VERSION
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerBotPolicyTest {
    @Test
    fun onlyNewCompatibleHardSeatsReceiveTheExplicitProfile(): Unit =
        ProfileFixture().use { fixture ->
            val bots = fixture.service.store.read(fixture.game.gameId).bots.values

            assertThat(bots.map(AutomatedPlayer::policyVersion))
                .containsExactlyElementsIn(
                    List(3) { ServerBotProfile.COORDINATED_83_SUPPORT_1.version }
                )
        }

    @Test
    fun normalSeatsRetainTheLegacyWorkerIdentity(): Unit =
        ProfileFixture(difficulty = BotDifficulty.NORMAL).use { fixture ->
            val job = fixture.claim().observations.first()

            assertThat(job.proposal).isNull()
            assertThat(fixture.policy.calls).isEmpty()
            assertThat(
                    fixture.service.store.read(fixture.game.gameId).bots.values.map {
                        it.policyVersion
                    }
                )
                .containsExactlyElementsIn(List(3) { BOT_POLICY_VERSION })
        }

    @Test
    fun anotherSetupRetainsTheLegacyWorkerIdentity(): Unit =
        ProfileFixture(rules = RuleOptions()).use { fixture ->
            val job = fixture.claim().observations.first()

            assertThat(job.proposal).isNull()
            assertThat(fixture.policy.calls).isEmpty()
        }

    @Test
    fun proposalUsesOnlyTheActingSeatsViewAndExistingSeed(): Unit =
        ProfileFixture().use { fixture ->
            val work = fixture.claim()
            val job = work.observations.single()
            val stored = fixture.service.store.read(fixture.game.gameId)

            assertThat(fixture.policy.calls.single())
                .isEqualTo(
                    ProfileInput(stored.state().viewForPlayer(job.seat), job.observation, job.seed)
                )
            assertThat(job.proposal)
                .isEqualTo(
                    BotPolicyProposal(
                        fixture.policy.profile.version,
                        fixture.policy.assetSha256,
                        job.observation.candidates.first().command,
                    )
                )
            assertThat(job.purchase).isNull()
            assertThat(job.card).isNull()
            assertThat(stored.pendingBids).isEmpty()
        }

    @Test
    fun proposalWireContainsOnlyIdentityAndTheCanonicalCommand(): Unit =
        ProfileFixture().use { fixture ->
            val job = fixture.claim().observations.single()

            val wire = json.readTree(json.writeValueAsBytes(job))["proposal"]

            assertThat(wire.fieldNames().asSequence().toSet())
                .containsExactly("profile", "assetSha256", "command")
        }

    @Test
    fun aDifferentOfferedCommandCannotReplaceTheServerProposal(): Unit =
        ProfileFixture().use { fixture ->
            val work = fixture.claim()
            val job = work.observations.single()
            val alternative =
                job.observation.candidates.first { it.command != job.proposal?.command }.command

            val failure = assertFailsWith<ServiceFailure> { fixture.submit(work, job, alternative) }

            assertThat(failure.code).isEqualTo("BOT_POLICY_COMMAND_MISMATCH")
            assertThat(fixture.service.store.read(fixture.game.gameId).pendingBids).isEmpty()
        }

    @Test
    fun theExactProposalRetainsPrivateSubmissionAndIdempotency(): Unit =
        ProfileFixture().use { fixture ->
            val work = fixture.claim()
            val job = work.observations.single()
            val command =
                requireNotNull(job.proposal) { "The fixture requires its server proposal." }.command
            val operation = UUID.randomUUID().toString()
            fixture.submit(work, job, command, operation)
            val once = fixture.service.store.read(fixture.game.gameId)

            fixture.submit(work, job, command, operation)

            val twice = fixture.service.store.read(fixture.game.gameId)
            assertThat(twice.pendingBids).isEqualTo(once.pendingBids)
            assertThat(twice.submissionVersions).isEqualTo(once.submissionVersions)
            assertThat(twice.botRunner?.receipts?.count { it.operationId == operation })
                .isEqualTo(1)
            assertThat(twice.pendingBids.keys).containsExactly(job.seat)
        }

    @Test
    fun anUnavailableStoredServerProfileDoesNotFallBackToTheWorker(): Unit =
        ProfileFixture().use { fixture ->
            val legacyService = GameService(fixture.service.store, fixture.service.driver)

            val failure = assertFailsWith<ServiceFailure> { fixture.claim(legacyService) }

            assertThat(failure.code).isEqualTo("BOT_POLICY_UNAVAILABLE")
            assertThat(fixture.service.store.read(fixture.game.gameId).pendingBids).isEmpty()
        }

    @Test
    fun existingLegacySeatsStayPlayableWithTheNewProfileAvailable(): Unit =
        ProfileFixture().use { fixture ->
            fixture.service.store.update(fixture.game.gameId) { game ->
                game.copy(
                    bots =
                        game.bots.mapValues { (_, bot) ->
                            bot.copy(policyVersion = BOT_POLICY_VERSION, policyAssetSha256 = null)
                        }
                )
            }
            val restored =
                GameDocuments.read(
                    fixture.game.gameId,
                    GameDocuments.write(fixture.service.store.read(fixture.game.gameId)),
                )
            val work = fixture.claim()
            val job = work.observations.single()
            fixture.submit(work, job, job.observation.candidates.first().command)

            assertThat(restored.bots.values.all { it.policyVersion == BOT_POLICY_VERSION }).isTrue()
            assertThat(job.proposal).isNull()
            assertThat(fixture.policy.calls).isEmpty()
            assertThat(fixture.service.store.read(fixture.game.gameId).pendingBids.keys)
                .containsExactly(job.seat)
        }

    @Test
    fun aStoredProfileAndAssetBindingSurviveDocumentRoundTrip(): Unit =
        ProfileFixture().use { fixture ->
            val original = fixture.service.store.read(fixture.game.gameId)

            val restored = GameDocuments.read(fixture.game.gameId, GameDocuments.write(original))

            assertThat(restored.bots).isEqualTo(original.bots)
            assertThat(restored.bots.values.map { it.policyAssetSha256 })
                .containsExactlyElementsIn(List(3) { fixture.policy.assetSha256 })
        }

    @Test
    fun aDifferentAssetCannotReplaceASavedProfilesPolicy(): Unit =
        ProfileFixture().use { fixture ->
            val replacement = OfferedProfile("b".repeat(64))
            val changedService =
                GameService(
                    fixture.service.store,
                    fixture.service.driver,
                    serverBotPolicy = replacement,
                )

            val failure = assertFailsWith<ServiceFailure> { fixture.claim(changedService) }

            assertThat(failure.code).isEqualTo("BOT_POLICY_UNAVAILABLE")
            assertThat(replacement.calls).isEmpty()
        }

    @Test
    fun aNonCreatorCannotObtainTheServerProposal(): Unit =
        ProfileFixture(humans = 2).use { fixture ->
            val second = GameAccess.SeatToken(fixture.game.links[1].path.substringAfter("token="))

            val failure =
                assertFailsWith<ServiceFailure> {
                    BotAutomation.control(
                        fixture.service,
                        fixture.game.gameId,
                        second,
                        "claim",
                        fixture.runnerId,
                        0,
                        now = 1000,
                    )
                }

            assertThat(failure.code).isEqualTo("BOT_CONTROL_FORBIDDEN")
            assertThat(fixture.policy.calls).isEmpty()
        }
}

private data class ProfileInput(val view: GameView, val observation: BotObservation, val seed: Int)

/**
 * Records constructed policy inputs; this fixture never loads scientific models or plays a game.
 */
private class OfferedProfile(override val assetSha256: String = "a".repeat(64)) : ServerBotPolicy {
    override val profile = ServerBotProfile.COORDINATED_83_SUPPORT_1
    val calls = mutableListOf<ProfileInput>()

    override fun choose(view: GameView, observation: BotObservation, seed: Int): String {
        calls += ProfileInput(view, observation, seed)
        return observation.candidates.first().command
    }
}

private class ProfileFixture(
    difficulty: BotDifficulty = BotDifficulty.HARD,
    rules: RuleOptions =
        RuleOptions(
            deckModification = true,
            initialCard = InitialCardRule.NO_CRUSADES,
            immediateMasterArtDiscard = true,
        ),
    humans: Int = 1,
) : AutoCloseable {
    private val directory = Files.createTempDirectory("aor-server-profile-")
    val policy = OfferedProfile()
    val service =
        GameService(FileGameStore(directory), GameDriver(FixedRandom()), serverBotPolicy = policy)
    val game =
        service.create(
            List(humans) { "Human $it" },
            rules = rules,
            bots = List(4 - humans) { AutomatedPlayerRequest("Bot $it", difficulty) },
        )
    val runnerId = UUID.randomUUID().toString()
    private val access = GameAccess.SeatToken(game.links.first().path.substringAfter("token="))

    fun claim(owner: GameService = service): BotWork =
        BotAutomation.control(owner, game.gameId, access, "claim", runnerId, 0, now = 1000)

    fun submit(
        work: BotWork,
        job: BotDecisionWork,
        command: String,
        operation: String = UUID.randomUUID().toString(),
    ): BotWork =
        BotAutomation.submit(
            service,
            game.gameId,
            access,
            runnerId,
            work.epoch,
            work.revision,
            job.submissionVersion,
            job.seat,
            job.decisionId,
            command,
            operation,
            now = 1100,
        )

    override fun close() {
        directory.toFile().deleteRecursively()
    }
}
