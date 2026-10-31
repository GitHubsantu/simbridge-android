package `in`.imdevops.simbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import `in`.imdevops.simbridge.MainActivity
import `in`.imdevops.simbridge.R
import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.sms.SmsStatus
import `in`.imdevops.simbridge.tunnel.Hostnames
import `in`.imdevops.simbridge.tunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the loopback API (and, if enabled, the tunnel) alive while the app is in the background.
 * A foreground service improves the odds but does not guarantee unlimited runtime: the OS or the
 * phone maker's battery manager can still stop it. Every start/stop/restart is written to the
 * event log shown in Diagnostics.
 */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    private val app get() = application as SimBridgeApplication

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gateway status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that the SMS gateway is running in the background"
                setShowBadge(false)
            },
        )
        app.gatewayActive.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = app.gatewayPrefs
        val now = System.currentTimeMillis()
        when (intent?.action) {
            ACTION_STOP -> {
                stopGateway("Stopped by user")
                return START_NOT_STICKY
            }
            null -> {
                // Android restarted us after killing the process.
                if (!prefs.desiredRunning || !RestartGuard.allow(prefs.restartStamps(), now)) {
                    prefs.addEvent(now, "Automatic restart refused (not wanted, or too many restarts in 10 minutes)")
                    prefs.desiredRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                prefs.recordRestart(now)
                prefs.addEvent(now, "Restarted by Android after the process was killed")
            }
            else -> prefs.addEvent(now, "Started")
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(NotificationText.build(ServerState.Starting, TunnelState.Stopped, app.gatewayPrefs.startTunnelWithGateway, 0, 0)),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
            )
        } catch (e: Exception) {
            prefs.addEvent(now, "Could not enter foreground: ${e.javaClass.simpleName}")
            prefs.desiredRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.desiredRunning = true
        startWork()
        return START_STICKY
    }

    private fun startWork() {
        work?.cancel()
        work = scope.launch {
            // Watch first, so the notification follows every state change from the very start.
            launch { watchState() }
            launch { probeLoop() }
            app.apiServer.start()
            if (app.gatewayPrefs.startTunnelWithGateway) {
                app.tokenVault.load()?.let { app.tunnelManager.start(it) }
            }
        }
    }

    private suspend fun watchState() {
        val ticker = flow { while (true) { emit(Unit); delay(15_000) } }
        var lastSummary = ""
        combine(
            app.apiServer.state, app.tunnelManager.state, app.tunnelManager.lastRemoteProbe,
            app.messageStore.recent, ticker,
        ) { server, tunnel, probe, _, _ -> Triple(server, tunnel, probe) }
            .collect { (server, tunnel, probe) ->
                val fresh = probe?.takeIf { System.currentTimeMillis() - it.checkedAt < 180_000 }?.ok
                val sent = app.messageStore.countWithStatus(setOf(SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.DELIVERY_FAILED))
                val failed = app.messageStore.countWithStatus(setOf(SmsStatus.FAILED))
                val content = NotificationText.build(server, tunnel, app.gatewayPrefs.startTunnelWithGateway, sent, failed, fresh)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
                if (content.summary != lastSummary) {
                    lastSummary = content.summary
                    app.gatewayPrefs.addEvent(System.currentTimeMillis(), "Status: ${content.summary}")
                }
            }
    }

    /** While the tunnel is up, ask the public URL once a minute so the status rests on evidence. */
    private suspend fun probeLoop() {
        delay(10_000)
        while (true) {
            val host = Hostnames.normalize(app.tunnelPrefs.hostname)
            val t = app.tunnelManager.state.value
            if (host != null && app.gatewayPrefs.startTunnelWithGateway &&
                (t is TunnelState.Connected || t is TunnelState.Starting)
            ) {
                app.tunnelManager.probeRemote(host)
            }
            delay(60_000)
        }
    }

    private fun stopGateway(reason: String) {
        scope.launch {
            work?.cancel()
            app.tunnelManager.stop()
            app.apiServer.stop()
            app.gatewayPrefs.desiredRunning = false
            app.gatewayPrefs.addEvent(System.currentTimeMillis(), reason)
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        app.gatewayPrefs.addEvent(System.currentTimeMillis(), "App swiped away from recent apps (service keeps running unless the phone kills it)")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        app.gatewayActive.value = false
        super.onDestroy()
    }

    private fun buildNotification(content: NotificationContent): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_simbridge)
            .setColor(0xFF1E6BFF.toInt())
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.details))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Open", open)
            .addAction(0, "Stop gateway", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "in.imdevops.simbridge.GATEWAY_START"
        const val ACTION_STOP = "in.imdevops.simbridge.GATEWAY_STOP"

        fun startIntent(c: Context) = Intent(c, GatewayService::class.java).setAction(ACTION_START)
        fun stopIntent(c: Context) = Intent(c, GatewayService::class.java).setAction(ACTION_STOP)
    }
}
