package `in`.imdevops.simbridge.service

import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.tunnel.TunnelState

data class NotificationContent(val title: String, val summary: String, val details: String)

object NotificationText {
    /**
     * [publicOk] is the result of a RECENT public-URL probe (null = not tested / stale). Only that
     * evidence lets the text say the public URL works; the tunnel log alone never does.
     */
    fun build(
        server: ServerState,
        tunnel: TunnelState,
        tunnelEnabled: Boolean,
        sent: Int,
        failed: Int,
        publicOk: Boolean? = null,
    ): NotificationContent {
        val tunnelUsable = tunnel is TunnelState.Connected || (publicOk == true && tunnel is TunnelState.Starting)
        val broken = server is ServerState.Failed || tunnel is TunnelState.Failed
        val starting = server is ServerState.Starting || server is ServerState.Stopped ||
            (tunnel is TunnelState.Starting && !tunnelUsable)
        val title = when {
            broken -> "SimBridge needs attention"
            starting -> "SimBridge is starting"
            else -> "SimBridge is running"
        }
        val apiShort = when (server) {
            is ServerState.Running -> "API ready"
            ServerState.Starting -> "API starting"
            ServerState.Stopped -> "API stopped"
            is ServerState.Failed -> "API failed"
        }
        val tunnelShort = when {
            !tunnelEnabled && tunnel is TunnelState.Stopped -> "local only"
            publicOk == true && tunnelUsable -> "public URL OK"
            tunnel is TunnelState.Connected && publicOk == false -> "tunnel connected, URL test failing"
            tunnel is TunnelState.Connected -> "tunnel connected"
            tunnel is TunnelState.Starting -> "tunnel connecting"
            tunnel is TunnelState.Failed -> "tunnel failed"
            else -> "tunnel off"
        }
        val apiLine = when (server) {
            is ServerState.Running -> "API: listening on 127.0.0.1:${server.port}"
            is ServerState.Failed -> "API: failed — ${server.reason.take(80)}"
            ServerState.Starting -> "API: starting"
            ServerState.Stopped -> "API: stopped"
        }
        val tunnelLine = when (tunnel) {
            is TunnelState.Connected -> "Tunnel: connected (${tunnel.connections} connection${if (tunnel.connections == 1) "" else "s"})"
            TunnelState.Starting -> if (tunnelUsable) "Tunnel: up (confirmed by URL test)" else "Tunnel: connecting…"
            TunnelState.Stopped -> if (tunnelEnabled) "Tunnel: stopped" else "Tunnel: off (local only)"
            is TunnelState.Failed -> "Tunnel: failed — ${tunnel.reason.take(80)}"
        }
        val urlLine = when (publicOk) {
            true -> "Public URL: working"
            false -> "Public URL: not answering"
            null -> "Public URL: not tested"
        }
        return NotificationContent(
            title = title,
            summary = "$apiShort · $tunnelShort",
            details = "$apiLine\n$tunnelLine\n$urlLine\nMessages: $sent sent · $failed failed",
        )
    }
}
