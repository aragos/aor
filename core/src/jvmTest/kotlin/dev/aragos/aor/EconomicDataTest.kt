package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EconomicDataTest {
    @Test
    fun advanceCatalogCoversThePrintedLettersExactlyOnce() {
        assertThat(AdvanceData.definitions.map { advance -> advance.id.letter })
            .containsExactlyElementsIn(('A'..'Z').toList())
            .inOrder()
    }

    @Test
    fun advanceCostsMatchTheAh1996Log() {
        assertThat(AdvanceData.definitions.map(AdvanceDefinition::cost))
            .containsExactlyElementsIn(
                listOf(
                    30,
                    60,
                    90,
                    120,
                    30,
                    60,
                    90,
                    120,
                    20,
                    40,
                    50,
                    80,
                    110,
                    30,
                    60,
                    90,
                    120,
                    40,
                    80,
                    120,
                    160,
                    20,
                    60,
                    100,
                    150,
                    170,
                )
            )
            .inOrder()
    }

    @Test
    fun categoryTotalsMatchThePrintedScoreOfTwentyOneHundred() {
        val totals =
            AdvanceData.definitions.groupBy(AdvanceDefinition::category).mapValues { (_, advances)
                ->
                advances.sumOf(AdvanceDefinition::cost)
            }

        assertThat(totals)
            .containsExactly(
                AdvanceCategory.SCIENCE,
                300,
                AdvanceCategory.RELIGION,
                300,
                AdvanceCategory.COMMERCE,
                300,
                AdvanceCategory.COMMUNICATION,
                300,
                AdvanceCategory.EXPLORATION,
                400,
                AdvanceCategory.CIVICS,
                500,
            )
        assertThat(totals.values.sum()).isEqualTo(2100)
    }

    @Test
    fun advanceCreditsUseTheOriginalEditionSchedule() {
        assertThat(AdvanceData.definitions.map(AdvanceDefinition::credit))
            .containsExactlyElementsIn(
                listOf(
                    20,
                    20,
                    20,
                    20,
                    20,
                    20,
                    20,
                    20,
                    10,
                    10,
                    10,
                    10,
                    0,
                    10,
                    10,
                    10,
                    0,
                    20,
                    20,
                    20,
                    0,
                    10,
                    20,
                    30,
                    40,
                    50,
                )
            )
            .inOrder()
    }

    @Test
    fun miseryReliefRatingsAreDollarsFromTheAdvanceLog() {
        assertThat(AdvanceData.definitions.map(AdvanceDefinition::miseryRelief))
            .containsExactlyElementsIn(
                listOf(
                    5,
                    10,
                    5,
                    50,
                    10,
                    0,
                    0,
                    25,
                    5,
                    5,
                    25,
                    0,
                    5,
                    5,
                    10,
                    5,
                    100,
                    5,
                    5,
                    5,
                    25,
                    5,
                    5,
                    10,
                    25,
                    50,
                )
            )
            .inOrder()
    }

    @Test
    fun prerequisitesIncludeTheCrossCategoryDependencies() {
        val dependencies =
            AdvanceData.definitions
                .filter { advance -> advance.prerequisites.isNotEmpty() }
                .associate { advance ->
                    advance.id.letter to
                        advance.prerequisites.map(AdvanceId::letter).sorted().joinToString("")
                }

        assertThat(dependencies)
            .containsExactly(
                'H',
                "F",
                'J',
                "I",
                'K',
                "J",
                'L',
                "K",
                'M',
                "L",
                'O',
                "N",
                'P',
                "O",
                'Q',
                "P",
                'T',
                "AS",
                'U',
                "TV",
                'Y',
                "R",
                'Z',
                "K",
            )
    }

    @Test
    fun purchaseMiseryDistinguishesSpacesFromReliefRatings() {
        val changes =
            AdvanceData.definitions
                .filter { advance -> advance.purchaseMisery != 0 }
                .associate { advance -> advance.id.letter to advance.purchaseMisery }

        assertThat(changes).containsExactly('B', -1, 'E', 1, 'F', 1, 'G', 1, 'H', 1, 'K', -1)
    }

    @Test
    fun advanceDefinitionsRejectNonPositiveCosts() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AdvanceData.definitions.first().copy(cost = 0)
            }

        assertThat(failure).hasMessageThat().contains("cost must be positive")
    }

    @Test
    fun advanceDefinitionsRejectNegativeCredits() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AdvanceData.definitions.first().copy(credit = -1)
            }

        assertThat(failure).hasMessageThat().contains("credit cannot be negative")
    }

    @Test
    fun advanceDefinitionsRejectNegativeRelief() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AdvanceData.definitions.first().copy(miseryRelief = -1)
            }

        assertThat(failure).hasMessageThat().contains("relief cannot be negative")
    }

    @Test
    fun advanceDefinitionsRejectSelfPrerequisites() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AdvanceData.definitions.first().copy(prerequisites = setOf(AdvanceId.THE_HEAVENS))
            }

        assertThat(failure).hasMessageThat().contains("cannot require itself")
    }

    @Test
    fun advanceCatalogRejectsDuplicateIds() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances {
                    add(AdvanceData.definitions.first())
                    add(AdvanceData.definitions.first())
                }
            }

        assertThat(failure).hasMessageThat().contains("identifiers must be unique")
    }

    @Test
    fun advanceCatalogRejectsMissingAdvances() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances {
                    AdvanceData.definitions.dropLast(1).forEach { definition -> add(definition) }
                }
            }

        assertThat(failure).hasMessageThat().contains("all 26 advances")
    }

    @Test
    fun advanceCatalogRejectsIncorrectCategoryTotals() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances {
                    AdvanceData.definitions.forEach { definition ->
                        add(
                            if (definition.id == AdvanceId.THE_HEAVENS) definition.copy(cost = 31)
                            else definition
                        )
                    }
                }
            }

        assertThat(failure).hasMessageThat().contains("printed category total")
    }

    @Test
    fun advanceCatalogRejectsIndirectPrerequisiteCycles() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances {
                    AdvanceData.definitions.forEach { definition ->
                        add(
                            if (definition.id == AdvanceId.THE_HEAVENS) {
                                definition.copy(prerequisites = setOf(AdvanceId.OCEAN_NAVIGATION))
                            } else definition
                        )
                    }
                }
            }

        assertThat(failure).hasMessageThat().contains("prerequisites contain a cycle")
    }

    @Test
    fun advanceDslRejectsRepeatedPrerequisitesBeforeTheyBecomeASet() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                advances {
                    advance(
                        AdvanceId.CATHEDRAL,
                        AdvanceCategory.RELIGION,
                        120,
                        20,
                        25,
                        AdvanceId.HOLY_INDULGENCE,
                        AdvanceId.HOLY_INDULGENCE,
                    )
                }
            }

        assertThat(failure).hasMessageThat().contains("prerequisites cannot repeat")
    }

    @Test
    fun commodityValuesAndPrintedLimitsMatchTheBoard() {
        val rows =
            CommodityData.definitions.map { commodity ->
                listOf(commodity.baseValue, commodity.diceTotal, commodity.ownershipTrackBoxes)
            }

        assertThat(CommodityData.definitions.map(CommodityDefinition::id))
            .containsExactlyElementsIn(Commodity.entries)
            .inOrder()
        assertThat(rows)
            .containsExactlyElementsIn(
                listOf(
                    listOf(1, 2, 8),
                    listOf(2, 3, 9),
                    listOf(3, 4, 7),
                    listOf(4, 5, 8),
                    listOf(5, 6, 8),
                    listOf(5, 7, 6),
                    listOf(6, 8, 7),
                    listOf(7, 9, 5),
                    listOf(8, 10, 6),
                    listOf(9, 11, 7),
                    listOf(10, 12, 5),
                    listOf(10, 12, 4),
                )
            )
            .inOrder()
    }

    @Test
    fun commodityCardPrintsTenPaymentBoxesIndependentlyOfOwnershipTrack() {
        val wine = CommodityData.definitions.single { commodity -> commodity.id == Commodity.WINE }

        assertThat(wine.printedPayouts)
            .containsExactly(5, 20, 45, 80, 125, 180, 245, 320, 405, 500)
            .inOrder()
    }

    @Test
    fun commodityDefinitionsRejectInvalidBaseValues() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CommodityData.definitions.first().copy(baseValue = 11)
            }

        assertThat(failure).hasMessageThat().contains("base value must be 1 through 10")
    }

    @Test
    fun commodityDefinitionsRejectImpossibleDiceTotals() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CommodityData.definitions.first().copy(diceTotal = 1)
            }

        assertThat(failure).hasMessageThat().contains("dice total must be 2 through 12")
    }

    @Test
    fun commodityDefinitionsRequirePrintedPayoutBoxes() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CommodityData.definitions.first().copy(ownershipTrackBoxes = 0)
            }

        assertThat(failure).hasMessageThat().contains("at least one printed ownership box")
    }

    @Test
    fun commodityCatalogRejectsDuplicateIds() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                commodities {
                    add(CommodityData.definitions.first())
                    add(CommodityData.definitions.first())
                }
            }

        assertThat(failure).hasMessageThat().contains("identifiers must be unique")
    }

    @Test
    fun commodityCatalogRejectsMissingCommodities() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                commodities {
                    CommodityData.definitions.dropLast(1).forEach { definition -> add(definition) }
                }
            }

        assertThat(failure).hasMessageThat().contains("all twelve commodities")
    }

    @Test
    fun commodityCatalogRejectsMissingDiceTotals() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                commodities {
                    CommodityData.definitions.forEach { definition ->
                        add(
                            if (definition.id == Commodity.STONE) definition.copy(diceTotal = 3)
                            else definition
                        )
                    }
                }
            }

        assertThat(failure).hasMessageThat().contains("cover every two-dice total")
    }

    @Test
    fun commodityCatalogAllowsOnlyGoldAndIvoryToShareARoll() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                commodities {
                    CommodityData.definitions.forEach { definition ->
                        add(
                            if (definition.id == Commodity.GOLD) definition.copy(diceTotal = 11)
                            else definition
                        )
                    }
                }
            }

        assertThat(failure).hasMessageThat().contains("Only Gold and Ivory share")
    }

    @Test
    fun incomeIncludesZeroThroughTwentyFiveDominatedMarkets() {
        for (playerCount in 3..6) {
            val income = EconomyTables.incomeFor(playerCount)

            assertThat(income).hasSize(26)
            assertThat(income.first()).isEqualTo(15)
            assertThat(income[1]).isEqualTo(15 + playerCount)
            assertThat(income[17]).isEqualTo(15 + 17 * playerCount)
            assertThat(income.last()).isEqualTo(15 + 25 * playerCount)
        }
    }

    @Test
    fun incomeRejectsUnsupportedPlayerCounts() {
        for (playerCount in listOf(2, 7)) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    EconomyTables.incomeFor(playerCount)
                }

            assertThat(failure).hasMessageThat().contains("three through six starting players")
        }
    }

    @Test
    fun stabilizationMatchesThePrintedTriangularSchedule() {
        assertThat((0..9).map(EconomyTables::stabilizationCost))
            .containsExactly(0L, 1L, 3L, 6L, 10L, 15L, 21L, 28L, 36L, 45L)
            .inOrder()
    }

    @Test
    fun stabilizationDoesNotOverflowForLargeHands() {
        assertThat(EconomyTables.stabilizationCost(Int.MAX_VALUE))
            .isEqualTo(2_305_843_008_139_952_128L)
    }

    @Test
    fun stabilizationRejectsNegativeHandSizes() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                EconomyTables.stabilizationCost(-1)
            }

        assertThat(failure).hasMessageThat().contains("non-negative hand size")
    }

    private fun AdvanceCatalogBuilder.add(definition: AdvanceDefinition) {
        advance(
            definition.id,
            definition.category,
            definition.cost,
            definition.credit,
            definition.miseryRelief,
            *definition.prerequisites.toTypedArray(),
        )
    }

    private fun CommodityCatalogBuilder.add(definition: CommodityDefinition) {
        commodity(
            definition.id,
            definition.baseValue,
            definition.diceTotal,
            definition.ownershipTrackBoxes,
        )
    }
}
