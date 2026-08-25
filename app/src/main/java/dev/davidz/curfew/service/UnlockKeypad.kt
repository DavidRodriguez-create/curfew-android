package dev.davidz.curfew.service

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.davidz.curfew.core.TotpVerifier

/**
 * The six-digit entry that lives on the shield.
 *
 * A drawn keypad rather than an EditText and the soft keyboard, for one blunt reason: the shield
 * window is `FLAG_NOT_FOCUSABLE`, and it has to stay that way. Making it focusable to summon an
 * IME means the back key stops reaching the app underneath, means a `TYPE_ACCESSIBILITY_OVERLAY`
 * fallback that cannot take focus at all, and means trusting the IME to appear above an overlay
 * window. Ten buttons have none of those failure modes.
 */
class UnlockKeypad(
    private val context: Context,
    private val onSubmit: (String) -> Unit,
    private val onClose: () -> Unit,
) {

    private val entered = StringBuilder()

    private val digitsView: TextView
    private val statusView: TextView
    private val keys = mutableListOf<Button>()

    val view: View

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }

        column.addView(
            TextView(context).apply {
                text = "Code from your approver"
                setTextColor(ShieldStyle.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            },
        )

        digitsView = TextView(context).apply {
            setTextColor(ShieldStyle.FOREGROUND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(4))
        }
        column.addView(digitsView)

        statusView = TextView(context).apply {
            setTextColor(ShieldStyle.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        column.addView(statusView)

        for (row in listOf("123", "456", "789")) {
            column.addView(keyRow(row.map { it.toString() }))
        }
        column.addView(keyRow(listOf(BACKSPACE, "0", DONE)))

        column.addView(
            Button(context).apply {
                text = "Never mind"
                setTextColor(ShieldStyle.FAINT)
                background = null
                stateListAnimator = null
                setOnClickListener { onClose() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8)
            },
        )

        view = column
        render()
    }

    // ---- state --------------------------------------------------------------------------

    fun reset() {
        entered.setLength(0)
        statusView.text = ""
        statusView.setTextColor(ShieldStyle.FAINT)
        setKeysEnabled(true)
        render()
    }

    fun setStatus(text: String, error: Boolean = false) {
        statusView.text = text
        statusView.setTextColor(if (error) ShieldStyle.DANGER else ShieldStyle.MUTED)
    }

    /** Clears the digits but keeps the message, so a rejection can be read before retrying. */
    fun clearDigits() {
        entered.setLength(0)
        render()
    }

    /** Whether the digits are live, so a ticker can tell a lockout from a fresh keypad. */
    var keysEnabled: Boolean = true
        private set

    fun setKeysEnabled(enabled: Boolean) {
        keysEnabled = enabled
        keys.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
    }

    // ---- view construction --------------------------------------------------------------

    private fun keyRow(labels: List<String>): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        labels.forEach { label -> row.addView(keyButton(label)) }
        return row
    }

    private fun keyButton(label: String): Button {
        val button = Button(context).apply {
            text = when (label) {
                BACKSPACE -> "⌫"
                DONE -> "↵"
                else -> label
            }
            setTextColor(if (label == DONE) Color.BLACK else ShieldStyle.FOREGROUND)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = ShieldStyle.pill(
                context,
                if (label == DONE) ShieldStyle.ACCENT else ShieldStyle.SURFACE,
                radiusDp = 16,
            )
            stateListAnimator = null
            setOnClickListener { onKey(label) }
        }
        button.layoutParams = LinearLayout.LayoutParams(dp(74), dp(58)).apply {
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }
        keys.add(button)
        return button
    }

    private fun onKey(label: String) {
        when (label) {
            BACKSPACE -> if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
            DONE -> {
                if (entered.length == TotpVerifier.DIGITS) onSubmit(entered.toString())
                return
            }
            else -> if (entered.length < TotpVerifier.DIGITS) entered.append(label)
        }
        render()
        // Six digits is the whole code; making the user reach for a second button after that
        // is pure friction on the one screen that already has enough of it.
        if (entered.length == TotpVerifier.DIGITS) onSubmit(entered.toString())
    }

    private fun render() {
        digitsView.text = (0 until TotpVerifier.DIGITS).joinToString(" ") { index ->
            if (index < entered.length) entered[index].toString() else "—"
        }
    }

    private fun dp(value: Int) = ShieldStyle.dp(context, value)

    private companion object {
        const val BACKSPACE = "back"
        const val DONE = "done"
    }
}
