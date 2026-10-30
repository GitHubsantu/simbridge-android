package `in`.imdevops.simbridge.sms

import kotlin.math.ceil
import kotlin.math.max

data class RateLimits(val perMinute: Int, val perDay: Int)

sealed interface RateDecision {
    data object Allowed : RateDecision
    data class Limited(val scope: String, val retryAfterSeconds: Int) : RateDecision
}

/**
 * Sliding-window limit computed from the creation times of messages already stored. Because it is
 * derived from persisted history, restarting the app does not reset the counters.
 */
object RatePolicy {
    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L

    fun check(createdAt: List<Long>, now: Long, limits: RateLimits): RateDecision {
        val minute = retryFor(createdAt, now, MINUTE, limits.perMinute)
        val day = retryFor(createdAt, now, DAY, limits.perDay)
        val worst = listOfNotNull(minute, day).maxOrNull() ?: return RateDecision.Allowed
        val scope = if (day != null && (minute == null || day >= minute)) "day" else "minute"
        return RateDecision.Limited(scope, worst)
    }

    private fun retryFor(createdAt: List<Long>, now: Long, window: Long, limit: Int): Int? {
        val inWindow = createdAt.filter { it > now - window }.sorted()
        if (inWindow.size < limit) return null
        // This record must leave the window before one more message fits.
        val blocker = inWindow[inWindow.size - limit]
        return max(1, ceil((blocker + window - now) / 1000.0).toInt())
    }
}
