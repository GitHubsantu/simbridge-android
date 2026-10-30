package `in`.imdevops.simbridge.tunnel

import kotlinx.coroutines.flow.StateFlow

sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Starting : TunnelState
    /** cloudflared reported registered connections to Cloudflare. This is NOT proof the public URL works. */
    data class Connected(val connections: Int) : TunnelState
    data class Failed(val reason: String, val retryable: Boolean) : TunnelState
}

data class TunnelCompatibility(
    val binaryPresent: Boolean,
    val binarySizeBytes: Long,
    val elf: ElfInfo?,
    val executable: Boolean,
    val processIs64Bit: Boolean,
    val supportedAbis: List<String>,
    val verdict: BinaryVerdict,
)

data class ProbeResult(val exitCode: Int?, val output: String, val error: String?)

data class RemoteProbeResult(val ok: Boolean, val httpStatus: Int?, val detail: String, val checkedAt: Long)

/** The only tunnel surface the rest of the app may depend on. */
interface TunnelManager {
    val state: StateFlow<TunnelState>
    /** Recent cloudflared output, secrets removed. */
    val logs: StateFlow<List<String>>
    val lastRemoteProbe: StateFlow<RemoteProbeResult?>

    fun inspect(): TunnelCompatibility
    /** Runs `cloudflared --version` to prove the binary can be executed here. */
    suspend fun probeBinary(): ProbeResult
    suspend fun start(token: String)
    suspend fun stop()
    suspend fun probeRemote(hostname: String): RemoteProbeResult
}
