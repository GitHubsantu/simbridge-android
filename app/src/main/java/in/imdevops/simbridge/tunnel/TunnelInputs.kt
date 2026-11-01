package `in`.imdevops.simbridge.tunnel

object Hostnames {
    private val HOST = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    /** Returns the normalised (trimmed, lower-case) hostname, or null if it is not a plain DNS name. */
    fun normalize(input: String): String? = input.trim().lowercase().takeIf { HOST.matches(it) }
}

object TokenFormat {
    private val ALLOWED = Regex("^[A-Za-z0-9+/_=-]+$")

    /** Cheap sanity check only; Cloudflare is the real validator. */
    fun looksValid(token: String): Boolean = token.length in 40..4096 && ALLOWED.matches(token)
}

object DnsConfig {
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    val FALLBACK = listOf("1.1.1.1", "8.8.8.8")

    /**
     * The phone's own IPv4 DNS servers first, then public resolvers as a safety net, so a carrier DNS that
     * drops SRV lookups or cannot be reached does not stop the tunnel from starting.
     */
    fun servers(systemServers: List<String>): List<String> =
        (systemServers.filter { IPV4.matches(it) }.distinct().take(3) + FALLBACK).distinct()

    fun render(servers: List<String>): String =
        servers.joinToString("\n") { "nameserver $it" } + "\noptions timeout:3 attempts:2\n"

    fun resolvConf(systemServers: List<String>): String = render(servers(systemServers))
}

object RemoteProbeRules {
    /** Our API answers 401 UNAUTHORIZED to an unauthenticated request; that proves the tunnel reaches SimBridge. */
    fun classify(host: String, httpStatus: Int, body: String, now: Long): RemoteProbeResult {
        val ok = httpStatus == 401 && body.contains("\"UNAUTHORIZED\"")
        val detail = when {
            ok -> "Reached SimBridge through the tunnel (HTTP 401 without a key is the expected answer)."
            httpStatus in 520..530 || httpStatus == 502 || httpStatus == 530 ->
                "Cloudflare answered HTTP $httpStatus: the tunnel is not connected to this phone or the service URL is wrong."
            httpStatus == 404 -> "HTTP 404: the hostname resolves, but no tunnel route points at SimBridge."
            else -> "HTTP $httpStatus from $host, but it does not look like SimBridge."
        }
        return RemoteProbeResult(ok, httpStatus, detail, now)
    }
}
