package `in`.imdevops.simbridge.tunnel

sealed interface LogEvent {
    data class Registered(val connIndex: Int) : LogEvent
    data class Unregistered(val connIndex: Int) : LogEvent
    data object DnsFailure : LogEvent
    data object TokenProblem : LogEvent
    data class Error(val line: String) : LogEvent
    data object Other : LogEvent
}

object CloudflaredLog {
    private val connIndex = Regex("connIndex=(\\d+)")
    private val dns = Regex("dial udp [^ ]*:53|lookup [^ ]+ on [^ ]+:53|no such host|server misbehaving")

    fun parse(line: String): LogEvent {
        val idx = connIndex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val lower = line.lowercase()
        return when {
            "unregistered tunnel connection" in lower && idx != null -> LogEvent.Unregistered(idx)
            "registered tunnel connection" in lower && idx != null -> LogEvent.Registered(idx)
            dns.containsMatchIn(line) -> LogEvent.DnsFailure
            "token" in line.lowercase() && listOf("invalid", "malformed", "not valid", "could not").any { it in line.lowercase() } ->
                LogEvent.TokenProblem
            " ERR " in line || line.startsWith("ERR ") -> LogEvent.Error(line)
            else -> LogEvent.Other
        }
    }
}

/** Turns cloudflared log lines into a [TunnelState]. Pure, so it is unit-tested. */
class TunnelTracker {
    private val registered = mutableSetOf<Int>()
    var hint: String? = null
        private set
    var lastError: String? = null
        private set

    /** Returns the new state if this line changed it, otherwise null. */
    fun onLine(line: String): TunnelState? = when (val e = CloudflaredLog.parse(line)) {
        is LogEvent.Registered -> if (registered.add(e.connIndex)) TunnelState.Connected(registered.size) else null
        is LogEvent.Unregistered ->
            if (registered.remove(e.connIndex)) {
                if (registered.isEmpty()) TunnelState.Starting else TunnelState.Connected(registered.size)
            } else null
        LogEvent.DnsFailure -> {
            hint = "DNS lookups failed. cloudflared on Android needs a resolv.conf workaround; see docs/CLOUDFLARE_SETUP.md."
            null
        }
        LogEvent.TokenProblem -> {
            hint = "Cloudflare rejected the tunnel token. Re-copy it from the Zero Trust dashboard."
            null
        }
        is LogEvent.Error -> {
            lastError = e.line
            null
        }
        LogEvent.Other -> null
    }

    fun failureReason(exitCode: Int): String =
        listOfNotNull("cloudflared exited with code $exitCode.", hint, lastError?.let { "Last error: $it" })
            .joinToString(" ")
}

object LogRedactor {
    private val longTokenLike = Regex("[A-Za-z0-9+/_=-]{80,}")

    fun redact(line: String, secrets: Collection<String>): String {
        var out = line
        for (s in secrets) if (s.length >= 8) out = out.replace(s, "[redacted]")
        return longTokenLike.replace(out, "[redacted-long-string]")
    }
}
