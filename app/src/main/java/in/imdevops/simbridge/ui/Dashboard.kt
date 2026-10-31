package `in`.imdevops.simbridge.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.service.GatewayService
import `in`.imdevops.simbridge.sms.SmsStatus
import `in`.imdevops.simbridge.sms.SubscriptionLookup
import `in`.imdevops.simbridge.tunnel.Hostnames
import `in`.imdevops.simbridge.tunnel.TunnelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun Context.isGranted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.ignoresBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

@Composable
fun DashboardScreen(app: SimBridgeApplication, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by app.apiServer.state.collectAsState()
    val tunnel by app.tunnelManager.state.collectAsState()
    val probe by app.tunnelManager.lastRemoteProbe.collectAsState()
    val active by app.gatewayActive.collectAsState()
    val records by app.messageStore.recent.collectAsState(initial = emptyList())
    var tick by remember { mutableStateOf(0) }
    var sent by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var delivered by remember { mutableStateOf(0) }
    val nowMs by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }

    LaunchedEffect(records) {
        sent = app.messageStore.countWithStatus(setOf(SmsStatus.SENT, SmsStatus.DELIVERED, SmsStatus.DELIVERY_FAILED))
        failed = app.messageStore.countWithStatus(setOf(SmsStatus.FAILED))
        delivered = app.messageStore.countWithStatus(setOf(SmsStatus.DELIVERED))
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ContextCompat.startForegroundService(context, GatewayService.startIntent(context))
        tick++
    }

    val smsOk = context.isGranted(Manifest.permission.SEND_SMS)
    val hasKey = remember(tick) { app.keyStore.hasKey() }
    val hostname = app.tunnelPrefs.hostname
    val running = server is ServerState.Running
    // "Verified" only counts a RECENT successful public-URL probe while the tunnel is not stopped or failed.
    val remoteVerified = probe?.let { it.ok && nowMs - it.checkedAt < 180_000 } == true &&
        tunnel !is TunnelState.Stopped && tunnel !is TunnelState.Failed
    val tunnelUp = tunnel is TunnelState.Connected || remoteVerified

    val (heroHealth, heroLabel, heroDetail) = when {
        server is ServerState.Failed -> Triple(Health.BAD, "Server problem", (server as ServerState.Failed).reason)
        tunnel is TunnelState.Failed -> Triple(Health.BAD, "Tunnel problem", (tunnel as TunnelState.Failed).reason)
        running && tunnelUp && remoteVerified -> Triple(Health.OK, "Reachable remotely", "Your public URL answered a moment ago.")
        running && tunnelUp -> Triple(Health.WARN, "Tunnel connected", "Remote access not verified yet. Tap “Test public URL”.")
        running -> Triple(Health.WARN, "Local only", "The API works on this phone but is not reachable from the internet.")
        server is ServerState.Starting -> Triple(Health.WARN, "Starting…", "Please wait a few seconds.")
        else -> Triple(Health.IDLE, "Stopped", "Start the gateway to accept API requests.")
    }

    ScrollColumn(modifier) {
        // ---- hero
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(Color(0xFF1E6BFF), Color(0xFF0A2E8F))),
                    RoundedCornerShape(24.dp),
                )
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SimBridge", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Your SIM. Your API. Your Gateway.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                Row(
                    Modifier.background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(when (heroHealth) { Health.OK -> Color(0xFF7FE3A0); Health.WARN -> Color(0xFFFFD37A); Health.BAD -> Color(0xFFFFA39A); Health.IDLE -> Color(0xFFC9CFE4) })
                    Text(heroLabel, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text(heroDetail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0A2E8F)),
                        onClick = {
                            if (active) {
                                context.startService(GatewayService.stopIntent(context))
                            } else if (Build.VERSION.SDK_INT >= 33 && !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                ContextCompat.startForegroundService(context, GatewayService.startIntent(context))
                            }
                        },
                    ) { Text(if (active) "Stop gateway" else "Start gateway") }
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        enabled = running && tunnelUp && Hostnames.normalize(hostname) != null,
                        onClick = { scope.launch { app.tunnelManager.probeRemote(Hostnames.normalize(hostname)!!) } },
                    ) { Text("Test public URL") }
                }
            }
        }

        // ---- three status chips
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusChip(
                "API", when (server) { is ServerState.Running -> "Running"; ServerState.Starting -> "Starting"; ServerState.Stopped -> "Stopped"; is ServerState.Failed -> "Failed" },
                when (server) { is ServerState.Running -> Health.OK; ServerState.Starting -> Health.WARN; ServerState.Stopped -> Health.IDLE; is ServerState.Failed -> Health.BAD },
                Modifier.weight(1f),
            )
            StatusChip(
                "Tunnel",
                if (remoteVerified && tunnel is TunnelState.Starting) "Up (URL ok)"
                else when (val t = tunnel) { is TunnelState.Connected -> "${t.connections} links"; TunnelState.Starting -> "Connecting"; TunnelState.Stopped -> "Off"; is TunnelState.Failed -> "Failed" },
                if (remoteVerified && tunnel is TunnelState.Starting) Health.OK
                else when (tunnel) { is TunnelState.Connected -> Health.OK; TunnelState.Starting -> Health.WARN; TunnelState.Stopped -> Health.IDLE; is TunnelState.Failed -> Health.BAD },
                Modifier.weight(1f),
            )
            StatusChip("SMS", if (smsOk) "Allowed" else "Blocked", if (smsOk) Health.OK else Health.BAD, Modifier.weight(1f))
        }

        // ---- counters
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Sent", sent.toString(), Modifier.weight(1f))
            StatTile("Delivered", delivered.toString(), Modifier.weight(1f))
            StatTile("Failed", failed.toString(), Modifier.weight(1f))
        }
        Text(
            "“Sent” means Android's radio accepted the message. “Delivered” needs a receipt, which many carriers never send.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!context.ignoresBatteryOptimizations()) KeepAliveCard()

        SurfaceCard {
            StatusRow("Selected SIM", selectedSimLabel(app))
            StatusRow("Public URL", when { remoteVerified -> "Working"; probe == null -> "Not tested"; probe?.ok == true -> "Worked earlier"; else -> "Not working" })
            StatusRow("API key", if (hasKey) "Set" else "Not created")
        }

        val hints = buildList {
            if (!hasKey) add("Create an API key on the API tab.")
            if (app.settingsStore.load().allowedPrefixes.isEmpty()) add("No destination restriction set. On the API tab, limit sending to your own country prefix (for example +91).")
            if (!smsOk) add("Grant the SMS permission on the API tab, otherwise sending is refused.")
            probe?.takeIf { !it.ok }?.let { add("Public URL: ${it.detail}") }
        }
        if (hints.isNotEmpty()) {
            SurfaceCard {
                SectionTitle("Things to check")
                hints.forEach { Text("⚠  $it", style = MaterialTheme.typography.bodySmall) }
            }
        }

        SurfaceCard {
            SectionTitle("Recent activity")
            if (records.isEmpty()) Text("No messages yet.", style = MaterialTheme.typography.bodySmall)
            records.take(5).forEach {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(it.to, style = MaterialTheme.typography.bodyMedium)
                    StatusBadge(it.status)
                }
            }
        }
    }
}

internal fun selectedSimLabel(app: SimBridgeApplication): String {
    val preferred = app.settingsStore.load().preferredSubscriptionId
    return when (val l = app.subscriptionProvider.lookup()) {
        SubscriptionLookup.PermissionMissing -> "Unknown (permission)"
        is SubscriptionLookup.Available -> {
            val sim = l.sims.firstOrNull { it.subscriptionId == preferred }
            when {
                l.sims.isEmpty() -> "No active SIM"
                sim != null -> "${sim.displayName} (slot ${sim.slotIndex + 1})"
                else -> "Automatic"
            }
        }
    }
}
