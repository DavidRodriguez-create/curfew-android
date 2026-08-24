package dev.davidz.curfew.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.davidz.curfew.CurfewApp
import dev.davidz.curfew.MainActivity
import dev.davidz.curfew.R
import dev.davidz.curfew.core.Phase
import dev.davidz.curfew.core.Policy
import dev.davidz.curfew.core.ScheduleEngine

/**
 * Belt and braces. The accessibility service does the actual work and the system restarts it on
 * its own, but Doze and the OEM battery killers are far less interested in a process that owns a
 * foreground notification. It also gives an at-a-glance state readout on the lock screen.
 */
class CurfewForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            updateNotification()
            // Nudge the blocker in case an event was missed while the screen was off.
            BlockerAccessibilityService.instance?.evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Must happen within 5s of the service starting, so it is the very first thing we do.
        startAsForeground()
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 15 refuses some background-started foreground services (for instance from
            // BOOT_COMPLETED with the specialUse type). Enforcement does not depend on this
            // service, so degrade quietly rather than crashing the blocker.
            Log.w(TAG, "Could not enter the foreground; running without the notification", t)
        }
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "Notification update failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val snap = Policy.snapshot(this)

        val text = when (snap.phase) {
            Phase.DISARMED -> "Disarmed - nothing is being blocked"
            Phase.IDLE -> "Armed - curfew starts at " +
                ScheduleEngine.format(snap.startMinute) +
                " (in " + ScheduleEngine.formatDuration(snap.minutesUntilStart) + ")"
            Phase.ENFORCING -> "Blocking " + snap.blockedCount + " apps until " +
                ScheduleEngine.format(snap.endMinute) +
                " (" + ScheduleEngine.formatDuration(snap.minutesUntilEnd) + " to go)"
            Phase.OVERRIDE -> {
                val s = snap.overrideSecondsLeft
                String.format("Override active - %d:%02d left", s / 60, s % 60)
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CurfewApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_curfew)
            .setContentTitle("Curfew")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CurfewFgs"
        private const val NOTIFICATION_ID = 0x0C0FFEE
        private const val TICK_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, CurfewForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Foreground service start refused", t)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CurfewForegroundService::class.java))
        }
    }
}
