package `in`.imdevops.simbridge

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import `in`.imdevops.simbridge.api.ApiContext
import `in`.imdevops.simbridge.api.ApiServer
import `in`.imdevops.simbridge.security.ApiKeyStore

/** Owns the long-lived objects. Moves behind a foreground service in Milestone 6. */
class SimBridgeApplication : Application() {
    lateinit var keyStore: ApiKeyStore
        private set
    lateinit var apiServer: ApiServer
        private set

    override fun onCreate() {
        super.onCreate()
        keyStore = ApiKeyStore(this)
        apiServer = ApiServer(
            ApiContext(
                versionName = readVersionName(),
                sdkInt = Build.VERSION.SDK_INT,
                isSendSmsGranted = {
                    checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                },
                isAuthorized = keyStore::verify,
            ),
        )
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
