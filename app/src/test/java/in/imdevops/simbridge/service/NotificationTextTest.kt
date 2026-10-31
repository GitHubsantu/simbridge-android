package `in`.imdevops.simbridge.service

import `in`.imdevops.simbridge.api.ServerState
import `in`.imdevops.simbridge.tunnel.TunnelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test fun runningWithTunnel() {
        val c = NotificationText.build(ServerState.Running(8080), TunnelState.Connected(4), true, 5, 1)
        assertEquals("SimBridge is running", c.title)
        assertEquals("API ready · tunnel connected", c.summary)
        assertTrue(c.details.contains("Tunnel: connected (4 connections)"))
        assertTrue(c.details.contains("5 sent · 1 failed"))
    }

    @Test fun localOnlyWhenTunnelDisabled() {
        val c = NotificationText.build(ServerState.Running(8080), TunnelState.Stopped, false, 0, 0)
        assertEquals("API ready · local only", c.summary)
    }

    @Test fun problemsAreCalledOut() {
        val c = NotificationText.build(ServerState.Running(8080), TunnelState.Failed("token rejected", false), true, 0, 0)
        assertEquals("SimBridge needs attention", c.title)
        assertTrue(c.details.contains("failed — token rejected"))
    }

    @Test fun startingState() {
        assertEquals("SimBridge is starting", NotificationText.build(ServerState.Starting, TunnelState.Stopped, true, 0, 0).title)
    }

    @Test fun urlEvidenceOverridesALaggingTunnelLog() {
        val c = NotificationText.build(ServerState.Running(8080), TunnelState.Starting, true, 0, 0, publicOk = true)
        assertEquals("SimBridge is running", c.title)
        assertEquals("API ready · public URL OK", c.summary)
    }

    @Test fun failingUrlTestIsShownEvenWhenTunnelLooksConnected() {
        val c = NotificationText.build(ServerState.Running(8080), TunnelState.Connected(4), true, 0, 0, publicOk = false)
        assertEquals("API ready · tunnel connected, URL test failing", c.summary)
    }
}
