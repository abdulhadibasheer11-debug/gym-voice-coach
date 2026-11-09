package com.abdelhadi.gymcoach

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 16-bit mono PCM WAV read/write (MediaPlayer plays these directly). */
object Wav {
    fun write(f: File, pcm: ShortArray, rate: Int) {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        pcm.forEach { b.putShort(it) }
        f.parentFile?.mkdirs()
        f.writeBytes(b.array())
    }

    /** Returns samples and sample rate, or null if the file is not a 16-bit mono WAV written by us. */
    fun read(f: File): Pair<ShortArray, Int>? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val all = ByteArray(r.length().toInt()); r.readFully(all)
            val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            val rate = b.getInt(24)
            val n = (all.size - 44) / 2
            b.position(44)
            ShortArray(n) { b.short } to rate
        }
    }.getOrNull()
}

/**
 * Safe clean-up for a recorded sentence. Every step is conservative so it cannot make speech worse:
 * 1. remove low rumble (high-pass at 80 Hz: air-conditioning, handling noise; speech is above it),
 * 2. trim the silence before and after the words (keeps a small margin so no word is clipped),
 * 3. even out loudness (target level, never more than +18 dB, peaks kept below clipping),
 * 4. 8 ms fade in/out so cuts never click.
 */
object AudioFx {
    fun process(input: ShortArray, rate: Int): ShortArray {
        if (input.isEmpty()) return input
        var x = FloatArray(input.size) { input[it] / 32768f }
        x = highPass(x, rate, 80.0)
        x = trim(x, rate)
        normalize(x)
        fade(x, rate)
        return ShortArray(x.size) { (x[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }

    private fun highPass(x: FloatArray, rate: Int, f: Double): FloatArray {
        val w0 = 2 * Math.PI * f / rate
        val alpha = sin(w0) / (2 * 0.7071)
        val c = cos(w0)
        val a0 = 1 + alpha
        val b0 = ((1 + c) / 2 / a0).toFloat(); val b1 = (-(1 + c) / a0).toFloat(); val b2 = b0
        val a1 = (-2 * c / a0).toFloat(); val a2 = ((1 - alpha) / a0).toFloat()
        val y = FloatArray(x.size)
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v; y[i] = v
        }
        return y
    }

    /** RMS of each 10 ms frame. */
    fun frames(x: FloatArray, rate: Int): FloatArray {
        val n = max(1, rate / 100)
        return FloatArray(x.size / n) { k ->
            var s = 0.0
            for (i in k * n until (k + 1) * n) s += x[i] * x[i]
            sqrt(s / n).toFloat()
        }
    }

    private fun trim(x: FloatArray, rate: Int): FloatArray {
        val fr = frames(x, rate)
        if (fr.size < 10) return x
        val floor = fr.sorted()[fr.size / 5]                // quietest 20% = background
        val thr = max(floor * 3f, 0.01f)                     // ≈ −40 dBFS minimum
        val first = fr.indexOfFirst { it > thr }
        val last = fr.indexOfLast { it > thr }
        if (first < 0 || last <= first) return x             // no clear speech found: keep everything
        val n = rate / 100
        val start = max(0, (first - 6) * n)                  // 60 ms before the first word
        val end = min(x.size, (last + 1 + 15) * n)           // 150 ms after the last word (soft endings)
        return x.copyOfRange(start, end)
    }

    private fun normalize(x: FloatArray) {
        var peak = 0f; var s = 0.0
        for (v in x) { peak = max(peak, abs(v)); s += v * v }
        if (peak < 1e-4f) return
        val rms = sqrt(s / x.size).toFloat()
        val gain = minOf(0.89f / peak, 0.16f / max(rms, 1e-4f), 8f) // peak −1 dBFS, loudness ≈ −16 dBFS, at most +18 dB
        for (i in x.indices) x[i] *= gain
    }

    private fun fade(x: FloatArray, rate: Int) {
        val n = min(x.size / 2, rate * 8 / 1000)
        for (i in 0 until n) { val g = i / n.toFloat(); x[i] *= g; x[x.size - 1 - i] *= g }
    }
}

/**
 * Records one sentence as PCM. Stops by itself about 1.2 s after the speaker stops talking,
 * after 10 s at most, or when [stop] is set. Optional phone noise suppressor.
 */
class PcmRecorder(private val noiseSuppress: Boolean) {
    @Volatile var stop = false
    /** Set together with [stop] when the recording must be thrown away. */
    @Volatile var discard = false
    class Result(val pcm: ShortArray, val rate: Int, val heardSpeech: Boolean)

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO first
    suspend fun record(maxMs: Int = 10_000, silenceMs: Int = 1200, onLevel: (Float) -> Unit = {}): Result? = withContext(Dispatchers.IO) {
        val rate = 44100
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate / 5))
        }.getOrNull() ?: return@withContext null
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext null }
        val ns = if (noiseSuppress && NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val out = java.io.ByteArrayOutputStream()
        val frame = ShortArray(rate / 50) // 20 ms
        var spoke = false; var quietMs = 0; var elapsed = 0
        var noise = 0f; var noiseFrames = 0
        try {
            rec.startRecording()
            while (!stop && elapsed < maxMs) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var s = 0.0
                for (i in 0 until n) { bb.putShort(frame[i]); val v = frame[i] / 32768.0; s += v * v }
                out.write(bb.array())
                val rms = sqrt(s / n).toFloat()
                elapsed += n * 1000 / rate
                onLevel(rms)
                // first 300 ms: learn the room's background level
                if (elapsed <= 300) { noise += rms; noiseFrames++; continue }
                val thr = max((noise / max(1, noiseFrames)) * 3f, 0.012f)
                if (rms > thr) { spoke = true; quietMs = 0 } else if (spoke) quietMs += n * 1000 / rate
                if (spoke && quietMs >= silenceMs) break
                if (!spoke && elapsed >= 6000) break // nothing said
            }
        } finally {
            runCatching { rec.stop() }; rec.release(); ns?.release()
        }
        val bytes = out.toByteArray()
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = ShortArray(sb.remaining()).also { sb.get(it) }
        Result(pcm, rate, spoke)
    }
}
