package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelValidationTest {
    @Test
    fun theCreatorMustBeRegistered() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                GameHeader(
                    players =
                        (1..3).map { number ->
                            Player(id = PlayerId("p$number"), name = "Player $number")
                        },
                    creatorId = PlayerId("p6"),
                )
            }

        assertThat(failure).hasMessageThat().contains("registered player")
    }

    @Test
    fun playerIdsMustUseTheDocumentedFormat() {
        for (id in listOf("", "p0", "p7", "alice", "P1")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PlayerId(id)
                }

            assertThat(failure).hasMessageThat().contains("Player id")
        }
    }

    @Test
    fun playerNamesMustContainTextAndFitTheLengthLimit() {
        for (name in listOf("", "   ", "a".repeat(61))) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    Player(id = PlayerId("p1"), name = name)
                }

            assertThat(failure).hasMessageThat().contains("Player name")
        }
    }

    @Test
    fun playerNamesCanReachTheDocumentedMaximumLength() {
        val name = "a".repeat(60)

        assertThat(Player(id = PlayerId("p1"), name = name).name).isEqualTo(name)
    }

    @Test
    fun headersRequireUniquePlayerIds() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                GameHeader(
                    creatorId = PlayerId("p1"),
                    players =
                        listOf(
                            Player(id = PlayerId("p1"), name = "Alice"),
                            Player(id = PlayerId("p1"), name = "Bob"),
                            Player(id = PlayerId("p3"), name = "Carol"),
                        ),
                )
            }

        assertThat(failure).hasMessageThat().contains("unique")
    }

    @Test
    fun epochOneContainsTwentyNineUniqueCardInstances() {
        assertThat(CardData.epochOne).hasSize(29)
        assertThat(CardData.epochOne.map(CardDefinition::id)).containsNoDuplicates()
    }

    @Test
    fun cardCatalogsRejectDuplicateIdsWithADiagnostic() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                cards {
                    event("war", shape = CardShape.CIRCLE)
                    event("war", shape = CardShape.CIRCLE)
                }
            }

        assertThat(failure).hasMessageThat().contains("Card instance id must be unique")
    }

    @Test
    fun commodityCopiesHaveExplicitIdsAndSharePresentation() {
        val stone = CardData.epochOne.filter { card -> card.messageId == "card.stone" }

        assertThat(stone.map(CardDefinition::id)).containsExactly("stone-1", "stone-2").inOrder()
    }

    @Test
    fun cardKindsMatchTheEpochOneManifest() {
        val kinds = CardData.epochOne.groupingBy(CardDefinition::kind).eachCount()

        assertThat(kinds)
            .containsExactly(CardKind.COMMODITY, 11, CardKind.EVENT, 13, CardKind.LEADER, 5)
    }

    @Test
    fun epochOneRecyclingUsesTheDocumentedQ7Classification() {
        val removedCards = CardData.epochOne.filter { card -> !card.shape.recycledOnEpochChange }

        assertThat(removedCards.map(CardDefinition::id))
            .containsExactly(
                "armor",
                "stirrups",
                "charlemagne",
                "dionysus-exiguus",
                "rashid-ad-din",
                "st-benedict",
                "walter-the-penniless",
            )
    }

    @Test
    fun cardIdsMustUseTheDocumentedFormat() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CardDefinition(
                    id = "Stone 1",
                    kind = CardKind.COMMODITY,
                    messageId = "card.stone",
                    shape = CardShape.CIRCLE,
                )
            }

        assertThat(failure).hasMessageThat().contains("Card id")
    }

    @Test
    fun cardsRequireATranslationKey() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                cards { commodity("stone-1", messageId = " ") }
            }

        assertThat(failure).hasMessageThat().contains("presentation message id")
    }

    @Test
    fun miseryTrackSeparatesSpacesFromPrintedValues() {
        assertThat(MiseryStep.NINETY.ordinal + 1).isEqualTo(MiseryStep.HUNDRED.ordinal)
        assertThat(MiseryStep.HUNDRED.ordinal + 1).isEqualTo(MiseryStep.HUNDRED_TWENTY_FIVE.ordinal)
        assertThat(MiseryStep.HUNDRED_TWENTY_FIVE.value).isEqualTo(125)
    }

    @Test
    fun chaosIsBeyondTheLastPrintedMiseryBox() {
        assertThat(MiseryStep.THOUSAND.ordinal + 1).isEqualTo(MiseryStep.CHAOS.ordinal)
        assertThat(MiseryStep.CHAOS.value).isNull()
    }

    @Test
    fun shippingCapabilitiesIncludeOnlyPrintedGalleyLevels() {
        val levels =
            ShippingCapability.entries.filter { shipping -> shipping.fleet == ShippingFleet.GALLEY }

        assertThat(levels.map(ShippingCapability::level)).containsExactly(2, 4, 6, 8).inOrder()
    }

    @Test
    fun shippingCapabilitiesIncludeOnlyPrintedSeaworthyLevels() {
        val levels =
            ShippingCapability.entries.filter { shipping ->
                shipping.fleet == ShippingFleet.SEAWORTHY
            }

        assertThat(levels.map(ShippingCapability::level)).containsExactly(10, 12, 14, 16).inOrder()
    }

    @Test
    fun shippingCapabilitiesIncludeOnlyPrintedOceanLevels() {
        val levels =
            ShippingCapability.entries.filter { shipping -> shipping.fleet == ShippingFleet.OCEAN }

        assertThat(levels.map(ShippingCapability::level)).containsExactly(1, 2, 3, 4).inOrder()
    }

    @Test
    fun capitalCatalogsRejectDuplicateIdsWithADiagnostic() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                capitals {
                    capital("venice")
                    capital("venice")
                }
            }

        assertThat(failure).hasMessageThat().contains("Capital id must be unique")
    }
}
