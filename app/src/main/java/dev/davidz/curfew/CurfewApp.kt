package dev.davidz.curfew

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CurfewApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Curfew status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The ongoing notification that keeps the blocker alive."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "curfew_status"
    }
}
