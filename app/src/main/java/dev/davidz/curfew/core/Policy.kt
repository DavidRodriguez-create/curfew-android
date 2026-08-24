package dev.davidz.curfew.core

import android.content.Context
import java.time.LocalTime

enum class Phase {
    /** Master switch off. Nothing is enforced. */
    DISARMED,

    /** Armed, but the clock is outside the curfew window. */
    IDLE,

    /** Inside the window, blocking. */
    ENFORCING,

    /** Inside the window, but a panic grant is currently running. */
    OVERRIDE,
}

enum class PanicState { NONE, COOLING, READY }

data class PanicView(val state: PanicState, val secondsLeft: Int)

data class CurfewSnapshot(
    val phase: Phase,
    val armed: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val nowMinute: Int,
    val minutesUntilEnd: Int,
    val minutesUntilStart: Int,
    val overrideUntil: Long,
    val overrideSecondsLeft: Int,
    val panic: PanicView,
    val blockedCount: Int,
) {
    val insideWindow: Boolean get() = phase == Phase.ENFORCING || phase == Phase.OVERRIDE
}

data class ShieldDecision(
    val block: Boolean,
    val pkg: String?,
    val snapshot: CurfewSnapshot,
)

/**
 * Everything that decides "is this app allowed right now". The accessibility service and the
 * UI both go through here so there is exactly one answer.
 */
object Policy {

    /** How long you have to sit and stare at the shield before the escape hatch opens. */
    const val PANIC_COOLDOWN_MS: Long = 5 * 60_000L

    /** What a panic override buys you. */
    const val PANIC_GRANT_MINUTES: Int = 15

    /** A panic request left unredeemed this long after cooling down is abandoned. */
    private const val PANIC_REDEEM_WINDOW_MS: Long = 30 * 60_000L

    fun snapshot(
        context: Context,
        now: Long = System.currentTimeMillis(),
        nowMinute: Int = currentMinuteOfDay(),
    ): CurfewSnapshot {
        val prefs = CurfewPrefs.get(context)
        val start = prefs.startMinute
        val end = prefs.endMinute
        val inside = ScheduleEngine.isInside(nowMinute, start, end)
        val overrideUntil = prefs.overrideUntil
        val overrideLive = overrideUntil > now

        val phase = when {
            !prefs.armed -> Phase.DISARMED
            !inside -> Phase.IDLE
            overrideLive -> Phase.OVERRIDE
            else -> Phase.ENFORCING
        }

        return CurfewSnapshot(
            phase = phase,
            armed = prefs.armed,
            startMinute = start,
            endMinute = end,
            nowMinute = nowMinute,
            minutesUntilEnd = ScheduleEngine.minutesUntilEnd(nowMinute, start, end),
            minutesUntilStart = ScheduleEngine.minutesUntilStart(nowMinute, start, end),
            overrideUntil = overrideUntil,
            overrideSecondsLeft = if (overrideLive) ((overrideUntil - now) / 1000L).toInt() else 0,
            panic = panicView(prefs, now),
            blockedCount = prefs.blockedPackages.size,
        )
    }

    fun evaluate(
        context: Context,
        pkg: String?,
        now: Long = System.currentTimeMillis(),
    ): ShieldDecision {
        val snap = snapshot(context, now)
        val prefs = CurfewPrefs.get(context)
        val block = snap.phase == Phase.ENFORCING && pkg != null && prefs.isBlocked(pkg)
        return ShieldDecision(block = block, pkg = pkg, snapshot = snap)
    }

    /**
     * Housekeeping the tick loop calls every couple of seconds: expire stale grants and write
     * one log line per real state change. Idempotent — safe to call as often as you like.
     */
    fun recordTransitions(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = CurfewPrefs.get(context)

        if (prefs.overrideUntil != 0L && prefs.overrideUntil <= now) {
            prefs.overrideUntil = 0L
            prefs.append(LogType.OVERRIDE_EXPIRED, "", now)
        }

        val panicAt = prefs.panicStartedAt
        if (panicAt != 0L && now - panicAt > PANIC_COOLDOWN_MS + PANIC_REDEEM_WINDOW_MS) {
            prefs.panicStartedAt = 0L
            prefs.append(LogType.PANIC_CANCELLED, "timed out", now)
        }

        val phase = snapshot(context, now).phase
        if (phase.name != prefs.lastLoggedPhase) {
            val previous = prefs.lastLoggedPhase
            prefs.lastLoggedPhase = phase.name
            if (previous.isNotEmpty()) {
                when (phase) {
                    Phase.ENFORCING -> if (previous == Phase.IDLE.name) {
                        prefs.append(LogType.CURFEW_START, "", now)
                    }
                    Phase.IDLE -> if (previous == Phase.ENFORCING.name || previous == Phase.OVERRIDE.name) {
                        prefs.append(LogType.CURFEW_END, "", now)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---- panic override -----------------------------------------------------------------

    fun panicView(prefs: CurfewPrefs, now: Long = System.currentTimeMillis()): PanicView {
        val startedAt = prefs.panicStartedAt
        if (startedAt == 0L) return PanicView(PanicState.NONE, 0)
        val elapsed = now - startedAt
        if (elapsed < PANIC_COOLDOWN_MS) {
            val left = ((PANIC_COOLDOWN_MS - elapsed + 999) / 1000L).toInt()
            return PanicView(PanicState.COOLING, left)
        }
        return PanicView(PanicState.READY, 0)
    }

    fun startPanic(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = CurfewPrefs.get(context)
        if (prefs.panicStartedAt != 0L) return
        prefs.panicStartedAt = now
        prefs.append(LogType.PANIC_STARTED, "", now)
    }

    fun cancelPanic(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = CurfewPrefs.get(context)
        if (prefs.panicStartedAt == 0L) return
        prefs.panicStartedAt = 0L
        prefs.append(LogType.PANIC_CANCELLED, "", now)
    }

    /** Returns true if the cooldown had actually elapsed and a grant was written. */
    fun redeemPanic(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val prefs = CurfewPrefs.get(context)
        if (panicView(prefs, now).state != PanicState.READY) return false
        prefs.panicStartedAt = 0L
        prefs.overrideUntil = now + PANIC_GRANT_MINUTES * 60_000L
        prefs.append(LogType.PANIC_GRANTED, "$PANIC_GRANT_MINUTES min", now)
        return true
    }

    fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
}
