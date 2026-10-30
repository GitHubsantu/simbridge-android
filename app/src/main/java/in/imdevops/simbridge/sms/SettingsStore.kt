package `in`.imdevops.simbridge.sms

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simbridge_settings", Context.MODE_PRIVATE)

    @Volatile
    private var cached: ApiSettings = read()

    fun load(): ApiSettings = cached

    /** Returns an error message and saves nothing if [settings] is invalid. */
    fun save(settings: ApiSettings): String? {
        settings.validate()?.let { return it }
        prefs.edit()
            .putInt("per_minute", settings.perMinute)
            .putInt("per_day", settings.perDay)
            .putInt("max_chars", settings.maxMessageChars)
            .apply()
        cached = settings
        return null
    }

    private fun read(): ApiSettings {
        val d = ApiSettings.DEFAULT
        val s = ApiSettings(
            prefs.getInt("per_minute", d.perMinute),
            prefs.getInt("per_day", d.perDay),
            prefs.getInt("max_chars", d.maxMessageChars),
        )
        return if (s.validate() == null) s else d
    }
}
