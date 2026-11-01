package `in`.imdevops.simbridge.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String?,
)

/**
 * Updates SimBridge itself from its own GitHub Releases. This replaces the whole APK (which already
 * contains the pinned cloudflared), so it does not conflict with "Android cannot run a downloaded
 * executable". Nothing is installed unless: the release has SHA256SUMS.txt, the download matches it,
 * the APK is signed with the same key as the installed app, and the user confirms in Android's installer.
 */
object AppUpdater {
    const val REPO = "GitHubsantu/simbridge-android"
    const val ACTION_INSTALL_RESULT = "in.imdevops.simbridge.UPDATE_RESULT"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

    // ---------- pure helpers (unit-tested) ----------

    /** "v1.2.3" -> [1,2,3]; null when there is no leading number. Ignores suffixes like "-beta". */
    fun versionParts(v: String): List<Int>? {
        val m = Regex("""^[vV]?(\d+(?:\.\d+)*)""").find(v.trim()) ?: return null
        return m.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
    }

    /** True/false when both parse, null otherwise. Missing parts count as 0 ("1.0" == "1.0.0"). */
    fun isNewer(latest: String, current: String): Boolean? {
        val a = versionParts(latest) ?: return null
        val b = versionParts(current) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Matches the release file names: SimBridge-<version>-32bit.apk / -64bit.apk / -universal.apk. */
    fun pickApk(names: List<String>, is64BitUserspace: Boolean): String? {
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        val wanted = if (is64BitUserspace) "-64bit.apk" else "-32bit.apk"
        return apks.firstOrNull { it.endsWith(wanted, ignoreCase = true) }
            ?: apks.firstOrNull { it.endsWith("-universal.apk", ignoreCase = true) }
    }

    /** Reads `sha256sum`-style lines: "<64 hex>  name" or "<64 hex> *name". */
    fun checksumFor(sums: String, fileName: String): String? {
        val line = Regex("""^\s*([0-9a-fA-F]{64})\s+\*?(.+?)\s*$""")
        return sums.lineSequence().firstNotNullOfOrNull { l ->
            val m = line.find(l) ?: return@firstNotNullOfOrNull null
            val name = m.groupValues[2].substringAfterLast('/').substringAfterLast('\\')
            if (name == fileName) m.groupValues[1].lowercase() else null
        }
    }

    /** Windows PowerShell often writes UTF-16 with a BOM; handle that and a UTF-8 BOM. */
    fun decodeText(bytes: ByteArray): String =
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        } else {
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        }

    // ---------- network ----------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "SimBridge-Gateway")
        return c
    }

    private fun fetchBytes(url: String): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun latest(): Result<AppRelease> = withContext(Dispatchers.IO) {
        try {
            val c = open("https://api.github.com/repos/$REPO/releases/latest")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            val body = try {
                if (c.responseCode == 404) return@withContext Result.failure(IOException("No release has been published yet"))
                if (c.responseCode != 200) return@withContext Result.failure(IOException("GitHub answered HTTP ${c.responseCode}"))
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
            val json = JSONObject(body)
            val assets = json.getJSONArray("assets")
            val byName = HashMap<String, JSONObject>()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                byName[a.getString("name")] = a
            }
            val apkName = pickApk(byName.keys.toList(), Process.is64Bit())
                ?: return@withContext Result.failure(IOException("The release has no APK for this phone"))
            val apk = byName.getValue(apkName)
            val apkUrl = apk.getString("browser_download_url")
            // Only ever download from this repository's release area.
            if (!apkUrl.startsWith(DOWNLOAD_PREFIX)) return@withContext Result.failure(IOException("Unexpected download address"))
            val sums = byName.entries.firstOrNull { it.key.equals("SHA256SUMS.txt", ignoreCase = true) }
                ?.value?.getString("browser_download_url")?.takeIf { it.startsWith(DOWNLOAD_PREFIX) }
            Result.success(
                AppRelease(
                    tag = json.getString("tag_name"),
                    notes = json.optString("body"),
                    pageUrl = json.optString("html_url"),
                    apkName = apkName,
                    apkUrl = apkUrl,
                    apkSize = apk.optLong("size"),
                    sumsUrl = sums,
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: JSONException) {
            Result.failure(e)
        }
    }

    /** Downloads into the app cache and returns the file only if its SHA-256 matches SHA256SUMS.txt. */
    suspend fun download(context: Context, r: AppRelease, onProgress: (done: Long, total: Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val sumsUrl = r.sumsUrl
                    ?: return@withContext Result.failure(IOException("This release has no SHA256SUMS.txt, so the download cannot be verified"))
                val expected = checksumFor(decodeText(fetchBytes(sumsUrl)), r.apkName)
                    ?: return@withContext Result.failure(IOException("SHA256SUMS.txt has no entry for ${r.apkName}"))

                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val part = File(dir, r.apkName + ".part")
                val done = File(dir, r.apkName)
                val digest = MessageDigest.getInstance("SHA-256")
                val c = open(r.apkUrl)
                try {
                    if (c.responseCode != 200) throw IOException("Download failed: HTTP ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
                    var read = 0L
                    var lastReported = 0L
                    c.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                read += n
                                if (read - lastReported >= 256 * 1024) { lastReported = read; onProgress(read, total) }
                            }
                        }
                    }
                    onProgress(read, total)
                } finally {
                    c.disconnect()
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    part.delete()
                    return@withContext Result.failure(IOException("Checksum mismatch: the download is not the published file"))
                }
                if (!part.renameTo(done)) return@withContext Result.failure(IOException("Could not finish the download"))
                Result.success(done)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    // ---------- verification and install ----------

    fun installedVersionName(context: Context): String = packageInfo(context, null)?.versionName ?: "unknown"

    @Suppress("DEPRECATION")
    private fun packageInfo(context: Context, apk: File?): PackageInfo? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return try {
            if (apk == null) pm.getPackageInfo(context.packageName, flags) else pm.getPackageArchiveInfo(apk.path, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { it.toCharsString() }?.toSet() ?: emptySet()
    }

    /** True only when the downloaded APK is signed with the same key as the installed app. */
    suspend fun signaturesMatch(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        val mine = certs(packageInfo(context, null))
        val theirs = certs(packageInfo(context, apk))
        mine.isNotEmpty() && mine == theirs
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands the APK to Android's installer. The result arrives in [UpdateStatusReceiver]. */
    suspend fun install(context: Context, apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("simbridge.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val callback = Intent(app, UpdateStatusReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
                // Must be mutable: the installer adds its status extras to this intent.
                val pending = PendingIntent.getBroadcast(app, id, callback, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        }
    }
}
