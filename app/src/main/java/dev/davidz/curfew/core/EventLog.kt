package dev.davidz.curfew.core

/**
 * A tiny append-only activity log: a capped list of pipe-separated lines in SharedPreferences.
 * It is what makes an override cost something — panic or approved, it is visible after the fact,
 * and v0.2 adds the refused codes and the clock-tampering notes to the same ledger.
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
    PAIRED("Approver paired"),
    UNPAIRED("Pairing removed"),
    CODE_GRANTED("Unlocked by approver"),
    CODE_REJECTED("Unlock code refused"),
    CLOCK_TAMPERED("Clock tampering detected"),
    SETTINGS_SHIELDED("Accessibility settings covered"),
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
