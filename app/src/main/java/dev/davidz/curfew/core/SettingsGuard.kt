package dev.davidz.curfew.core

/**
 * The obvious way out of an accessibility-based blocker is to walk to Settings and switch the
 * accessibility service off. So that screen gets covered too.
 *
 * This cannot be a hard lock — Safe Mode still wins, and nothing here survives someone who is
 * genuinely determined. It does not need to. It needs to cost more than "half-asleep in bed",
 * and a shield over the toggle does exactly that.
 *
 * Matching is deliberately conservative: it takes a settings package *and* a signal that this
 * particular screen is the accessibility one. Covering all of Settings would mean not being able
 * to change the alarm volume at 23:05, which is not the deal.
 */
object SettingsGuard {

    /** How long after an approved code the guard stands down, so the unlock can be acted on. */
    const val GRACE_MS: Long = 60_000L

    /** Settings apps across the OEMs that ship their own. */
    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",
        "com.android.tv.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.huawei.systemmanager",
    )

    fun isSettingsPackage(pkg: String?): Boolean = pkg != null && pkg in SETTINGS_PACKAGES

    /**
     * True when [pkg] is a settings app and this screen looks like the accessibility list, the
     * per-service detail page, or our own App info page — the three places the switch can be
     * reached from.
     *
     * [texts] are the visible strings in the window. Matching our own [appLabel] is the part that
     * survives translation: the class name is English, the screen title is not, but "Curfew" is
     * spelled the same everywhere and appears on exactly the pages that matter.
     */
    fun shouldShield(
        pkg: String?,
        className: String?,
        texts: Collection<String>,
        appLabel: String,
    ): Boolean {
        if (!isSettingsPackage(pkg)) return false
        if (className != null && className.contains(ACCESSIBILITY, ignoreCase = true)) return true
        if (appLabel.isNotBlank() && texts.any { it.contains(appLabel, ignoreCase = true) }) return true
        return texts.any { it.contains(ACCESSIBILITY, ignoreCase = true) }
    }

    private const val ACCESSIBILITY = "accessibility"
}
