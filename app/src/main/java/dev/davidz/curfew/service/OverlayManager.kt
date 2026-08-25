package dev.davidz.curfew.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.davidz.curfew.core.Blocklist
import dev.davidz.curfew.core.CurfewPrefs
import dev.davidz.curfew.core.PanicState
import dev.davidz.curfew.core.Pairing
import dev.davidz.curfew.core.Policy
import dev.davidz.curfew.core.ScheduleEngine
import dev.davidz.curfew.core.ShieldDecision
import dev.davidz.curfew.core.ShieldReason
import dev.davidz.curfew.core.UnlockOutcome

/**
 * The shield: a full-screen window drawn on top of a blocked app, or on top of the accessibility
 * settings screen someone went looking for.
 *
 * Built from plain Views on purpose. A ComposeView inside a WindowManager overlay needs a
 * hand-rolled LifecycleOwner and SavedStateRegistryOwner, which is one more thing to be broken
 * at 03:00. The Compose surface lives in MainActivity, where it belongs.
 */
class OverlayManager(
    private val service: AccessibilityService,
    private val onStateChanged: () -> Unit,
) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val themed = ContextThemeWrapper(service, android.R.style.Theme_Material)
    private val handler = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var lastDecision: ShieldDecision? = null

    /** The WindowManager type the live window was added with. See [windowTypeFor]. */
    private var currentType: Int = 0

    /** True while the keypad is up, so the ticker leaves the main column alone. */
    private var enteringCode = false

    private lateinit var mainColumn: LinearLayout
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var unlockButton: Button
    private lateinit var panicButton: Button
    private lateinit var cancelButton: Button
    private lateinit var keypad: UnlockKeypad

    val isShowing: Boolean get() = root != null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    fun show(decision: ShieldDecision) {
        val type = windowTypeFor(decision.reason)
        // A window's type is fixed when it is added, so a shield that has to change type — an
        // app shield followed by a settings shield — has to be torn down and put back.
        if (root != null && type != currentType) hide()

        lastDecision = decision
        if (root == null) {
            val view = buildView()
            try {
                windowManager.addView(view, buildLayoutParams(type))
            } catch (t: Throwable) {
                Log.e(TAG, "Could not add shield window", t)
                return
            }
            root = view
            currentType = type
            handler.postDelayed(ticker, 1000L)
        }
        render(decision)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val view = root ?: return
        root = null
        lastDecision = null
        currentType = 0
        enteringCode = false
        try {
            // removeView, not removeViewImmediate: hide() is reachable from a button's own click
            // handler, and detaching the window synchronously mid-dispatch is asking for trouble.
            windowManager.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "Shield window was already gone", t)
        }
    }

    // ---- rendering ----------------------------------------------------------------------

    private fun refresh() {
        val current = lastDecision ?: return
        render(current.copy(snapshot = Policy.snapshot(service)))
    }

    private fun render(decision: ShieldDecision) {
        lastDecision = decision
        val snap = decision.snapshot

        if (enteringCode) {
            // Only the rate-limit countdown moves while the keypad is up.
            val locked = snap.unlockLockedSeconds
            if (locked > 0) {
                keypad.setKeysEnabled(false)
                keypad.setStatus("Too many wrong codes. ${locked}s.", error = true)
            } else if (!keypad.keysEnabled) {
                keypad.setKeysEnabled(true)
                keypad.setStatus("Try again.")
            }
            return
        }

        headline.text = when (decision.reason) {
            ShieldReason.SETTINGS -> "Not while the curfew is running"
            else -> (decision.pkg?.let { appLabel(it) } ?: "This app") + " is off limits"
        }
        subline.text = when (decision.reason) {
            ShieldReason.SETTINGS ->
                "Turning the blocker off is the one thing this screen will not do tonight."
            else ->
                "Curfew until " + ScheduleEngine.format(snap.endMinute) +
                    "  ·  " + ScheduleEngine.formatDuration(snap.minutesUntilEnd) + " to go"
        }

        unlockButton.visibility = if (snap.paired) View.VISIBLE else View.GONE
        unlockButton.text = if (snap.clockTampered) "Clock changed — unlock disabled" else
            "Enter a code from your approver"
        unlockButton.isEnabled = !snap.clockTampered

        when (snap.panic.state) {
            PanicState.NONE -> {
                panicButton.isEnabled = true
                panicButton.text = "I really need this"
                cancelButton.visibility = View.GONE
            }
            PanicState.COOLING -> {
                panicButton.isEnabled = false
                val s = snap.panic.secondsLeft
                panicButton.text = String.format("Unlock in %d:%02d", s / 60, s % 60)
                cancelButton.visibility = View.VISIBLE
            }
            PanicState.READY -> {
                panicButton.isEnabled = true
                panicButton.text = "Unlock " + Policy.PANIC_GRANT_MINUTES + " minutes"
                cancelButton.visibility = View.VISIBLE
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = service.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        Blocklist.fallbackLabelFor(pkg)
    }

    // ---- code entry ---------------------------------------------------------------------

    private fun openCodeEntry() {
        if (!Pairing.isPaired(service)) return
        enteringCode = true
        keypad.reset()
        mainColumn.visibility = View.GONE
        keypad.view.visibility = View.VISIBLE
        refresh()
    }

    private fun closeCodeEntry() {
        enteringCode = false
        keypad.view.visibility = View.GONE
        mainColumn.visibility = View.VISIBLE
        lastDecision?.let { render(it.copy(snapshot = Policy.snapshot(service))) }
    }

    private fun onCodeSubmitted(code: String) {
        keypad.setKeysEnabled(false)
        when (val outcome = Policy.redeemCode(service, code)) {
            is UnlockOutcome.Granted -> {
                keypad.setStatus("Unlocked for ${outcome.minutes} minutes.")
                // evaluate() sees the fresh grant, decides nothing is blocked, and hides us.
                handler.postDelayed({ onStateChanged() }, GRANT_PAUSE_MS)
            }
            UnlockOutcome.Rejected -> reject("That code is not valid right now.")
            UnlockOutcome.Replayed -> reject("That code has already been used.")
            is UnlockOutcome.RateLimited ->
                keypad.setStatus("Too many wrong codes. ${outcome.secondsLeft}s.", error = true)
            is UnlockOutcome.ClockTampered -> {
                keypad.setStatus("The phone clock has been changed.", error = true)
                handler.postDelayed({ closeCodeEntry() }, GRANT_PAUSE_MS)
            }
            UnlockOutcome.NotPaired -> reject("No approver is paired.")
        }
    }

    private fun reject(message: String) {
        keypad.setStatus(message, error = true)
        keypad.clearDigits()
        val locked = Policy.snapshot(service).unlockLockedSeconds
        if (locked == 0) keypad.setKeysEnabled(true)
        onStateChanged()
    }

    // ---- view construction --------------------------------------------------------------

    private fun buildView(): View {
        val container = FrameLayout(themed).apply {
            setBackgroundColor(ShieldStyle.BACKGROUND)
            isClickable = true
            isFocusable = false
        }

        mainColumn = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(24), dp(36), dp(24))
        }

        val eyebrow = TextView(themed).apply {
            text = "CURFEW"
            setTextColor(ShieldStyle.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
        }

        headline = TextView(themed).apply {
            setTextColor(ShieldStyle.FOREGROUND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        subline = TextView(themed).apply {
            setTextColor(ShieldStyle.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(40))
        }

        val homeButton = Button(themed).apply {
            text = "Go to the home screen"
            setTextColor(Color.BLACK)
            background = ShieldStyle.pill(themed, ShieldStyle.ACCENT)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            stateListAnimator = null
            setOnClickListener {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }

        unlockButton = Button(themed).apply {
            setTextColor(ShieldStyle.FOREGROUND)
            background = ShieldStyle.pill(themed, ShieldStyle.SURFACE, stroke = ShieldStyle.ACCENT)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            stateListAnimator = null
            setOnClickListener { openCodeEntry() }
        }

        panicButton = Button(themed).apply {
            setTextColor(ShieldStyle.MUTED)
            background = ShieldStyle.pill(themed, Color.TRANSPARENT, stroke = ShieldStyle.MUTED)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            stateListAnimator = null
            setOnClickListener { onPanicClicked() }
        }

        cancelButton = Button(themed).apply {
            text = "Never mind"
            setTextColor(ShieldStyle.FAINT)
            background = null
            stateListAnimator = null
            visibility = View.GONE
            setOnClickListener {
                Policy.cancelPanic(service)
                onStateChanged()
                refresh()
            }
        }

        val footnote = TextView(themed).apply {
            text = "Every unlock, approved or not, is written to the log."
            setTextColor(ShieldStyle.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        mainColumn.addView(eyebrow)
        mainColumn.addView(headline)
        mainColumn.addView(subline)
        mainColumn.addView(homeButton, buttonParams(0))
        mainColumn.addView(unlockButton, buttonParams(dp(28)))
        mainColumn.addView(panicButton, buttonParams(dp(12)))
        mainColumn.addView(cancelButton, buttonParams(dp(4)))
        mainColumn.addView(footnote)

        keypad = UnlockKeypad(
            context = themed,
            onSubmit = ::onCodeSubmitted,
            onClose = ::closeCodeEntry,
        )

        val stack = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            // CENTER, not CENTER_HORIZONTAL: `isFillViewport` stretches this to the viewport
            // height, so without the vertical half the shield rides against the status bar.
            gravity = Gravity.CENTER
            addView(
                mainColumn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                keypad.view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // The keypad is tall enough to overflow a short screen in landscape, and a shield you
        // cannot reach the buttons on is worse than no shield.
        val scroller = ScrollView(themed).apply {
            isFillViewport = true
            addView(
                stack,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }

        container.addView(
            scroller,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    private fun buttonParams(topMarginPx: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = topMarginPx
    }

    private fun onPanicClicked() {
        val prefs = CurfewPrefs.get(service)
        when (Policy.panicView(prefs, Policy.effectiveNow(service)).state) {
            PanicState.NONE -> Policy.startPanic(service)
            PanicState.READY -> Policy.redeemPanic(service)
            PanicState.COOLING -> return
        }
        onStateChanged()
        refresh()
    }

    /**
     * TYPE_APPLICATION_OVERLAY once the user has granted "draw over other apps".
     * TYPE_ACCESSIBILITY_OVERLAY otherwise: no permission needed, but the window dies with the
     * service, so the setup checklist still pushes for the real permission.
     *
     * The settings shield is the exception and always takes the accessibility type. The
     * accessibility settings screen calls `setHideOverlayWindows(true)`, and the framework then
     * force-hides every non-system overlay over it — the window is added, `addView` returns
     * cleanly, and nothing is drawn. Only `TYPE_ACCESSIBILITY_OVERLAY` is exempt. Found on the
     * emulator: `dumpsys window windows` showed our window with
     * `mForceHideNonSystemOverlayWindow=true` while the screen underneath stayed readable.
     */
    private fun windowTypeFor(reason: ShieldReason): Int =
        if (reason != ShieldReason.SETTINGS && Settings.canDrawOverlays(service)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

    private fun buildLayoutParams(type: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Not focusable: the shield swallows touches but never takes keyboard focus, so the
            // back key still reaches the app underneath instead of trapping the user in here.
            // It is also why the code entry is a drawn keypad — see [UnlockKeypad].
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Int): Int = ShieldStyle.dp(service, value)

    private companion object {
        const val TAG = "CurfewOverlay"

        /** Long enough to read "unlocked for 30 minutes" before the shield disappears. */
        const val GRANT_PAUSE_MS = 900L
    }
}
