package dev.davidz.curfew

import dev.davidz.curfew.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate limiter on code entry. Brute-forcing six digits needs a million tries; this makes the
 * millionth arrive some time next decade, without punishing the first fat-fingered attempt at
 * three in the morning.
 */
class UnlockLockoutTest {

    @Test
    fun `the first attempts are free`() {
        for (failures in 0 until Policy.FREE_UNLOCK_ATTEMPTS) {
            assertEquals("failures $failures", 0L, Policy.lockoutMillis(failures))
        }
    }

    @Test
    fun `the lockout doubles once the free attempts are gone`() {
        assertEquals(30_000L, Policy.lockoutMillis(Policy.FREE_UNLOCK_ATTEMPTS))
        assertEquals(60_000L, Policy.lockoutMillis(Policy.FREE_UNLOCK_ATTEMPTS + 1))
        assertEquals(120_000L, Policy.lockoutMillis(Policy.FREE_UNLOCK_ATTEMPTS + 2))
        assertEquals(240_000L, Policy.lockoutMillis(Policy.FREE_UNLOCK_ATTEMPTS + 3))
    }

    /** Capped, because the approver may well be typing the code out over the phone. */
    @Test
    fun `the lockout is capped and never overflows`() {
        val cap = 15 * 60_000L
        assertEquals(cap, Policy.lockoutMillis(20))
        assertEquals(cap, Policy.lockoutMillis(64))
        assertEquals(cap, Policy.lockoutMillis(Int.MAX_VALUE))
        for (failures in 0..200) {
            assertTrue("failures $failures", Policy.lockoutMillis(failures) in 0L..cap)
        }
    }
}
