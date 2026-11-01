package `in`.imdevops.simbridge.ui

import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.api.ApiServer
import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.sms.ApiSettings
import `in`.imdevops.simbridge.sms.MessageService
import `in`.imdevops.simbridge.sms.SendOutcome
import `in`.imdevops.simbridge.sms.SubscriptionLookup
import `in`.imdevops.simbridge.tunnel.BinaryVerdict
import `in`.imdevops.simbridge.tunnel.Hostnames
import `in`.imdevops.simbridge.tunnel.TokenFormat
import `in`.imdevops.simbridge.tunnel.TunnelState
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import `in`.imdevops.simbridge.service.GatewayService
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SmsCard(app: SimBridgeApplication) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val permissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val smsGranted = remember(tick) {
        app.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val lookup = remember(tick) { app.subscriptionProvider.lookup() }
    var selectedSim by remember { mutableStateOf<Int?>(null) }
    var number by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("SimBridge test message") }
    var confirm by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val records by app.messageStore.recent.collectAsState(initial = emptyList())

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SMS engine (manual test)", style = MaterialTheme.typography.titleMedium)
            Text("SMS permission: ${if (smsGranted) "granted" else "not granted"}")
            if (!smsGranted || lookup is SubscriptionLookup.PermissionMissing) {
                Button(onClick = { launcher.launch(permissions) }) { Text("Grant SMS and SIM permissions") }
                Text(
                    "SEND_SMS sends the message. READ_PHONE_STATE only lists your SIMs so you can choose one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (lookup) {
                SubscriptionLookup.PermissionMissing -> Text("SIMs: permission needed to list them.")
                is SubscriptionLookup.Available -> {
                    if (lookup.sims.isEmpty()) Text("SIMs: none active.")
                    lookup.sims.forEach { sim ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedSim == sim.subscriptionId,
                                onClick = { selectedSim = sim.subscriptionId },
                            )
                            Text("Slot ${sim.slotIndex + 1}: ${sim.displayName} (id ${sim.subscriptionId})")
                        }
                    }
                    if (lookup.sims.size > 1 && selectedSim == null) {
                        Text("Two SIMs active: pick one.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(
                value = number, onValueChange = { number = it }, singleLine = true,
                label = { Text("To (e.g. +91XXXXXXXXXX)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("Message (${text.length}/${app.settingsStore.load().maxMessageChars})") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { confirm = true }) { Text("Send real SMS…") }
            feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Recent messages", style = MaterialTheme.typography.labelLarge)
            if (records.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
            records.take(8).forEach { r ->
                Text(
                    "${r.status} · ${r.to} · ${r.partsSent}/${r.partsTotal} sent" +
                        (r.errorCode?.let { " · $it" } ?: "") + "\n${r.id.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "SENT means Android accepted it for transmission; DELIVERED needs a carrier receipt. " +
                    "History is stored on this phone for 30 days (numbers and status only, never the text).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Send a real SMS?") },
            text = { Text("This sends an actual message to $number from your SIM. Carrier charges may apply.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val to = number
                    val body = text
                    val sim = selectedSim
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { app.messageService.send(to, body, sim) }
                        feedback = when (outcome) {
                            is SendOutcome.Created -> "Submitted: ${outcome.record.status} (id ${outcome.record.id.take(8)}…)"
                            is SendOutcome.Replayed -> "Duplicate request ignored (id ${outcome.record.id.take(8)}…)"
                            is SendOutcome.Rejected -> "Rejected: ${outcome.code} — ${outcome.message}"
                        }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SecurityCard(app: SimBridgeApplication) {
    val current = remember { app.settingsStore.load() }
    var perMinute by remember { mutableStateOf(current.perMinute.toString()) }
    var perDay by remember { mutableStateOf(current.perDay.toString()) }
    var maxChars by remember { mutableStateOf(current.maxMessageChars.toString()) }
    var prefixes by remember { mutableStateOf(current.allowedPrefixes.joinToString(", ")) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sending limits", style = MaterialTheme.typography.titleMedium)
            Text(
                "Apply to every send, from this app and from the API. Counted from stored history, so a restart does not reset them.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = perMinute, onValueChange = { perMinute = it.filter(Char::isDigit).take(3) }, singleLine = true,
                label = { Text("Messages per minute (1–60)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = perDay, onValueChange = { perDay = it.filter(Char::isDigit).take(4) }, singleLine = true,
                label = { Text("Messages per day (1–5000)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxChars, onValueChange = { maxChars = it.filter(Char::isDigit).take(4) }, singleLine = true,
                label = { Text("Max message length (1–1000)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefixes, onValueChange = { prefixes = it.take(80) }, singleLine = true,
                label = { Text("Allowed country prefixes (e.g. +91). Empty = any") }, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Restricting destinations to your own country blocks most abuse and premium-rate fraud. Some premium/satellite ranges are always blocked.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                val parsed = `in`.imdevops.simbridge.sms.DestinationPolicy.parse(prefixes)
                if (parsed is `in`.imdevops.simbridge.sms.DestinationPolicy.Parsed.Bad) {
                    status = parsed.message
                    return@OutlinedButton
                }
                val candidate = app.settingsStore.load().copy(
                    allowedPrefixes = (parsed as `in`.imdevops.simbridge.sms.DestinationPolicy.Parsed.Ok).prefixes,
                    perMinute = perMinute.toIntOrNull() ?: 0,
                    perDay = perDay.toIntOrNull() ?: 0,
                    maxMessageChars = maxChars.toIntOrNull() ?: 0,
                )
                status = app.settingsStore.save(candidate) ?: "Saved."
            }) { Text("Save limits") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun TunnelCard(app: SimBridgeApplication) {
    val scope = rememberCoroutineScope()
    val manager = app.tunnelManager
    val state by manager.state.collectAsState()
    val logs by manager.logs.collectAsState()
    val probe by manager.lastRemoteProbe.collectAsState()
    var hostname by remember { mutableStateOf(app.tunnelPrefs.hostname) }
    var tokenInput by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(app.tokenVault.hasToken()) }
    var note by remember { mutableStateOf<String?>(null) }
    var binaryReport by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val gatewayActive by app.gatewayActive.collectAsState()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startGatewayWithTunnel(context, app)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloudflare Tunnel (proof of concept)", style = MaterialTheme.typography.titleMedium)

            OutlinedButton(onClick = {
                scope.launch {
                    val c = manager.inspect()
                    val head = "Binary: ${c.verdict}" +
                        (c.elf?.let { " (${it.architecture}, ${if (it.is64Bit) "64" else "32"}-bit)" } ?: "") +
                        " · this phone: ${c.supportedAbis.joinToString()} · executable flag: ${c.executable}"
                    binaryReport = head
                    if (c.verdict == BinaryVerdict.OK) {
                        val p = withContext(Dispatchers.IO) { manager.probeBinary() }
                        binaryReport = head + "\n" + (p.error?.let { "Run failed: $it" }
                            ?: "Ran OK (exit ${p.exitCode}): ${p.output}")
                    }
                }
            }) { Text("Check cloudflared binary") }
            binaryReport?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }

            OutlinedTextField(
                value = hostname, onValueChange = { hostname = it }, singleLine = true,
                label = { Text("Public hostname (sms-api.YOURDOMAIN.com)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it.trim() }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (hasToken) "Replace tunnel token" else "Tunnel token") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val host = Hostnames.normalize(hostname)
                when {
                    hostname.isNotBlank() && host == null -> note = "That is not a valid hostname."
                    tokenInput.isNotEmpty() && !TokenFormat.looksValid(tokenInput) ->
                        note = "That does not look like a tunnel token."
                    else -> {
                        if (host != null) { app.tunnelPrefs.hostname = host; hostname = host }
                        if (tokenInput.isNotEmpty()) {
                            if (app.tokenVault.save(tokenInput)) { hasToken = true; tokenInput = "" }
                            else note = "Android Keystore refused to store the token."
                        }
                        if (note?.startsWith("Android") != true) note = "Saved. The token is stored encrypted and is never shown again."
                    }
                }
            }) { Text("Save hostname / token") }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text(
                when (val s = state) {
                    TunnelState.Stopped -> "Tunnel: stopped"
                    TunnelState.Starting -> "Tunnel: starting…"
                    is TunnelState.Connected ->
                        "Tunnel: cloudflared reports ${s.connections} connection(s) to Cloudflare. " +
                            "Use “Test public URL” to prove it reaches SimBridge."
                    is TunnelState.Failed -> "Tunnel failed: ${s.reason}"
                },
            )
            val running = state is TunnelState.Connected || state is TunnelState.Starting
            Button(
                enabled = running || hasToken,
                onClick = {
                    when {
                        running -> scope.launch { manager.stop() }
                        // Starting the tunnel must go through the foreground service, which owns the persistent notification.
                        !gatewayActive ->
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startGatewayWithTunnel(context, app)
                        else -> scope.launch {
                            app.tokenVault.load()?.let { manager.start(it) }
                                ?: run { note = "Stored token could not be read; paste it again." }
                        }
                    }
                },
            ) { Text(if (running) "Stop tunnel" else "Start tunnel") }

            OutlinedButton(
                enabled = Hostnames.normalize(hostname) != null,
                onClick = { scope.launch { manager.probeRemote(Hostnames.normalize(hostname)!!) } },
            ) { Text("Test public URL") }
            probe?.let {
                Text(
                    (if (it.ok) "Public URL: OK. " else "Public URL: not working. ") + it.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (logs.isNotEmpty()) {
                Text("cloudflared log (last lines)", style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(logs.takeLast(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Starting the tunnel also starts the gateway service, which keeps it running in the background and shows a persistent status notification.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun startGatewayWithTunnel(context: Context, app: SimBridgeApplication) {
    app.gatewayPrefs.startTunnelWithGateway = true
    ContextCompat.startForegroundService(context, GatewayService.startIntent(context))
}
