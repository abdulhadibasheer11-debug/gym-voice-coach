package com.abdelhadi.gymcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Background music during the session.
 *  - "none": nothing (music from other apps is still lowered automatically while the coach talks)
 *  - "phone": shuffles audio files from a folder the user picked
 *  - "app": an energetic electronic beat generated on the device (no copyright issues)
 * The music is ducked to 20% while the coach speaks, and paused with the session.
 */
class Music(private val ctx: Context, private val scope: CoroutineScope, private val mode: String, folder: String?, private val baseVolume: Float) {
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var track: AudioTrack? = null
    private var player: MediaPlayer? = null
    private var files: List<Uri> = emptyList()
    private var index = 0
    private var ducked = false
    private var paused = false
    private var stopped = false
    private val folderUri = folder?.let { Uri.parse(it) }

    private fun vol() = baseVolume * (if (ducked) 0.2f else 1f)

    fun start() {
        when (mode) {
            "app" -> scope.launch {
                val order = BeatSynth.styles.shuffled()
                var k = 0
                while (true) {
                    val pcm = withContext(Dispatchers.Default) { BeatSynth.render(order[k % order.size]) }
                    val old = track
                    runCatching {
                        val t = AudioTrack.Builder()
                            .setAudioAttributes(attrs)
                            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(BeatSynth.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(pcm.size * 2)
                            .build()
                        t.write(pcm, 0, pcm.size)
                        t.setLoopPoints(0, pcm.size, -1)
                        t.setVolume(vol())
                        if (stopped) { t.release(); return@launch }
                        if (!paused) t.play()
                        track = t
                    }
                    runCatching { old?.stop(); old?.release() }
                    k++
                    delay(150_000) // a new style every two and a half minutes
                    if (stopped) return@launch
                }
            }
            "phone" -> scope.launch {
                files = withContext(Dispatchers.IO) { listAudio() }.shuffled()
                playNext()
            }
        }
    }

    private fun listAudio(): List<Uri> {
        val tree = folderUri ?: return emptyList()
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val out = mutableListOf<Uri>()
            ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: ""
                    if (mime.startsWith("audio/")) out += DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun playNext() {
        if (files.isEmpty()) return
        val uri = files[index % files.size]; index++
        runCatching {
            player?.release()
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(ctx, uri)
            mp.setOnCompletionListener { playNext() }
            mp.setOnErrorListener { _, _, _ -> playNext(); true }
            mp.prepare()
            mp.setVolume(vol(), vol())
            if (!paused) mp.start()
            player = mp
        }.onFailure { if (files.size > 1 && index < files.size * 2) playNext() }
    }

    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        runCatching { track?.setVolume(vol()); player?.setVolume(vol(), vol()) }
    }

    fun pause(p: Boolean) {
        paused = p
        runCatching {
            if (p) { track?.pause(); player?.pause() } else { track?.play(); player?.start() }
        }
    }

    fun stop() {
        stopped = true
        runCatching { track?.stop(); track?.release() }; track = null
        runCatching { player?.release() }; player = null
    }
}

/** A music style for the on-device generator. Patterns are 16 steps (sixteenth notes) per bar. */
data class Style(
    val name: String, val bpm: Double, val chords: List<Pair<Int, Boolean>>, // (MIDI root, minor?) one chord per 2 bars
    val kick: Set<Int>, val snare: Set<Int>, val hatEvery: Int, val openHat: Set<Int>,
    val bass: String, val lead: String, val swing: Double = 0.0, val drive: Boolean = false,
)

object BeatSynth {
    const val SR = 22050

    val styles = listOf(
        Style("House", 126.0, listOf(57 to true, 53 to false, 48 to false, 55 to false), setOf(0, 4, 8, 12), setOf(4, 12), 2, setOf(2, 6, 10, 14), "offbeat", "pluck"),
        Style("Drum and Bass", 172.0, listOf(50 to true, 46 to false, 53 to false, 48 to false), setOf(0, 10), setOf(4, 12), 1, emptySet(), "reese", "pad"),
        Style("Trap", 140.0, listOf(48 to true, 44 to false, 51 to false, 46 to false), setOf(0, 7, 10), setOf(8), 1, emptySet(), "808", "bell"),
        Style("Rock", 124.0, listOf(52 to false, 48 to false, 55 to false, 50 to false), setOf(0, 6, 8), setOf(4, 12), 2, emptySet(), "eighths", "power", drive = true),
        Style("Synthwave", 110.0, listOf(52 to true, 48 to false, 55 to false, 50 to false), setOf(0, 4, 8, 12), setOf(4, 12), 2, emptySet(), "octave", "saw"),
        Style("Boom Bap", 92.0, listOf(50 to true, 55 to true, 46 to false, 45 to false), setOf(0, 7, 10), setOf(4, 12), 2, emptySet(), "kickroot", "keys", swing = 0.18),
    )

    private fun hz(midi: Double) = 440.0 * Math.pow(2.0, (midi - 69) / 12)

    fun render(style: Style): ShortArray {
        val step = 60.0 / style.bpm / 4
        val steps = 8 * 16
        val n = (steps * step * SR).toInt() + SR / 4
        val buf = FloatArray(n)
        val rnd = Random(style.name.hashCode())
        for (s in 0 until steps) {
            val swingShift = if (s % 2 == 1) style.swing * step else 0.0
            val t0 = ((s * step + swingShift) * SR).toInt()
            val inBar = s % 16
            val (root, minor) = style.chords[(s / 32) % style.chords.size]
            val third = if (minor) 3 else 4
            if (inBar in style.kick) kick(buf, t0)
            if (inBar in style.snare) snare(buf, t0, rnd)
            if (inBar % style.hatEvery == 0 || inBar in style.openHat) hat(buf, t0, rnd, if (inBar in style.openHat) 0.22f else 0.07f, if (inBar in style.openHat) 0.03 else 0.008)
            if (style.lead == "bell" && inBar >= 12 && s % 32 >= 28) hat(buf, t0 + (step * SR / 2).toInt(), rnd, 0.06f, 0.006) // trap hat roll
            val bassRoot = hz(root - 24.0)
            when (style.bass) {
                "offbeat" -> if (inBar % 4 == 2) tone(buf, t0, bassRoot * 2, step * 1.8, 0.3, "saw", 0.12)
                "reese" -> if (inBar == 0 || inBar == 8) tone(buf, t0, bassRoot, step * 7.5, 0.28, "reese", 0.6)
                "808" -> if (inBar in style.kick) tone(buf, t0, bassRoot, step * 3.5, 0.45, "sine", 0.35)
                "eighths" -> if (inBar % 2 == 0) tone(buf, t0, bassRoot, step * 1.8, 0.3, "saw", 0.2)
                "octave" -> tone(buf, t0, if (inBar % 2 == 0) bassRoot else bassRoot * 2, step * 0.9, 0.25, "saw", 0.08)
                "kickroot" -> if (inBar in style.kick) tone(buf, t0, bassRoot, step * 3.0, 0.32, "sine", 0.3)
            }
            val chord = listOf(0, third, 7)
            when (style.lead) {
                "pluck" -> if (inBar % 2 == 1) tone(buf, t0, hz(root + 12.0 + chord[(s / 2) % 3]), 0.25, 0.1, "sine", 0.07)
                "pad" -> if (inBar == 0 && s % 32 == 0) chord.forEach { tone(buf, t0, hz(root + 12.0 + it), step * 31, 0.05, "saw", 2.5) }
                "bell" -> if (inBar == 3 || inBar == 11) tone(buf, t0, hz(root + 24.0 + chord[(s / 8) % 3]), 0.5, 0.09, "bell", 0.25)
                "power" -> if (inBar == 0 || inBar == 6 || inBar == 8) listOf(0, 7, 12).forEach { tone(buf, t0, hz(root + it.toDouble()), step * 1.6, 0.07, "saw", 0.3) }
                "saw" -> if (inBar % 2 == 0) tone(buf, t0, hz(root + 12.0 + listOf(0, third, 7, 12)[(s / 2) % 4]), step * 1.8, 0.08, "saw", 0.15)
                "keys" -> if (inBar == 0 || inBar == 10) chord.forEach { tone(buf, t0, hz(root + 12.0 + it), 0.6, 0.07, "sine", 0.3) }
            }
        }
        if (style.drive) for (i in buf.indices) buf[i] = kotlin.math.tanh(buf[i] * 1.6f)
        var peak = 0.001f
        for (x in buf) if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
        val g = 0.85f / peak
        val loopLen = (steps * step * SR).toInt()
        return ShortArray(loopLen) { (buf[it] * g * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun kick(b: FloatArray, t0: Int) {
        var ph = 0.0
        for (i in 0 until (0.2 * SR).toInt()) {
            if (t0 + i >= b.size) return
            val f = 48 + 100 * exp(-i / (0.012 * SR))
            ph += 2 * PI * f / SR
            b[t0 + i] += (sin(ph) * exp(-i / (0.05 * SR)) * 0.9).toFloat()
        }
    }

    private fun snare(b: FloatArray, t0: Int, r: Random) {
        for (i in 0 until (0.16 * SR).toInt()) {
            if (t0 + i >= b.size) return
            val noise = (r.nextFloat() * 2 - 1) * exp(-i / (0.035 * SR)).toFloat() * 0.45f
            val body = (sin(2 * PI * 185 * i / SR) * exp(-i / (0.02 * SR)) * 0.3).toFloat()
            b[t0 + i] += noise + body
        }
    }

    private fun hat(b: FloatArray, t0: Int, r: Random, amp: Float, decay: Double) {
        var prev = 0f
        for (i in 0 until (decay * 5 * SR).toInt()) {
            if (t0 + i >= b.size) return
            val x = r.nextFloat() * 2 - 1
            b[t0 + i] += (x - prev) * amp * exp(-i / (decay * SR)).toFloat()
            prev = x
        }
    }

    /** Generic voice: sine, saw (3 harmonics), reese (two detuned saws), bell (inharmonic). */
    private fun tone(b: FloatArray, t0: Int, f: Double, dur: Double, amp: Double, wave: String, decay: Double) {
        for (i in 0 until (dur * SR).toInt()) {
            if (t0 + i >= b.size) return
            val t = i.toDouble() / SR
            val env = minOf(1.0, i / (0.004 * SR)) * exp(-t / decay) * minOf(1.0, (dur - t) / 0.01)
            val w = 2 * PI * f * t
            val v = when (wave) {
                "saw" -> sin(w) + 0.5 * sin(2 * w) + 0.33 * sin(3 * w)
                "reese" -> sin(w) + 0.5 * sin(2 * w) + sin(w * 1.007) + 0.5 * sin(2 * w * 1.007)
                "bell" -> sin(w) + 0.5 * sin(w * 2.76) + 0.25 * sin(w * 5.4)
                else -> sin(w)
            }
            b[t0 + i] += (v * env * amp).toFloat()
        }
    }
}
