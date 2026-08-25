package dev.davidz.curfew

import dev.davidz.curfew.core.ClockAnchor
import dev.davidz.curfew.core.ClockGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockGuardTest {

    private val anchor = ClockAnchor(wallMillis = 1_770_000_000_000L, elapsedMillis = 60_000L)

    @Test
    fun `two clocks moving together show no skew`() {
        val skew = ClockGuard.skewMillis(
            anchor,
            nowWall = anchor.wallMillis + 3_600_000L,
            nowElapsed = anchor.elapsedMillis + 3_600_000L,
        )
        assertEquals(0L, skew)
        assertFalse(ClockGuard.isTampered(anchor, anchor.wallMillis + 3_600_000L, anchor.elapsedMillis + 3_600_000L))
    }

    @Test
    fun `winding the wall clock forward shows positive skew`() {
        val nowElapsed = anchor.elapsedMillis + 60_000L
        val nowWall = anchor.wallMillis + 60_000L + 3_600_000L
        assertEquals(3_600_000L, ClockGuard.skewMillis(anchor, nowWall, nowElapsed))
        assertTrue(ClockGuard.isTampered(anchor, nowWall, nowElapsed))
    }

    @Test
    fun `winding it back shows negative skew and still counts as tampering`() {
        val nowElapsed = anchor.elapsedMillis + 60_000L
        val nowWall = anchor.wallMillis + 60_000L - 3_600_000L
        assertEquals(-3_600_000L, ClockGuard.skewMillis(anchor, nowWall, nowElapsed))
        assertTrue(ClockGuard.isTampered(anchor, nowWall, nowElapsed))
    }

    /** NTP nudges and ordinary drift must not read as an attack. */
    @Test
    fun `small corrections are tolerated`() {
        val nowElapsed = anchor.elapsedMillis + 600_000L
        listOf(-59_000L, -1_000L, 0L, 1_000L, 59_000L).forEach { drift ->
            val nowWall = anchor.wallMillis + 600_000L + drift
            assertFalse("drift $drift", ClockGuard.isTampered(anchor, nowWall, nowElapsed))
        }
        val nowWall = anchor.wallMillis + 600_000L + 61_000L
        assertTrue(ClockGuard.isTampered(anchor, nowWall, nowElapsed))
    }

    /** A reboot resets elapsedRealtime. That invalidates the anchor; it does not accuse anyone. */
    @Test
    fun `a reboot asks for a new anchor rather than reporting tampering`() {
        val afterReboot = 5_000L
        assertTrue(ClockGuard.needsReanchor(anchor, afterReboot))
        assertEquals(0L, ClockGuard.skewMillis(anchor, anchor.wallMillis + 86_400_000L, afterReboot))
        assertFalse(ClockGuard.isTampered(anchor, anchor.wallMillis + 86_400_000L, afterReboot))
    }

    @Test
    fun `an unset anchor is not evidence of anything`() {
        assertTrue(ClockGuard.needsReanchor(ClockAnchor.NONE, 1_000L))
        assertFalse(ClockGuard.isTampered(ClockAnchor.NONE, 1_770_000_000_000L, 1_000L))
    }
}
