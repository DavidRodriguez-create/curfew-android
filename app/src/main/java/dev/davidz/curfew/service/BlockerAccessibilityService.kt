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
import android.view.accessibility.AccessibilityNodeInfo
import dev.davidz.curfew.core.Blocklist
import dev.davidz.curfew.core.CurfewPrefs
import dev.davidz.curfew.core.LogType
import dev.davidz.curfew.core.Phase
import dev.davidz.curfew.core.Policy
import dev.davidz.curfew.core.SettingsGuard
import dev.davidz.curfew.core.ShieldReason

/**
 * Phase 1 of the enforcement plan: watch TYPE_WINDOW_STATE_CHANGED, learn which package owns the
 * foreground, and cover it with the shield when policy says so.
 *
 * Two things drive an evaluation:
 *  - an accessibility event (instant reaction when the user opens a blocked app), and
 *  - a 2s ticker (catches the curfew starting while a blocked app is already on screen, and
 *    catches an override expiring under the user).
 *
 * v0.2 added a second question to each event: is this the settings screen that could switch the
 * blocker off? See [SettingsGuard].
 */
class BlockerAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayManager? = null

    /** Last real, non-transient foreground package. */
    @Volatile
    private var lastPackage: String? = null

    /** Whether that window looked like the accessibility settings. */
    @Volatile
    private var lastWasSettingsScreen: Boolean = false

    private val ticker = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The framework reconnects the service without always destroying the old one first, and a
        // second OverlayManager strands the first one's window on screen: a shield nothing can
        // hide any more, still naming whichever app was blocked when it leaked. Covers both a
        // reconnect onto this same object and a reconnect onto a fresh one.
        instance?.takeIf { it !== this }?.teardown()
        overlay?.hide()

        instance = this
        Blocklist.refreshInstalled(this)
        overlay = OverlayManager(this) { evaluate() }
        CurfewPrefs.get(this)
            .append(LogType.NOTE, "Blocker service connected", Policy.effectiveNow(this))
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
        lastWasSettingsScreen = detectSettingsScreen(pkg, event.className?.toString())
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

        var decision = Policy.evaluate(this, lastPackage, settingsScreen = lastWasSettingsScreen)

        // Settings can walk from its front page to the accessibility list without always
        // emitting a window-state event — in-app search is the usual way. So while a settings
        // app is in the foreground during a curfew and has not been flagged yet, the tick asks
        // again. Bounded twice over: only inside the window, and only until the answer is yes.
        if (decision.reason == ShieldReason.NONE &&
            decision.snapshot.phase == Phase.ENFORCING &&
            !lastWasSettingsScreen &&
            SettingsGuard.isSettingsPackage(lastPackage)
        ) {
            lastWasSettingsScreen = detectSettingsScreen(lastPackage!!, null)
            if (lastWasSettingsScreen) {
                decision = Policy.evaluate(this, lastPackage, settingsScreen = true)
            }
        }

        when (decision.reason) {
            ShieldReason.BLOCKED_APP -> {
                decision.pkg?.let {
                    CurfewPrefs.get(this).appendBlockThrottled(it, Policy.effectiveNow(this))
                }
                shield.show(decision)
            }
            ShieldReason.SETTINGS -> {
                CurfewPrefs.get(this).appendThrottled(
                    SETTINGS_LOG_BUCKET,
                    LogType.SETTINGS_SHIELDED,
                    now = Policy.effectiveNow(this),
                )
                shield.show(decision)
            }
            ShieldReason.NONE -> if (shield.isShowing) shield.hide()
        }
    }

    // ---- settings detection --------------------------------------------------------------

    /**
     * Only asks the expensive question — walk the window and read its text — when the foreground
     * package is a settings app to begin with. That is a handful of times a day, not every event.
     */
    private fun detectSettingsScreen(pkg: String, className: String?): Boolean {
        if (!SettingsGuard.isSettingsPackage(pkg)) return false
        return SettingsGuard.shouldShield(
            pkg = pkg,
            className = className,
            texts = visibleTexts(),
            appLabel = getString(dev.davidz.curfew.R.string.app_name),
        )
    }

    /** Text and content descriptions from the active window, capped so a deep tree cannot stall us. */
    private fun visibleTexts(): List<String> {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        val out = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES && out.size < MAX_TEXTS) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return out
    }

    companion object {
        private const val TAG = "CurfewBlocker"
        private const val TICK_MS = 2_000L
        private const val MAX_NODES = 300
        private const val MAX_TEXTS = 120
        private const val SETTINGS_LOG_BUCKET = "settings_guard"

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
