package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BoardMapDefinitionTest {
    private val standard = StandardMapData.definition

    @Test
    fun anEmptyDraftCopiesTheCompleteStandardMap() {
        val draft = boardMap(standard) {}

        assertThat(draft).isEqualTo(standard)
        assertThat((3..6).map { draft.availableFor(it).size }).containsExactly(67, 83, 96, 108)
    }

    @Test
    fun theEditableEuropeanDraftPassesItsStructuralValidation() {
        val draft = EuropeanMapData.definition

        assertThat(draft.availableFor(3)).isNotEmpty()
    }

    @Test
    fun provinceChangesLeaveTheBaseMapUnchanged() {
        val draft =
            boardMap(standard) {
                changeProvince("vienna") { copy(marketNumber = 3) }
            }

        assertThat(province(draft, "vienna").marketNumber).isEqualTo(3)
        assertThat(province(standard, "vienna").marketNumber).isEqualTo(4)
    }

    @Test
    fun removingAProvinceRemovesEveryKindOfInheritedIncidentEdge() {
        val removed = ProvinceId("belgrade")

        val draft = boardMap(standard) { removeProvince(removed.value) }

        assertThat(draft.provinces.map(ProvinceDefinition::id)).doesNotContain(removed)
        assertThat(draft.landBorders.any { it.first == removed || it.second == removed }).isFalse()
        assertThat(draft.coastalConnections.any { it.first == removed || it.second == removed })
            .isFalse()
        assertThat(draft.satelliteSupports.any { it.satellite == removed || it.market == removed })
            .isFalse()
        assertThat(draft.seaCoasts.any { it.province == removed }).isFalse()
        assertThat(standard.landBorders.any { it.first == removed || it.second == removed })
            .isTrue()
    }

    @Test
    fun newProvincesCanDeclareLandCoastSeaAndDirectedSupport() {
        val draft =
            boardMap(standard) {
                addProvinces {
                    area(GeographicalArea.VII) {
                        market("trieste", number = 3, commodity = Commodity.METAL)
                        satellite("istria")
                    }
                }
                addConnections {
                    land("trieste", "venice", "istria")
                    coast("trieste", "venice", "istria")
                    supports("istria", "trieste")
                    sea(SeaArea.ADRIATIC_SEA, "trieste", "istria")
                }
            }

        assertThat(province(draft, "trieste").commodities).containsExactly(Commodity.METAL)
        assertThat(draft.landBorders).contains(edge("trieste", "venice"))
        assertThat(draft.coastalConnections).contains(edge("trieste", "istria"))
        assertThat(draft.satelliteSupports)
            .contains(SatelliteSupport(ProvinceId("istria"), ProvinceId("trieste")))
        assertThat(draft.seaCoasts)
            .contains(SeaCoastConnection(SeaArea.ADRIATIC_SEA, ProvinceId("trieste")))
    }

    @Test
    fun namedConnectionRemovalsUseCanonicalUndirectedOrder() {
        val draft =
            boardMap(standard) {
                removeConnections {
                    land("venice", "milan")
                    coast("venice", "florence")
                    supports("durazzo", "belgrade")
                    sea(SeaArea.ADRIATIC_SEA, "belgrade")
                }
            }

        assertThat(draft.landBorders).doesNotContain(edge("milan", "venice"))
        assertThat(draft.coastalConnections).doesNotContain(edge("florence", "venice"))
        assertThat(draft.satelliteSupports)
            .doesNotContain(SatelliteSupport(ProvinceId("durazzo"), ProvinceId("belgrade")))
        assertThat(draft.seaCoasts)
            .doesNotContain(SeaCoastConnection(SeaArea.ADRIATIC_SEA, ProvinceId("belgrade")))
    }

    @Test
    fun readdingARemovedProvinceDoesNotRestoreItsInheritedLinks() {
        val draft =
            boardMap(standard) {
                removeProvince("belgrade")
                addProvinces {
                    area(GeographicalArea.VII) {
                        market("belgrade", number = 2, commodity = Commodity.GRAIN)
                    }
                }
                addConnections { land("belgrade", "venice") }
            }

        val belgrade = ProvinceId("belgrade")
        assertThat(draft.landBorders.filter { it.first == belgrade || it.second == belgrade })
            .containsExactly(edge("belgrade", "venice"))
        assertThat(draft.coastalConnections.any { it.first == belgrade || it.second == belgrade })
            .isFalse()
        assertThat(draft.satelliteSupports.any { it.market == belgrade }).isFalse()
        assertThat(draft.seaCoasts.any { it.province == belgrade }).isFalse()
    }

    @Test
    fun removingAnUnknownConnectionReportsTheMistake() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { removeConnections { land("venice", "london") } }
            }

        assertThat(failure).hasMessageThat().contains("Cannot remove unknown land border")
    }

    @Test
    fun connectionDeclarationsCannotSilentlyOmitTheirEndpoints() {
        val declarations =
            listOf<BoardConnectionBuilder.() -> Unit>(
                { land("venice") },
                { coast("venice") },
                { supports("durazzo") },
                { sea(SeaArea.ADRIATIC_SEA) },
            )

        val failures = declarations.map { declaration ->
            assertFailsWith<IllegalArgumentException> {
                BoardConnectionBuilder().apply(declaration)
            }
        }

        assertThat(failures.all { "at least one" in it.message.orEmpty() }).isTrue()
    }

    @Test
    fun areaPlayerCutoffsMustFitTheSupportedRegistrationRange() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { minimumPlayers(GeographicalArea.V, count = 2) }
            }

        assertThat(failure).hasMessageThat().contains("three through six")
    }

    @Test
    fun addingAnExistingReverseConnectionReportsTheDuplicate() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { addConnections { land("venice", "milan") } }
            }

        assertThat(failure).hasMessageThat().contains("Duplicate land border")
    }

    @Test
    fun newlyDeclaredReferencesToRemovedProvincesAreRejected() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) {
                    removeProvince("belgrade")
                    addConnections { land("venice", "belgrade") }
                }
            }

        assertThat(failure).hasMessageThat().contains("unknown province: belgrade")
    }

    @Test
    fun changingAnUnknownProvinceReportsItsName() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { changeProvince("viennna") { copy(marketNumber = 3) } }
            }

        assertThat(failure).hasMessageThat().contains("Cannot change unknown province: viennna")
    }

    @Test
    fun removingAnUnknownProvinceReportsItsName() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { removeProvince("viennna") }
            }

        assertThat(failure).hasMessageThat().contains("Cannot remove unknown province: viennna")
    }

    @Test
    fun duplicateProvinceAdditionsCannotSilentlyReplaceTheBase() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) {
                    addProvinces { area(GeographicalArea.VII) { satellite("venice") } }
                }
            }

        assertThat(failure).hasMessageThat().contains("use changeProvince")
    }

    @Test
    fun changingTheStableProvinceIdRequiresAnExplicitReplacement() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) {
                    changeProvince("vienna") { copy(id = ProvinceId("wien")) }
                }
            }

        assertThat(failure).hasMessageThat().contains("cannot rename vienna")
    }

    @Test
    fun inlandChangesMustRemoveInheritedCoastalWaterLinks() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { changeProvince("belgrade") { copy(coastal = false) } }
            }

        assertThat(failure).hasMessageThat().contains("two coastal main-map provinces")
    }

    @Test
    fun supportArrowsCannotOriginateAtMarkets() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { addConnections { supports("venice", "genoa") } }
            }

        assertThat(failure).hasMessageThat().contains("Only a satellite can originate")
    }

    @Test
    fun areaAvailabilityChangesAffectTheDraftsReducedBoards() {
        val draft = boardMap(standard) { minimumPlayers(GeographicalArea.V, count = 4) }

        assertThat(draft.availableFor(3).map(ProvinceDefinition::id))
            .doesNotContain(ProvinceId("kiev"))
        assertThat(draft.availableFor(4).map(ProvinceDefinition::id)).contains(ProvinceId("kiev"))
        assertThat(standard.availableFor(3).map(ProvinceDefinition::id))
            .contains(ProvinceId("kiev"))
    }

    @Test
    fun capitalAvailabilityMustMatchRegistrationOrder() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                boardMap(standard) { minimumPlayers(GeographicalArea.I, count = 5) }
            }

        assertThat(failure).hasMessageThat().contains("disagree for 5 players")
    }

    @Test
    fun newCapitalsCanReplaceAnExistingCapitalInTheSameRegistrationSlot() {
        val draft =
            boardMap(standard) {
                changeProvince("hamburg") { copy(capital = null) }
                changeProvince("stockholm") { copy(capital = "stockholm") }
                capitals("venice", "genoa", "barcelona", "paris", "london", "stockholm")
            }

        assertThat(draft.capitalOrder.last()).isEqualTo(ProvinceId("stockholm"))
        assertThat(province(draft, "stockholm").capital).isEqualTo("stockholm")
    }

    @Test
    fun seaAreaAssignmentsCanBeChangedOrRemoved() {
        val draft =
            boardMap(standard) {
                seaArea(SeaArea.ADRIATIC_SEA, GeographicalArea.VIII)
                seaArea(SeaArea.BALTIC_SEA, null)
            }

        assertThat(draft.seaAreas[SeaArea.ADRIATIC_SEA]).isEqualTo(GeographicalArea.VIII)
        assertThat(draft.seaAreas[SeaArea.BALTIC_SEA]).isNull()
        assertThat(standard.seaAreas[SeaArea.BALTIC_SEA]).isEqualTo(GeographicalArea.I)
    }

    @Test
    fun draftTopologyDoesNotApplyStandardMapSpecificSeaErrata() {
        val draft =
            boardMap(standard) {
                addConnections { sea(SeaArea.NORWEGIAN_SEA, "bergen") }
            }

        assertThat(draft.seaCoasts)
            .contains(SeaCoastConnection(SeaArea.NORWEGIAN_SEA, ProvinceId("bergen")))
    }

    private fun province(map: BoardMapDefinition, id: String): ProvinceDefinition =
        requireNotNull(map.provinces.singleOrNull { it.id == ProvinceId(id) }) {
            "The fixture requires province $id."
        }

    private fun edge(first: String, second: String): ProvinceConnection =
        ProvinceConnection.of(ProvinceId(first), ProvinceId(second))
}
