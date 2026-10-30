package `in`.imdevops.simbridge.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatePolicyTest {
    private val now = 10_000_000L
    private val limits = RateLimits(perMinute = 2, perDay = 5)

    @Test fun allowsUnderLimit() =
        assertEquals(RateDecision.Allowed, RatePolicy.check(listOf(now - 5_000), now, limits))

    @Test fun minuteLimitReportsWhenOldestLeavesWindow() {
        val d = RatePolicy.check(listOf(now - 10_000, now - 5_000), now, limits) as RateDecision.Limited
        assertEquals("minute", d.scope)
        assertEquals(50, d.retryAfterSeconds)
    }

    @Test fun oldEntriesDoNotCount() =
        assertEquals(RateDecision.Allowed, RatePolicy.check(listOf(now - 61_000, now - 62_000, now - 63_000), now, limits))

    @Test fun dayLimitWins() {
        val stamps = (1..5).map { now - it * 3_600_000L } // one per hour, none in the last minute
        val d = RatePolicy.check(stamps, now, limits) as RateDecision.Limited
        assertEquals("day", d.scope)
        assertTrue(d.retryAfterSeconds > 60)
    }
}
