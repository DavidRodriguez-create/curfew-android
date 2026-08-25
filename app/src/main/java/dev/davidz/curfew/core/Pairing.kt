package dev.davidz.curfew.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The shared secret, and where it lives.
 *
 * 160 bits from [SecureRandom], wrapped with an AES-GCM key that never leaves the Android
 * Keystore, and only then written to app-private preferences. `EncryptedSharedPreferences`
 * would do the same job; doing it directly is fifty lines, costs no dependency, and keeps the
 * one interesting decision — the key is not extractable — visible in the source.
 *
 * The plaintext secret exists only for as long as it takes to verify a code or draw a QR.
 */
object Pairing {

    /** What the approver's app scans. Custom scheme: `otpauth` has nowhere to put the duration. */
    const val URI_SCHEME = "curfew"
    const val URI_VERSION = 1

    fun isPaired(context: Context): Boolean = prefs(context).contains(KEY_SECRET)

    fun pairedAt(context: Context): Long = prefs(context).getLong(KEY_PAIRED_AT, 0L)

    /** Decrypts the stored secret, or null when unpaired or the Keystore refuses. */
    fun secret(context: Context): ByteArray? {
        val stored = prefs(context).getString(KEY_SECRET, null) ?: return null
        return try {
            val (iv, cipherText) = stored.split('.').let {
                if (it.size != 2) return null
                Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP)
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key() ?: return null, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (t: Throwable) {
            Log.e(TAG, "Stored secret could not be decrypted", t)
            null
        }
    }

    /**
     * Generates a fresh secret and stores it, replacing any previous pairing.
     * Returns the plaintext for the QR and the recovery string, or null if the Keystore failed.
     */
    fun generate(context: Context, now: Long = System.currentTimeMillis()): ByteArray? {
        val fresh = ByteArray(TotpVerifier.SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        return if (store(context, fresh, now)) fresh else null
    }

    /** Re-pairs from a typed recovery string. Returns false on a malformed or wrong-length one. */
    fun restore(context: Context, recovery: String, now: Long = System.currentTimeMillis()): Boolean {
        val decoded = Base32.decode(recovery) ?: return false
        if (decoded.size != TotpVerifier.SECRET_BYTES) return false
        return store(context, decoded, now)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SECRET).remove(KEY_PAIRED_AT).apply()
    }

    fun recoveryString(secret: ByteArray): String = Base32.encodeGrouped(secret)

    fun pairingUri(secret: ByteArray): String =
        "$URI_SCHEME://pair?v=$URI_VERSION&s=${Base32.encode(secret)}"

    // ---- storage ------------------------------------------------------------------------

    private fun store(context: Context, secret: ByteArray, now: Long): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: error("no keystore key"))
        val cipherText = cipher.doFinal(secret)
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_SECRET, blob).putLong(KEY_PAIRED_AT, now).apply()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Could not store the pairing secret", t)
        false
    }

    private fun key(): SecretKey? = try {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: generateKey()
    } catch (t: Throwable) {
        Log.e(TAG, "Keystore unavailable", t)
        null
    }

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val TAG = "CurfewPairing"
    private const val FILE = "curfew_pairing"
    private const val KEY_SECRET = "secret"
    private const val KEY_PAIRED_AT = "paired_at"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "curfew_totp_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
}
