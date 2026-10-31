package `in`.imdevops.simbridge.tunnel

object CloudflaredVersions {
    private val VERSION = Regex("\\b(20\\d\\d)\\.(\\d+)\\.(\\d+)\\b")

    /** Pulls "2026.10.4" out of `cloudflared --version` output or a release tag. */
    fun extract(text: String): String? = VERSION.find(text)?.value

    private fun parts(v: String): List<Int>? = VERSION.matchEntire(v)?.groupValues?.drop(1)?.map { it.toInt() }

    /** True/false when both parse, null otherwise. */
    fun isNewer(latest: String, current: String): Boolean? {
        val a = parts(latest) ?: return null
        val b = parts(current) ?: return null
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
