package dev.davidz.curfew.core

/**
 * Pure time-window arithmetic, in minutes-of-day. Kept free of Android types so it is
 * unit-testable on the JVM — this is the piece that has to be right at 03:00.
 *
 * A window may wrap midnight (23:00 -> 07:00). start == end means "no window at all",
 * not "all day": an accidental equal pair should fail open, not lock the phone forever.
 */
object ScheduleEngine {

    const val MINUTES_PER_DAY: Int = 24 * 60

    fun isInside(nowMinuteOfDay: Int, startMinuteOfDay: Int, endMinuteOfDay: Int): Boolean {
        val now = wrap(nowMinuteOfDay)
        val start = wrap(startMinuteOfDay)
        val end = wrap(endMinuteOfDay)
        if (start == end) return false
        return if (start < end) now in start until end else now >= start || now < end
    }

    /** Minutes from [nowMinuteOfDay] until the window closes. 0 when not inside. */
    fun minutesUntilEnd(nowMinuteOfDay: Int, startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
        if (!isInside(nowMinuteOfDay, startMinuteOfDay, endMinuteOfDay)) return 0
        return forwardDistance(wrap(nowMinuteOfDay), wrap(endMinuteOfDay))
    }

    /** Minutes from [nowMinuteOfDay] until the window opens. 0 when already inside. */
    fun minutesUntilStart(nowMinuteOfDay: Int, startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
        if (isInside(nowMinuteOfDay, startMinuteOfDay, endMinuteOfDay)) return 0
        if (wrap(startMinuteOfDay) == wrap(endMinuteOfDay)) return 0
        return forwardDistance(wrap(nowMinuteOfDay), wrap(startMinuteOfDay))
    }

    /** Total length of the window in minutes; 0 when start == end. */
    fun windowLength(startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
        val start = wrap(startMinuteOfDay)
        val end = wrap(endMinuteOfDay)
        if (start == end) return 0
        return forwardDistance(start, end)
    }

    fun format(minuteOfDay: Int): String {
        val m = wrap(minuteOfDay)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** "4h 12m", "12m", "now" — for countdowns shown on the shield. */
    fun formatDuration(minutes: Int): String {
        if (minutes <= 0) return "now"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    private fun forwardDistance(from: Int, to: Int): Int =
        ((to - from) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private fun wrap(minuteOfDay: Int): Int =
        ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
