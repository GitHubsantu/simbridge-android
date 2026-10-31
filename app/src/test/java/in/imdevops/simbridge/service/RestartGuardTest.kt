package `in`.imdevops.simbridge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartGuardTest {
    @Test fun allowsFewRestartsThenBlocksTheLoop() {
        val now = 10_000_000L
        assertTrue(RestartGuard.allow(emptyList(), now))
        assertTrue(RestartGuard.allow(listOf(now - 1000, now - 2000), now))
        assertFalse(RestartGuard.allow(listOf(now - 1000, now - 2000, now - 3000), now))
    }

    @Test fun oldRestartsAgeOut() {
        val now = 10_000_000L
        assertTrue(RestartGuard.allow(listOf(now - 700_000, now - 800_000, now - 900_000), now))
    }
}
