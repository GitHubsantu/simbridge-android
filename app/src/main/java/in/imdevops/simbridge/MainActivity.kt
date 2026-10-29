package `in`.imdevops.simbridge

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One labelled diagnostic fact shown on the Milestone 1 screen. */
private data class Fact(val label: String, val value: String)

/** Reads device facts needed for the Cloudflare Tunnel feasibility decision. */
private fun collectFacts(nativeLibDir: String): List<Fact> = listOf(
    Fact("Android release", Build.VERSION.RELEASE ?: "unknown"),
    Fact("API level (SDK_INT)", Build.VERSION.SDK_INT.toString()),
    Fact("Device", "${Build.MANUFACTURER} ${Build.MODEL}"),
    Fact("This process is 64-bit", Process.is64Bit().toString()),
    Fact("Supported ABIs", Build.SUPPORTED_ABIS.joinToString()),
    Fact("Supported 32-bit ABIs", Build.SUPPORTED_32_BIT_ABIS.joinToString().ifEmpty { "none" }),
    Fact("Supported 64-bit ABIs", Build.SUPPORTED_64_BIT_ABIS.joinToString().ifEmpty { "none" }),
    // The last path segment (arm / arm64 / x86 ...) is the ABI directory Android chose for this install.
    Fact("Native library dir", nativeLibDir),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val facts = collectFacts(applicationInfo.nativeLibraryDir)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold { innerPadding ->
                        DiagnosticsScreen(facts, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(facts: List<Fact>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SimBridge Gateway", style = MaterialTheme.typography.headlineMedium)
        Text("Your SIM. Your API. Your Gateway.", style = MaterialTheme.typography.bodyMedium)
        Text("Milestone 1 — device diagnostics", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        facts.forEach { fact ->
            Card(modifier = Modifier.padding(0.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(fact.label, style = MaterialTheme.typography.labelMedium)
                    Text(fact.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            "Server, SMS and tunnel are not implemented yet; nothing here claims they work.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}