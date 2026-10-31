package `in`.imdevops.simbridge.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudflaredVersionsTest {
    @Test fun extractsFromVersionOutputAndTags() {
        assertEquals("2026.10.4", CloudflaredVersions.extract("cloudflared version 2026.10.4 (built 2026-10-26 linux_arm)"))
        assertEquals("2026.11.0", CloudflaredVersions.extract("2026.11.0"))
        assertNull(CloudflaredVersions.extract("no version here"))
    }

    @Test fun comparesNumerically() {
        assertEquals(true, CloudflaredVersions.isNewer("2026.10.6", "2026.10.4"))
        assertEquals(true, CloudflaredVersions.isNewer("2026.11.0", "2026.10.9"))
        assertEquals(true, CloudflaredVersions.isNewer("2026.10.10", "2026.10.9"))
        assertEquals(false, CloudflaredVersions.isNewer("2026.10.4", "2026.10.4"))
        assertEquals(false, CloudflaredVersions.isNewer("2026.9.9", "2026.10.0"))
        assertNull(CloudflaredVersions.isNewer("latest", "2026.10.4"))
    }
}
