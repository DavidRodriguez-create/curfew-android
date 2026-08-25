package dev.davidz.curfew.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A code that verified: which 30-second window it belonged to, and how long it buys. */
data class GrantCode(val counter: Long, val durationMinutes: Int)

/**
 * TOTP with one extra authenticated field.
 *
 *     T        = floor(unix_seconds / 30)
 *     msg      = T (8 bytes, big endian) || duration (1 byte)
 *     code     = truncate6( HMAC-SHA1(S, msg) )
 *
 * The duration is inside the MAC, so a 15-minute code cannot be presented as a 60-minute one:
 * the two are different messages and produce unrelated digits. Verification sweeps
 * `T-1, T, T+1` against every offered duration, which is nine HMACs — nothing, and it means the
 * approver and the phone do not have to agree on the second.
 *
 * Pure logic, no Android types, so it is unit-testable against the RFC 4226 vectors.
 */
object TotpVerifier {

    const val STEP_SECONDS: Long = 30L
    const val DIGITS: Int = 6

    /** How many steps either side of `now` are accepted. One step = 30 seconds. */
    const val SKEW_STEPS: Int = 1

    /** The durations the approver's app can grant, in minutes. */
    val DURATIONS: List<Int> = listOf(15, 30, 60)

    /** Length of the shared secret. 160 bits, as RFC 4226 asks for. */
    const val SECRET_BYTES: Int = 20

    fun counterFor(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, STEP_SECONDS)

    /** Seconds until the current step rolls over — the countdown ring on the approver's side. */
    fun secondsLeftInStep(epochSeconds: Long): Int =
        (STEP_SECONDS - Math.floorMod(epochSeconds, STEP_SECONDS)).toInt()

    /** RFC 4226 HOTP over an arbitrary message. Exposed so the RFC test vectors can hit it. */
    fun hotp(secret: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        return truncate(mac.doFinal(message))
    }

    fun code(secret: ByteArray, counter: Long, durationMinutes: Int): String {
        require(durationMinutes in 1..255) { "duration must fit in one byte" }
        return hotp(secret, message(counter, durationMinutes))
    }

    /**
     * Returns the `(counter, duration)` pair the entered code authenticates, or null if nothing
     * in the accepted window matches. Replay is deliberately *not* checked here — a code that
     * verified but was already spent is a different answer to the user than a wrong code, and
     * [ReplayWindow] is what knows the difference.
     */
    fun verify(
        secret: ByteArray,
        entered: String,
        epochSeconds: Long,
        durations: List<Int> = DURATIONS,
    ): GrantCode? {
        val normalised = entered.filter { it.isDigit() }
        if (normalised.length != DIGITS) return null

        val centre = counterFor(epochSeconds)
        var match: GrantCode? = null
        for (offset in -SKEW_STEPS..SKEW_STEPS) {
            val counter = centre + offset
            for (duration in durations) {
                // No early return: every candidate costs the same work whether or not it hit.
                if (constantTimeEquals(code(secret, counter, duration), normalised)) {
                    if (match == null) match = GrantCode(counter, duration)
                }
            }
        }
        return match
    }

    /** T (8 bytes, big endian) || duration (1 byte). */
    fun message(counter: Long, durationMinutes: Int): ByteArray =
        counterBytes(counter) + byteArrayOf(durationMinutes.toByte())

    fun counterBytes(counter: Long): ByteArray {
        val out = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return out
    }

    /** RFC 4226 §5.3 dynamic truncation, six digits, zero padded. */
    private fun truncate(hmac: ByteArray): String {
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary =
            ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)
        var modulus = 1
        repeat(DIGITS) { modulus *= 10 }
        return (binary % modulus).toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
