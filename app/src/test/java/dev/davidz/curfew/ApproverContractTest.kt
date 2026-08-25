package dev.davidz.curfew

import dev.davidz.curfew.core.TotpVerifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract with the approver's PWA (`../curfew-approver`).
 *
 * The two codebases share no code and cannot share a test run, so what keeps them honest is a
 * table of numbers asserted on both sides. This one is duplicated verbatim in that repo's
 * `test/vectors.test.mjs`; if either implementation drifts, one of the two suites goes red and
 * names the counter and duration that moved.
 *
 * [TotpVerifierTest] already pins the HMAC and the truncation against RFC 4226. What the RFC
 * cannot cover is *our* message — the duration byte after the counter — which is precisely the
 * part with no external authority to appeal to. Hence this.
 */
class ApproverContractTest {

    /** The same secret the JS side builds: 20 bytes, 1..20. */
    private val secret = ByteArray(TotpVerifier.SECRET_BYTES) { (it + 1).toByte() }

    private val vectors = listOf(
        Triple(0L, 15, "187377"),
        Triple(0L, 30, "806133"),
        Triple(0L, 60, "238754"),
        Triple(1L, 15, "280160"),
        Triple(58_000_000L, 15, "468014"),
        Triple(58_000_000L, 30, "511676"),
        Triple(58_000_000L, 60, "730623"),
        Triple(59_000_000L, 60, "866935"),
        Triple(4_294_967_296L, 30, "130689"), // past 2^32: the counter really is 64 bits wide
    )

    @Test
    fun `generates the codes the approver's app generates`() {
        vectors.forEach { (counter, duration, expected) ->
            assertEquals("$counter/$duration", expected, TotpVerifier.code(secret, counter, duration))
        }
    }

    /** And, from the other direction: each of those codes verifies back to its own duration. */
    @Test
    fun `verifies the codes the approver's app generates`() {
        vectors.forEach { (counter, duration, code) ->
            val epochSeconds = counter * TotpVerifier.STEP_SECONDS
            val match = TotpVerifier.verify(secret, code, epochSeconds)
            assertEquals("$counter/$duration", counter, match?.counter)
            assertEquals("$counter/$duration", duration, match?.durationMinutes)
        }
    }
}
