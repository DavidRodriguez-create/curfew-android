package dev.davidz.curfew

import dev.davidz.curfew.core.LogEntry
import dev.davidz.curfew.core.LogType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The log is written against the *effective* clock, which is anchored and does not follow the
 * wall clock when that jumps. So the order entries are appended in is not the order their
 * timestamps are in, and rendering the file backwards is not enough.
 *
 * This is the emulator finding of 2026-08-25 pinned: the activity list read
 * 15:12, 15:10, 14:57, 15:08, 14:56 after the host clock moved twelve minutes.
 */
class EventLogOrderTest {

    /** What [CurfewPrefs.log] does, minus the Android SharedPreferences it cannot reach here. */
    private fun render(lines: List<String>): List<LogEntry> =
        lines.mapNotNull(LogEntry::decode).asReversed().sortedByDescending { it.timestamp }

    @Test
    fun `entries come out newest first even when they were appended out of order`() {
        val appended = listOf(
            LogEntry(1_000L, LogType.BLOCKED, "Chrome"),
            LogEntry(3_000L, LogType.PANIC_STARTED, ""),
            LogEntry(2_000L, LogType.NOTE, "clock moved back"),
            LogEntry(4_000L, LogType.CODE_GRANTED, "30 min"),
        ).map(LogEntry::encode)

        assertEquals(listOf(4_000L, 3_000L, 2_000L, 1_000L), render(appended).map { it.timestamp })
    }

    /** Two entries in the same millisecond keep the order they were written in, newest first. */
    @Test
    fun `ties fall back to insertion order`() {
        val appended = listOf(
            LogEntry(5_000L, LogType.BLOCKED, "first"),
            LogEntry(5_000L, LogType.BLOCKED, "second"),
        ).map(LogEntry::encode)

        assertEquals(listOf("second", "first"), render(appended).map { it.detail })
    }

    @Test
    fun `a malformed line is dropped rather than taking the log with it`() {
        val appended = listOf(
            LogEntry(1_000L, LogType.ARMED, "").encode(),
            "not a log line",
            LogEntry(2_000L, LogType.DISARMED, "").encode(),
        )
        assertEquals(listOf(2_000L, 1_000L), render(appended).map { it.timestamp })
    }
}
