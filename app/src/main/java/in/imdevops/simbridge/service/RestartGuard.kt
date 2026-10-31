package `in`.imdevops.simbridge.service

/** Stops Android and us from restarting the service in an endless loop. */
object RestartGuard {
    fun allow(stamps: List<Long>, now: Long, maxRestarts: Int = 3, windowMillis: Long = 10 * 60_000L): Boolean =
        stamps.count { it > now - windowMillis } < maxRestarts
}
