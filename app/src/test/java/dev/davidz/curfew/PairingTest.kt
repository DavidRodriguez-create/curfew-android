package dev.davidz.curfew

import dev.davidz.curfew.core.Base32
import dev.davidz.curfew.core.Pairing
import dev.davidz.curfew.core.TotpVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the two pure halves of [Pairing] are reachable from a JVM test — everything else needs
 * a real Keystore. Those two are the ones that matter here anyway: they are the wire format the
 * approver's PWA has to parse, so a change to either is a change to a contract with another
 * codebase.
 */
class PairingTest {

    private val secret = ByteArray(TotpVerifier.SECRET_BYTES) { (it * 13 + 7).toByte() }

    @Test
    fun `the pairing URI carries a version and a decodable secret`() {
        val uri = Pairing.pairingUri(secret)
        assertTrue(uri.startsWith("${Pairing.URI_SCHEME}://pair?"))

        val query = uri.substringAfter('?').split('&').associate {
            it.substringBefore('=') to it.substringAfter('=')
        }
        assertEquals(Pairing.URI_VERSION.toString(), query["v"])
        assertArrayEquals(secret, Base32.decode(query.getValue("s")))
    }

    /** No spaces in the QR payload — grouping is for human eyes, not for the scanner. */
    @Test
    fun `the pairing URI is unspaced base32`() {
        assertEquals(Base32.encode(secret), Pairing.pairingUri(secret).substringAfter("&s="))
    }

    @Test
    fun `the recovery string is grouped and reads back as the same secret`() {
        val recovery = Pairing.recoveryString(secret)
        assertEquals(32, recovery.count { it != ' ' }) // 20 bytes → 32 base32 characters
        assertTrue(recovery.contains(' '))
        assertArrayEquals(secret, Base32.decode(recovery))
    }
}
