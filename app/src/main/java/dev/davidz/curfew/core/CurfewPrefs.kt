package dev.davidz.curfew.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for MVP state. Deliberately SharedPreferences and not Room:
 * every read happens on the accessibility service's main loop and must never block.
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

    fun isBlocked(pkg: String): Boolean = pkg in blockedPackages

    fun setBlocked(pkg: String, blocked: Boolean) {
        val next = blockedPackages.toMutableSet()
        if (blocked) next.add(pkg) else next.remove(pkg)
        blockedPackages = next
    }

    // ---- activity log -------------------------------------------------------------------

    fun log(): List<LogEntry> =
        (sp.getString(KEY_LOG, "") ?: "")
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull(LogEntry::decode)
            .toList()
            .asReversed() // newest first

    @Synchronized
    fun append(type: LogType, detail: String = "", now: Long = System.currentTimeMillis()) {
        val existing = (sp.getString(KEY_LOG, "") ?: "").lineSequence().filter { it.isNotBlank() }.toMutableList()
        existing.add(LogEntry(now, type, detail).encode())
        while (existing.size > LogEntry.MAX_ENTRIES) existing.removeAt(0)
        sp.edit().putString(KEY_LOG, existing.joinToString("\n")).apply()
    }

    /** Rate-limits repeated BLOCKED entries for the same app so one night is not 4000 lines. */
    @Synchronized
    fun appendBlockThrottled(pkg: String, now: Long = System.currentTimeMillis()) {
        val lastKey = "$KEY_LAST_BLOCK_PREFIX$pkg"
        val last = sp.getLong(lastKey, 0L)
        if (now - last < BLOCK_LOG_THROTTLE_MS) return
        sp.edit().putLong(lastKey, now).apply()
        append(LogType.BLOCKED, Blocklist.fallbackLabelFor(pkg), now)
    }

    fun clearLog() = sp.edit().putString(KEY_LOG, "").apply()

    companion object {
        const val DEFAULT_START_MINUTE = 23 * 60 // 23:00
        const val DEFAULT_END_MINUTE = 7 * 60    // 07:00

        private const val FILE = "curfew_prefs"
        private const val KEY_ARMED = "armed"
        private const val KEY_START = "start_minute"
        private const val KEY_END = "end_minute"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_OVERRIDE_UNTIL = "override_until"
        private const val KEY_PANIC_AT = "panic_started_at"
        private const val KEY_LAST_PHASE = "last_logged_phase"
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
