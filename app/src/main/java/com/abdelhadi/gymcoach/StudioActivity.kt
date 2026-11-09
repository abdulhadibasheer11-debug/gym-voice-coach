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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var speaker: Speaker? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var counter: TextView
    private lateinit var phrase: TextView
    private lateinit var state: TextView
    private lateinit var recBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "استوديو تسجيل صوت المدرب، تجريبي", big = true))
        col.addView(Ui.styled(this, "لكل جملة: اسمعها، ثم اضغط تسجيل وانتظر الصفارة، وقلها بحماس، ثم اضغط إيقاف. يمكنك قولها بلهجتك. التسجيلات تُستخدم بدل صوت الهاتف عند تفعيل خيار الصوت البشري.", 18f, color = Ui.MUTED))
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
        col.addView(Ui.button(this, "الجملة التالية") { move(1) })
        col.addView(Ui.button(this, "الجملة السابقة") { move(-1) })
        col.addView(Ui.heading(this, "مشاركة الصوت مع الأصدقاء"))
        col.addView(Ui.styled(this, "بعد أن تسجّل، أرسل الحزمة لأصدقائك بملف واحد عبر واتساب أو غيره، ويستوردونها من نفس الشاشة. استأذن صاحب الصوت قبل المشاركة.", 17f, color = Ui.MUTED))
        col.addView(Ui.button(this, "مشاركة حزمة الصوت") { sharePack() })
        col.addView(Ui.button(this, "استيراد حزمة صوت من ملف") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 9)
        })
        col.addView(Ui.button(this, "حذف تسجيل هذه الجملة") {
            VoicePack.file(this, phrases[i]).delete(); render(false); announce("تم الحذف")
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
        state.text = if (VoicePack.has(this, phrases[i])) "مسجّلة" else "غير مسجّلة"
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
        scope.launch {
            speaker?.beep(Beep.COUNTDOWN)
            delay(450) // let the beep finish so it is not recorded
            val tmp = java.io.File(cacheDir, "rec.m4a")
            runCatching {
                @Suppress("DEPRECATION")
                val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(this@StudioActivity) else MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(96000)
                r.setOutputFile(tmp.absolutePath)
                r.prepare(); r.start()
                recorder = r
                recBtn.text = "إيقاف التسجيل"
                state.text = "جاري التسجيل…"
                handler.postDelayed({ if (recorder === r) stopRecording(save = true) }, 7000)
            }.onFailure { announce("تعذر بدء التسجيل") }
        }
    }

    private fun stopRecording(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        recBtn.text = "تسجيل"
        val tmp = java.io.File(cacheDir, "rec.m4a")
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        if (save && ok && tmp.length() > 800) {
            tmp.copyTo(VoicePack.file(this, phrases[i]), overwrite = true)
            render(false); announce(if (filter == "todo") "تم الحفظ. اضغط الجملة التالية للمتابعة." else "تم الحفظ")
            playMine()
        } else if (save) { render(false); announce("التسجيل قصير جداً، حاول مرة أخرى") }
    }

    private fun playMine() {
        val f = VoicePack.file(this, phrases[i])
        if (!f.exists()) { announce("لا يوجد تسجيل لهذه الجملة"); return }
        runCatching {
            player?.release()
            player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        }
    }

    override fun onDestroy() {
        stopRecording(save = false)
        player?.release(); speaker?.shutdown(); scope.cancel()
        super.onDestroy()
    }
}
