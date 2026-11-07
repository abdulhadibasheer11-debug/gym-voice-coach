package com.abdelhadi.gymcoach

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Energetic tracks by Kevin MacLeod (incompetech.com), licensed under Creative Commons Attribution 4.0.
 * Downloaded once into the app (about 60 MB), then played offline.
 */
object MusicLibrary {
    const val CREDIT = "الموسيقى: Kevin MacLeod، من موقع incompetech.com، برخصة المشاع الإبداعي CC BY 4.0."
    val tracks = listOf(
        "Volatile Reaction", "Movement Proposition", "Raving Energy", "Danger Storm",
        "Club Diver", "Rhinoceros", "Killers", "Hit the Streets",
    )
    private fun url(t: String) = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/" + t.replace(" ", "%20") + ".mp3"
    fun dir(ctx: Context) = File(ctx.filesDir, "music").apply { mkdirs() }
    fun file(ctx: Context, t: String) = File(dir(ctx), t.replace(" ", "_") + ".mp3")
    fun downloaded(ctx: Context) = tracks.map { file(ctx, it) }.filter { it.exists() && it.length() > 100_000 }

    /** Downloads missing tracks; progress(done, total). Returns how many tracks are available. */
    suspend fun download(ctx: Context, progress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) {
        tracks.forEachIndexed { i, t ->
            val f = file(ctx, t)
            if (!(f.exists() && f.length() > 100_000)) runCatching {
                val c = URL(url(t)).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GymVoiceCoach")
                if (c.responseCode == 200) {
                    val tmp = File(f.path + ".part")
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(f)
                }
            }
            withContext(Dispatchers.Main) { progress(i + 1, tracks.size) }
        }
        downloaded(ctx).size
    }
}
