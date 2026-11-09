package com.abdelhadi.gymcoach

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Control screen during a session: a large colour-coded card (orange = set, green = rest, blue = moving,
 * amber = warm-up) with a big rep count or countdown inside a progress ring, a session progress bar,
 * a "next" card, then the controls: one big pause button, two rows of compact buttons, and End.
 * Visible labels are short; TalkBack reads the full label (contentDescription).
 * Texts are updated silently (no live regions) so TalkBack never talks over the coach.
 */
class WorkoutActivity : BaseActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var heroCard: LinearLayout
    private lateinit var phaseLabel: TextView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var big: TextView
    private lateinit var ring: RingView
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var bar: BarView
    private lateinit var timeLine: TextView
    private lateinit var nextText: TextView
    private lateinit var nextImage: ImageView
    private lateinit var pauseBtn: Button
    private lateinit var image: ImageView
    private var shownEx: String? = "-"
    private var shownNext: String? = "-"
    private var seen = -1L
    private val openedAt = System.currentTimeMillis()
    private val clock = SimpleDateFormat("h:mm a", Ui.AR)

    private val tick = object : Runnable {
        override fun run() {
            // the service needs a moment to start; only close once it has finished
            if (!Session.running && System.currentTimeMillis() - openedAt > 6000) { finish(); return }
            if (Session.version != seen) { seen = Session.version; render() }
            renderTime()
            handler.postDelayed(this, 250)
        }
    }

    private fun setIfChanged(v: TextView, t: String) { if (v.text.toString() != t) v.text = t }

    private fun render() {
        val (label, color) = when (Session.phase) {
            "set" -> "جولة" to Ui.SET
            "rest" -> "راحة" to Ui.REST
            "move" -> "تجهيز التمرين" to Ui.MOVE
            else -> "إحماء" to Ui.WARM
        }
        val bg = if (Session.paused) Ui.PAUSED else color
        val fg = Ui.onPhase(bg)
        if (Session.paused) setIfChanged(phaseLabel, "متوقف مؤقتاً") else setIfChanged(phaseLabel, label)
        (heroCard.background as? GradientDrawable)?.setColor(bg)
        listOf(phaseLabel, title, subtitle, big).forEach { it.setTextColor(fg) }
        ring.color = fg
        if (shownEx != Session.exId) {
            shownEx = Session.exId
            val bmp = Session.exId?.let { ExerciseImages.get(this, it) }
            if (bmp != null) { image.setImageBitmap(bmp); image.visibility = View.VISIBLE } else image.visibility = View.GONE
        }
        setIfChanged(title, Session.title)
        setIfChanged(subtitle, Session.subtitle)
        val r = Session.remainingSec
        val isSet = Session.phase == "set"
        val bigText = when {
            r < 0 -> ""
            isSet -> "$r"
            else -> "%d:%02d".format(r / 60, r % 60)
        }
        setIfChanged(big, bigText)
        big.contentDescription = if (isSet && r >= 0) "التكرار $r من ${Session.repTotal}" else if (r >= 0) "متبقي $r ثانية" else ""
        ring.fraction = when {
            r < 0 -> 0f
            isSet -> if (Session.repTotal > 0) r.toFloat() / Session.repTotal else 0f
            else -> if (Session.clockTotal > 0) r.toFloat() / Session.clockTotal else 0f
        }
        setIfChanged(status, Session.status)
        setIfChanged(progress, Session.progress)
        setIfChanged(nextText, Session.next)
        if (shownNext != Session.nextExId) {
            shownNext = Session.nextExId
            // only preview when the next exercise differs from the one already shown in the big card
            val bmp = Session.nextExId?.takeIf { it != Session.exId }?.let { ExerciseImages.get(this, it) }
            if (bmp != null) { nextImage.setImageBitmap(bmp); nextImage.visibility = View.VISIBLE } else nextImage.visibility = View.GONE
        }
        val paused = Session.paused
        setIfChanged(pauseBtn, if (paused) "استئناف" else "إيقاف مؤقت")
        pauseBtn.setCompoundDrawablesRelative(Ui.icon(this, if (paused) R.drawable.ic_play else R.drawable.ic_pause, android.graphics.Color.BLACK, 34), null, null, null)
    }

    /** Session bar and clock line: refreshed every tick (time moves even when nothing else changes). */
    private fun renderTime() {
        val start = Session.startMs; val end = Session.endMs
        if (start <= 0 || end <= start) { bar.fraction = 0f; return }
        val now = System.currentTimeMillis()
        bar.fraction = (now - start).toFloat() / (end - start)
        val doneMin = ((now - start) / 60000).toInt()
        val leftMin = maxOf(0, ((end - now) / 60000.0).let { kotlin.math.ceil(it).toInt() })
        val t = "مضى $doneMin د · باقي $leftMin د · الانتهاء ${clock.format(Date(end))}"
        setIfChanged(timeLine, t)
        timeLine.contentDescription = "مضى $doneMin دقيقة، باقي $leftMin دقيقة، الانتهاء ${clock.format(Date(end))}"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "الحصة جارية"))

        heroCard = Ui.card(this, Ui.MOVE)
        phaseLabel = Ui.styled(this, "", 18f, bold = true).also { heroCard.addView(it) }
        title = Ui.styled(this, "", 28f, bold = true).also { heroCard.addView(it) }
        subtitle = Ui.styled(this, "", 18f).also { heroCard.addView(it) }
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@WorkoutActivity, 160))
                .apply { setMargins(0, Ui.dp(this@WorkoutActivity, 8), 0, Ui.dp(this@WorkoutActivity, 8)) }
        }
        heroCard.addView(image)
        // big number inside a progress ring
        val ringBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(this@WorkoutActivity, 200), Ui.dp(this@WorkoutActivity, 200))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(this@WorkoutActivity, 4) }
        }
        ring = RingView(this).also { ringBox.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        big = Ui.styled(this, "", 64f, bold = true).also {
            Ui.fixedSize(it, 64f)
            it.gravity = Gravity.CENTER
            ringBox.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        heroCard.addView(ringBox)
        col.addView(heroCard)

        // whole-session progress
        bar = BarView(this).also {
            col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)).apply { topMargin = Ui.dp(this@WorkoutActivity, 8) })
        }
        timeLine = Ui.styled(this, "", 17f, bold = true).also { col.addView(it) }

        // what's next
        val nextCard = Ui.card(this)
        val nextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nextImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        nextText = Ui.styled(this, "", 18f)
        nextRow.addView(nextText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        nextRow.addView(nextImage, LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)).apply { marginStart = Ui.dp(this@WorkoutActivity, 10) })
        nextCard.addView(nextRow)
        col.addView(nextCard)

        // controls
        pauseBtn = Ui.button(this, "إيقاف مؤقت", primary = true, iconRes = R.drawable.ic_pause) { WorkoutService.send(this, WorkoutService.ACTION_PAUSE) }
        col.addView(pauseBtn)
        col.addView(Ui.row(this,
            Ui.button(this, "+30 ث", compact = true, iconRes = R.drawable.ic_add_time, desc = "أحتاج راحة أكثر، ثلاثين ثانية") { WorkoutService.send(this, WorkoutService.ACTION_MORE_REST) },
            Ui.button(this, "تخطي", compact = true, iconRes = R.drawable.ic_skip, desc = "تخطي الخطوة الحالية") { WorkoutService.send(this, WorkoutService.ACTION_SKIP) },
            Ui.button(this, "إعادة", compact = true, iconRes = R.drawable.ic_replay, desc = "إعادة آخر تعليمات") { WorkoutService.send(this, WorkoutService.ACTION_REPEAT) },
        ))
        col.addView(Ui.row(this,
            Ui.button(this, "التمرين التالي", compact = true, iconRes = R.drawable.ic_dumbbell, desc = "تخطي إلى التمرين التالي") { WorkoutService.send(this, WorkoutService.ACTION_SKIP_EXERCISE) },
            Ui.button(this, "أغنية أخرى", compact = true, iconRes = R.drawable.ic_music, desc = "الأغنية التالية") { WorkoutService.send(this, WorkoutService.ACTION_NEXT_SONG) },
            Ui.button(this, "أمر صوتي", compact = true, iconRes = R.drawable.ic_mic, desc = "أمر صوتي، تجريبي") { WorkoutService.send(this, WorkoutService.ACTION_VOICE) },
        ))

        progress = Ui.styled(this, "", 17f, color = Ui.MUTED).also { col.addView(it) }
        status = Ui.styled(this, "", 19f).also { col.addView(it) }

        col.addView(Ui.button(this, "إنهاء الحصة", danger = true, iconRes = R.drawable.ic_stop, desc = "إنهاء الحصة، اضغط مرتين") { WorkoutService.send(this, WorkoutService.ACTION_STOP) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    override fun onResume() { super.onResume(); seen = -1; handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
