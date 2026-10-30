package `in`.imdevops.simbridge.api

import kotlin.math.ceil
import kotlin.math.max

/**
 * Slows down repeated bad credentials. Requests with a VALID key are never blocked by this, so an
 * attacker cannot lock the owner out; they only get 429 instead of 401 once past the threshold.
 */
class FailedAuthThrottle(
    private val maxFailuresPerMinute: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val failures = ArrayDeque<Long>()

    /** Records one failed attempt. Returns Retry-After seconds when over the limit, else null. */
    fun recordFailure(): Int? = synchronized(lock) {
        val now = clock()
        while (failures.isNotEmpty() && failures.first() <= now - 60_000) failures.removeFirst()
        failures.addLast(now)
        while (failures.size > 10_000) failures.removeFirst()
        if (failures.size > maxFailuresPerMinute) {
            max(1, ceil((failures.first() + 60_000 - now) / 1000.0).toInt())
        } else null
    }
}
