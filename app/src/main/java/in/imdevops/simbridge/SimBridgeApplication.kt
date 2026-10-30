package `in`.imdevops.simbridge

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import `in`.imdevops.simbridge.api.ApiContext
import `in`.imdevops.simbridge.api.ApiServer
import `in`.imdevops.simbridge.api.SendCommand
import `in`.imdevops.simbridge.security.ApiKeyStore
import `in`.imdevops.simbridge.sms.AndroidSmsSender
import `in`.imdevops.simbridge.sms.AndroidSubscriptionProvider
import `in`.imdevops.simbridge.data.AppDatabase
import `in`.imdevops.simbridge.data.RoomIdempotencyStore
import `in`.imdevops.simbridge.data.RoomMessageStore
import `in`.imdevops.simbridge.sms.MessageStore
import `in`.imdevops.simbridge.sms.SettingsStore
import `in`.imdevops.simbridge.api.TunnelJson
import `in`.imdevops.simbridge.tunnel.CloudflaredTunnelManager
import `in`.imdevops.simbridge.tunnel.TokenVault
import `in`.imdevops.simbridge.tunnel.TunnelManager
import `in`.imdevops.simbridge.tunnel.TunnelPrefs
import `in`.imdevops.simbridge.sms.MessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Owns the long-lived objects. Moves behind a foreground service in Milestone 6. */
class SimBridgeApplication : Application() {
    lateinit var keyStore: ApiKeyStore
        private set
    lateinit var apiServer: ApiServer
        private set
    /** Process-wide scope for short background work such as saving SMS callbacks. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var messageStore: MessageStore
        private set
    lateinit var subscriptionProvider: AndroidSubscriptionProvider
        private set
    lateinit var messageService: MessageService
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var tunnelManager: TunnelManager
        private set
    lateinit var tokenVault: TokenVault
        private set
    lateinit var tunnelPrefs: TunnelPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        keyStore = ApiKeyStore(this)
        val database = AppDatabase.create(this)
        messageStore = RoomMessageStore(database)
        settingsStore = SettingsStore(this)
        tunnelManager = CloudflaredTunnelManager(this, appScope)
        tokenVault = TokenVault(this)
        tunnelPrefs = TunnelPrefs(this)
        // Keep recipient numbers only for a limited time.
        appScope.launch {
            messageStore.purgeOlderThan(System.currentTimeMillis() - RETENTION_MILLIS)
        }
        subscriptionProvider = AndroidSubscriptionProvider(this)
        val smsGranted = { checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED }
        messageService = MessageService(
            sender = AndroidSmsSender(this),
            subscriptions = subscriptionProvider,
            store = messageStore,
            idempotency = RoomIdempotencyStore(database),
            smsPermissionGranted = smsGranted,
            settings = settingsStore::load,
        )
        apiServer = ApiServer(
            ApiContext(
                versionName = readVersionName(),
                sdkInt = Build.VERSION.SDK_INT,
                isSendSmsGranted = smsGranted,
                isAuthorized = keyStore::verify,
                getMessage = { id -> messageStore.get(id) },
                listMessages = { limit, offset -> messageStore.list(limit, offset) },
                sendMessage = { c: SendCommand ->
                    messageService.send(c.to, c.body, c.subscriptionId, c.requestDelivery, c.idempotencyKey)
                },
                limits = settingsStore::load,
                tunnelStatus = {
                    TunnelJson.build(
                        tunnelManager.state.value, tunnelManager.inspect(), tunnelManager.lastRemoteProbe.value,
                    )
                },
            ),
        )
    }

    private companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    private fun readVersionName(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return info.versionName ?: "unknown"
    }
}
