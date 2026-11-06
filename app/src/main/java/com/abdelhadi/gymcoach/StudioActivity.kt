package com.abdelhadi.gymcoach

import android.Manifest
import android.app.Activity
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
class StudioActivity : Activity() {
    private val scope = MainScope()
    private val phrases = CoachLines.recordable()
    private var i = 0
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
        col.addView(Ui.button(this, "انتقل لأول جملة غير مسجلة") {
            val j = phrases.indexOfFirst { !VoicePack.has(this, it) }
            if (j >= 0) { i = j; render(true) } else announce("كل الجمل مسجلة. ممتاز!")
        })
        col.addView(Ui.button(this, "حذف تسجيل هذه الجملة") {
            VoicePack.file(this, phrases[i]).delete(); render(false); announce("تم الحذف")
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        render(false)
        scope.launch { speaker = Speaker(this@StudioActivity).also { it.init() } }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
    }

    private fun announce(t: String) = phrase.announceForAccessibility(t)

    private fun render(speakIt: Boolean) {
        val done = VoicePack.recordedCount(this)
        counter.text = "الجملة ${i + 1} من ${phrases.size}. المسجّل: $done"
        phrase.text = phrases[i]
        state.text = if (VoicePack.has(this, phrases[i])) "مسجّلة" else "غير مسجّلة"
        if (speakIt) phrase.announceForAccessibility("${phrases[i]}، ${state.text}")
    }

    private fun move(d: Int) {
        stopRecording(save = false)
        i = (i + d).mod(phrases.size); render(true)
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
            render(false); announce("تم الحفظ")
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
