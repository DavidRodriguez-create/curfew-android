package dev.davidz.curfew.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.davidz.curfew.core.CurfewPrefs
import dev.davidz.curfew.core.Policy

/**
 * Phase 1 of the enforcement plan: watch TYPE_WINDOW_STATE_CHANGED, learn which package owns the
 * foreground, and cover it with the shield when policy says so.
 *
 * Two things drive an evaluation:
 *  - an accessibility event (instant reaction when the user opens a blocked app), and
 *  - a 2s ticker (catches the curfew starting while a blocked app is already on screen, and
 *    catches an override expiring under the user).
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayManager? = null

    /** Last real, non-transient foreground package. */
    @Volatile
    private var lastPackage: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayManager(this) { evaluate() }
        CurfewPrefs.get(this).append(
            dev.davidz.curfew.core.LogType.NOTE,
            "Blocker service connected",
        )
        CurfewForegroundService.start(this)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        Log.i(TAG, "Curfew blocker connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        // The shade, the recents surface and our own UI are not "apps the user opened".
        // Ignoring them keeps the shield up when the notification panel is pulled down.
        if (pkg in TRANSPARENT_PACKAGES || pkg == packageName) return

        lastPackage = pkg
        evaluate()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        handler.removeCallbacks(ticker)
        overlay?.hide()
        overlay = null
        if (instance === this) instance = null
    }

    /** Recompute policy for the current foreground package and show or hide the shield. */
    fun evaluate() {
        val shield = overlay ?: return
        Policy.recordTransitions(this)

        val decision = Policy.evaluate(this, lastPackage)
        if (decision.block) {
            decision.pkg?.let { CurfewPrefs.get(this).appendBlockThrottled(it) }
            shield.show(decision)
        } else if (shield.isShowing) {
            shield.hide()
        }
    }

    companion object {
        private const val TAG = "CurfewBlocker"
        private const val TICK_MS = 2_000L

        private val TRANSPARENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
        )

        @Volatile
        var instance: BlockerAccessibilityService? = null
            private set

        /**
         * Whether the user has switched us on in Settings. Read from Settings.Secure rather than
         * from [instance], because the process can be alive with the service turned off.
         */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(
                context.packageName,
                BlockerAccessibilityService::class.java.name,
            )
            // Different OEMs persist the long or the short flattened form; accept either.
            val expected = setOf(
                component.flattenToString(),
                component.flattenToShortString(),
            )

            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (entry in splitter) {
                if (expected.any { it.equals(entry, ignoreCase = true) }) return true
            }
            return false
        }
    }
}
