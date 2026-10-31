package `in`.imdevops.simbridge.ui

import android.Manifest
import android.app.Activity
import android.view.WindowManager
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.api.ApiServer
import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.sms.SmsRecord
import `in`.imdevops.simbridge.sms.SubscriptionLookup
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- Messages

@Composable
fun MessagesScreen(app: SimBridgeApplication, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val records by app.messageStore.recent.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<SmsRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    ScrollColumn(modifier) {
        ScreenHeader("Messages", "Newest 50 · number, status and length only, never the text · kept 30 days")
        if (records.isEmpty()) Text("No messages yet. Send one from the API tab or through the API.")
        records.forEach { r ->
            Card(Modifier.fillMaxWidth().clickable { selected = r }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.to, style = MaterialTheme.typography.titleSmall)
                        StatusBadge(r.status)
                    }
                    Text(formatTime(r.createdAt) + (r.errorCode?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (records.isNotEmpty()) {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear history…") }
        }
    }

    selected?.let { r ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Message ${r.id.take(8)}…") },
            text = {
                SelectionContainer {
                    Text(
                        "id: ${r.id}\nto: ${r.to}\nstatus: ${r.status}\nparts: ${r.partsSent}/${r.partsTotal} sent, ${r.partsDelivered} delivered\n" +
                            "length: ${r.bodyChars} characters\nSIM subscription: ${r.subscriptionId}\ncreated: ${formatTime(r.createdAt)}\n" +
                            "updated: ${formatTime(r.updatedAt)}" +
                            (r.errorCode?.let { "\nerror: $it — ${r.errorMessage}" } ?: ""),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes every stored message record. Sending limits are counted from this history, so they restart from zero too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { app.messageStore.purgeOlderThan(Long.MAX_VALUE) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun formatTime(ms: Long): String = DateFormat.getDateTimeInstance().format(Date(ms))

// ---------------------------------------------------------------- API

@Composable
fun ApiScreen(app: SimBridgeApplication, modifier: Modifier = Modifier) {
    ScrollColumn(modifier) {
        ScreenHeader("API", "Key, permissions, SIM and sending limits")
        PermissionsCard(app)
        ApiKeyCard(app)
        SimCard(app)
        SecurityCard(app)
        SmsCard(app)
    }
}

@Composable
private fun PermissionsCard(app: SimBridgeApplication) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val wanted = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val states = remember(tick) { wanted.associateWith { context.isGranted(it) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleMedium)
            StatusRow("Send SMS (sends your messages)", if (states[Manifest.permission.SEND_SMS] == true) "Granted" else "Missing")
            StatusRow("Phone state (lists your SIMs)", if (states[Manifest.permission.READ_PHONE_STATE] == true) "Granted" else "Missing")
            if (Build.VERSION.SDK_INT >= 33) {
                StatusRow("Notifications (gateway notice)", if (states[Manifest.permission.POST_NOTIFICATIONS] == true) "Granted" else "Missing")
            }
            if (states.values.any { !it }) {
                Button(onClick = { launcher.launch(wanted.toTypedArray()) }) { Text("Grant permissions") }
            }
        }
    }
}

@Composable
private fun ApiKeyCard(app: SimBridgeApplication) {
    var newKey by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(app.keyStore.hasKey()) }
    var confirm by remember { mutableStateOf(false) }
    val port = ApiServer.DEFAULT_PORT
    val server by app.apiServer.state.collectAsState()
    // While a freshly generated key is on screen, block screenshots, screen recording and the recents thumbnail.
    val activity = LocalContext.current as? Activity
    DisposableEffect(newKey != null) {
        if (newKey != null) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("API key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Server: " + when (val s = server) {
                    is ServerState.Running -> "running on 127.0.0.1:${s.port}"
                    ServerState.Starting -> "starting"
                    ServerState.Stopped -> "stopped (start the gateway on Home)"
                    is ServerState.Failed -> "failed — ${s.reason}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(if (hasKey) "A key is set. Only its hash is stored, so it cannot be shown again." else "No key yet.")
            newKey?.let { key ->
                Text("Copy it now (long-press to select). It will not be shown again. Screenshots are blocked while it is visible.")
                SelectionContainer { Text(key, style = MaterialTheme.typography.bodyLarge) }
            }
            OutlinedButton(onClick = {
                if (hasKey) confirm = true else { newKey = app.keyStore.createNewKey(); hasKey = true }
            }) { Text(if (hasKey) "Generate new key" else "Generate key") }
            SelectionContainer {
                Text(
                    "Local test (PowerShell):\nadb forward tcp:$port tcp:$port\ncurl.exe -H \"Authorization: Bearer YOUR_API_KEY\" http://127.0.0.1:$port/api/v1/health",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Replace API key?") },
            text = { Text("The old key stops working immediately. Anything using it must be updated.") },
            confirmButton = {
                TextButton(onClick = { newKey = app.keyStore.createNewKey(); hasKey = true; confirm = false }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SimCard(app: SimBridgeApplication) {
    var tick by remember { mutableStateOf(0) }
    val lookup = remember(tick) { app.subscriptionProvider.lookup() }
    var preferred by remember { mutableStateOf(app.settingsStore.load().preferredSubscriptionId) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SIM for API messages", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used when a request does not name a SIM. A request can still pass subscription_id to override this.",
                style = MaterialTheme.typography.bodySmall,
            )
            when (lookup) {
                SubscriptionLookup.PermissionMissing -> Text("Grant the phone-state permission to list your SIMs.")
                is SubscriptionLookup.Available -> {
                    SimRow("Automatic (Android's default SMS SIM)", preferred == null) {
                        preferred = null; app.settingsStore.setPreferredSim(null)
                    }
                    lookup.sims.forEach { sim ->
                        SimRow("Slot ${sim.slotIndex + 1}: ${sim.displayName} (id ${sim.subscriptionId})", preferred == sim.subscriptionId) {
                            preferred = sim.subscriptionId; app.settingsStore.setPreferredSim(sim.subscriptionId)
                        }
                    }
                    if (lookup.sims.isEmpty()) Text("No active SIM found.")
                }
            }
            OutlinedButton(onClick = { tick++ }) { Text("Refresh SIMs") }
        }
    }
}

@Composable
private fun SimRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- Diagnostics

@Composable
fun DiagnosticsScreen(app: SimBridgeApplication, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val active by app.gatewayActive.collectAsState()
    val tunnelLogs by app.tunnelManager.logs.collectAsState()
    var autostart by remember { mutableStateOf(app.gatewayPrefs.autostartOnBoot) }
    var tunnelWithGateway by remember { mutableStateOf(app.gatewayPrefs.startTunnelWithGateway) }
    var tick by remember { mutableStateOf(0) }
    val events = remember(tick, active) { app.gatewayPrefs.events().reversed() }
    val batteryOk = remember(tick) { context.ignoresBatteryOptimizations() }

    ScrollColumn(modifier) {
        ScreenHeader("Diagnostics", "Background running, events and device facts")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Background running", style = MaterialTheme.typography.titleMedium)
                StatusRow("Gateway service alive", if (active) "Yes" else "No")
                StatusRow("User wants it running", if (app.gatewayPrefs.desiredRunning) "Yes" else "No")
                StatusRow("Battery optimization", if (batteryOk) "Exempt" else "Restricted")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = tunnelWithGateway, onCheckedChange = { tunnelWithGateway = it; app.gatewayPrefs.startTunnelWithGateway = it })
                    Text("  Start the tunnel with the gateway")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autostart, onCheckedChange = { autostart = it; app.gatewayPrefs.autostartOnBoot = it })
                    Text("  Start after reboot / app update")
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    tick++
                }) { Text("Open battery optimization settings") }
                Text(
                    "A foreground service raises the chance the gateway keeps running but cannot guarantee it. On Xiaomi/Poco phones also set the app's Battery saver to “No restrictions”, enable Autostart for it, and lock it in the recent-apps list. Android Go may still close it when memory is low.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Service events (newest first)", style = MaterialTheme.typography.titleMedium)
                if (events.isEmpty()) Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Column {
                        events.take(15).forEach { e ->
                            val ms = e.substringBefore('|').toLongOrNull()
                            Text((ms?.let { formatTime(it) } ?: "?") + " — " + e.substringAfter('|'), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device", style = MaterialTheme.typography.titleMedium)
                StatusRow("Android / API", "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}")
                StatusRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                StatusRow("Process 64-bit", Process.is64Bit().toString())
                StatusRow("ABIs", Build.SUPPORTED_ABIS.joinToString())
            }
        }

        if (tunnelLogs.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("cloudflared log (secrets removed)", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(tunnelLogs.takeLast(20).joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text("SimBridge Gateway — SMS only. No RCS, no root, no Termux.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Third-party software: cloudflared (Cloudflare), Ktor (JetBrains), AndroidX/Jetpack Compose/Room (Google). " +
                        "Tab icons are from Google's Material Icons (Apache-2.0). Each is distributed under its own licence (believed Apache-2.0 — confirm against each project's LICENSE before redistributing an APK). " +
                        "No code was copied from SMS-Gateway-Free (MIT) or cloudflared-android (no licence file).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Sending SMS requires recipient consent and must follow your carrier's terms and local law.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
