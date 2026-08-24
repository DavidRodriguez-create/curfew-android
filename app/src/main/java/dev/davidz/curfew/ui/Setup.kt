package dev.davidz.curfew.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.davidz.curfew.core.Blocklist
import dev.davidz.curfew.service.BlockerAccessibilityService

data class SetupItem(
    val title: String,
    val detail: String,
    val done: Boolean,
    val required: Boolean,
    val actionLabel: String,
    val intent: (Context) -> Intent,
)

data class InstalledApp(val pkg: String, val label: String)

object Setup {

    fun items(context: Context): List<SetupItem> = listOf(
        SetupItem(
            title = "Accessibility service",
            detail = "Lets Curfew see which app is in front. Without it nothing is blocked.",
            done = BlockerAccessibilityService.isEnabled(context),
            required = true,
            actionLabel = "Open accessibility settings",
            intent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        ),
        SetupItem(
            title = "Draw over other apps",
            detail = "Without it the shield still works, but it dies whenever the service restarts.",
            done = Settings.canDrawOverlays(context),
            required = true,
            actionLabel = "Grant overlay permission",
            intent = { ctx ->
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.packageName),
                )
            },
        ),
        SetupItem(
            title = "Unrestricted battery",
            detail = "Doze and OEM battery managers will kill the blocker overnight otherwise.",
            done = isIgnoringBatteryOptimizations(context),
            required = true,
            actionLabel = "Allow background running",
            intent = { ctx ->
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.packageName),
                )
            },
        ),
        SetupItem(
            title = "Notifications",
            detail = "Carries the ongoing status notification. Not required, but the service is " +
                "harder to kill with it.",
            done = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            required = false,
            actionLabel = "Open notification settings",
            intent = { ctx ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            },
        ),
    )

    fun blockingIsLive(context: Context): Boolean = BlockerAccessibilityService.isEnabled(context)

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        true // Nothing we can do about it; do not nag.
    }

    /** The curated candidates that are actually installed, with their real launcher labels. */
    fun installedCandidates(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        return Blocklist.CANDIDATES.mapNotNull { candidate ->
            try {
                val info = pm.getApplicationInfo(candidate.pkg, 0)
                InstalledApp(candidate.pkg, pm.getApplicationLabel(info).toString())
            } catch (t: Throwable) {
                null
            }
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w("CurfewSetup", "No activity for " + intent.action, t)
        }
    }
}
