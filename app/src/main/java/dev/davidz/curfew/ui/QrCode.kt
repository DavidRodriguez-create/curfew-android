package dev.davidz.curfew.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR rendering for the pairing screen.
 *
 * `com.google.zxing:core` only — the pure encoder, no `zxing-android-embedded`, no camera, no
 * extra activity. Curfew only ever *shows* a code; the scanning happens on the approver's phone.
 */
object QrCode {

    fun bitmap(
        content: String,
        sizePx: Int,
        dark: Int = Color.BLACK,
        light: Int = Color.WHITE,
    ): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val row = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[row + x] = if (matrix.get(x, y)) dark else light
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    } catch (t: Throwable) {
        Log.e("CurfewQr", "Could not encode the pairing QR", t)
        null
    }
}
