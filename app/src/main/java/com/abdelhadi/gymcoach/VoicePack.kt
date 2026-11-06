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
    fun file(ctx: Context, text: String) = File(dir(ctx), key(text) + ".m4a")
    fun has(ctx: Context, text: String) = file(ctx, text).let { it.exists() && it.length() > 800 }
    fun recordedCount(ctx: Context) = CoachLines.recordable().count { has(ctx, it) }

    private val safeName = Regex("^[0-9a-f]{40}\\.m4a$")

    /** Pack all recordings into one .zip that can be sent to friends (WhatsApp, Telegram, email…). */
    fun export(ctx: Context, packName: String): File? {
        val files = dir(ctx).listFiles { f -> safeName.matches(f.name) }?.toList().orEmpty()
        if (files.isEmpty()) return null
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "gym-coach-voice.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("pack.json"))
            z.write(JSONObject().put("name", packName).put("count", files.size).put("app", "gym-voice-coach").toString().toByteArray())
            z.closeEntry()
            files.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
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
                            File(dir(ctx), n).outputStream().use { z.copyTo(it) }; count++
                        }
                    }
                    e = z.nextEntry
                }
            }
        }
        return count to name
    }
}
