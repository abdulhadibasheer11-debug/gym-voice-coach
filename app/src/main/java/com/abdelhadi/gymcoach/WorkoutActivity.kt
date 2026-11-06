package com.abdelhadi.gymcoach

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Control screen during a session: a large colour-coded card (orange = set, green = rest, blue = moving,
 * yellow = warm-up) with a big rep count or countdown for sighted users, plus large buttons.
 * Texts are updated silently (no live regions) so TalkBack never talks over the coach.
 */
class WorkoutActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var heroCard: LinearLayout
    private lateinit var phaseLabel: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var big: TextView
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var pauseBtn: Button
    private lateinit var image: android.widget.ImageView
    private var shownEx: String? = "-"
    private var seen = -1L
    private val openedAt = System.currentTimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            // the service needs a moment to start; only close once it has finished
            if (!Session.running && System.currentTimeMillis() - openedAt > 6000) { finish(); return }
            if (Session.version != seen) { seen = Session.version; render() }
            handler.postDelayed(this, 250)
        }
    }

    private fun setIfChanged(v: TextView, t: String) { if (v.text.toString() != t) v.text = t }

    private fun render() {
        val (label, color) = when (Session.phase) {
            "set" -> "جولة" to Ui.SET
            "rest" -> "راحة" to Ui.REST
            "move" -> "تجهيز التمرين" to Ui.MOVE
            else -> "إحماء" to 0xFF8A6D00.toInt()
        }
        if (Session.paused) setIfChanged(phaseLabel, "متوقف مؤقتاً") else setIfChanged(phaseLabel, label)
        (heroCard.background as? GradientDrawable)?.setColor(if (Session.paused) 0xFF555555.toInt() else color)
        if (shownEx != Session.exId) {
            shownEx = Session.exId
            val bmp = Session.exId?.let { ExerciseImages.get(this, it) }
            if (bmp != null) { image.setImageBitmap(bmp); image.visibility = android.view.View.VISIBLE } else image.visibility = android.view.View.GONE
        }
        setIfChanged(title, Session.title)
        setIfChanged(subtitle, Session.subtitle)
        val r = Session.remainingSec
        val bigText = when {
            r < 0 -> ""
            Session.phase == "set" -> "$r"
            else -> "%d:%02d".format(r / 60, r % 60)
        }
        setIfChanged(big, bigText)
        big.contentDescription = if (Session.phase == "set") "التكرار $r" else if (r >= 0) "متبقي $r ثانية" else ""
        setIfChanged(status, Session.status)
        setIfChanged(progress, Session.progress)
        setIfChanged(pauseBtn, if (Session.paused) "استئناف" else "إيقاف مؤقت")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "الحصة جارية", big = true))

        heroCard = Ui.card(this, Ui.MOVE)
        phaseLabel = Ui.styled(this, "", 18f, bold = true).also { heroCard.addView(it) }
        title = Ui.styled(this, "", 30f, bold = true).also { heroCard.addView(it) }
        subtitle = Ui.styled(this, "", 18f).also { heroCard.addView(it) }
        image = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@WorkoutActivity, 190))
                .apply { setMargins(0, Ui.dp(this@WorkoutActivity, 8), 0, Ui.dp(this@WorkoutActivity, 8)) }
        }
        heroCard.addView(image)
        big = Ui.styled(this, "", 84f, bold = true).also { it.gravity = Gravity.CENTER; heroCard.addView(it) }
        col.addView(heroCard)

        progress = Ui.styled(this, "", 17f, color = Ui.MUTED).also { col.addView(it) }
        status = Ui.styled(this, "", 19f).also { col.addView(it) }

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
