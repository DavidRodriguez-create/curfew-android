package dev.davidz.curfew.core

/**
 * "Just change the phone clock" is the cheapest attack on a schedule, and the only one that
 * costs nothing at 03:00. Wall-clock time is attacker-controlled; `SystemClock.elapsedRealtime()`
 * is not — it counts since boot and no settings screen can move it.
 *
 * So: anchor the two together while things are quiet, and from then on compare how far wall-clock
 * has travelled against how far the monotonic clock has. They should move at the same rate.
 * A gap wider than [MAX_SKEW_MS] means the wall clock was moved by hand, and an unlock is refused
 * until the anchor is retaken.
 *
 * Deliberately no HTTPS `Date:` header cross-check, even though it would be stronger: the app
 * has no network code at all, and that is a property worth more than this check is.
 */
data class ClockAnchor(val wallMillis: Long, val elapsedMillis: Long) {
    val isSet: Boolean get() = wallMillis != 0L || elapsedMillis != 0L

    companion object {
        val NONE = ClockAnchor(0L, 0L)
    }
}

object ClockGuard {

    /** Tolerated wall-clock drift before we call it tampering. */
    const val MAX_SKEW_MS: Long = 60_000L

    /**
     * Signed drift in millis: positive means the wall clock ran ahead of the monotonic clock
     * (moved forwards), negative means it was pushed back. 0 when there is no usable anchor.
     */
    fun skewMillis(anchor: ClockAnchor, nowWall: Long, nowElapsed: Long): Long {
        if (!anchor.isSet || needsReanchor(anchor, nowElapsed)) return 0L
        val monotonicElapsed = nowElapsed - anchor.elapsedMillis
        val wallElapsed = nowWall - anchor.wallMillis
        return wallElapsed - monotonicElapsed
    }

    fun isTampered(anchor: ClockAnchor, nowWall: Long, nowElapsed: Long): Boolean =
        kotlin.math.abs(skewMillis(anchor, nowWall, nowElapsed)) > MAX_SKEW_MS

    /**
     * True when the anchor cannot be compared any more: either it was never taken, or the
     * monotonic clock has gone backwards, which only happens across a reboot. A reboot is not
     * evidence of tampering — it just means the old anchor is worthless.
     */
    fun needsReanchor(anchor: ClockAnchor, nowElapsed: Long): Boolean =
        !anchor.isSet || nowElapsed < anchor.elapsedMillis
}
