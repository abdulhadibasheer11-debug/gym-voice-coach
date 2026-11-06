package com.abdelhadi.gymcoach

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text-to-speech with mixed Arabic/English segments.
 * Every segment is spoken in its own language. Uses the "navigation guidance" audio usage so that
 * music is ducked rather than paused, and TalkBack (accessibility stream) is not interrupted.
 * Later, pre-recorded clips can be plugged in here (look up a clip by text before falling back to TTS).
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

    var rate = 1.0f
    var arabicAvailable = false
        private set
    var englishAvailable = false
        private set
    var lastSpoken: List<String> = emptyList()
        private set
    var onText: ((String) -> Unit)? = null

    private val ar = Locale("ar")
    private val en = Locale.US

    suspend fun init(): Boolean {
        val ready = CompletableDeferred<Boolean>()
        tts = TextToSpeech(ctx.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(8000) { ready.await() } ?: false
        if (!ok) return false
        tts?.apply {
            setAudioAttributes(attrs)
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

    private fun enqueue(text: String, lang: Locale, flush: Boolean, r: Float): CompletableDeferred<Unit> {
        val id = "u" + ids.incrementAndGet()
        val d = CompletableDeferred<Unit>()
        pending[id] = d
        val t = tts
        if (t == null) { d.complete(Unit); return d }
        t.setLanguage(lang)
        t.setSpeechRate(r)
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        return d
    }

    /** Speak parts in order and wait until finished. */
    suspend fun say(vararg parts: String, record: Boolean = true, interrupt: Boolean = false) {
        val list = parts.filter { it.isNotBlank() }
        if (list.isEmpty()) return
        if (record) { lastSpoken = list; onText?.invoke(list.joinToString(" ")) }
        audio.requestAudioFocus(focus)
        var last: CompletableDeferred<Unit>? = null
        list.forEachIndexed { i, p ->
            last = enqueue(p, if (isArabic(p)) ar else en, interrupt && i == 0, rate)
        }
        val estimate = (list.sumOf { it.length } * 110L / rate.coerceAtLeast(0.5f)).toLong() + 3000L
        withTimeoutOrNull(estimate) { last?.await() }
        audio.abandonAudioFocusRequest(focus)
    }

    suspend fun say(parts: List<String>, record: Boolean = true, interrupt: Boolean = false) =
        say(*parts.toTypedArray(), record = record, interrupt = interrupt)

    /** Fire-and-forget count word (does not wait). */
    fun count(word: String, arabic: Boolean, flush: Boolean, r: Float) {
        enqueue(word, if (arabic) ar else en, flush, r)
    }

    fun stop() { tts?.stop(); pending.values.forEach { it.complete(Unit) }; pending.clear() }

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
        tts?.shutdown(); tts = null
        tone?.release(); tone = null
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }
}

enum class Beep { COUNTDOWN, GO, TICK, DONE }
