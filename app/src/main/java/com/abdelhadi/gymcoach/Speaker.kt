package com.abdelhadi.gymcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The coach's voice. Each segment is spoken in its own language (Arabic / English).
 * If "use recorded voice" is on and a human recording exists for a segment, the recording is played instead.
 * Uses the "navigation guidance" audio usage: other apps' music is ducked, TalkBack is not interrupted.
 * onSpeech(true/false) lets the in-app music duck while the coach talks.
 */
class Speaker(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ids = AtomicInteger()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
    private val main = Handler(Looper.getMainLooper())

    var rate = 1.2f
    var useRecorded = false
    var onSpeech: ((Boolean) -> Unit)? = null
    var arabicAvailable = false
        private set
    var englishAvailable = false
        private set
    var lastSpoken: List<String> = emptyList()
        private set
    var onText: ((String) -> Unit)? = null

    private val ar = Locale("ar")
    private val en = Locale.US
    private var arVoice: Voice? = null
    private var enVoice: Voice? = null

    /** Pick an installed offline voice for a language explicitly, so English never falls back to the Arabic voice. */
    private fun pickVoice(vs: Set<Voice>, lang: String): Voice? = vs
        .filter { it.locale.language == lang && !it.isNetworkConnectionRequired && it.features?.contains("notInstalled") != true }
        .sortedWith(compareByDescending<Voice> { lang == "en" && it.locale.country == "US" }.thenByDescending { it.quality })
        .firstOrNull()

    suspend fun init(): Boolean {
        val ready = CompletableDeferred<Boolean>()
        tts = TextToSpeech(ctx.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(8000) { ready.await() } ?: false
        if (!ok) return false
        tts?.apply {
            setAudioAttributes(attrs)
            runCatching {
                val vs = voices ?: emptySet()
                arVoice = pickVoice(vs, "ar"); enVoice = pickVoice(vs, "en")
            }
            arabicAvailable = isLanguageAvailable(ar) >= TextToSpeech.LANG_AVAILABLE
            englishAvailable = isLanguageAvailable(en) >= TextToSpeech.LANG_AVAILABLE
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            })
        }
        return true
    }

    private fun isArabic(s: String) = s.any { it in '؀'..'ۿ' }

    // ---------- ducking of in-app music ----------
    private var duckUntil = 0L
    private val unduck = Runnable {
        if (SystemClock.elapsedRealtime() >= duckUntil) onSpeech?.invoke(false)
    }
    private fun duckFor(ms: Long) {
        duckUntil = maxOf(duckUntil, SystemClock.elapsedRealtime() + ms)
        onSpeech?.invoke(true)
        main.removeCallbacks(unduck)
        main.postDelayed(unduck, duckUntil - SystemClock.elapsedRealtime() + 50)
    }

    // ---------- low level ----------
    private fun enqueue(text: String, lang: Locale, flush: Boolean, r: Float): CompletableDeferred<Unit> {
        val id = "u" + ids.incrementAndGet()
        val d = CompletableDeferred<Unit>()
        pending[id] = d
        val t = tts
        if (t == null) { d.complete(Unit); return d }
        val v = if (lang == ar) arVoice else enVoice
        if (v != null) t.setVoice(v) else t.setLanguage(lang)
        t.setSpeechRate(r)
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        return d
    }

    private var player: MediaPlayer? = null
    private fun playClip(text: String, flush: Boolean): CompletableDeferred<Unit>? {
        if (!useRecorded || !VoicePack.has(ctx, text)) return null
        val d = CompletableDeferred<Unit>()
        runCatching {
            if (flush) { player?.release(); player = null }
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(VoicePack.file(ctx, text).absolutePath)
            mp.setOnCompletionListener { it.release(); if (player === it) player = null; d.complete(Unit) }
            mp.setOnErrorListener { p, _, _ -> p.release(); d.complete(Unit); true }
            mp.prepare()
            mp.start()
            player = mp
        }.onFailure { d.complete(Unit) }
        return d
    }

    /** Speak segments in order and wait until finished. */
    suspend fun say(vararg parts: String, record: Boolean = true, interrupt: Boolean = false) {
        val list = parts.filter { it.isNotBlank() }
        if (list.isEmpty()) return
        if (record) { lastSpoken = list; onText?.invoke(list.joinToString(" ")) }
        if (interrupt) stop()
        audio.requestAudioFocus(focus)
        for (p in list) {
            duckFor(p.length * 110L + 1500)
            val d = playClip(p, false) ?: enqueue(p, if (isArabic(p)) ar else en, false, rate)
            withTimeoutOrNull((p.length * 150f / rate.coerceAtLeast(0.5f)).toLong() + 4000L) { d.await() }
        }
        duckFor(400)
        audio.abandonAudioFocusRequest(focus)
    }

    suspend fun say(parts: List<String>, record: Boolean = true, interrupt: Boolean = false) =
        say(*parts.toTypedArray(), record = record, interrupt = interrupt)

    /** Fire-and-forget count word (does not wait). clipKey = text of the recorded clip (e.g. "7"). */
    fun count(word: String, arabic: Boolean, flush: Boolean, r: Float, clipKey: String = word) {
        duckFor(1300)
        playClip(clipKey, flush) ?: enqueue(word, if (arabic) ar else en, flush, r)
    }

    fun stop() {
        tts?.stop()
        runCatching { player?.release() }; player = null
        pending.values.forEach { it.complete(Unit) }; pending.clear()
    }

    fun beep(kind: Beep) {
        val g = tone ?: return
        runCatching {
            when (kind) {
                Beep.COUNTDOWN -> g.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                Beep.GO -> g.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                Beep.TICK -> g.startTone(ToneGenerator.TONE_CDMA_PIP, 60)
                Beep.DONE -> g.startTone(ToneGenerator.TONE_PROP_ACK, 250)
            }
        }
    }

    fun shutdown() {
        stop()
        main.removeCallbacks(unduck)
        tts?.shutdown(); tts = null
        tone?.release(); tone = null
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }
}

enum class Beep { COUNTDOWN, GO, TICK, DONE }
