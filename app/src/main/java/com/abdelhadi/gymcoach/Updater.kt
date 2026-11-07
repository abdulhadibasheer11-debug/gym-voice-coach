package com.abdelhadi.gymcoach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and opens the Android installer. */
object Updater {
    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10000; c.readTimeout = 10000
            if (c.responseCode != 200) return@runCatching null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val code = j.getString("tag_name").trimStart('v').toIntOrNull() ?: return@runCatching null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            // show only user-facing notes: no links or technical lines
            val notes = j.optString("body").lines().filter { l ->
                l.isNotBlank() && !l.contains("http", true) && !l.contains("claude", true) && !l.contains("Co-Authored", true)
            }.joinToString("\n")
            url?.let { Release(code, j.optString("name"), it, notes) }
        }.getOrNull()
    }

    suspend fun download(act: Activity, r: Release): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(act.cacheDir, "updates").apply { mkdirs() }
            val f = File(dir, "update.apk")
            val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true // GitHub redirects https -> https, which is followed automatically
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (conn.responseCode != 200) return@runCatching null
            conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }.getOrNull()
    }

    fun install(act: Activity, f: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("إذن التثبيت مطلوب")
                .setMessage("للتحديث، اسمح لهذا التطبيق بتثبيت التطبيقات. سأفتح لك الإعدادات، فعّل الخيار ثم ارجع واضغط فحص التحديثات مرة أخرى.")
                .setPositiveButton("فتح الإعدادات") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                }
                .setNegativeButton("لاحقاً", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
        act.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
