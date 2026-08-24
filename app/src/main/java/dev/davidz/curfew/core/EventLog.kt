package dev.davidz.curfew.core

/**
 * A tiny append-only activity log. Phase 2 replaces this with Room and syncs it into the PWA;
 * for the MVP a capped list of pipe-separated lines in SharedPreferences is enough, and it is
 * what makes the panic override cost something — it is visible after the fact.
 */
enum class LogType(val label: String) {
    ARMED("Armed"),
    DISARMED("Disarmed"),
    CURFEW_START("Curfew started"),
    CURFEW_END("Curfew ended"),
    BLOCKED("Blocked"),
    PANIC_STARTED("Panic override requested"),
    PANIC_CANCELLED("Panic override abandoned"),
    PANIC_GRANTED("Panic override used"),
    OVERRIDE_EXPIRED("Override expired"),
    NOTE("Note"),
    ;

    companion object {
        fun fromName(name: String): LogType =
            entries.firstOrNull { it.name == name } ?: NOTE
    }
}

data class LogEntry(
    val timestamp: Long,
    val type: LogType,
    val detail: String,
) {
    fun encode(): String = "$timestamp|${type.name}|${detail.replace('\n', ' ').replace('|', '/')}"

    companion object {
        const val MAX_ENTRIES = 200

        fun decode(line: String): LogEntry? {
            val parts = line.split('|', limit = 3)
            if (parts.size < 3) return null
            val ts = parts[0].toLongOrNull() ?: return null
            return LogEntry(ts, LogType.fromName(parts[1]), parts[2])
        }
    }
}
