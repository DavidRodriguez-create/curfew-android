package dev.davidz.curfew

import dev.davidz.curfew.core.ClockAnchor
import dev.davidz.curfew.core.ClockGuard
import dev.davidz.curfew.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the policy engine believes the time is. [ClockGuardTest] covers the drift arithmetic;
 * this covers the decision built on top of it — when the wall clock stops being believed, and
 * what replaces it.
 */
class EffectiveClockTest {

    private val wall0 = 1_800_000_000_000L // some fixed evening
    private val elapsed0 = 90_000_000L
    private val anchor = ClockAnchor(wall0, elapsed0)

    /** No anchor yet: nothing to compare against, so the wall clock is all there is. */
    @Test
    fun `an unanchored clock is believed`() {
        val view = Policy.clockView(ClockAnchor.NONE, wall0, elapsed0)
        assertEquals(wall0, view.effectiveNow)
        assertFalse(view.tampered)
        assertEquals(0, view.skewSeconds)
    }

    @Test
    fun `a clock that tracks the monotonic one is believed`() {
        val d = 3 * 3_600_000L
        val view = Policy.clockView(anchor, wall0 + d, elapsed0 + d)
        assertEquals(wall0 + d, view.effectiveNow)
        assertFalse(view.tampered)
    }

    /** NTP nudges of a few seconds are drift, not tampering. */
    @Test
    fun `small drift is tolerated and still believed`() {
        val d = 60_000L
        val skew = ClockGuard.MAX_SKEW_MS - 1_000L
        val view = Policy.clockView(anchor, wall0 + d + skew, elapsed0 + d)
        assertFalse(view.tampered)
        assertEquals(wall0 + d + skew, view.effectiveNow)
        assertEquals(59, view.skewSeconds)
    }

    /**
     * The attack this exists for: sit in front of the shield at 23:05, wind the clock to 08:00,
     * walk out of the window. The projected time ignores the jump entirely.
     */
    @Test
    fun `winding the clock forward buys nothing`() {
        val d = 60_000L
        val jump = 9 * 3_600_000L
        val view = Policy.clockView(anchor, wall0 + d + jump, elapsed0 + d)
        assertTrue(view.tampered)
        assertEquals(wall0 + d, view.effectiveNow)
        assertEquals(jump / 1000L, view.skewSeconds.toLong())
    }

    /** The same in reverse — winding back would otherwise stretch a 15-minute grant. */
    @Test
    fun `winding the clock backward is refused too`() {
        val d = 60_000L
        val view = Policy.clockView(anchor, wall0 + d - 2 * 3_600_000L, elapsed0 + d)
        assertTrue(view.tampered)
        assertEquals(wall0 + d, view.effectiveNow)
        assertTrue(view.skewSeconds < 0)
    }

    /** A reboot invalidates the anchor rather than incriminating the clock. */
    @Test
    fun `a reboot is not tampering`() {
        val view = Policy.clockView(anchor, wall0 + 3_600_000L, 5_000L)
        assertFalse(view.tampered)
        assertEquals(wall0 + 3_600_000L, view.effectiveNow)
    }

    /** Believed or not, the projected clock only ever moves forwards while the phone is up. */
    @Test
    fun `the effective clock is monotonic across a wall-clock jump`() {
        var last = Long.MIN_VALUE
        for (step in 0..120) {
            val elapsed = elapsed0 + step * 1_000L
            // Someone starts dragging the clock around halfway through.
            val wall = wall0 + step * 1_000L + if (step > 60) (step - 60) * 600_000L else 0L
            val now = Policy.clockView(anchor, wall, elapsed).effectiveNow
            assertTrue("step $step went backwards", now >= last)
            last = now
        }
    }
}
