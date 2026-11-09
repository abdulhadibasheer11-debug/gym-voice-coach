package com.abdelhadi.gymcoach

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Experimental: record a human voice for each coach sentence.
 * Flow per sentence: hear it → press Record (a beep, then speak) → press Stop → listen → next.
 */
class StudioActivity : BaseActivity() {
    private val scope = MainScope()
    private val phrases = CoachLines.recordable()
    private var i = 0
    /** todo = unrecorded only, done = recorded only (to redo one), all */
    private var filter = "todo"
    private fun matches(j: Int) = when (filter) {
        "todo" -> !VoicePack.has(this, phrases[j]); "done" -> VoicePack.has(this, phrases[j]); else -> true
    }
    private var recorder: PcmRecorder? = null
    private lateinit var prefs: Prefs
    private lateinit var progressBar: BarView
    private var player: MediaPlayer? = null
    private var speaker: Speaker? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var counter: TextView
    private lateinit var phrase: TextView
    private lateinit var state: TextView
    private lateinit var recBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "استوديو التسجيل", big = true))
        col.addView(Ui.styled(this, "اسمع الجملة، ثم اضغط تسجيل وقلها بعد الصفارة بحماس وبلهجتك. التسجيل يتوقف وحده عندما تسكت.", 17f, color = Ui.MUTED))

        // the current sentence
        val card = Ui.card(this)
        counter = Ui.styled(this, "", 16f, color = Ui.MUTED).also { card.addView(it) }
        progressBar = BarView(this).also {
            card.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)).apply { setMargins(0, Ui.dp(this@StudioActivity, 6), 0, Ui.dp(this@StudioActivity, 10)) })
        }
        phrase = Ui.styled(this, "", 30f, bold = true).also { card.addView(it) }
        state = Ui.styled(this, "", 18f).also { card.addView(it) }
        col.addView(card)

        // main controls
        recBtn = Ui.button(this, "تسجيل", primary = true, iconRes = R.drawable.ic_mic) { toggleRecord() }
        col.addView(recBtn)
        col.addView(Ui.row(this,
            Ui.button(this, "اسمع الجملة", compact = true, iconRes = R.drawable.ic_replay, desc = "اسمع الجملة بصوت الهاتف") { hear() },
            Ui.button(this, "اسمع تسجيلي", compact = true, iconRes = R.drawable.ic_play) { playMine() },
        ))
        col.addView(Ui.row(this,
            Ui.button(this, "السابقة", compact = true, desc = "الجملة السابقة") { move(-1) },
            Ui.button(this, "التالية", compact = true, desc = "الجملة التالية") { move(1) },
        ))
        col.addView(Ui.button(this, "خيارات هذه الجملة", iconRes = R.drawable.ic_tune) { sentenceOptions() })

        // finding sentences
        col.addView(Ui.section(this, "التنقل بين الجمل"))
        col.addView(Ui.radioGroup(this, "التالية والسابقة تنتقل إلى", listOf("todo" to "الجمل غير المسجّلة فقط", "done" to "المسجّلة فقط، لإعادة إحداها", "all" to "كل الجمل"), filter) {
            filter = it; jumpToFirst()
        })
        col.addView(Ui.button(this, "قائمة كل الجمل", iconRes = R.drawable.ic_list) { pickFromList() })

        col.addView(Ui.section(this, "النتيجة والإعدادات"))
        col.addView(Ui.button(this, "اسمع عيّنة من الحصة بصوتك", iconRes = R.drawable.ic_play) { sample() })
        col.addView(Ui.button(this, "الجودة والمشاركة", iconRes = R.drawable.ic_tune, desc = "إعدادات جودة التسجيل، ومشاركة حزمة الصوت واستيرادها") {
            stopRecording(save = false); startActivity(Intent(this, StudioSettingsActivity::class.java))
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        jumpToFirst(speak = false)
        scope.launch { speaker = Speaker(this@StudioActivity).also { it.init() } }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    override fun onResume() { super.onResume(); if (::phrase.isInitialized) render(false) } // a pack may have been imported

    private fun announce(t: String) = phrase.announceForAccessibility(t)

    /** Less frequent actions for the current sentence, in one place. */
    private fun sentenceOptions() {
        val t = phrases[i]
        if (!VoicePack.has(this, t)) { announce("هذه الجملة غير مسجّلة بعد"); return }
        val both = VoicePack.original(this, t).exists() && VoicePack.hasEnhanced(this, t)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (both) {
            items += "اسمع الأصلي بدون تحسين" to { playOriginal() }
            items += (if (VoicePack.usesOriginal(this, t)) "استخدم المحسّن لهذه الجملة" else "استخدم الأصلي لهذه الجملة") to { toggleVersion() }
        }
        items += "حذف تسجيل هذه الجملة" to {
            Ui.confirm(this, "حذف تسجيل: $t؟") { VoicePack.delete(this, t); render(false); announce("تم الحذف") }
        }
        android.app.AlertDialog.Builder(this).setTitle(t)
            .setItems(items.map { it.first }.toTypedArray()) { _, w -> items[w].second() }
            .setNegativeButton("إغلاق", null).show()
    }

    private fun jumpToFirst(speak: Boolean = true) {
        val j = phrases.indices.firstOrNull { matches(it) }
        if (j == null) {
            render(false)
            announce(if (filter == "todo") "كل الجمل مسجّلة. ممتاز!" else "لا توجد جمل مسجّلة بعد.")
        } else { i = j; render(speak) }
    }

    private fun pickFromList() {
        val items = phrases.mapIndexed { j, t -> (if (VoicePack.has(this, t)) "مسجّلة: " else "غير مسجّلة: ") + t }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("كل الجمل").setItems(items) { _, w ->
            stopRecording(save = false); i = w; filter = "all"; render(true)
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun render(speakIt: Boolean) {
        val done = VoicePack.recordedCount(this)
        counter.text = "الجملة ${i + 1} من ${phrases.size} · المسجّل $done، والباقي ${phrases.size - done}"
        phrase.text = phrases[i]
        val t = phrases[i]
        val has = VoicePack.has(this, t)
        val hasOrig = VoicePack.original(this, t).exists()
        val both = hasOrig && VoicePack.hasEnhanced(this, t)
        state.text = when {
            !has -> "غير مسجّلة"
            !hasOrig -> "مسجّلة"
            both && VoicePack.usesOriginal(this, t) -> "مسجّلة، يُستخدم الأصلي"
            both -> "مسجّلة، يُستخدم المحسّن"
            else -> "مسجّلة بدون تحسين"
        }
        recBtn.text = if (recorder != null) "إيقاف التسجيل" else if (has) "أعد التسجيل" else "تسجيل"
        progressBar.fraction = done.toFloat() / phrases.size
        if (speakIt) phrase.announceForAccessibility("${phrases[i]}، ${state.text}")
    }

    private fun move(d: Int) {
        stopRecording(save = false)
        var j = i
        repeat(phrases.size) {
            j = (j + d).mod(phrases.size)
            if (matches(j)) { i = j; render(true); return }
        }
        announce(if (filter == "todo") "لا توجد جمل أخرى غير مسجّلة." else "لا توجد جمل أخرى.")
    }

    private fun hear() {
        scope.launch { speaker?.say(phrases[i], record = false, interrupt = true) }
    }

    private fun toggleRecord() { if (recorder == null) startRecording() else stopRecording(save = true) }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        player?.release(); player = null
        speaker?.stop()
        val text = phrases[i]
        val rec = PcmRecorder(prefs.studioNoise)
        recorder = rec
        recBtn.text = "إيقاف التسجيل"
        scope.launch {
            speaker?.beep(Beep.COUNTDOWN)
            delay(450) // let the beep finish so it is not recorded
            if (recorder !== rec) return@launch
            state.text = "جاري التسجيل… تكلّم الآن"
            val r = rec.record()
            recorder = null
            when {
                r == null -> { render(false); announce("تعذر بدء التسجيل") }
                rec.discard || (rec.stop && r.pcm.size < r.rate / 4) -> render(false) // cancelled
                !r.heardSpeech || r.pcm.size < r.rate / 2 -> { render(false); announce("لم أسمع صوتاً واضحاً، حاول مرة أخرى وقرّب الهاتف") }
                else -> {
                    state.text = "جاري الحفظ…"
                    val enhanced = if (prefs.studioEnhance) withContext(Dispatchers.Default) { AudioFx.process(r.pcm, r.rate) } else null
                    withContext(Dispatchers.IO) { VoicePack.save(this@StudioActivity, text, r.pcm, enhanced, r.rate) }
                    if (phrases[i] == text) {
                        render(false)
                        announce(if (filter == "todo") "تم الحفظ. اضغط الجملة التالية للمتابعة." else "تم الحفظ")
                        playMine()
                    }
                }
            }
        }
    }

    /** Stops the current recording; with save = false the recording is discarded. */
    private fun stopRecording(save: Boolean) {
        val r = recorder ?: return
        if (!save) { recorder = null; r.discard = true }
        r.stop = true
        if (!save) render(false)
    }

    private fun playFile(f: java.io.File) {
        runCatching {
            player?.release()
            player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        }
    }

    private fun playOriginal() {
        val f = VoicePack.original(this, phrases[i])
        if (f.exists()) playFile(f) else announce("لا يوجد أصلي لهذه الجملة")
    }

    private fun toggleVersion() {
        val t = phrases[i]
        val toOriginal = !VoicePack.usesOriginal(this, t)
        VoicePack.useOriginal(this, t, toOriginal)
        render(false)
        announce(if (toOriginal) "سيُستخدم الأصلي لهذه الجملة" else "سيُستخدم المحسّن لهذه الجملة")
        playMine()
    }

    /** A short piece of a session with the recorded voice: start, counting, encouragement, end of set, rest. */
    private fun sample() {
        stopRecording(save = false)
        scope.launch {
            val sp = speaker ?: return@launch
            val was = sp.useRecorded
            sp.useRecorded = true
            sp.rate = prefs.speechRate
            val ar = prefs.countLang == "ar"
            sp.stop()
            sp.beep(Beep.GO)
            sp.count(if (ar) "ابدأ!" else "Go!", ar, flush = true, r = 1.2f)
            delay(900)
            for (n in 1..6) {
                sp.count(if (ar) n.toString() else NumberWords.en(n), ar, flush = true, r = sp.rate * 1.15f, clipKey = n.toString())
                val extra = when (n) { 3 -> CoachLines.midSet().first(); 4 -> CoachLines.push().first(); 6 -> CoachLines.lastRep().first(); else -> null }
                if (extra != null) sp.count(extra, extra.any { it in '؀'..'ۿ' }, flush = false, r = sp.rate * 1.15f)
                delay(1700)
            }
            sp.beep(Beep.DONE)
            sp.say(CoachLines.setDone(), record = false)
            sp.say(CoachLines.restMotivation(), record = false)
            sp.say("عشر ثواني، جهّز نفسك.", record = false)
            sp.useRecorded = was
        }
    }

    private fun playMine() {
        val f = VoicePack.file(this, phrases[i])
        if (!f.exists()) { announce("لا يوجد تسجيل لهذه الجملة"); return }
        playFile(f)
    }

    override fun onDestroy() {
        stopRecording(save = false)
        player?.release(); speaker?.shutdown(); scope.cancel()
        super.onDestroy()
    }
}
