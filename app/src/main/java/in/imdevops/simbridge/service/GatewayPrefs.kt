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

    /** Set just before an in-app update so the gateway comes back after Android replaces the app. */
    var resumeAfterUpdate: Boolean
        get() = prefs.getBoolean("resume_after_update", false)
        set(v) { prefs.edit().putBoolean("resume_after_update", v).commit() }

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
