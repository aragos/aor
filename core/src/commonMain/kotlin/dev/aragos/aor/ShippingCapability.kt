package dev.aragos.aor

/** The fleet family governing shipping reach and how its upgrade level is interpreted. */
enum class ShippingFleet {
    NONE,
    GALLEY,
    SEAWORTHY,
    OCEAN,
}

/**
 * A legal space on the ship upgrade display, including the initial absence of ships.
 *
 * [level] is per-destination token capacity for galleys and seaworthy vessels. For ocean navigation
 * it limits occupied overseas boxes; ocean shipping itself has unlimited token capacity. [NONE] has
 * level zero and no shipping capacity.
 */
enum class ShippingCapability(val fleet: ShippingFleet, val level: Int) {
    NONE(ShippingFleet.NONE, 0),
    GALLEY_2(ShippingFleet.GALLEY, 2),
    GALLEY_4(ShippingFleet.GALLEY, 4),
    GALLEY_6(ShippingFleet.GALLEY, 6),
    GALLEY_8(ShippingFleet.GALLEY, 8),
    SEAWORTHY_10(ShippingFleet.SEAWORTHY, 10),
    SEAWORTHY_12(ShippingFleet.SEAWORTHY, 12),
    SEAWORTHY_14(ShippingFleet.SEAWORTHY, 14),
    SEAWORTHY_16(ShippingFleet.SEAWORTHY, 16),
    OCEAN_1(ShippingFleet.OCEAN, 1),
    OCEAN_2(ShippingFleet.OCEAN, 2),
    OCEAN_3(ShippingFleet.OCEAN, 3),
    OCEAN_4(ShippingFleet.OCEAN, 4),
}
