package com.abdelhadi.gymcoach

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView

/**
 * Control screen during a session. Texts are updated silently (no live regions) so TalkBack never talks
 * over the coach voice; the user can swipe to them to read the current status.
 */
class WorkoutActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var remaining: TextView
    private lateinit var pauseBtn: Button
    private var seen = -1L
    private val openedAt = System.currentTimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            // the service needs a moment to start; only close once it has finished
            if (!Session.running && System.currentTimeMillis() - openedAt > 6000) { finish(); return }
            if (Session.version != seen) {
                seen = Session.version
                setIfChanged(status, Session.status)
                setIfChanged(progress, Session.progress)
                setIfChanged(remaining, if (Session.remainingSec >= 0) "متبقي في هذه الخطوة ${Session.remainingSec} ثانية" else "")
                setIfChanged(pauseBtn, if (Session.paused) "استئناف" else "إيقاف مؤقت")
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun setIfChanged(v: TextView, t: String) { if (v.text.toString() != t) v.text = t }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "الحصة جارية", big = true))
        status = Ui.text(this, "", 24f).also { col.addView(it) }
        progress = Ui.text(this, "", 19f).also { col.addView(it) }
        remaining = Ui.text(this, "", 19f).also { col.addView(it) }
        pauseBtn = Ui.button(this, "إيقاف مؤقت", primary = true) { WorkoutService.send(this, WorkoutService.ACTION_PAUSE) }
        col.addView(pauseBtn)
        col.addView(Ui.button(this, "إعادة آخر تعليمات") { WorkoutService.send(this, WorkoutService.ACTION_REPEAT) })
        col.addView(Ui.button(this, "أحتاج راحة أكثر، ثلاثين ثانية") { WorkoutService.send(this, WorkoutService.ACTION_MORE_REST) })
        col.addView(Ui.button(this, "تخطي الخطوة الحالية") { WorkoutService.send(this, WorkoutService.ACTION_SKIP) })
        col.addView(Ui.button(this, "تخطي إلى التمرين التالي") { WorkoutService.send(this, WorkoutService.ACTION_SKIP_EXERCISE) })
        col.addView(Ui.button(this, "إنهاء الحصة، اضغط مرتين") { WorkoutService.send(this, WorkoutService.ACTION_STOP) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    override fun onResume() { super.onResume(); seen = -1; handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
