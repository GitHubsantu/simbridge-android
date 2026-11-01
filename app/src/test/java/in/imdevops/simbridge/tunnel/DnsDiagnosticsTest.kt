package `in`.imdevops.simbridge.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class DnsDiagnosticsTest {
    private val old = "/etc/resolv.conf".toByteArray()
    private val patched = "//proc/self/fd/0".toByteArray()

    private fun blob(size: Int, needle: ByteArray?, at: Int): ByteArray {
        val b = ByteArray(size) { (it % 251).toByte() }
        if (needle != null) needle.copyInto(b, at)
        return b
    }

    @Test fun countsAcrossChunkBoundaries() {
        for (at in listOf(0, 65520, 65535, 65536, 65551, 140000)) {
            assertEquals("at $at", 1, DnsDiagnostics.count(ByteArrayInputStream(blob(200000, old, at)), old))
        }
        assertEquals(0, DnsDiagnostics.count(ByteArrayInputStream(ByteArray(200000)), old))
    }

    @Test fun classification() {
        assertEquals(DnsPatchState.UNPATCHED, DnsDiagnostics.classify(1, 0))
        assertEquals(DnsPatchState.PATCHED, DnsDiagnostics.classify(0, 1))
        assertEquals(DnsPatchState.UNKNOWN, DnsDiagnostics.classify(0, 0))
        assertEquals(DnsPatchState.UNKNOWN, DnsDiagnostics.classify(1, 1))
    }

    @Test fun fileStates() {
        val f = File.createTempFile("cfbinary", ".bin")
        try {
            f.writeBytes(blob(150000, old, 90000))
            assertEquals(DnsPatchState.UNPATCHED, DnsDiagnostics.patchState(f))
            f.writeBytes(blob(150000, patched, 90000))
            assertEquals(DnsPatchState.PATCHED, DnsDiagnostics.patchState(f))
            f.writeBytes(ByteArray(1000))
            assertEquals(DnsPatchState.UNKNOWN, DnsDiagnostics.patchState(f))
        } finally { f.delete() }
        assertEquals(DnsPatchState.UNKNOWN, DnsDiagnostics.patchState(File("/definitely/not/here")))
    }
}
