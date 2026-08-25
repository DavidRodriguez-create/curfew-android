package dev.davidz.curfew.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for app state. Deliberately SharedPreferences and not Room:
 * every read happens on the accessibility service's main loop and must never block.
 *
 * v0.2 added the pairing-adjacent state — the replay window, the clock anchor and the unlock
 * rate limiter — and none of it changed that calculus. A replay window is at most a few dozen
 * short strings that expire after two minutes; a relational store for it would buy nothing and
 * cost the non-blocking read. Room still waits for v0.3, where per-app schedules and usage
 * stats create state that is actually relational.
 */
class CurfewPrefs private constructor(private val sp: SharedPreferences) {

    var armed: Boolean
        get() = sp.getBoolean(KEY_ARMED, true)
        set(value) = sp.edit().putBoolean(KEY_ARMED, value).apply()

    var startMinute: Int
        get() = sp.getInt(KEY_START, DEFAULT_START_MINUTE)
        set(value) = sp.edit().putInt(KEY_START, value).apply()

    var endMinute: Int
        get() = sp.getInt(KEY_END, DEFAULT_END_MINUTE)
        set(value) = sp.edit().putInt(KEY_END, value).apply()

    /** SharedPreferences hands back a shared mutable set, so always copy in and out. */
    var blockedPackages: Set<String>
        get() = HashSet(sp.getStringSet(KEY_BLOCKED, null) ?: Blocklist.DEFAULT_ON)
        set(value) = sp.edit().putStringSet(KEY_BLOCKED, HashSet(value)).apply()

    /** Epoch millis until which blocking is suspended. 0 = no grant. */
    var overrideUntil: Long
        get() = sp.getLong(KEY_OVERRIDE_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_OVERRIDE_UNTIL, value).apply()

    /** Epoch millis the panic cooldown started. 0 = no panic in flight. */
    var panicStartedAt: Long
        get() = sp.getLong(KEY_PANIC_AT, 0L)
        set(value) = sp.edit().putLong(KEY_PANIC_AT, value).apply()

    /** Last phase we logged a transition for, so the tick loop stays idempotent. */
    var lastLoggedPhase: String
        get() = sp.getString(KEY_LAST_PHASE, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_PHASE, value).apply()

    /** How the running override was obtained: [OVERRIDE_PANIC], [OVERRIDE_CODE], or "". */
    var overrideSource: String
        get() = sp.getString(KEY_OVERRIDE_SOURCE, "") ?: ""
        set(value) = sp.edit().putString(KEY_OVERRIDE_SOURCE, value).apply()

    /** Length of the running override in minutes, for the status line. */
    var overrideMinutes: Int
        get() = sp.getInt(KEY_OVERRIDE_MINUTES, 0)
        set(value) = sp.edit().putInt(KEY_OVERRIDE_MINUTES, value).apply()

    // ---- unlock codes -------------------------------------------------------------------

    /** Spent `(T, duration)` pairs. See [ReplayWindow]. */
    var replayKeys: Set<String>
        get() = HashSet(sp.getStringSet(KEY_REPLAY, null) ?: emptySet())
        set(value) = sp.edit().putStringSet(KEY_REPLAY, HashSet(value)).apply()

    fun replayWindow(): ReplayWindow = ReplayWindow(replayKeys)

    fun saveReplayWindow(window: ReplayWindow) {
        replayKeys = window.keys()
    }

    /** Consecutive rejected codes. Reset by a good one. */
    var unlockFailures: Int
        get() = sp.getInt(KEY_UNLOCK_FAILURES, 0)
        set(value) = sp.edit().putInt(KEY_UNLOCK_FAILURES, value).apply()

    /** Epoch millis until which the code entry refuses to look at anything. */
    var unlockLockedUntil: Long
        get() = sp.getLong(KEY_UNLOCK_LOCKED_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_UNLOCK_LOCKED_UNTIL, value).apply()

    /**
     * When a code was last accepted. The accessibility-settings shield stands down for a minute
     * afterwards, so an approved unlock can actually reach the switch it is meant to reach.
     */
    var lastCodeAcceptedAt: Long
        get() = sp.getLong(KEY_LAST_CODE_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_CODE_AT, value).apply()

    // ---- clock guard --------------------------------------------------------------------

    /** The wall/monotonic pair [ClockGuard] measures drift against. */
    var clockAnchor: ClockAnchor
        get() = ClockAnchor(
            sp.getLong(KEY_ANCHOR_WALL, 0L),
            sp.getLong(KEY_ANCHOR_ELAPSED, 0L),
        )
        set(value) = sp.edit()
            .putLong(KEY_ANCHOR_WALL, value.wallMillis)
            .putLong(KEY_ANCHOR_ELAPSED, value.elapsedMillis)
            .apply()

    /** Latched so the log gets one line per episode, not one per tick. */
    var clockTamperLogged: Boolean
        get() = sp.getBoolean(KEY_CLOCK_TAMPERED, false)
        set(value) = sp.edit().putBoolean(KEY_CLOCK_TAMPERED, value).apply()

    fun isBlocked(pkg: String): Boolean = pkg in blockedPackages

    fun setBlocked(pkg: String, blocked: Boolean) {
        val next = blockedPackages.toMutableSet()
        if (blocked) next.add(pkg) else next.remove(pkg)
        blockedPackages = next
    }

    // ---- activity log -------------------------------------------------------------------

    /**
     * Newest first, by timestamp rather than by insertion order.
     *
     * Those are not the same thing. Entries are written against [Policy.effectiveNow], which is
     * anchored and does not move when the wall clock does, so a device whose clock jumps writes
     * a run of entries whose stored times run backwards against the ones around them. Reversing
     * the file put them on screen in that order — a log that reads 15:12, 15:10, 14:57, 15:08.
     * Sorting is stable, and the list is reversed first, so entries sharing a timestamp still
     * come out newest-written first.
     */
    fun log(): List<LogEntry> =
        (sp.getString(KEY_LOG, "") ?: "")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(LogEntry::decode)
            .toList()
            .asReversed()
            .sortedByDescending { it.timestamp }

    @Synchronized
    fun append(type: LogType, detail: String = "", now: Long = System.currentTimeMillis()) {
        val existing = (sp.getString(KEY_LOG, "") ?: "").lineSequence().filter { it.isNotBlank() }.toMutableList()
        existing.add(LogEntry(now, type, detail).encode())
        while (existing.size > LogEntry.MAX_ENTRIES) existing.removeAt(0)
        sp.edit().putString(KEY_LOG, existing.joinToString("\n")).apply()
    }

    /**
     * At most one entry per [bucket] per minute, so a night spent bouncing off the shield is a
     * handful of lines rather than four thousand. Returns whether anything was written.
     */
    @Synchronized
    fun appendThrottled(
        bucket: String,
        type: LogType,
        detail: String = "",
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val lastKey = "$KEY_LAST_BLOCK_PREFIX$bucket"
        val last = sp.getLong(lastKey, 0L)
        if (now - last < BLOCK_LOG_THROTTLE_MS) return false
        sp.edit().putLong(lastKey, now).apply()
        append(type, detail, now)
        return true
    }

    /** Rate-limits repeated BLOCKED entries for the same app. */
    fun appendBlockThrottled(pkg: String, now: Long) {
        appendThrottled(pkg, LogType.BLOCKED, Blocklist.fallbackLabelFor(pkg), now)
    }

    fun clearLog() = sp.edit().putString(KEY_LOG, "").apply()

    companion object {
        const val DEFAULT_START_MINUTE = 23 * 60 // 23:00
        const val DEFAULT_END_MINUTE = 7 * 60    // 07:00

        const val OVERRIDE_PANIC = "panic"
        const val OVERRIDE_CODE = "code"

        private const val FILE = "curfew_prefs"
        private const val KEY_ARMED = "armed"
        private const val KEY_START = "start_minute"
        private const val KEY_END = "end_minute"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_OVERRIDE_UNTIL = "override_until"
        private const val KEY_PANIC_AT = "panic_started_at"
        private const val KEY_LAST_PHASE = "last_logged_phase"
        private const val KEY_OVERRIDE_SOURCE = "override_source"
        private const val KEY_OVERRIDE_MINUTES = "override_minutes"
        private const val KEY_REPLAY = "replay_keys"
        private const val KEY_UNLOCK_FAILURES = "unlock_failures"
        private const val KEY_UNLOCK_LOCKED_UNTIL = "unlock_locked_until"
        private const val KEY_LAST_CODE_AT = "last_code_accepted_at"
        private const val KEY_ANCHOR_WALL = "clock_anchor_wall"
        private const val KEY_ANCHOR_ELAPSED = "clock_anchor_elapsed"
        private const val KEY_CLOCK_TAMPERED = "clock_tampered"
        private const val KEY_LOG = "log"
        private const val KEY_LAST_BLOCK_PREFIX = "last_block_"
        private const val BLOCK_LOG_THROTTLE_MS = 60_000L

        @Volatile
        private var instance: CurfewPrefs? = null

        fun get(context: Context): CurfewPrefs =
            instance ?: synchronized(this) {
                instance ?: CurfewPrefs(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
