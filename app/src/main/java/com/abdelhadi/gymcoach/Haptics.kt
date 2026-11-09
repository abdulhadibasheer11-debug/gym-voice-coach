package com.abdelhadi.gymcoach

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibrations that mirror the beeps, so cues are felt even with loud music or in a pocket. */
class Haptics(ctx: Context, private val mode: String) {
    private val vib: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private fun one(ms: Long) = runCatching { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    private fun pattern(vararg t: Long) = runCatching { vib?.vibrate(VibrationEffect.createWaveform(t, -1)) }

    fun beep(kind: Beep) {
        if (mode == "off") return
        when (kind) {
            Beep.COUNTDOWN -> one(60)
            Beep.GO -> one(350)
            Beep.DONE -> pattern(0, 150, 120, 150)
            Beep.TICK -> {} // mid-rep tick: sound only
        }
    }

    fun rep() { if (mode == "all") one(40) }
}
