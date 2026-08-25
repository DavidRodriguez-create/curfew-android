package dev.davidz.curfew.core

import android.content.Context
import android.os.SystemClock
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

enum class Phase {
    /** Master switch off. Nothing is enforced. */
    DISARMED,

    /** Armed, but the clock is outside the curfew window. */
    IDLE,

    /** Inside the window, blocking. */
    ENFORCING,

    /** Inside the window, but a grant — panic or approved — is currently running. */
    OVERRIDE,
}

enum class PanicState { NONE, COOLING, READY }

data class PanicView(val state: PanicState, val secondsLeft: Int)

/** Why the shield is up. */
enum class ShieldReason { NONE, BLOCKED_APP, SETTINGS }

/**
 * What the policy engine believes the time is, and whether it believes the phone.
 *
 * [effectiveNow] is the wall clock normally, and the monotonically-projected time once the wall
 * clock has been caught moving. Everything time-based in here runs off it — the schedule, the
 * panic cooldown, the grant countdown — so winding the clock forward buys nothing.
 */
data class ClockView(
    val effectiveNow: Long,
    val tampered: Boolean,
    val skewSeconds: Int,
)

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
    val overrideSource: String,
    val overrideMinutes: Int,
    val panic: PanicView,
    val blockedCount: Int,
    val paired: Boolean,
    val clockTampered: Boolean,
    val clockSkewSeconds: Int,
    val unlockLockedSeconds: Int,
) {
    val insideWindow: Boolean get() = phase == Phase.ENFORCING || phase == Phase.OVERRIDE
    val overrideByCode: Boolean get() = overrideSource == CurfewPrefs.OVERRIDE_CODE
}

data class ShieldDecision(
    val block: Boolean,
    val pkg: String?,
    val reason: ShieldReason,
    val snapshot: CurfewSnapshot,
)

/** Every way a typed code can end. */
sealed class UnlockOutcome {
    data class Granted(val minutes: Int) : UnlockOutcome()

    /** Verified, but this exact (T, duration) pair was already spent. */
    data object Replayed : UnlockOutcome()

    /** Nothing in the accepted window matched. */
    data object Rejected : UnlockOutcome()

    data class RateLimited(val secondsLeft: Int) : UnlockOutcome()

    data class ClockTampered(val skewSeconds: Int) : UnlockOutcome()

    data object NotPaired : UnlockOutcome()
}

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

    /** Wrong codes that cost nothing. Fat fingers on a 6-digit keypad are not an attack. */
    const val FREE_UNLOCK_ATTEMPTS: Int = 3
    private const val BASE_LOCKOUT_MS: Long = 30_000L
    private const val MAX_LOCKOUT_MS: Long = 15 * 60_000L

    /** Below this the anchor is left alone, so a quiet phase does not rewrite prefs every tick. */
    private const val REANCHOR_THRESHOLD_MS: Long = 1_000L

    // ---- clock --------------------------------------------------------------------------

    fun clockView(
        prefs: CurfewPrefs,
        wallNow: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime(),
    ): ClockView = clockView(prefs.clockAnchor, wallNow, elapsed)

    /**
     * The same thing against a bare anchor. This is the whole clock-tampering decision, and it
     * is the one piece of it that owns no Android type, so it is the one a unit test can reach.
     */
    fun clockView(anchor: ClockAnchor, wallNow: Long, elapsed: Long): ClockView {
        if (ClockGuard.needsReanchor(anchor, elapsed)) return ClockView(wallNow, false, 0)
        val skew = ClockGuard.skewMillis(anchor, wallNow, elapsed)
        if (abs(skew) <= ClockGuard.MAX_SKEW_MS) {
            return ClockView(wallNow, false, (skew / 1000L).toInt())
        }
        // The wall clock moved by hand. Stop believing it and project from the anchor instead.
        val projected = anchor.wallMillis + (elapsed - anchor.elapsedMillis)
        return ClockView(projected, true, (skew / 1000L).toInt())
    }

    // ---- snapshot -----------------------------------------------------------------------

    fun snapshot(
        context: Context,
        wallNow: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime(),
    ): CurfewSnapshot {
        val prefs = CurfewPrefs.get(context)
        val clock = clockView(prefs, wallNow, elapsed)
        val now = clock.effectiveNow
        val start = prefs.startMinute
        val end = prefs.endMinute
        val nowMinute = minuteOfDay(now)
        val inside = ScheduleEngine.isInside(nowMinute, start, end)
        val overrideUntil = prefs.overrideUntil
        val overrideLive = overrideUntil > now

        val phase = when {
            !prefs.armed -> Phase.DISARMED
            !inside -> Phase.IDLE
            overrideLive -> Phase.OVERRIDE
            else -> Phase.ENFORCING
        }

        val lockedUntil = prefs.unlockLockedUntil

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
            overrideSource = if (overrideLive) prefs.overrideSource else "",
            overrideMinutes = if (overrideLive) prefs.overrideMinutes else 0,
            panic = panicView(prefs, now),
            // Only apps that exist on this device — the old raw count claimed to be blocking
            // things that were never installed.
            blockedCount = prefs.blockedPackages.count {
                it in Blocklist.installedPackages(context)
            },
            paired = Pairing.isPaired(context),
            clockTampered = clock.tampered,
            clockSkewSeconds = clock.skewSeconds,
            unlockLockedSeconds = if (lockedUntil > now) {
                ((lockedUntil - now + 999L) / 1000L).toInt()
            } else {
                0
            },
        )
    }

    fun evaluate(
        context: Context,
        pkg: String?,
        settingsScreen: Boolean = false,
        wallNow: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime(),
    ): ShieldDecision {
        val snap = snapshot(context, wallNow, elapsed)
        val prefs = CurfewPrefs.get(context)
        val now = clockView(prefs, wallNow, elapsed).effectiveNow
        val enforcing = snap.phase == Phase.ENFORCING

        val reason = when {
            enforcing && pkg != null && prefs.isBlocked(pkg) -> ShieldReason.BLOCKED_APP
            enforcing && settingsScreen && !recentlyUnlocked(prefs, now) -> ShieldReason.SETTINGS
            else -> ShieldReason.NONE
        }

        return ShieldDecision(
            block = reason != ShieldReason.NONE,
            pkg = pkg,
            reason = reason,
            snapshot = snap,
        )
    }

    /** True inside the grace period after an approved code, when the settings shield stands down. */
    fun recentlyUnlocked(prefs: CurfewPrefs, now: Long): Boolean {
        val at = prefs.lastCodeAcceptedAt
        return at != 0L && now - at in 0 until SettingsGuard.GRACE_MS
    }

    /**
     * Housekeeping the tick loop calls every couple of seconds: keep the clock anchor honest,
     * expire stale grants and write one log line per real state change. Idempotent — safe to
     * call as often as you like.
     */
    fun recordTransitions(
        context: Context,
        wallNow: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime(),
    ) {
        val prefs = CurfewPrefs.get(context)
        val clock = clockView(prefs, wallNow, elapsed)
        val now = clock.effectiveNow

        if (prefs.overrideUntil != 0L && prefs.overrideUntil <= now) {
            prefs.overrideUntil = 0L
            prefs.overrideSource = ""
            prefs.overrideMinutes = 0
            prefs.append(LogType.OVERRIDE_EXPIRED, "", now)
        }

        val panicAt = prefs.panicStartedAt
        if (panicAt != 0L && now - panicAt > PANIC_COOLDOWN_MS + PANIC_REDEEM_WINDOW_MS) {
            prefs.panicStartedAt = 0L
            prefs.append(LogType.PANIC_CANCELLED, "timed out", now)
        }

        val phase = snapshot(context, wallNow, elapsed).phase
        maintainClockAnchor(prefs, phase, clock, wallNow, elapsed, now)

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

    /**
     * Re-anchors whenever nothing is being enforced, which is the only moment a clock change
     * costs nothing. During a curfew the anchor is frozen on purpose: that is what makes the
     * projected clock, and therefore the schedule, immune to the settings app.
     */
    private fun maintainClockAnchor(
        prefs: CurfewPrefs,
        phase: Phase,
        clock: ClockView,
        wallNow: Long,
        elapsed: Long,
        now: Long,
    ) {
        val anchor = prefs.clockAnchor
        val quiet = phase == Phase.DISARMED || phase == Phase.IDLE
        val drifting = abs(ClockGuard.skewMillis(anchor, wallNow, elapsed)) > REANCHOR_THRESHOLD_MS

        if (ClockGuard.needsReanchor(anchor, elapsed) || (quiet && drifting)) {
            prefs.clockAnchor = ClockAnchor(wallNow, elapsed)
            if (prefs.clockTamperLogged) prefs.clockTamperLogged = false
            return
        }

        if (clock.tampered && !prefs.clockTamperLogged) {
            prefs.clockTamperLogged = true
            prefs.append(LogType.CLOCK_TAMPERED, "${clock.skewSeconds}s off", now)
        }
    }

    // ---- unlock codes -------------------------------------------------------------------

    /**
     * The whole point of the project: a six-digit code only the approver can produce, carrying
     * an authenticated duration. See [TotpVerifier].
     */
    fun redeemCode(
        context: Context,
        entered: String,
        wallNow: Long = System.currentTimeMillis(),
        elapsed: Long = SystemClock.elapsedRealtime(),
    ): UnlockOutcome {
        val prefs = CurfewPrefs.get(context)
        val clock = clockView(prefs, wallNow, elapsed)
        val now = clock.effectiveNow

        // Refused before anything else is looked at: the approver's code is derived from her
        // clock, and we have just established that ours is not to be trusted.
        if (clock.tampered) return UnlockOutcome.ClockTampered(clock.skewSeconds)

        val lockedUntil = prefs.unlockLockedUntil
        if (lockedUntil > now) {
            return UnlockOutcome.RateLimited(((lockedUntil - now + 999L) / 1000L).toInt())
        }

        val secret = Pairing.secret(context) ?: return UnlockOutcome.NotPaired
        val epochSeconds = Math.floorDiv(now, 1000L)
        val match = try {
            TotpVerifier.verify(secret, entered, epochSeconds)
        } finally {
            secret.fill(0)
        }

        if (match == null) {
            registerFailure(prefs, now)
            prefs.append(LogType.CODE_REJECTED, "", now)
            return UnlockOutcome.Rejected
        }

        val window = prefs.replayWindow()
        val fresh = window.consume(
            match.counter,
            match.durationMinutes,
            TotpVerifier.counterFor(epochSeconds),
        )
        prefs.saveReplayWindow(window)

        if (!fresh) {
            registerFailure(prefs, now)
            prefs.append(LogType.CODE_REJECTED, "already used", now)
            return UnlockOutcome.Replayed
        }

        prefs.unlockFailures = 0
        prefs.unlockLockedUntil = 0L
        prefs.lastCodeAcceptedAt = now
        prefs.panicStartedAt = 0L
        prefs.overrideUntil = now + match.durationMinutes * 60_000L
        prefs.overrideSource = CurfewPrefs.OVERRIDE_CODE
        prefs.overrideMinutes = match.durationMinutes
        prefs.append(LogType.CODE_GRANTED, "${match.durationMinutes} min", now)
        return UnlockOutcome.Granted(match.durationMinutes)
    }

    /** Doubling lockout, capped. Pure so the escalation can be pinned down in a test. */
    fun lockoutMillis(failures: Int): Long {
        if (failures < FREE_UNLOCK_ATTEMPTS) return 0L
        val steps = min(failures - FREE_UNLOCK_ATTEMPTS, 20)
        val scaled = BASE_LOCKOUT_MS shl steps
        return if (scaled <= 0L) MAX_LOCKOUT_MS else min(scaled, MAX_LOCKOUT_MS)
    }

    private fun registerFailure(prefs: CurfewPrefs, now: Long) {
        val failures = prefs.unlockFailures + 1
        prefs.unlockFailures = failures
        val lockout = lockoutMillis(failures)
        if (lockout > 0L) prefs.unlockLockedUntil = now + lockout
    }

    // ---- pairing ------------------------------------------------------------------------

    /**
     * Pairing is a settings change like any other, so it is refused mid-curfew. Otherwise the
     * escape is trivial: re-pair to a secret you generated yourself and approve your own unlock.
     */
    fun canChangePairing(snapshot: CurfewSnapshot): Boolean = snapshot.phase != Phase.ENFORCING

    fun notePaired(context: Context, now: Long = effectiveNow(context)) {
        CurfewPrefs.get(context).append(LogType.PAIRED, "", now)
    }

    fun unpair(context: Context, now: Long = effectiveNow(context)) {
        Pairing.clear(context)
        CurfewPrefs.get(context).append(LogType.UNPAIRED, "", now)
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

    fun startPanic(context: Context, now: Long = effectiveNow(context)) {
        val prefs = CurfewPrefs.get(context)
        if (prefs.panicStartedAt != 0L) return
        prefs.panicStartedAt = now
        prefs.append(LogType.PANIC_STARTED, "", now)
    }

    fun cancelPanic(context: Context, now: Long = effectiveNow(context)) {
        val prefs = CurfewPrefs.get(context)
        if (prefs.panicStartedAt == 0L) return
        prefs.panicStartedAt = 0L
        prefs.append(LogType.PANIC_CANCELLED, "", now)
    }

    /** Returns true if the cooldown had actually elapsed and a grant was written. */
    fun redeemPanic(context: Context, now: Long = effectiveNow(context)): Boolean {
        val prefs = CurfewPrefs.get(context)
        if (panicView(prefs, now).state != PanicState.READY) return false
        prefs.panicStartedAt = 0L
        prefs.overrideUntil = now + PANIC_GRANT_MINUTES * 60_000L
        prefs.overrideSource = CurfewPrefs.OVERRIDE_PANIC
        prefs.overrideMinutes = PANIC_GRANT_MINUTES
        prefs.append(LogType.PANIC_GRANTED, "$PANIC_GRANT_MINUTES min", now)
        return true
    }

    /**
     * The clock the policy engine runs on. Identical to `System.currentTimeMillis()` until the
     * wall clock is caught moving, which is exactly when the panic cooldown would otherwise
     * become a five-second wait.
     */
    fun effectiveNow(context: Context): Long = clockView(CurfewPrefs.get(context)).effectiveNow

    fun currentMinuteOfDay(): Int = minuteOfDay(System.currentTimeMillis())

    fun minuteOfDay(epochMillis: Long): Int =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
            .let { it.hour * 60 + it.minute }
}
