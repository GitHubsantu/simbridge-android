package `in`.imdevops.simbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import `in`.imdevops.simbridge.SimBridgeApplication

/** Restarts the gateway after a reboot or app update if the user switched "Start after reboot" on, or after an in-app update that was started while the gateway was running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = (context.applicationContext as SimBridgeApplication).gatewayPrefs
        val resumeAfterUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && prefs.resumeAfterUpdate
        if (resumeAfterUpdate) prefs.resumeAfterUpdate = false
        if (!prefs.autostartOnBoot && !resumeAfterUpdate) return
        val now = System.currentTimeMillis()
        try {
            ContextCompat.startForegroundService(context, GatewayService.startIntent(context))
            prefs.addEvent(now, "Start requested after ${if (intent.action == Intent.ACTION_BOOT_COMPLETED) "reboot" else "app update"}")
        } catch (e: Exception) {
            // Android may refuse background foreground-service starts; do not retry in a loop.
            prefs.addEvent(now, "Start after ${intent.action?.substringAfterLast('.')} refused: ${e.javaClass.simpleName}")
        }
    }
}
