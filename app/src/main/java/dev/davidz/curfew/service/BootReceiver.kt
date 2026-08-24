package dev.davidz.curfew.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The accessibility service is restarted by the system on its own after a reboot or an update.
 * This only brings the foreground service back with it.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> CurfewForegroundService.start(context)
        }
    }
}
