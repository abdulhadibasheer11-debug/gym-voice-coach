package com.abdelhadi.gymcoach

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Human voice recordings: one audio file per sentence, looked up by the sentence text. */
object VoicePack {
    fun dir(ctx: Context) = File(ctx.filesDir, "voice").apply { mkdirs() }
    private fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.trim().toByteArray()).joinToString("") { "%02x".format(it) }
    fun file(ctx: Context, text: String) = File(dir(ctx), key(text) + ".m4a")
    fun has(ctx: Context, text: String) = file(ctx, text).let { it.exists() && it.length() > 800 }
    fun recordedCount(ctx: Context) = CoachLines.recordable().count { has(ctx, it) }
}
