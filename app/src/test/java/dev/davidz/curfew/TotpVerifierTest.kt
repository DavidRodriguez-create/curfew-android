package dev.davidz.curfew

import dev.davidz.curfew.core.TotpVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpVerifierTest {

    private val secret = ByteArray(20) { (it + 1).toByte() }

    /**
     * RFC 4226 appendix D, against the shared secret the RFC uses. This pins the HMAC and the
     * dynamic truncation — the parts that have to agree byte for byte with WebCrypto on the
     * approver's side, where nothing can be stepped through.
     */
    @Test
    fun `matches the RFC 4226 HOTP vectors`() {
        val rfcSecret = "12345678901234567890".toByteArray()
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code ->
            assertEquals(
                "counter $counter",
                code,
                TotpVerifier.hotp(rfcSecret, TotpVerifier.counterBytes(counter.toLong())),
            )
        }
    }

    @Test
    fun `counter advances every thirty seconds`() {
        assertEquals(0L, TotpVerifier.counterFor(0L))
        assertEquals(0L, TotpVerifier.counterFor(29L))
        assertEquals(1L, TotpVerifier.counterFor(30L))
        assertEquals(2L, TotpVerifier.counterFor(75L))
        assertEquals(30, TotpVerifier.secondsLeftInStep(0L))
        assertEquals(1, TotpVerifier.secondsLeftInStep(29L))
    }

    @Test
    fun `codes are six digits, zero padded`() {
        for (counter in 0L until 400L) {
            val code = TotpVerifier.code(secret, counter, 15)
            assertEquals("counter $counter -> $code", 6, code.length)
            assertEquals(true, code.all { it.isDigit() })
        }
    }

    /** The duration is inside the MAC, so the three grants are three unrelated codes. */
    @Test
    fun `each duration produces a different code for the same step`() {
        val counter = 58_000_000L
        val codes = TotpVerifier.DURATIONS.map { TotpVerifier.code(secret, counter, it) }
        assertEquals(codes.toSet().size, codes.size)
    }

    @Test
    fun `verification recovers the counter and the duration`() {
        val epochSeconds = 1_770_000_000L
        val counter = TotpVerifier.counterFor(epochSeconds)
        TotpVerifier.DURATIONS.forEach { duration ->
            val code = TotpVerifier.code(secret, counter, duration)
            val match = TotpVerifier.verify(secret, code, epochSeconds)
            assertEquals(counter, match?.counter)
            assertEquals(duration, match?.durationMinutes)
        }
    }

    /**
     * The point of authenticating the duration: a code the approver generated for fifteen
     * minutes verifies as fifteen minutes and nothing else. There is no field to lie about.
     */
    @Test
    fun `a fifteen minute code cannot buy sixty`() {
        val epochSeconds = 1_770_000_000L
        val counter = TotpVerifier.counterFor(epochSeconds)
        val fifteen = TotpVerifier.code(secret, counter, 15)

        assertEquals(15, TotpVerifier.verify(secret, fifteen, epochSeconds)?.durationMinutes)
        assertNotEquals(fifteen, TotpVerifier.code(secret, counter, 60))
        // Offered only the 60-minute grant, the 15-minute code is simply not a valid code.
        assertNull(TotpVerifier.verify(secret, fifteen, epochSeconds, durations = listOf(60)))
    }

    @Test
    fun `accepts one step of skew either side and no more`() {
        val epochSeconds = 1_770_000_000L
        val counter = TotpVerifier.counterFor(epochSeconds)

        listOf(-1L, 0L, 1L).forEach { offset ->
            val code = TotpVerifier.code(secret, counter + offset, 30)
            assertEquals(
                "offset $offset",
                counter + offset,
                TotpVerifier.verify(secret, code, epochSeconds)?.counter,
            )
        }

        listOf(-2L, 2L, 10L).forEach { offset ->
            val code = TotpVerifier.code(secret, counter + offset, 30)
            assertNull("offset $offset", TotpVerifier.verify(secret, code, epochSeconds))
        }
    }

    @Test
    fun `rejects a code from a different secret`() {
        val epochSeconds = 1_770_000_000L
        val other = ByteArray(20) { (it + 2).toByte() }
        val code = TotpVerifier.code(other, TotpVerifier.counterFor(epochSeconds), 15)
        assertNull(TotpVerifier.verify(secret, code, epochSeconds))
    }

    @Test
    fun `ignores separators but not length`() {
        val epochSeconds = 1_770_000_000L
        val code = TotpVerifier.code(secret, TotpVerifier.counterFor(epochSeconds), 15)
        val spaced = code.substring(0, 3) + " " + code.substring(3)
        assertEquals(15, TotpVerifier.verify(secret, spaced, epochSeconds)?.durationMinutes)
        assertNull(TotpVerifier.verify(secret, code.drop(1), epochSeconds))
        assertNull(TotpVerifier.verify(secret, code + "0", epochSeconds))
    }
}
