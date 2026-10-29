package `in`.imdevops.simbridge

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.imdevops.simbridge.api.ApiServer
import `in`.imdevops.simbridge.api.ServerState
import kotlinx.coroutines.launch

private data class Fact(val label: String, val value: String)

private fun collectFacts(nativeLibDir: String): List<Fact> = listOf(
    Fact("Android release / API", "${Build.VERSION.RELEASE} / ${Build.VERSION.SDK_INT}"),
    Fact("Device", "${Build.MANUFACTURER} ${Build.MODEL}"),
    Fact("This process is 64-bit", Process.is64Bit().toString()),
    Fact("Supported ABIs", Build.SUPPORTED_ABIS.joinToString()),
    Fact("Native library dir", nativeLibDir),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SimBridgeApplication
        val facts = collectFacts(applicationInfo.nativeLibraryDir)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding ->
                        Dashboard(app, facts, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun Dashboard(app: SimBridgeApplication, facts: List<Fact>, modifier: Modifier = Modifier) {
    val state by app.apiServer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var newKey by remember { mutableStateOf<String?>(null) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(app.keyStore.hasKey()) }
    val port = ApiServer.DEFAULT_PORT

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SimBridge Gateway", style = MaterialTheme.typography.headlineMedium)
        Text("Your SIM. Your API. Your Gateway.", style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HTTP server", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val s = state) {
                        ServerState.Stopped -> "Stopped"
                        ServerState.Starting -> "Starting…"
                        is ServerState.Running ->
                            "Running — accepted a test connection on 127.0.0.1:${s.port}. Reachable on this phone only; not remotely."
                        is ServerState.Failed -> "Failed: ${s.reason}"
                    },
                )
                val running = state is ServerState.Running
                val busy = state is ServerState.Starting
                Button(
                    enabled = !busy && (running || hasKey),
                    onClick = {
                        scope.launch { if (running) app.apiServer.stop() else app.apiServer.start(port) }
                    },
                ) { Text(if (running) "Stop server" else "Start server") }
                if (!hasKey) Text("Generate an API key first.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API key", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (hasKey) "A key is set. Only its hash is stored, so it cannot be shown again."
                    else "No key yet.",
                )
                newKey?.let { key ->
                    Text("Copy it now (long-press to select). It will not be shown again:")
                    SelectionContainer { Text(key, style = MaterialTheme.typography.bodyLarge) }
                }
                OutlinedButton(onClick = { if (hasKey) confirmRegenerate = true else {
                    newKey = app.keyStore.createNewKey(); hasKey = true
                } }) { Text(if (hasKey) "Generate new key" else "Generate key") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Test from your PC (PowerShell)", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        "adb forward tcp:$port tcp:$port\n" +
                            "curl.exe -H \"Authorization: Bearer YOUR_API_KEY\" http://127.0.0.1:$port/api/v1/health",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text("Device diagnostics", style = MaterialTheme.typography.titleMedium)
        facts.forEach { fact ->
            Column {
                Text(fact.label, style = MaterialTheme.typography.labelMedium)
                Text(fact.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "SMS sending and the tunnel are not implemented yet.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Replace API key?") },
            text = { Text("The old key stops working immediately. Anything using it must be updated.") },
            confirmButton = {
                TextButton(onClick = {
                    newKey = app.keyStore.createNewKey(); hasKey = true; confirmRegenerate = false
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } },
        )
    }
}
