package dev.davidz.curfew.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import dev.davidz.curfew.core.Blocklist
import dev.davidz.curfew.core.CurfewPrefs
import dev.davidz.curfew.core.PanicState
import dev.davidz.curfew.core.Policy
import dev.davidz.curfew.core.ScheduleEngine
import dev.davidz.curfew.core.ShieldDecision

/**
 * The shield: a full-screen window drawn on top of a blocked app.
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

    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var panicButton: Button
    private lateinit var cancelButton: Button

    val isShowing: Boolean get() = root != null

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    fun show(decision: ShieldDecision) {
        lastDecision = decision
        if (root == null) {
            val view = buildView()
            try {
                windowManager.addView(view, buildLayoutParams())
            } catch (t: Throwable) {
                Log.e(TAG, "Could not add shield window", t)
                return
            }
            root = view
            handler.postDelayed(ticker, 1000L)
        }
        render(decision)
    }

    fun hide() {
        handler.removeCallbacks(ticker)
        val view = root ?: return
        root = null
        lastDecision = null
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
        val label = decision.pkg?.let { appLabel(it) } ?: "This app"

        headline.text = label + " is off limits"
        subline.text = "Curfew until " + ScheduleEngine.format(snap.endMinute) +
            "  ·  " + ScheduleEngine.formatDuration(snap.minutesUntilEnd) + " to go"

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

    // ---- view construction --------------------------------------------------------------

    private fun buildView(): View {
        val container = FrameLayout(themed).apply {
            setBackgroundColor(BACKGROUND)
            isClickable = true
            isFocusable = false
        }

        val column = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(24), dp(36), dp(24))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        val eyebrow = TextView(themed).apply {
            text = "CURFEW"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
        }

        headline = TextView(themed).apply {
            setTextColor(FOREGROUND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        subline = TextView(themed).apply {
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(40))
        }

        val homeButton = Button(themed).apply {
            text = "Go to the home screen"
            setTextColor(Color.BLACK)
            background = pill(ACCENT)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            stateListAnimator = null
            setOnClickListener {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }

        panicButton = Button(themed).apply {
            setTextColor(MUTED)
            background = pill(Color.TRANSPARENT, stroke = MUTED)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            stateListAnimator = null
            setOnClickListener { onPanicClicked() }
        }

        cancelButton = Button(themed).apply {
            text = "Never mind"
            setTextColor(FAINT)
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
            text = "Every override is written to the log."
            setTextColor(FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        column.addView(eyebrow)
        column.addView(headline)
        column.addView(subline)
        column.addView(homeButton, buttonParams(0))
        column.addView(panicButton, buttonParams(dp(28)))
        column.addView(cancelButton, buttonParams(dp(4)))
        column.addView(footnote)
        container.addView(column)
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
        when (Policy.panicView(prefs).state) {
            PanicState.NONE -> Policy.startPanic(service)
            PanicState.READY -> Policy.redeemPanic(service)
            PanicState.COOLING -> return
        }
        onStateChanged()
        refresh()
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // TYPE_APPLICATION_OVERLAY once the user has granted "draw over other apps".
        // TYPE_ACCESSIBILITY_OVERLAY otherwise: no permission needed, but the window dies
        // with the service, so the setup checklist still pushes for the real permission.
        val type = if (Settings.canDrawOverlays(service)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Not focusable: the shield swallows touches but never takes keyboard focus, so the
            // back key still reaches the app underneath instead of trapping the user in here.
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

    private fun pill(fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(28).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CurfewOverlay"
        const val BACKGROUND = 0xF20E1116.toInt()
        const val FOREGROUND = 0xFFF8FAFC.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
        const val FAINT = 0xFF64748B.toInt()
        const val ACCENT = 0xFF7DD3FC.toInt()
    }
}
