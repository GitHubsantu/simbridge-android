package `in`.imdevops.simbridge.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import `in`.imdevops.simbridge.SimBridgeApplication

/**
 * Receives the Android installer's progress. It either opens the system "Update this app?" screen,
 * or records why the update did not happen. On success the process is replaced, so nothing runs here.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_RESULT) return
        val prefs = (context.applicationContext as SimBridgeApplication).gatewayPrefs
        val now = System.currentTimeMillis()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        prefs.resumeAfterUpdate = false
                        prefs.addEvent(now, "Update: could not open the installer screen (${e.javaClass.simpleName})")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.addEvent(now, "Update installed")
            else -> {
                prefs.resumeAfterUpdate = false
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120)
                val label = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled" else "failed (code $status) $why"
                prefs.addEvent(now, "Update $label")
            }
        }
    }
}
