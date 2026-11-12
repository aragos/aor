package dev.aragos.aor.training

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ExpansionPairedWindowTest {
    @Test
    fun passOnlyOrdinaryWindowsCountBeforeTheAssignedOrdinal() {
        val tracker = ExpansionPairedWindow(ExpansionPairedRequest(12))
        tracker.observe(1, 0, 2, "expand", 1)
        tracker.observe(1, 0, 2, "expand", 3)

        val selected = tracker.observe(1, 0, 2, "expand", 5)

        assertThat(selected).isEqualTo(ExpansionWindowSelection.BRANCHING)
        assertThat(tracker.observedWindows).isEqualTo(3)
    }

    @Test
    fun nestedOffersDoNotAdvanceTheOrdinaryOrdinal() {
        val tracker = ExpansionPairedWindow(ExpansionPairedRequest(12))
        tracker.observe(1, 0, 2, "windmill", 2)
        tracker.observe(1, 0, 2, "cathedral", 2)

        val selected = tracker.observe(1, 0, 2, "expand", 4)

        assertThat(selected).isNull()
        assertThat(tracker.observedWindows).isEqualTo(1)
    }

    @Test
    fun otherSeatsAndEpochsCannotStartTheTargetTurn() {
        val tracker = ExpansionPairedWindow(ExpansionPairedRequest(0))
        tracker.observe(1, 1, 1, "expand", 4)
        tracker.observe(2, 0, 5, "expand", 4)

        assertThat(tracker.firstTurn).isNull()
        assertThat(tracker.observedWindows).isEqualTo(0)
    }

    @Test
    fun aLaterTurnCannotRefillAnUnreachedOrdinal() {
        val tracker = ExpansionPairedWindow(ExpansionPairedRequest(12))
        tracker.observe(1, 0, 1, "expand", 2)
        tracker.observe(1, 0, 2, "expand", 2)

        val selected = tracker.observe(1, 0, 2, "expand", 4)

        assertThat(selected).isNull()
        assertThat(tracker.firstTurn).isEqualTo(1)
        assertThat(tracker.observedWindows).isEqualTo(1)
    }

    @Test
    fun aPassOnlyTargetRemainsMissingRatherThanSearchingForABranch() {
        val tracker = ExpansionPairedWindow(ExpansionPairedRequest(0))
        val target = tracker.observe(1, 0, 2, "expand", 1)

        val later = tracker.observe(1, 0, 2, "expand", 4)

        assertThat(target).isEqualTo(ExpansionWindowSelection.NONBRANCHING)
        assertThat(later).isNull()
        assertThat(tracker.selectedNonbranching).isTrue()
    }
}
