package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EuropeanMapGameTest {
    private val owner = PlayerId("p1")
    private val european = RuleOptions(map = MapRule.EUROPEAN)

    @Test
    fun europeanCatalogResolvesForEveryPlayerCount() {
        for (playerCount in 3..6) {
            val catalog = GameCatalog.requireResolvedFor(playerCount, european)

            assertThat(ProvinceId("loire") in catalog.provinceIds).isEqualTo(playerCount >= 4)
            assertThat(catalog.provinceIds).doesNotContain(ProvinceId("smolensk"))
            assertThat(catalog.capitals).hasSize(playerCount)
            assertThat(catalog.commodities.single { it.id == Commodity.WINE }.ownershipTrackBoxes)
                .isEqualTo(7)
        }
    }

    @Test
    fun parisCanEstablishAMarkerInLoire() {
        val state = expansion()

        val placed = state.step(TextCodec.parse("p1: place 3 loire"))

        assertThat(placed.board.pieces[ProvinceId("loire")])
            .isEqualTo(ProvincePieces.Marker(owner, PieceSide.WHITE))
        assertThat(placed.playerState(owner).poolTokens).isEqualTo(7)
        assertThat(placed.board.commodityCount(owner, Commodity.WINE)).isEqualTo(1)
    }

    @Test
    fun removedSmolenskCannotBePlacedOnTheEuropeanBoard() {
        val failure =
            assertFailsWith<RuleViolation> {
                expansion().step(TextCodec.parse("p1: place 1 smolensk"))
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNKNOWN_PROVINCE)
    }

    @Test
    fun loirePaysAsAWineMarket() {
        val state = expansion()
        val board = state.board.placeMarker(ProvinceId("loire"), owner).board

        val payment =
            CardRules.commodityPayment(state.copy(board = board).cardContext(), Commodity.WINE)

        assertThat(payment.payments[owner]).isEqualTo(5)
    }

    @Test
    fun londonReachesChesterByLandOnlyOnTheEuropeanMap() {
        val europeanContext = context("london", MapRule.EUROPEAN)
        val standardContext = context("london", MapRule.STANDARD)

        assertThat(ExpansionReach.routesTo(europeanContext, owner, ProvinceId("chester")))
            .isNotEmpty()
        assertThat(ExpansionReach.routesTo(standardContext, owner, ProvinceId("chester"))).isEmpty()
    }

    @Test
    fun stMaloCannotReachBordeauxInOneLandStep() {
        val routes =
            ExpansionReach.routesTo(
                context("st-malo", MapRule.EUROPEAN),
                owner,
                ProvinceId("bordeaux"),
            )

        assertThat(routes).isEmpty()
    }

    @Test
    fun europeanSeaContactsArePublishedWithoutChangingTheStandardMap() {
        val catalog = PublicCatalog.forPlayers(6, european)
        val standard = PublicCatalog.forPlayers(6)

        assertThat(catalog.seaCoasts)
            .contains(SeaCoastConnection(SeaArea.CENTRAL_MEDITERRANEAN, ProvinceId("alexandria")))
        for (province in listOf("crete", "libya", "barca")) {
            val coast = SeaCoastConnection(SeaArea.EASTERN_MEDITERRANEAN, ProvinceId(province))
            assertThat(catalog.seaCoasts).doesNotContain(coast)
            assertThat(standard.seaCoasts).contains(coast)
        }
        assertThat(standard.provinces.map(ProvinceDefinition::id)).contains(ProvinceId("smolensk"))
        assertThat(standard.provinces.map(ProvinceDefinition::id))
            .doesNotContain(ProvinceId("loire"))
        assertThat(standard.commodities.single { it.id == Commodity.WINE }.ownershipTrackBoxes)
            .isEqualTo(6)
    }

    @Test
    fun europeanBoardsHaveOneCentralMediterraneanWithoutAnAegeanSea() {
        for (playerCount in 3..6) {
            val configuration = CatalogConfiguration(playerCount, european)
            val catalog = PublicCatalog.forPlayers(playerCount, european)

            assertThat(configuration.seas).doesNotContain(SeaArea.AEGEAN_SEA)
            assertThat(catalog.seaAreas).doesNotContainKey(SeaArea.AEGEAN_SEA)
            assertThat(catalog.seaAreas[SeaArea.CENTRAL_MEDITERRANEAN])
                .isEqualTo(GeographicalArea.VIII)
            assertThat(catalog.seaCoasts.map(SeaCoastConnection::sea))
                .doesNotContain(SeaArea.AEGEAN_SEA)
            assertThat(catalog.seaCoasts.filter { it.province == ProvinceId("crete") })
                .containsExactly(
                    SeaCoastConnection(SeaArea.CENTRAL_MEDITERRANEAN, ProvinceId("crete"))
                )
        }
    }

    @Test
    fun galleyCanCrossFromSalonikaToTunisThroughTheMergedEuropeanSea() {
        val routes =
            ExpansionReach.routesTo(shippingContext(MapRule.EUROPEAN), owner, ProvinceId("tunis"))

        assertThat(routes).contains(mergedSeaRoute())
    }

    @Test
    fun standardMapCannotUseTheCentralMediterraneanAtSalonika() {
        val failure =
            assertFailsWith<RuleViolation> {
                ExpansionReach.validate(
                    shippingContext(MapRule.STANDARD),
                    owner,
                    ProvinceId("tunis"),
                    mergedSeaRoute(),
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    private fun shippingContext(map: MapRule): BoardRulesContext {
        val context = context("salonika", map)
        return context.copy(
            players =
                mapOf(
                    owner to
                        context
                            .player(owner)
                            .copy(
                                ships = ShippingCapability.GALLEY_2,
                                advances = setOf(AdvanceId.THE_HEAVENS),
                            )
                )
        )
    }

    private fun mergedSeaRoute(): ExpansionRoute.Shipping =
        ExpansionRoute.Shipping(
            ProvinceId("salonika"),
            listOf(
                ShippingSpace.Coast(ProvinceId("salonika")),
                ShippingSpace.Sea(SeaArea.CENTRAL_MEDITERRANEAN),
                ShippingSpace.Coast(ProvinceId("tunis")),
            ),
        )

    private fun expansion(): GameState {
        val players = (1..4).map { Player(PlayerId("p$it"), "Player $it") }
        val capitals = listOf("paris", "venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, owner),
            rules = european,
            stage = Stage.EXPANSION,
            turnNumber = 1,
            flow = TurnFlow(actor = owner, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = 10,
                                stockTokens = 26,
                            )
                    }
                    .toMap(),
        )
    }

    private fun context(base: String, map: MapRule): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(6, RuleOptions(map = map)),
            board =
                BoardState(definition = map.definition)
                    .replaceWithToken(ProvinceId(base), owner)
                    .board,
            players = mapOf(owner to BoardPlayerProfile(owner, ProvinceId("venice"))),
        )
}
