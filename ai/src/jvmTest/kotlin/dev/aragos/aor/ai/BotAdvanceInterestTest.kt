package dev.aragos.aor.ai

import com.google.common.truth.Truth.assertThat
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.AdvancePurchases
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import kotlin.test.Test

class BotAdvanceInterestTest {
    private val interestFeature = BotEconomicFeature.FOREGONE_INTEREST.key
    private val model =
        BotModel(weights = mapOf("points" to 1.0, "income" to 1.0, interestFeature to 1.0))

    @Test
    fun twoIndividuallyHarmlessPurchasesCanCrossTheInterestCapTogether() {
        val observation = nonlinearObservation()
        val interest = interest(observation)

        val single = interest.value(70, setOf("buy-advance A"))
        val combined = interest.value(40, setOf("buy-advance A", "buy-advance B"))

        assertThat(single).isEqualTo(0.0)
        assertThat(combined).isEqualTo(-20.0)
    }

    @Test
    fun cumulativeInterestRejectsTheSeeminglyBetterThirtyPlusThirtyBundle() {
        val observation = nonlinearObservation()

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("buy-advance C")
    }

    @Test
    fun acquiringInterestAloneKeepsItsStandaloneQuoteButOtherSpendingReducesIt() {
        val observation =
            observation(
                ownsInterest = false,
                candidates = listOf(candidate("L", 30, income = 60), candidate("A", 30)),
            )
        val interest = interest(observation)

        val alone = interest.value(70, setOf("buy-advance L"))
        val combined = interest.value(40, setOf("buy-advance L", "buy-advance A"))
        val beforeAcquisition = interest.value(70, setOf("buy-advance A"))

        assertThat(alone).isEqualTo(0.0)
        assertThat(combined).isEqualTo(-20.0)
        assertThat(beforeAcquisition).isEqualTo(0.0)
    }

    @Test
    fun aPlanCannotKeepTheFullQuotedInterestAfterSpendingItsCash() {
        val observation =
            observation(
                ownsInterest = false,
                candidates =
                    listOf(
                        candidate("L", 30, income = 60),
                        candidate("A", 30, points = 25),
                        candidate("T", 100, points = 80),
                    ),
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("buy-advance T")
    }

    @Test
    fun researchSavingRecoversBothCashAndOtherwiseLostInterest() {
        val observation =
            observation(
                cap = 80,
                candidates = listOf(candidate("X", 10), candidate("B", 60)),
            )
        val model =
            BotModel(
                weights =
                    mapOf(
                        "cash" to 1.0,
                        interestFeature to 1.0,
                        BotAdvanceFeature.RESEARCH_SAVING.key to 1.0,
                    )
            )
        val sequence =
            checkNotNull(BotAdvanceSequencing.forObservation(observation, model)) {
                "The research fixture requires repricing."
            }
        val interest = interest(observation, model)
        val purchase = observation.candidates.single { it.command == "buy-advance B" }
        val research = setOf("buy-advance X")
        val commands = research + purchase.command

        val price = sequence.price(purchase, research)
        val recoveredInterest =
            interest.value(100 - 10 - price, commands) -
                interest.value(100 - 10 - purchase.cashCost, commands)

        assertThat(price).isEqualTo(50)
        assertThat(recoveredInterest).isEqualTo(10.0)
        assertThat(sequence.value(purchase, research) + recoveredInterest).isEqualTo(20.0)
    }

    @Test
    fun isolatedForegoneInterestIsRemovedBeforeTheCumulativeTermIsAdded() {
        val observation =
            observation(
                cash = 50,
                candidates =
                    listOf(
                        candidate("A", 20, points = 30, foregone = -20),
                        candidate("B", 20, points = 29, foregone = -20),
                        candidate("C", 0, points = 15),
                    ),
            )

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("buy-advance A")
    }

    @Test
    fun aSingleMiddleClassPurchaseCanRaiseAnExistingInterestReceipt() {
        val observation = observation(candidates = listOf(candidate("Z", 0, points = -5)))

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("buy-advance Z")
    }

    @Test
    fun plannedMiddleClassRaisesTheCapForNewlyAcquiredInterest() {
        val observation =
            observation(
                ownsInterest = false,
                candidates = listOf(candidate("L", 10, income = 60), candidate("Z", 0)),
            )

        val correction = interest(observation).value(90, setOf("buy-advance L", "buy-advance Z"))

        assertThat(correction).isEqualTo(10.0)
    }

    @Test
    fun zeroWeightPreservesThePreviousBeamChoices() {
        val observation = nonlinearObservation()
        val disabled = BotModel(weights = model.weights + (interestFeature to 0.0))

        val current = BotPolicy.choose(observation, BotDifficulty.HARD, 0, disabled)
        val previous =
            BotPolicy.choose(
                observation.copy(advancePlanning = null),
                BotDifficulty.HARD,
                0,
                disabled,
            )

        assertThat(current).isEqualTo("buy-advance C")
        assertThat(current).isEqualTo(previous)
        assertThat(BotAdvanceInterest.forObservation(observation, disabled)).isNull()
    }

    @Test
    fun olderMetadataWithoutAnIncomeQuoteRetainsIsolatedInterestScoring() {
        val original = nonlinearObservation()
        val planning =
            checkNotNull(original.advancePlanning) { "The fixture requires advance metadata." }
        val observation = original.copy(advancePlanning = planning.copy(ordinaryIncome = null))

        val selected = BotPolicy.choose(observation, BotDifficulty.HARD, 0, model)

        assertThat(selected).isEqualTo("buy-advance A")
        assertThat(BotAdvanceInterest.forObservation(observation, model)).isNull()
    }

    private fun interest(
        observation: BotObservation,
        model: BotModel = this.model,
    ): BotAdvanceInterest =
        checkNotNull(BotAdvanceInterest.forObservation(observation, model)) {
            "An interest fixture requires a weighted receipt projection."
        }

    private fun nonlinearObservation(): BotObservation =
        observation(
            candidates =
                listOf(
                    candidate("A", 30, points = 22),
                    candidate("B", 30, points = 21),
                    candidate("C", 80, points = 75, foregone = -40),
                )
        )

    private fun observation(
        cash: Int = 100,
        cap: Int = 60,
        ownsInterest: Boolean = true,
        candidates: List<BotCandidate>,
    ): BotObservation =
        BotObservation(
            playerId = "p1",
            decision = "purchase-advances",
            context = mapOf("stabilization" to 0.0),
            availableCash = cash,
            availableTokens = 0,
            candidates = candidates + BotCandidate("pass", emptyMap()),
            rulesVersion = RULES_VERSION,
            dataVersion = DATA_VERSION,
            advancePlanning =
                BotAdvancePlanning(
                    steps =
                        candidates.associate { candidate ->
                            val advance = AdvanceId.fromLetter(candidate.command.last())
                            candidate.command to
                                BotAdvanceStep(
                                    advance,
                                    minOf(
                                        candidate.cashCost,
                                        AdvancePurchases.institutionalResearchCredit(
                                            advance,
                                            owned = true,
                                        ),
                                    ),
                                )
                        },
                    ownsInterestProfit = ownsInterest,
                    ordinaryIncome = cap,
                ),
        )

    private fun candidate(
        letter: String,
        cost: Int,
        points: Int = 0,
        income: Int = 0,
        foregone: Int = 0,
    ): BotCandidate =
        BotCandidate(
            command = "buy-advance $letter",
            cashCost = cost,
            family = "advance",
            group = "advance.$letter",
            features =
                mapOf(
                        "points" to points.toDouble(),
                        "cash" to -cost.toDouble(),
                        "income" to income.toDouble(),
                        interestFeature to foregone.toDouble(),
                    )
                    .filterValues { it != 0.0 },
        )
}
