package `in`.imdevops.simbridge.service

import android.content.Context

/** Small persisted state about the background gateway. Holds no secrets. */
class GatewayPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simbridge_gateway", Context.MODE_PRIVATE)

    /** True from the moment the user starts the gateway until a clean stop. If a new process finds it true, the old one died uncleanly. */
    var desiredRunning: Boolean
        get() = prefs.getBoolean("desired_running", false)
        set(v) { prefs.edit().putBoolean("desired_running", v).commit() }

    var autostartOnBoot: Boolean
        get() = prefs.getBoolean("autostart", false)
        set(v) { prefs.edit().putBoolean("autostart", v).apply() }

    var startTunnelWithGateway: Boolean
        get() = prefs.getBoolean("start_tunnel", true)
        set(v) { prefs.edit().putBoolean("start_tunnel", v).apply() }

    fun restartStamps(): List<Long> =
        prefs.getString("restarts", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun recordRestart(now: Long) {
        val kept = (restartStamps() + now).takeLast(10)
        prefs.edit().putString("restarts", kept.joinToString(",")).apply()
    }

    /** Newest last. Each entry is "epochMillis|text". */
    fun events(): List<String> = prefs.getString("events", "").orEmpty().split('\n').filter { it.isNotBlank() }

    @Synchronized
    fun addEvent(now: Long, text: String) {
        val line = "$now|${text.replace('\n', ' ').take(200)}"
        prefs.edit().putString("events", (events() + line).takeLast(40).joinToString("\n")).apply()
    }
}

/** Stops Android and us from restarting the service in an endless loop. */
object RestartGuard {
    fun allow(stamps: List<Long>, now: Long, maxRestarts: Int = 3, windowMillis: Long = 10 * 60_000L): Boolean =
        stamps.count { it > now - windowMillis } < maxRestarts
}
