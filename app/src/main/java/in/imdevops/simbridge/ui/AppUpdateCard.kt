package `in`.imdevops.simbridge.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import `in`.imdevops.simbridge.SimBridgeApplication
import `in`.imdevops.simbridge.update.AppRelease
import `in`.imdevops.simbridge.update.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data class UpToDate(val tag: String) : UpdatePhase
    data class Available(val release: AppRelease) : UpdatePhase
    data class Downloading(val done: Long, val total: Long) : UpdatePhase
    data class Ready(val release: AppRelease, val file: File) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

/** Manual, user-started update: nothing contacts GitHub until the button is pressed. */
@Composable
fun AppUpdateCard(app: SimBridgeApplication) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { AppUpdater.installedVersionName(context) }
    var phase by remember { mutableStateOf<UpdatePhase>(UpdatePhase.Idle) }
    var note by remember { mutableStateOf<String?>(null) }

    SurfaceCard {
        SectionTitle("App update")
        StatusRow("Installed version", installed)

        when (val p = phase) {
            UpdatePhase.Idle -> Unit
            UpdatePhase.Checking -> Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
            is UpdatePhase.UpToDate -> Text("You have the latest version (${p.tag}).", style = MaterialTheme.typography.bodyMedium)
            is UpdatePhase.Available -> {
                val mb = if (p.release.apkSize > 0) " · %.1f MB".format(p.release.apkSize / 1_000_000.0) else ""
                Text("Version ${p.release.tag} is available$mb", style = MaterialTheme.typography.titleSmall)
                if (p.release.notes.isNotBlank()) {
                    Text(p.release.notes.trim().take(600), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    val r = p.release
                    scope.launch {
                        phase = UpdatePhase.Downloading(0, r.apkSize)
                        AppUpdater.download(context, r) { d, t -> phase = UpdatePhase.Downloading(d, t) }.fold(
                            onSuccess = { f ->
                                phase = if (AppUpdater.signaturesMatch(context, f)) UpdatePhase.Ready(r, f)
                                else UpdatePhase.Failed("The download is signed with a different key than the installed app, so Android would refuse it.")
                            },
                            onFailure = { phase = UpdatePhase.Failed(it.message ?: it.javaClass.simpleName) },
                        )
                    }
                }) { Text("Download and verify") }
            }
            is UpdatePhase.Downloading -> {
                val frac = if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                Text("Downloading… ${(frac * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
            }
            is UpdatePhase.Ready -> {
                Text(
                    "Version ${p.release.tag} downloaded. Checksum and signing key verified.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = {
                    if (!AppUpdater.canInstall(context)) {
                        note = "Allow SimBridge to install apps on the next screen, go back, then tap Install again."
                        context.startActivity(AppUpdater.unknownSourcesIntent(context))
                    } else {
                        // So the gateway comes back by itself after Android replaces the app.
                        app.gatewayPrefs.resumeAfterUpdate = app.gatewayPrefs.desiredRunning
                        scope.launch {
                            AppUpdater.install(context, p.file).fold(
                                onSuccess = { note = "Android will ask you to confirm. The gateway restarts after the update if it was running." },
                                onFailure = {
                                    app.gatewayPrefs.resumeAfterUpdate = false
                                    phase = UpdatePhase.Failed(it.message ?: it.javaClass.simpleName)
                                },
                            )
                        }
                    }
                }) { Text("Install now") }
            }
            is UpdatePhase.Failed -> Text(p.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedButton(
                enabled = phase !is UpdatePhase.Checking && phase !is UpdatePhase.Downloading,
                onClick = {
                    note = null
                    phase = UpdatePhase.Checking
                    scope.launch {
                        AppUpdater.latest().fold(
                            onSuccess = { r ->
                                phase = when (AppUpdater.isNewer(r.tag, installed)) {
                                    true -> UpdatePhase.Available(r)
                                    false -> UpdatePhase.UpToDate(r.tag)
                                    null -> UpdatePhase.Failed("Could not compare version \"${r.tag}\" with \"$installed\".")
                                }
                            },
                            onFailure = { phase = UpdatePhase.Failed("Could not check: ${it.message ?: it.javaClass.simpleName}") },
                        )
                    }
                },
            ) { Text("Check for update") }
        Text(
            "Updates come only from this app's GitHub releases. The file must match the published SHA-256 and be signed with the same key as this app. Your API key, tunnel token and history are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
