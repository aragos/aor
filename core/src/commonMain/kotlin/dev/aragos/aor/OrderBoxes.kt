package dev.aragos.aor

/** Printed order-of-play boxes, indexed by player count and then ascending turn position. */
object OrderBoxes {
    private val byPlayerCount =
        OrderBoxBuilder()
            .apply {
                players(3, boxes = listOf(2, 4, 6))
                players(4, boxes = listOf(1, 3, 5, 6))
                players(5, boxes = listOf(1, 2, 3, 4, 5))
                players(6, boxes = listOf(1, 2, 3, 4, 5, 6))
            }
            .build()

    /** Project an order whose player membership and uniqueness are owned by game state. */
    fun forOrder(order: List<PlayerId>): Map<PlayerId, Int> {
        val boxes =
            requireNotNull(byPlayerCount[order.size]) {
                "Printed order boxes require three through six players."
            }
        return order.zip(boxes).toMap()
    }
}

@GameDataDsl
private class OrderBoxBuilder {
    private val rows = mutableMapOf<Int, List<Int>>()

    fun players(count: Int, boxes: List<Int>) {
        require(count in 3..6) { "Order-box rows require three through six players." }
        require(count !in rows) { "Each player count needs exactly one order-box row." }
        require(
            boxes.size == count &&
                boxes == boxes.distinct().sorted() &&
                boxes.all { box -> box in 1..6 }
        ) {
            "An order-box row requires one distinct ascending printed box per player."
        }
        rows[count] = boxes.toList()
    }

    fun build(): Map<Int, List<Int>> {
        require(rows.keys == (3..6).toSet()) { "Order boxes must cover three through six players." }
        return rows.toMap()
    }
}
