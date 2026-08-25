package dev.davidz.curfew

import dev.davidz.curfew.core.Base32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base32Test {

    /** RFC 4648 §10, minus the padding we do not emit. */
    @Test
    fun `encodes the RFC 4648 vectors`() {
        assertEquals("", Base32.encode("".toByteArray()))
        assertEquals("MY", Base32.encode("f".toByteArray()))
        assertEquals("MZXQ", Base32.encode("fo".toByteArray()))
        assertEquals("MZXW6", Base32.encode("foo".toByteArray()))
        assertEquals("MZXW6YQ", Base32.encode("foob".toByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".toByteArray()))
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray()))
    }

    @Test
    fun `decodes the RFC 4648 vectors`() {
        assertArrayEquals("f".toByteArray(), Base32.decode("MY"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
    }

    @Test
    fun `round-trips arbitrary bytes`() {
        val bytes = ByteArray(20) { (it * 37 + 11).toByte() }
        assertArrayEquals(bytes, Base32.decode(Base32.encode(bytes)))
    }

    /** The recovery string is read aloud and typed back, so decoding has to survive humans. */
    @Test
    fun `decoding tolerates case, spaces and hyphens`() {
        val bytes = ByteArray(20) { it.toByte() }
        val grouped = Base32.encodeGrouped(bytes)
        assertArrayEquals(bytes, Base32.decode(grouped))
        assertArrayEquals(bytes, Base32.decode(grouped.lowercase()))
        assertArrayEquals(bytes, Base32.decode(grouped.replace(' ', '-')))
    }

    @Test
    fun `grouping does not change the payload`() {
        val bytes = ByteArray(20) { (it * 5).toByte() }
        assertEquals(Base32.encode(bytes), Base32.encodeGrouped(bytes).replace(" ", ""))
    }

    /** A typo has to fail loudly. Silently pairing a wrong secret is the worst outcome here. */
    @Test
    fun `rejects characters outside the alphabet`() {
        assertNull(Base32.decode("MZXW6YTB01"))
        assertNull(Base32.decode("MZXW6YT!"))
    }

    @Test
    fun `rejects lengths that encoding could never produce`() {
        assertNull(Base32.decode("A"))
        assertNull(Base32.decode("ABC"))
        assertNull(Base32.decode("ABCDEF"))
    }

    @Test
    fun `rejects non-zero padding bits`() {
        // "MZ" decodes one byte from ten bits; the two spare bits must be zero, and in "MX"
        // they are not.
        assertNull(Base32.decode("MX"))
    }
}
