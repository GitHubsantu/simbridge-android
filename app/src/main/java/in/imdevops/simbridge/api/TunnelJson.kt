package `in`.imdevops.simbridge.api

import `in`.imdevops.simbridge.tunnel.RemoteProbeResult
import `in`.imdevops.simbridge.tunnel.TunnelCompatibility
import `in`.imdevops.simbridge.tunnel.TunnelState
import java.time.Instant

/** Builds the `GET /api/v1/tunnel/status` body. Contains no paths, tokens or hostnames. */
object TunnelJson {
    fun build(state: TunnelState, compat: TunnelCompatibility, probe: RemoteProbeResult?): String {
        val (name, connections, error) = when (state) {
            TunnelState.Stopped -> Triple("STOPPED", 0, null)
            TunnelState.Starting -> Triple("STARTING", 0, null)
            is TunnelState.Connected -> Triple("CONNECTED", state.connections, null)
            is TunnelState.Failed -> Triple("FAILED", 0, state.reason)
        }
        val err = error?.let { JsonText.quote(it) } ?: "null"
        val p = probe?.let {
            """{"ok":${it.ok},"http_status":${it.httpStatus ?: "null"},"checked_at":${JsonText.quote(Instant.ofEpochMilli(it.checkedAt).toString())},"detail":${JsonText.quote(it.detail)}}"""
        } ?: "null"
        return """{"success":true,"tunnel":{"state":"$name","connections":$connections,"error":$err,"binary":{"present":${compat.binaryPresent},"verdict":"${compat.verdict}","architecture":${JsonText.quote(compat.elf?.architecture ?: "unknown")},"process_64_bit":${compat.processIs64Bit}},"remote_probe":$p}}"""
    }
}
