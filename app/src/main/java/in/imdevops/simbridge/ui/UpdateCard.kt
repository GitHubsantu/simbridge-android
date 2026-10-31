package `in`.imdevops.simbridge.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.tunnel.BinaryVerdict
import `in`.imdevops.simbridge.tunnel.CloudflaredVersions
import `in`.imdevops.simbridge.tunnel.UpdateChecker
import kotlinx.coroutines.launch

@Composable
fun CloudflaredUpdateCard(app: SimBridgeApplication) {
    val scope = rememberCoroutineScope()
    var bundled by remember { mutableStateOf<String?>(null) }
    var bundledNote by remember { mutableStateOf("Reading…") }
    var upstream by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val c = app.tunnelManager.inspect()
        if (c.verdict != BinaryVerdict.OK) {
            bundledNote = "Binary ${c.verdict}"
        } else {
            val p = app.tunnelManager.probeBinary()
            bundled = CloudflaredVersions.extract(p.output)
            bundledNote = bundled ?: (p.error ?: "Version not recognised")
        }
    }

    SurfaceCard {
        SectionTitle("cloudflared version")
        StatusRow("Bundled in this app", bundled ?: bundledNote)
        OutlinedButton(onClick = {
            upstream = "Checking…"
            scope.launch {
                upstream = UpdateChecker.latestRelease().fold(
                    onSuccess = { r ->
                        val newer = bundled?.let { b -> CloudflaredVersions.extract(r.tag)?.let { CloudflaredVersions.isNewer(it, b) } }
                        when (newer) {
                            true -> "Newer upstream release: ${r.tag}. Updating means rebuilding the app (see below)."
                            false -> "Up to date (latest upstream: ${r.tag})."
                            null -> "Latest upstream: ${r.tag}. Could not compare with the bundled version."
                        }
                    },
                    onFailure = { "Could not check: ${it.message ?: it.javaClass.simpleName}" },
                )
            }
        }) { Text("Check for newer release") }
        upstream?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Why the app cannot update it by itself: Android only lets an app run executables that were installed with the APK, " +
                "and that folder is read-only. Downloading a new binary at runtime and running it is blocked on current Android; " +
                "getting around that by lowering the app's target SDK would weaken security.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                "To update: open Cloudflare's release page for the new version, copy the SHA-256 for cloudflared-linux-arm, " +
                    "put the version and hash into tools\\fetch-cloudflared.ps1, run it, then rebuild and reinstall. " +
                    "The script refuses any file whose checksum does not match.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
