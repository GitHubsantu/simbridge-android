package `in`.imdevops.simbridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleOwner

private fun Context.launch(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: Exception) {
    false
}

/** Opens the system dialog "Let this app always run in the background?". Falls back to the settings list. */
internal fun Context.requestBackgroundExemption() {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
    if (!launch(direct)) launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

internal fun Context.openAppSettings() {
    launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

/** Best effort for Xiaomi/Poco; if that screen is not available, open the app's settings page instead. */
internal fun Context.openAutostartSettings() {
    val miui = Intent().setComponent(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    )
    if (!launch(miui)) openAppSettings()
}

@Composable
fun KeepAliveCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    // Re-check when the user comes back from the system screen.
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    val exempt = remember(tick) { context.ignoresBatteryOptimizations() }
    SurfaceCard {
        SectionTitle("Keep it running in the background")
        StatusRow("Battery optimization", if (exempt) "Unrestricted" else "Restricted")
        Text(
            "Phones often stop background apps to save battery. Allow SimBridge to run in the background and enable Autostart. " +
                "This improves reliability but cannot guarantee it: Android Go may still close apps when memory is low.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !exempt, onClick = { context.requestBackgroundExemption() }) { Text("Allow background") }
            OutlinedButton(onClick = { context.openAutostartSettings() }) { Text("Autostart") }
        }
        OutlinedButton(onClick = { context.openAppSettings() }) { Text("App settings") }
    }
}
