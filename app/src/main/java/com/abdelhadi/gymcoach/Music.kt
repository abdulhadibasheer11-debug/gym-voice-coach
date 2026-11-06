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
    private val folderUri = folder?.let { Uri.parse(it) }

    private fun vol() = baseVolume * (if (ducked) 0.2f else 1f)

    fun start() {
        when (mode) {
            "app" -> scope.launch {
                val pcm = withContext(Dispatchers.Default) { BeatSynth.render() }
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
                    if (!paused) t.play()
                    track = t
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
        runCatching { track?.stop(); track?.release() }; track = null
        runCatching { player?.release() }; player = null
    }
}

/** Synthesises a 15-second, 126 BPM electronic loop: kick, snare, hats, bass and a plucked arpeggio. */
object BeatSynth {
    const val SR = 22050

    fun render(bpm: Double = 126.0): ShortArray {
        val step = 60.0 / bpm / 4
        val steps = 8 * 16
        val n = (steps * step * SR).toInt()
        val buf = FloatArray(n)
        val rnd = Random(7)
        val roots = doubleArrayOf(55.0, 43.65, 65.41, 49.0)                 // A1, F1, C2, G1 (2 bars each)
        val chords = arrayOf(doubleArrayOf(1.0, 1.189, 1.498), doubleArrayOf(1.0, 1.26, 1.498),
            doubleArrayOf(1.0, 1.26, 1.498), doubleArrayOf(1.0, 1.26, 1.498))
        for (s in 0 until steps) {
            val t0 = (s * step * SR).toInt()
            val inBar = s % 16
            val ci = (s / 32) % 4
            if (inBar % 4 == 0) kick(buf, t0)
            if (inBar == 4 || inBar == 12) snare(buf, t0, rnd)
            hat(buf, t0, rnd, if (inBar % 4 == 2) 0.22f else 0.08f)
            if (inBar % 2 == 0) bass(buf, t0, if (inBar % 4 == 2) roots[ci] * 2 else roots[ci], step * 1.8)
            if (inBar % 2 == 1) pluck(buf, t0, roots[ci] * 8 * chords[ci][(s / 2) % 3])
        }
        var peak = 0.001f
        for (x in buf) if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
        val g = 0.85f / peak
        return ShortArray(n) { (buf[it] * g * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
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

    private fun hat(b: FloatArray, t0: Int, r: Random, amp: Float) {
        var prev = 0f
        for (i in 0 until (0.035 * SR).toInt()) {
            if (t0 + i >= b.size) return
            val x = r.nextFloat() * 2 - 1
            b[t0 + i] += (x - prev) * amp * exp(-i / (0.008 * SR)).toFloat()
            prev = x
        }
    }

    private fun bass(b: FloatArray, t0: Int, f: Double, dur: Double) {
        for (i in 0 until (dur * SR).toInt()) {
            if (t0 + i >= b.size) return
            val t = i.toDouble() / SR
            val env = minOf(1.0, i / (0.005 * SR)) * exp(-t / 0.18)
            val w = 2 * PI * f * t
            b[t0 + i] += ((sin(w) + 0.5 * sin(2 * w) + 0.25 * sin(3 * w)) * env * 0.28).toFloat()
        }
    }

    private fun pluck(b: FloatArray, t0: Int, f: Double) {
        for (i in 0 until (0.25 * SR).toInt()) {
            if (t0 + i >= b.size) return
            val t = i.toDouble() / SR
            val w = 2 * PI * f * t
            b[t0 + i] += ((sin(w) + 0.3 * sin(2 * w)) * exp(-t / 0.07) * 0.12).toFloat()
        }
    }
}
