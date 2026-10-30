package `in`.imdevops.simbridge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FailedAuthThrottleTest {
    private var now = 1_000_000L
    private val throttle = FailedAuthThrottle(maxFailuresPerMinute = 3) { now }

    @Test fun staysQuietUnderTheLimitThenThrottles() {
        repeat(3) { assertNull(throttle.recordFailure()) }
        assertNotNull(throttle.recordFailure())
    }

    @Test fun recoversAfterAMinute() {
        repeat(4) { throttle.recordFailure() }
        now += 61_000
        assertNull(throttle.recordFailure())
    }
}
