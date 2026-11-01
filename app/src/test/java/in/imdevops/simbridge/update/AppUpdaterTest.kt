package `in`.imdevops.simbridge.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test fun comparesVersions() {
        assertEquals(true, AppUpdater.isNewer("v1.1", "1.0"))
        assertEquals(true, AppUpdater.isNewer("1.0.1", "1.0"))
        assertEquals(true, AppUpdater.isNewer("1.10", "1.9"))
        assertEquals(false, AppUpdater.isNewer("1.0", "1.0.0"))
        assertEquals(false, AppUpdater.isNewer("v1.0", "1.0"))
        assertEquals(false, AppUpdater.isNewer("0.9", "1.0"))
        assertEquals(true, AppUpdater.isNewer("2.0-beta", "1.9"))
        assertNull(AppUpdater.isNewer("latest", "1.0"))
    }

    @Test fun picksApkForUserspace() {
        val names = listOf("SimBridge-1.1-32bit.apk", "SimBridge-1.1-64bit.apk", "SimBridge-1.1-universal.apk", "SHA256SUMS.txt")
        assertEquals("SimBridge-1.1-64bit.apk", AppUpdater.pickApk(names, true))
        assertEquals("SimBridge-1.1-32bit.apk", AppUpdater.pickApk(names, false))
        assertEquals("SimBridge-1.1-universal.apk", AppUpdater.pickApk(listOf("SimBridge-1.1-universal.apk"), true))
        assertNull(AppUpdater.pickApk(listOf("SHA256SUMS.txt"), true))
    }

    @Test fun readsChecksumLines() {
        val h1 = "a".repeat(64)
        val h2 = "B".repeat(64)
        val text = "$h1  SimBridge-1.1-32bit.apk\r\n$h2 *SimBridge-1.1-64bit.apk\r\n"
        assertEquals(h1, AppUpdater.checksumFor(text, "SimBridge-1.1-32bit.apk"))
        assertEquals("b".repeat(64), AppUpdater.checksumFor(text, "SimBridge-1.1-64bit.apk"))
        assertNull(AppUpdater.checksumFor(text, "SimBridge-1.1-universal.apk"))
        assertNull(AppUpdater.checksumFor("garbage", "x.apk"))
    }

    @Test fun decodesUtf16AndBom() {
        val line = "${"c".repeat(64)}  x.apk"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + line.toByteArray(Charsets.UTF_16LE)
        assertEquals(line, AppUpdater.decodeText(utf16))
        assertEquals(line, AppUpdater.decodeText(("\uFEFF" + line).toByteArray(Charsets.UTF_8)))
    }
}
