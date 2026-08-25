package dev.davidz.curfew

import dev.davidz.curfew.core.SettingsGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {

    private val label = "Curfew"

    @Test
    fun `covers the accessibility list by class name`() {
        assertTrue(
            SettingsGuard.shouldShield(
                pkg = "com.android.settings",
                className = "com.android.settings.Settings\$AccessibilitySettingsActivity",
                texts = emptyList(),
                appLabel = label,
            ),
        )
    }

    /**
     * The per-service page comes through as a generic SubSettings class. Our own name on the
     * screen is the signal, and it is the one that survives the phone not being in English.
     */
    @Test
    fun `covers the service detail page by our own name`() {
        assertTrue(
            SettingsGuard.shouldShield(
                pkg = "com.android.settings",
                className = "com.android.settings.SubSettings",
                texts = listOf("Curfew blocker", "Use service"),
                appLabel = label,
            ),
        )
    }

    /** App info is where force-stop and uninstall live, so it counts too. */
    @Test
    fun `covers our own App info page`() {
        assertTrue(
            SettingsGuard.shouldShield(
                pkg = "com.android.settings",
                className = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = listOf("Curfew", "Force stop", "Uninstall"),
                appLabel = label,
            ),
        )
    }

    /** Covering all of Settings would mean not being able to change the alarm volume at 23:05. */
    @Test
    fun `leaves the rest of Settings alone`() {
        assertFalse(
            SettingsGuard.shouldShield(
                pkg = "com.android.settings",
                className = "com.android.settings.Settings\$SoundSettingsActivity",
                texts = listOf("Media volume", "Ring volume"),
                appLabel = label,
            ),
        )
    }

    @Test
    fun `ignores apps that are not a settings app`() {
        assertFalse(
            SettingsGuard.shouldShield(
                pkg = "com.instagram.android",
                className = "com.instagram.AccessibilitySomething",
                texts = listOf("Curfew"),
                appLabel = label,
            ),
        )
        assertFalse(SettingsGuard.isSettingsPackage(null))
    }

    @Test
    fun `recognises the OEM settings apps`() {
        assertTrue(SettingsGuard.isSettingsPackage("com.samsung.android.settings"))
        assertTrue(SettingsGuard.isSettingsPackage("com.miui.securitycenter"))
        assertFalse(SettingsGuard.isSettingsPackage("com.android.settings.fake"))
    }
}
