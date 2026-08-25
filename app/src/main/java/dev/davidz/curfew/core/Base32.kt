package dev.davidz.curfew.core

/**
 * RFC 4648 base32, no padding on the way out and forgiving on the way in.
 *
 * Two things are encoded with this: the QR payload the approver scans, and the recovery string
 * a human reads aloud or types when the PWA loses its storage. The second use is why decoding
 * accepts lower case, spaces and hyphens, and why the encoder groups its output in fours.
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val REVERSE: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c ->
            table[c.code] = index
            table[c.lowercaseChar().code] = index
        }
    }

    /** Unpadded upper-case base32. */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1F])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return out.toString()
    }

    /** Same thing in groups of four, for the recovery string. */
    fun encodeGrouped(bytes: ByteArray, group: Int = 4): String =
        encode(bytes).chunked(group).joinToString(" ")

    /**
     * Decodes base32, ignoring case, whitespace, hyphens and trailing `=` padding.
     * Returns null on any character that is not in the alphabet, or on a length that could
     * not have come from [encode] — a typo should fail loudly, not silently pair a wrong secret.
     */
    fun decode(text: String): ByteArray? {
        val cleaned = buildString(text.length) {
            for (c in text) {
                when {
                    c.isWhitespace() || c == '-' || c == '=' -> Unit
                    c.code >= 128 || REVERSE[c.code] < 0 -> return null
                    else -> append(c)
                }
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)

        // 1, 3 and 6 leftover characters cannot be produced by encoding whole bytes.
        if (cleaned.length % 8 in setOf(1, 3, 6)) return null

        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        for (c in cleaned) {
            buffer = (buffer shl 5) or REVERSE[c.code]
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[index++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        // Whatever is left over must be zero padding, not dropped data.
        if (bitsLeft > 0 && (buffer and ((1 shl bitsLeft) - 1)) != 0) return null
        return out
    }
}
