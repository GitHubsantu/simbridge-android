package `in`.imdevops.simbridge.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpstreamRelease(val tag: String, val url: String, val publishedAt: String)

/**
 * Only INFORMS. Android will not let the app download and run a new executable (see docs), so an
 * update always means rebuilding the APK with a checksum-verified binary.
 */
object UpdateChecker {
    suspend fun latestRelease(): Result<UpstreamRelease> = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/cloudflare/cloudflared/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "SimBridge-Gateway")
            try {
                if (c.responseCode != 200) return@withContext Result.failure(IOException("GitHub answered HTTP ${c.responseCode}"))
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                Result.success(UpstreamRelease(json.getString("tag_name"), json.optString("html_url"), json.optString("published_at")))
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: JSONException) {
            Result.failure(e)
        }
    }
}
