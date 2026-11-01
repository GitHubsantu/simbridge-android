package `in`.imdevops.simbridge.tunnel

import java.io.File
import java.io.IOException
import java.io.InputStream

enum class DnsPatchState { PATCHED, UNPATCHED, UNKNOWN }

/**
 * Tells whether a cloudflared binary was patched to read its resolv.conf from stdin. An unpatched
 * static Go binary has no DNS on Android and fails with "lookup ... on 127.0.0.1:53".
 */
object DnsDiagnostics {
    private val OLD = "/etc/resolv.conf".toByteArray(Charsets.US_ASCII)
    private val NEW = "//proc/self/fd/0".toByteArray(Charsets.US_ASCII)

    /** Counts [needle] in the stream chunk by chunk, so a 37 MB executable never has to fit in memory. */
    fun count(input: InputStream, needle: ByteArray): Int {
        val keep = needle.size - 1
        val buf = ByteArray(64 * 1024 + keep)
        var carry = 0
        var found = 0
        while (true) {
            val n = input.read(buf, carry, buf.size - carry)
            if (n < 0) break
            val len = carry + n
            var i = 0
            while (i <= len - needle.size) {
                var j = 0
                while (j < needle.size && buf[i + j] == needle[j]) j++
                if (j == needle.size) { found++; i += needle.size } else i++
            }
            carry = minOf(len, keep)
            System.arraycopy(buf, len - carry, buf, 0, carry)
        }
        return found
    }

    fun classify(oldCount: Int, newCount: Int): DnsPatchState = when {
        oldCount == 0 && newCount >= 1 -> DnsPatchState.PATCHED
        oldCount >= 1 && newCount == 0 -> DnsPatchState.UNPATCHED
        else -> DnsPatchState.UNKNOWN
    }

    fun patchState(file: File): DnsPatchState = try {
        classify(file.inputStream().use { count(it, OLD) }, file.inputStream().use { count(it, NEW) })
    } catch (_: IOException) {
        DnsPatchState.UNKNOWN
    }

    fun describe(state: DnsPatchState): String = when (state) {
        DnsPatchState.PATCHED -> "patched for Android DNS"
        DnsPatchState.UNPATCHED -> "NOT patched (DNS lookups will fail on Android)"
        DnsPatchState.UNKNOWN -> "unknown"
    }
}
