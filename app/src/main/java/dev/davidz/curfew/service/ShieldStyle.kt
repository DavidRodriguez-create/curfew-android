package dev.davidz.curfew.service

import android.content.Context
import android.graphics.drawable.GradientDrawable

/**
 * The shield's palette and the two-line drawable helpers, shared by [OverlayManager] and
 * [UnlockKeypad]. Plain constants rather than resources: these views are built by hand inside a
 * WindowManager window, and a theme lookup there is one more thing that can be wrong at 03:00.
 */
object ShieldStyle {

    /** Fully opaque on purpose. At 0xF2 the app underneath stayed legible through the shield. */
    const val BACKGROUND = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171C24.toInt()
    const val FOREGROUND = 0xFFF8FAFC.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val FAINT = 0xFF64748B.toInt()
    const val ACCENT = 0xFF7DD3FC.toInt()
    const val GOOD = 0xFF86EFAC.toInt()
    const val DANGER = 0xFFFCA5A5.toInt()

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun pill(context: Context, fill: Int, stroke: Int? = null, radiusDp: Int = 28) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(context, 1), stroke)
        }
}
