package `in`.imdevops.simbridge.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Runs an upstream cloudflared build as a child process. The executable is packaged as
 * libcloudflared.so and executed from the app's native-library directory (the one place Android
 * still allows executing app-supplied files). Nothing here auto-restarts: a failed tunnel stays
 * failed until the user starts it again, so a bad token cannot cause a restart loop.
 */
class CloudflaredTunnelManager(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : TunnelManager {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var process: Process? = null
    @Volatile private var stopping = false

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    override val state: StateFlow<TunnelState> = _state.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _probe = MutableStateFlow<RemoteProbeResult?>(null)
    override val lastRemoteProbe: StateFlow<RemoteProbeResult?> = _probe.asStateFlow()

    private fun binary() = File(appContext.applicationInfo.nativeLibraryDir, BINARY_NAME)

    override fun inspect(): TunnelCompatibility {
        val file = binary()
        val present = file.isFile
        val elf = if (present) {
            try {
                file.inputStream().use { s -> ByteArray(20).also { s.read(it) } }.let { ElfInfo.parse(it) }
            } catch (_: IOException) {
                null
            }
        } else null
        val abis = Build.SUPPORTED_ABIS.toList()
        return TunnelCompatibility(
            binaryPresent = present,
            binarySizeBytes = if (present) file.length() else 0,
            elf = elf,
            executable = present && file.canExecute(),
            processIs64Bit = android.os.Process.is64Bit(),
            supportedAbis = abis,
            verdict = BinaryCheck.verdict(present, elf, abis),
        )
    }

    override suspend fun probeBinary(): ProbeResult = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(binary().absolutePath, "--version").redirectErrorStream(true).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@withContext ProbeResult(null, "", "Timed out after 10 s")
            }
            ProbeResult(p.exitValue(), p.inputStream.bufferedReader().readText().take(400).trim(), null)
        } catch (e: IOException) {
            // Typical messages: "error=2, No such file or directory", "error=13, Permission denied".
            ProbeResult(null, "", e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun start(token: String) { lock.withLock {
        if (process?.isAlive == true) return@withLock
        val compat = inspect()
        if (compat.verdict != BinaryVerdict.OK) {
            _state.value = TunnelState.Failed("Tunnel binary unusable: ${compat.verdict}.", retryable = false)
            return@withLock
        }
        withContext(Dispatchers.IO) {
            stopping = false
            _logs.value = emptyList()
            _state.value = TunnelState.Starting
            val tracker = TunnelTracker()
            try {
                val dir = File(appContext.filesDir, "cloudflared").apply { mkdirs() }
                val resolv = File(dir, "resolv.conf").apply { writeText(DnsConfig.resolvConf(systemDns())) }
                val pb = ProcessBuilder(
                    binary().absolutePath, "tunnel", "--no-autoupdate", "--edge-ip-version", "4",
                    "--protocol", "http2", "run",
                )
                pb.redirectErrorStream(true)
                // Only used when the binary has been patched to read its resolv.conf from stdin; harmless otherwise.
                pb.redirectInput(resolv)
                pb.environment().apply {
                    // The token travels in the environment, not on the command line.
                    put("TUNNEL_TOKEN", token)
                    put("HOME", dir.absolutePath)
                    put("TMPDIR", appContext.cacheDir.absolutePath)
                    put("GODEBUG", "netdns=go")
                    put("SSL_CERT_DIR", "/system/etc/security/cacerts")
                }
                val p = pb.start()
                process = p
                scope.launch(Dispatchers.IO) { pump(p, tracker, listOf(token)) }
            } catch (e: IOException) {
                _state.value = TunnelState.Failed("Could not start cloudflared: ${e.message}", retryable = false)
            }
        }
    } }

    private fun pump(p: Process, tracker: TunnelTracker, secrets: List<String>) {
        try {
            p.inputStream.bufferedReader().forEachLine { raw ->
                val line = LogRedactor.redact(raw, secrets)
                _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
                tracker.onLine(line)?.let { _state.value = it }
            }
        } catch (_: IOException) {
            // stream closed while stopping
        }
        val exit = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        if (stopping) {
            _state.value = TunnelState.Stopped
        } else {
            _state.value = TunnelState.Failed(
                tracker.failureReason(exit),
                retryable = tracker.hint?.contains("token") != true,
            )
        }
        if (process === p) process = null
    }

    override suspend fun stop() { lock.withLock {
        withContext(Dispatchers.IO) {
            val p = process ?: run { _state.value = TunnelState.Stopped; return@withContext }
            stopping = true
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            process = null
            _state.value = TunnelState.Stopped
        }
    } }

    override suspend fun probeRemote(hostname: String): RemoteProbeResult = withContext(Dispatchers.IO) {
        val result = try {
            val c = URL("https://$hostname/api/v1/health").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.bufferedReader()?.use { it.readText().take(2000) } ?: ""
            c.disconnect()
            RemoteProbeRules.classify(hostname, code, body, clock())
        } catch (e: IOException) {
            RemoteProbeResult(false, null, "Could not reach https://$hostname (${e.javaClass.simpleName}).", clock())
        }
        _probe.value = result
        result
    }

    private fun systemDns(): List<String> = try {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        cm?.getLinkProperties(network)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val BINARY_NAME = "libcloudflared.so"
        const val MAX_LOG_LINES = 200
    }
}