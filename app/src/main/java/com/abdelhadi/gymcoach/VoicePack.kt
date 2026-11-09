package com.abdelhadi.gymcoach

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Human voice recordings: one audio file per sentence, looked up by the sentence text. */
object VoicePack {
    fun dir(ctx: Context) = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    /*
     * Files per sentence (key = SHA-1 of the text):
     *   key.wav       the enhanced recording (used by default)
     *   key.orig.wav  the original, untouched recording (always kept)
     *   key.raw       marker: use the original instead of the enhanced one
     *   key.m4a       older recordings (before 0.1.13) and imported packs from older versions
     */
    private fun usedFile(d: File, k: String): File {
        val orig = File(d, "$k.orig.wav"); val proc = File(d, "$k.wav")
        return when {
            File(d, "$k.raw").exists() && orig.exists() -> orig
            proc.exists() -> proc
            orig.exists() -> orig
            else -> File(d, "$k.m4a")
        }
    }

    /** The file to play for this sentence (may not exist). */
    fun file(ctx: Context, text: String) = usedFile(dir(ctx), key(text))
    fun has(ctx: Context, text: String) = file(ctx, text).let { it.exists() && it.length() > 800 }
    fun original(ctx: Context, text: String) = File(dir(ctx), key(text) + ".orig.wav")
    fun hasEnhanced(ctx: Context, text: String) = File(dir(ctx), key(text) + ".wav").exists()
    fun usesOriginal(ctx: Context, text: String) = File(dir(ctx), key(text) + ".raw").exists() || !hasEnhanced(ctx, text)

    /** Choose which version plays: the original or the enhanced one. */
    fun useOriginal(ctx: Context, text: String, on: Boolean) {
        val m = File(dir(ctx), key(text) + ".raw")
        if (on) m.writeText("1") else m.delete()
    }

    /** Saves a new recording: the original always, plus the enhanced version when given. */
    fun save(ctx: Context, text: String, original: ShortArray, enhanced: ShortArray?, rate: Int) {
        val d = dir(ctx); val k = key(text)
        delete(ctx, text)
        Wav.write(File(d, "$k.orig.wav"), original, rate)
        if (enhanced != null) Wav.write(File(d, "$k.wav"), enhanced, rate) else File(d, "$k.raw").writeText("1")
    }

    fun delete(ctx: Context, text: String) {
        val d = dir(ctx); val k = key(text)
        listOf(".wav", ".orig.wav", ".raw", ".m4a").forEach { File(d, k + it).delete() }
    }
    fun recordedCount(ctx: Context) = CoachLines.recordable().count { has(ctx, it) }

    private val safeName = Regex("^[0-9a-f]{40}\\.(m4a|wav)$")
    private val keyOf = Regex("^([0-9a-f]{40})\\.")

    /** Pack all recordings into one .zip that can be sent to friends (WhatsApp, Telegram, email…). */
    fun export(ctx: Context, packName: String): File? {
        val d = dir(ctx)
        // one file per sentence: the version that is actually used
        val keys = d.list().orEmpty().mapNotNull { keyOf.find(it)?.groupValues?.get(1) }.distinct()
        val files = keys.map { usedFile(d, it) }.filter { it.exists() && it.length() > 800 }
        if (files.isEmpty()) return null
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "gym-coach-voice.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("pack.json"))
            z.write(JSONObject().put("name", packName).put("count", files.size).put("app", "gym-voice-coach").toString().toByteArray())
            z.closeEntry()
            files.forEach { f ->
                val k = keyOf.find(f.name)!!.groupValues[1]
                z.putNextEntry(ZipEntry(k + if (f.name.endsWith(".m4a")) ".m4a" else ".wav"))
                f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }

    /** Import a voice pack .zip. Only recording files with the expected names are accepted. Returns how many. */
    fun import(ctx: Context, uri: Uri): Pair<Int, String?> {
        var count = 0
        var name: String? = null
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val n = e.name.substringAfterLast('/')
                    when {
                        n == "pack.json" -> name = runCatching { JSONObject(z.readBytes().decodeToString()).optString("name") }.getOrNull()
                        safeName.matches(n) && !e.isDirectory -> {
                            val k = n.substringBefore('.')
                            val d = dir(ctx)
                            listOf(".wav", ".orig.wav", ".raw", ".m4a").forEach { File(d, k + it).delete() }
                            File(d, n).outputStream().use { z.copyTo(it) }; count++
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        return count to name
    }
}
