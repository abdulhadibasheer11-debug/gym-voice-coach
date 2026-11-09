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
    private lateinit var versionBtn: Button
    private lateinit var origBtn: Button
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
        col.addView(Ui.heading(this, "استوديو تسجيل صوت المدرب، تجريبي", big = true))
        col.addView(Ui.styled(this, "لكل جملة: اسمعها، ثم اضغط تسجيل وانتظر الصفارة، وقلها بحماس. التسجيل يتوقف وحده عندما تسكت، أو اضغط إيقاف. يمكنك قولها بلهجتك. التسجيلات تُستخدم بدل صوت الهاتف عند تفعيل خيار الصوت البشري.", 18f, color = Ui.MUTED))
        col.addView(Ui.radioGroup(this, "عرض الجمل", listOf("todo" to "غير المسجّلة فقط", "done" to "المسجّلة، لإعادة تسجيل إحداها", "all" to "كل الجمل"), filter) {
            filter = it; jumpToFirst()
        })
        col.addView(Ui.button(this, "قائمة الجمل، للانتقال إلى أي جملة") { pickFromList() })
        val card = Ui.card(this)
        counter = Ui.styled(this, "", 18f, color = Ui.MUTED).also { card.addView(it) }
        phrase = Ui.styled(this, "", 30f, bold = true).also { card.addView(it) }
        state = Ui.styled(this, "", 18f).also { card.addView(it) }
        col.addView(card)
        col.addView(Ui.button(this, "اسمع الجملة") { hear() })
        recBtn = Ui.button(this, "تسجيل", primary = true) { toggleRecord() }
        col.addView(recBtn)
        col.addView(Ui.button(this, "اسمع تسجيلي") { playMine() })
        origBtn = Ui.button(this, "اسمع الأصلي بدون تحسين") { playOriginal() }.also { col.addView(it) }
        versionBtn = Ui.button(this, "") { toggleVersion() }.also { col.addView(it) }
        col.addView(Ui.button(this, "الجملة التالية") { move(1) })
        col.addView(Ui.button(this, "الجملة السابقة") { move(-1) })
        col.addView(Ui.button(this, "اسمع عيّنة من الحصة بصوتك", iconRes = R.drawable.ic_play) { sample() })

        col.addView(Ui.heading(this, "جودة التسجيل"))
        col.addView(Ui.styled(this, "تحسين الصوت تلقائياً", 20f, bold = true))
        col.addView(Ui.radioGroup(this, "تحسين الصوت تلقائياً", listOf(
            true to "نعم: قص الصمت قبل الكلام وبعده، وتوحيد مستوى الصوت، وإزالة الطنين المنخفض",
            false to "لا، احفظ التسجيل كما هو"), prefs.studioEnhance) { prefs.studioEnhance = it })
        col.addView(Ui.styled(this, "الأصلي يُحفظ دائماً، وتستطيع لكل جملة سماعه واختياره بدل المحسّن.", 16f, color = Ui.MUTED))
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            col.addView(Ui.styled(this, "كاتم الضوضاء من الهاتف أثناء التسجيل", 20f, bold = true))
            col.addView(Ui.radioGroup(this, "كاتم الضوضاء من الهاتف", listOf(false to "لا، الأفضل في مكان هادئ", true to "نعم، للأماكن المزعجة"), prefs.studioNoise) { prefs.studioNoise = it })
            col.addView(Ui.styled(this, "قد يجعل الصوت معدنياً في بعض الهواتف. جرّبه على جملة واحدة واسمعها قبل اعتماده.", 16f, color = Ui.MUTED))
        }

        col.addView(Ui.heading(this, "مشاركة الصوت مع الأصدقاء"))
        col.addView(Ui.styled(this, "بعد أن تسجّل، أرسل الحزمة لأصدقائك بملف واحد عبر واتساب أو غيره، ويستوردونها من نفس الشاشة. استأذن صاحب الصوت قبل المشاركة.", 17f, color = Ui.MUTED))
        col.addView(Ui.button(this, "مشاركة حزمة الصوت") { sharePack() })
        col.addView(Ui.button(this, "استيراد حزمة صوت من ملف") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 9)
        })
        col.addView(Ui.button(this, "حذف تسجيل هذه الجملة") {
            VoicePack.delete(this, phrases[i]); render(false); announce("تم الحذف")
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        jumpToFirst(speak = false)
        scope.launch { speaker = Speaker(this@StudioActivity).also { it.init() } }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun announce(t: String) = phrase.announceForAccessibility(t)

    private fun sharePack() {
        val f = VoicePack.export(this, "صوت المدرب")
        if (f == null) { announce("لا توجد تسجيلات للمشاركة بعد"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "حزمة صوت لتطبيق مدرّب الجيم الصوتي. افتح التطبيق، ثم استوديو التسجيل، ثم استيراد حزمة صوت.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة حزمة الصوت"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 9 && resultCode == RESULT_OK) {
            val (n, name) = runCatching { VoicePack.import(this, uri) }.getOrDefault(0 to null)
            render(false)
            if (n > 0) {
                Prefs(this).useRecorded = true
                announce("تم استيراد $n تسجيل${name?.let { "، $it" } ?: ""}. تم تفعيل الصوت البشري.")
            } else announce("هذا الملف ليس حزمة صوت صالحة")
        }
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
        counter.text = "الجملة ${i + 1} من ${phrases.size}. المسجّل: $done، والباقي: ${phrases.size - done}"
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
        origBtn.visibility = if (both) View.VISIBLE else View.GONE
        versionBtn.visibility = if (both) View.VISIBLE else View.GONE
        versionBtn.text = if (VoicePack.usesOriginal(this, t)) "استخدم المحسّن لهذه الجملة" else "استخدم الأصلي لهذه الجملة"
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
