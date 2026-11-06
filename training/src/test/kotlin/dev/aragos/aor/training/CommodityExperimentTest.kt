package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.Prompt
import dev.aragos.aor.ShippingFleet
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import kotlin.test.Test

class CommodityExperimentTest {
    @Test
    fun curriculumUsesTheSelectedFourPlayerConfiguration() {
        assertThat(CommodityExperiment.configuration.id)
            .isEqualTo("players-4/standard/no-crusades/deck-true/art-true")
    }

    @Test
    fun fixtureMakesEveryUnknownPhysicalCardLocationPubliclyConstrained() {
        val scenario = CommodityExperiment.scenario(24)
        val state = scenario.state
        val actor =
            checkNotNull(state.players[scenario.actor]) {
                "The fixture must contain its acting seat."
            }

        assertThat(
                state.players.filterKeys { it != scenario.actor }.values.all { it.hand.isEmpty() }
            )
            .isTrue()
        assertThat(state.viewForPlayer(scenario.actor).unplayedCards.map { it.id }.toSet())
            .isEqualTo((state.deck + actor.hand.map { it.definition.id }).toSet())
        assertThat((state.prompts().single() as Prompt.SinglePlayer).player)
            .isEqualTo(scenario.actor)
    }

    @Test
    fun scenarioSeedReproducesTheCompleteFixture() {
        assertThat(CommodityExperiment.scenario(27)).isEqualTo(CommodityExperiment.scenario(27))
    }

    @Test
    fun hiddenDeckOrderDoesNotChangeFeaturesOrPlanActions() {
        val scenario = CommodityExperiment.scenario(24)
        val first = CommodityExperiment.sampledState(scenario, 1)
        val second = CommodityExperiment.sampledState(scenario, 2)
        val firstView = first.viewForDecision(scenario.actor)
        val secondView = second.viewForDecision(scenario.actor)
        val firstObservation = BotObservations.from(first, scenario.actor)
        val secondObservation = BotObservations.from(second, scenario.actor)

        assertThat(first.deck.sorted()).isEqualTo(second.deck.sorted())
        assertThat(firstView).isEqualTo(secondView)
        assertThat(CommodityExperiment.features(firstView, scenario.actor, scenario.commodity))
            .isEqualTo(CommodityExperiment.features(secondView, scenario.actor, scenario.commodity))
        assertThat(
                CommodityExperiment.command(
                    firstView,
                    firstObservation,
                    CommodityExperimentStrategy.HOLD_EXPAND,
                    scenario.commodity,
                    scenario.cardReference,
                    scenario.startTurn,
                    BotModels.default,
                    1,
                )
            )
            .isEqualTo(
                CommodityExperiment.command(
                    secondView,
                    secondObservation,
                    CommodityExperimentStrategy.HOLD_EXPAND,
                    scenario.commodity,
                    scenario.cardReference,
                    scenario.startTurn,
                    BotModels.default,
                    1,
                )
            )
    }

    @Test
    fun holdingPlansRetainTheFocusedCardDuringTheInitialCardWindow() {
        val scenario = CommodityExperiment.scenario(24)
        val observation = BotObservations.from(scenario.state, scenario.actor)

        val command =
            CommodityExperiment.command(
                scenario.state.viewForDecision(scenario.actor),
                observation,
                CommodityExperimentStrategy.HOLD_EXPAND,
                scenario.commodity,
                scenario.cardReference,
                scenario.startTurn,
                BotModels.default,
                1,
            )

        assertThat(command).doesNotMatch("play ${scenario.cardReference}( .*|$)")
        val after =
            scenario.state.step(TextCodec.parseAction(command, scenario.state, scenario.actor))
        assertThat(after.players[scenario.actor]?.hand?.map { it.reference })
            .contains(scenario.cardReference)
    }

    @Test
    fun retainingOneCommodityDoesNotSkipOtherUsefulCards() {
        val scenario =
            (0..63).map(CommodityExperiment::scenario).first { fixture ->
                fixture.state.stage == Stage.PLAY_CARDS &&
                    fixture.state.viewForDecision(fixture.actor).cardOffers.any {
                        it.playable && it.reference != fixture.cardReference
                    }
            }
        val state = scenario.state
        val model =
            BotModels.default.copy(weights = BotModels.default.weights + ("progress" to 1000.0))

        val command =
            CommodityExperiment.command(
                state.viewForDecision(scenario.actor),
                BotObservations.from(state, scenario.actor),
                CommodityExperimentStrategy.HOLD_EXPAND,
                scenario.commodity,
                scenario.cardReference,
                scenario.startTurn,
                model,
                1,
            )

        assertThat(command).startsWith("play ")
        assertThat(command).doesNotMatch("play ${scenario.cardReference}( .*|$)")
        state.step(TextCodec.parseAction(command, state, scenario.actor))
    }

    @Test
    fun holdingPlansCashOutInTheFinalCardWindow() {
        val scenario = CommodityExperiment.scenario(27)

        val command =
            CommodityExperiment.command(
                scenario.state.viewForDecision(scenario.actor),
                BotObservations.from(scenario.state, scenario.actor),
                CommodityExperimentStrategy.HOLD_EXPAND,
                scenario.commodity,
                scenario.cardReference,
                scenario.startTurn,
                BotModels.default,
                1,
            )

        assertThat(scenario.state.stage).isEqualTo(Stage.FINAL_CARD_PLAY)
        assertThat(command).startsWith("play ${scenario.cardReference}")
        val after =
            scenario.state.step(TextCodec.parseAction(command, scenario.state, scenario.actor))
        assertThat(after.players[scenario.actor]?.hand?.map { it.reference })
            .doesNotContain(scenario.cardReference)
    }

    @Test
    fun commodityExpansionPlanChoosesAnOfferedMatchingMarket() {
        val scenario =
            (0..63).map(CommodityExperiment::scenario).first { fixture ->
                val view =
                    fixture.state
                        .copy(
                            stage = Stage.EXPANSION,
                            flow =
                                TurnFlow(
                                    actor = fixture.actor,
                                    cursor = fixture.state.turnOrder.indexOf(fixture.actor),
                                    phaseStarted = true,
                                    phaseBookkeepingApplied = true,
                                ),
                        )
                        .viewForDecision(fixture.actor)
                val catalog =
                    checkNotNull(view.catalog) { "The fixture requires a public catalog." }
                val targets =
                    catalog.provinces.filter { fixture.commodity in it.commodities }.map { it.id }
                view.expansionOffers.any {
                    it.destination in targets &&
                        it.establishesMarker &&
                        !it.uncertain &&
                        it.blockers.isEmpty() &&
                        it.requiredTokens > 0
                }
            }
        val state =
            scenario.state.copy(
                stage = Stage.EXPANSION,
                flow =
                    TurnFlow(
                        actor = scenario.actor,
                        cursor = scenario.state.turnOrder.indexOf(scenario.actor),
                        phaseStarted = true,
                        phaseBookkeepingApplied = true,
                    ),
            )
        val view = state.viewForDecision(scenario.actor)
        val observation = BotObservations.from(state, scenario.actor)

        val command =
            CommodityExperiment.command(
                view,
                observation,
                CommodityExperimentStrategy.HOLD_EXPAND,
                scenario.commodity,
                scenario.cardReference,
                scenario.startTurn,
                BotModels.default,
                1,
            )

        assertThat(command).startsWith("place ")
        assertThat(observation.candidates.map { it.command }).contains(command)
        val destination = command.substringAfterLast(' ')
        val catalog = checkNotNull(view.catalog) { "The expansion view requires a public catalog." }
        assertThat(catalog.provinces.single { it.id.value == destination }.commodities)
            .contains(scenario.commodity)
        state.step(TextCodec.parseAction(command, state, scenario.actor))
    }

    @Test
    fun everyFixtureOffersAllFourLegalInitialPlanActions() {
        for (seed in 0..31) {
            val scenario = CommodityExperiment.scenario(seed)
            val view = scenario.state.viewForDecision(scenario.actor)
            val observation = BotObservations.from(scenario.state, scenario.actor)
            assertThat(CommodityExperiment.features(view, scenario.actor, scenario.commodity))
                .hasSize(CommodityExperiment.featureNames.size)
            for (strategy in CommodityExperimentStrategy.entries) {
                val command =
                    CommodityExperiment.command(
                        view,
                        observation,
                        strategy,
                        scenario.commodity,
                        scenario.cardReference,
                        scenario.startTurn,
                        BotModels.default,
                        seed,
                    )
                assertThat(observation.candidates.map { it.command }).contains(command)
                scenario.state.step(TextCodec.parseAction(command, scenario.state, scenario.actor))
            }
        }
    }

    @Test
    fun keepBaselineDelegatesExactlyForEveryScenarioFamily() {
        for (seed in 0..31) {
            val scenario = CommodityExperiment.scenario(seed)
            val observation = BotObservations.from(scenario.state, scenario.actor)

            val command =
                CommodityExperiment.command(
                    scenario.state.viewForDecision(scenario.actor),
                    observation,
                    CommodityExperimentStrategy.KEEP_BASELINE,
                    scenario.commodity,
                    scenario.cardReference,
                    scenario.startTurn,
                    BotModels.default,
                    seed,
                )

            assertThat(command)
                .isEqualTo(
                    BotPolicy.choose(observation, BotDifficulty.HARD, seed, BotModels.default)
                )
        }
    }

    @Test
    fun heuristicKeepsBaselineWhenNoExpansionPoolIsAvailable() {
        val scenario = CommodityExperiment.scenario(5)

        val strategy =
            CommodityExperiment.heuristic(
                scenario.state.viewForDecision(scenario.actor),
                scenario.actor,
                scenario.commodity,
            )

        assertThat(strategy).isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE)
    }

    @Test
    fun developmentProfilesRespectAdvanceOrderAndShippingPrerequisites() {
        val scenarios = (0..31).map(CommodityExperiment::scenario)

        for (scenario in scenarios) {
            for (player in scenario.state.players.values) {
                val acquisitions = player.advances.acquisitions
                for ((index, acquisition) in acquisitions.withIndex()) {
                    assertThat(acquisitions.take(index).map { it.advance })
                        .containsAtLeastElementsIn(
                            AdvanceData.definition(acquisition.advance).prerequisites
                        )
                    assertThat(acquisition.turnNumber).isLessThan(scenario.startTurn)
                }
                when (player.ships.fleet) {
                    ShippingFleet.SEAWORTHY ->
                        assertThat(player.advances.owned).contains(AdvanceId.SEAWORTHY_VESSELS)
                    ShippingFleet.OCEAN ->
                        assertThat(player.advances.owned).contains(AdvanceId.OCEAN_NAVIGATION)
                    else -> Unit
                }
            }
        }
        assertThat(scenarios.flatMap { it.state.players.values }.map { it.ships.fleet }.toSet())
            .containsExactly(ShippingFleet.GALLEY, ShippingFleet.SEAWORTHY, ShippingFleet.OCEAN)
    }

    @Test
    fun eachFixtureMatchesDevelopmentAcrossSeatsToKeepCommodityChoicesRelevant() {
        val scenarios = (0..31).map(CommodityExperiment::scenario)

        for (scenario in scenarios) {
            assertThat(scenario.state.players.values.map { it.advances }.distinct()).hasSize(1)
            assertThat(scenario.state.players.values.map { it.ships }.distinct()).hasSize(1)
        }
        assertThat(scenarios.map { it.state.players[it.actor]?.advances }.distinct()).hasSize(3)
    }

    @Test
    fun broaderFixturesConservePhysicalCardsAcrossHandDeckAndPublicHistory() {
        val scenarios = (0..63).map(CommodityExperiment::scenario)

        for (scenario in scenarios) {
            val state = scenario.state
            val actor =
                checkNotNull(state.players[scenario.actor]) { "The fixture requires its actor." }
            val live = state.deck + actor.hand.map { it.definition.id }
            assertThat(live.distinct()).hasSize(live.size)
            assertThat(live.toSet().intersect(state.playedCards)).isEmpty()
            assertThat(state.viewForPlayer(scenario.actor).unplayedCards.map { it.id }.toSet())
                .isEqualTo(live.toSet())
            assertThat(
                    state.players
                        .filterKeys { it != scenario.actor }
                        .values
                        .all { it.hand.isEmpty() }
                )
                .isTrue()
        }
        assertThat(scenarios.map { it.state.players[it.actor]?.hand?.size }.toSet())
            .containsExactly(1, 2, 3)
        assertThat(scenarios.maxOf { it.state.deck.size }).isGreaterThan(12)
        assertThat(scenarios.mapNotNull { it.state.players[it.actor]?.cash }.max())
            .isGreaterThan(240)
    }

    @Test
    fun observableFeaturesIncludeDevelopmentAndHandComposition() {
        val scenario =
            (0..31).map(CommodityExperiment::scenario).first { fixture ->
                fixture.state.players[fixture.actor]?.hand?.size == 3
            }
        val view = scenario.state.viewForDecision(scenario.actor)

        val features =
            CommodityExperiment.featureNames
                .zip(CommodityExperiment.features(view, scenario.actor, scenario.commodity))
                .toMap()

        assertThat(features["advanceCount"]).isGreaterThan(0.0)
        assertThat(features["advanceScore"]).isGreaterThan(0.0)
        assertThat(features["shippingLevel"]).isGreaterThan(0.0)
        assertThat(features["handCount"]).isEqualTo(0.3)
        assertThat(CommodityExperimentStrategy.entries.first())
            .isEqualTo(CommodityExperimentStrategy.KEEP_BASELINE)
    }

    @Test
    fun fixtureContinuationReplaysAcrossExpansionAndTheNextCardWindow() {
        val scenario = CommodityExperiment.scenario(24)
        val initial = CommodityExperiment.sampledState(scenario, 77001)
        val session = ExperimentSession(initial, 77001L, recordEntries = true)
        var expansionVisited = false
        while (session.state.stage != Stage.FINISHED) {
            check(session.decisions < 1200) {
                "The late-game fixture must finish within 1200 decisions."
            }
            expansionVisited = expansionVisited || session.state.stage == Stage.EXPANSION
            val actions =
                session.actors().associateWith { actor ->
                    val observation = session.observation(actor)
                    if (actor == scenario.actor) {
                        CommodityExperiment.command(
                            session.state.viewForDecision(actor),
                            observation,
                            CommodityExperimentStrategy.HOLD_EXPAND,
                            scenario.commodity,
                            scenario.cardReference,
                            scenario.startTurn,
                            BotModels.default,
                            session.decisions,
                        )
                    } else {
                        BotPolicy.choose(
                            observation,
                            BotDifficulty.HARD,
                            session.decisions,
                            BotModels.default,
                        )
                    }
                }
            session.apply(actions)
        }
        val replayed =
            session.entries.fold(initial) { state, entry ->
                state.step(TextCodec.parse(entry.toText()))
            }

        assertThat(expansionVisited).isTrue()
        assertThat(replayed).isEqualTo(session.state)
        assertThat(replayed.stage).isEqualTo(Stage.FINISHED)
    }
}
