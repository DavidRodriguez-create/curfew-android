package dev.davidz.curfew.core

/**
 * Refuses a `(T, duration)` pair that has already been spent.
 *
 * Without this, a screenshot of a code is worth another 15 minutes for as long as its own step
 * is still inside the skew window — and the shield is exactly where someone would think to take
 * one. Pairs older than the widest window that could still verify are pruned, so the set stays
 * a handful of entries rather than a growing ledger.
 *
 * Pure in-memory logic; [CurfewPrefs] is what loads and stores the string form.
 */
class ReplayWindow(initial: Collection<String> = emptyList()) {

    private val used = LinkedHashSet<String>(initial)

    fun keys(): Set<String> = LinkedHashSet(used)

    fun isSpent(counter: Long, durationMinutes: Int): Boolean = key(counter, durationMinutes) in used

    /**
     * Marks the pair as spent. Returns false if it already was — that is a replay, and the
     * caller should refuse the unlock rather than treat it as a bad code.
     */
    fun consume(counter: Long, durationMinutes: Int, currentCounter: Long): Boolean {
        prune(currentCounter)
        return used.add(key(counter, durationMinutes))
    }

    /** Drops pairs whose step can no longer verify at [currentCounter]. */
    fun prune(currentCounter: Long) {
        val oldest = currentCounter - RETAIN_STEPS
        val newest = currentCounter + RETAIN_STEPS
        used.retainAll { entry ->
            val counter = entry.substringBefore(':').toLongOrNull() ?: return@retainAll false
            counter in oldest..newest
        }
        // A clock jumped far enough to make every entry look current is the one case that could
        // grow this without bound. Cap it and drop the oldest first.
        while (used.size > MAX_ENTRIES) used.remove(used.first())
    }

    private companion object {
        /**
         * Steps kept either side of now. [TotpVerifier.SKEW_STEPS] is 1, so 4 is generous
         * on purpose: pruning too eagerly would quietly re-open the replay it exists to close.
         */
        const val RETAIN_STEPS = 4L
        const val MAX_ENTRIES = 64

        fun key(counter: Long, durationMinutes: Int) = "$counter:$durationMinutes"
    }
}
