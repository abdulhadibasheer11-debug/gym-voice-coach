package com.abdelhadi.gymcoach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short, on-demand speech recognition (never always-on): used to log a weight after the first set,
 * and for a voice command after a double press of the headset button.
 * Must be used from the main thread (the service scope is Dispatchers.Main).
 */
class VoiceInput(private val ctx: Context, private val onListen: (Boolean) -> Unit) {
    val available: Boolean get() =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            SpeechRecognizer.isRecognitionAvailable(ctx)

    /**
     * Result of one listen. [silent] = the person said nothing; [failed] = the recogniser itself failed
     * (no internet, busy, no permission), so silence must not be read as "same as last time".
     */
    class Heard(val alts: List<String>?, val silent: Boolean, val failed: Boolean)

    /** Listen once; returns the recognised alternatives (best first), or null if nothing was understood. */
    suspend fun listen(maxMs: Long = 6000): List<String>? = hear(maxMs).alts

    suspend fun hear(maxMs: Long = 6000): Heard {
        if (!available) return Heard(null, silent = false, failed = true)
        val done = CompletableDeferred<Heard>()
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() ?: return Heard(null, false, true)
        var spoke = false
        onListen(true)
        try {
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val alts = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList()?.takeIf { it.isNotEmpty() }
                    done.complete(Heard(alts, silent = alts == null && !spoke, failed = false))
                }
                override fun onError(error: Int) {
                    val noSpeech = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || (error == SpeechRecognizer.ERROR_NO_MATCH && !spoke)
                    val notHeard = error == SpeechRecognizer.ERROR_NO_MATCH
                    done.complete(Heard(null, silent = noSpeech, failed = !noSpeech && !notHeard))
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() { spoke = true }
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-AE")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            })
            return withTimeoutOrNull(maxMs + 3000) { done.await() } ?: Heard(null, silent = !spoke, failed = false)
        } finally {
            runCatching { sr.cancel(); sr.destroy() }
            onListen(false)
        }
    }

    companion object {
        private val words = mapOf(
            "واحد" to 1.0, "اثنين" to 2.0, "ثلاثة" to 3.0, "خمسة" to 5.0, "سبعة" to 7.0, "عشرة" to 10.0,
            "خمستعش" to 15.0, "خمسة عشر" to 15.0, "عشرين" to 20.0, "خمسة وعشرين" to 25.0, "ثلاثين" to 30.0,
            "خمسة وثلاثين" to 35.0, "اربعين" to 40.0, "أربعين" to 40.0, "خمسين" to 50.0, "ستين" to 60.0,
            "سبعين" to 70.0, "ثمانين" to 80.0, "تسعين" to 90.0, "مية" to 100.0, "مئة" to 100.0, "مائة" to 100.0,
        )

        fun normalize(s: String): String {
            val sb = StringBuilder()
            for (ch in s) sb.append(when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠'); in '۰'..'۹' -> '0' + (ch - '۰'); '٫', '،' -> '.'
                'أ', 'إ', 'آ' -> 'ا'; 'ة' -> 'ه'; 'ى' -> 'ي'
                else -> ch
            })
            return sb.toString().lowercase().trim()
        }

        /** First number in any of the alternatives ("40", "٤٠", "اربعين", "22.5", "اثنين وعشرين ونص"). */
        fun number(alts: List<String>): Double? {
            for (a in alts) {
                val n = normalize(a)
                Regex("""(\d+(?:\.\d+)?)""").find(n)?.let { m ->
                    var v = m.value.toDouble()
                    if (n.contains("نص") && v % 1.0 == 0.0) v += 0.5
                    return v
                }
                // longest matching word first (e.g. "خمسة وعشرين" before "عشرين")
                words.entries.sortedByDescending { it.key.length }.firstOrNull { n.contains(normalize(it.key)) }?.let {
                    return it.value + if (n.contains("ونص")) 0.5 else 0.0
                }
            }
            return null
        }

        enum class Cmd { PAUSE, RESUME, SKIP, SKIP_EXERCISE, MORE_REST, REPEAT, TIME, WEIGHT, UNKNOWN }

        fun command(alts: List<String>): Cmd {
            for (a in alts) {
                val n = normalize(a)
                fun has(vararg k: String) = k.any { n.contains(normalize(it)) }
                when {
                    has("التمرين التالي", "التمرين الجاي", "next exercise") -> return Cmd.SKIP_EXERCISE
                    has("كم باقي", "كم بقي", "الوقت", "الساعه", "time") -> return Cmd.TIME
                    has("الوزن", "كيلو", "weight") -> return Cmd.WEIGHT
                    has("كمل", "نكمل", "استئناف", "استمر", "resume", "continue") -> return Cmd.RESUME
                    has("وقف", "توقف", "ايقاف", "pause", "stop") -> return Cmd.PAUSE
                    has("تخطي", "التالي", "سكب", "skip", "next") -> return Cmd.SKIP
                    has("راحه", "زيد", "زود", "وقت اكثر", "rest") -> return Cmd.MORE_REST
                    has("كرر", "اعد", "عيد", "repeat", "again") -> return Cmd.REPEAT
                }
            }
            return Cmd.UNKNOWN
        }
    }
}

/** Weights used per exercise (one entry per session) and the times extra rest was requested. */
object Weights {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE)
    private fun load(ctx: Context, key: String) =
        runCatching { JSONObject(sp(ctx).getString(key, "{}")!!) }.getOrDefault(JSONObject())

    data class Entry(val date: Long, val kg: Double)

    fun history(ctx: Context, id: String): List<Entry> {
        val a = load(ctx, "weights").optJSONArray(id) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { Entry(it.optLong(0), it.optDouble(1)) } }
    }

    fun all(ctx: Context): Map<String, List<Entry>> {
        val o = load(ctx, "weights")
        return o.keys().asSequence().associateWith { history(ctx, it) }.filterValues { it.isNotEmpty() }
    }

    fun last(ctx: Context, id: String): Double? = history(ctx, id).lastOrNull()?.kg

    /** Adds today's weight; a second entry within 6 hours replaces the first (same session). */
    fun add(ctx: Context, id: String, kg: Double, now: Long = System.currentTimeMillis()) {
        val o = load(ctx, "weights")
        val list = history(ctx, id).toMutableList()
        if (list.isNotEmpty() && now - list.last().date < 6 * 3600_000L) list.removeAt(list.size - 1)
        list += Entry(now, kg)
        val a = JSONArray(); list.takeLast(60).forEach { a.put(JSONArray().put(it.date).put(it.kg)) }
        o.put(id, a)
        sp(ctx).edit().putString("weights", o.toString()).apply()
    }

    /** Deletes one entry (by its date) or, with [date] = null, the whole history of this exercise. */
    fun remove(ctx: Context, id: String, date: Long?) {
        val o = load(ctx, "weights")
        if (date == null) o.remove(id) else {
            val keep = history(ctx, id).filter { it.date != date }
            if (keep.isEmpty()) o.remove(id) else {
                val a = JSONArray(); keep.forEach { a.put(JSONArray().put(it.date).put(it.kg)) }; o.put(id, a)
            }
        }
        sp(ctx).edit().putString("weights", o.toString()).apply()
    }

    /** Today's entry (same session window as [add]) if there is one. */
    fun today(ctx: Context, id: String, now: Long = System.currentTimeMillis()): Entry? =
        history(ctx, id).lastOrNull()?.takeIf { now - it.date < 6 * 3600_000L }

    fun addRestRequest(ctx: Context, id: String, now: Long = System.currentTimeMillis()) {
        val o = load(ctx, "restReq")
        val a = o.optJSONArray(id) ?: JSONArray()
        a.put(now)
        val t = JSONArray(); for (i in maxOf(0, a.length() - 20) until a.length()) t.put(a.optLong(i))
        o.put(id, t)
        sp(ctx).edit().putString("restReq", o.toString()).apply()
    }

    /** Distinct sessions (days) in the last [days] days in which extra rest was asked for this exercise. */
    fun restRequestSessions(ctx: Context, id: String, days: Int = 21, now: Long = System.currentTimeMillis()): Int {
        val a = load(ctx, "restReq").optJSONArray(id) ?: return 0
        val from = now - days * 86400_000L
        return (0 until a.length()).map { a.optLong(it) }.filter { it >= from }.map { it / 86400_000L }.distinct().size
    }

    fun kgAr(kg: Double): String {
        val s = if (kg % 1.0 == 0.0) kg.toInt().toString() else java.math.BigDecimal(kg).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return "$s كيلو"
    }

    /**
     * A short adaptive suggestion for this exercise, or null.
     * - Extra rest asked for in 2+ recent sessions -> the weight may be too heavy.
     * - Same weight for the last 3 sessions and no extra rest -> try a small increase.
     */
    fun suggestion(ctx: Context, id: String, now: Long = System.currentTimeMillis()): String? {
        val reqs = restRequestSessions(ctx, id, now = now)
        if (reqs >= 2) return "لاحظت أنك طلبت راحة إضافية في هذا التمرين في $reqs حصص مؤخراً. ممكن الوزن ثقيل شوي، جرّب تنقصه خطوة واحدة اليوم."
        val h = history(ctx, id).takeLast(3)
        if (h.size == 3 && h.all { it.kg == h[0].kg } && h[0].kg > 0 && restRequestSessions(ctx, id, now = now) == 0)
            return "ثابت على ${kgAr(h[0].kg)} في آخر 3 حصص بدون راحة إضافية. إذا كانت آخر التكرارات سهلة، جرّب تزيد خطوة واحدة."
        return null
    }
}
