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
    private lateinit var bar: BarView
    private lateinit var timeLine: TextView
    private lateinit var nextText: TextView
    private lateinit var pauseBtn: Button
    private lateinit var image: ImageView
    private var shownEx: String? = "-"
    private lateinit var ringBox: FrameLayout
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
        // English name and Arabic round info on separate lines, so the two directions never get mixed
        setIfChanged(subtitle, Session.subtitle.replace(" · ", "\n"))
        val r = Session.remainingSec
        val isSet = Session.phase == "set"
        val bigText = when {
            r < 0 -> ""
            isSet -> "$r"
            else -> "%d:%02d".format(r / 60, r % 60)
        }
        setIfChanged(big, bigText)
        // no countdown yet (the coach is still talking): hide the empty ring so the controls move up
        val ringVis = if (r < 0) View.GONE else View.VISIBLE
        if (ringBox.visibility != ringVis) ringBox.visibility = ringVis
        big.contentDescription = if (isSet && r >= 0) "التكرار $r من ${Session.repTotal}" else if (r >= 0) "متبقي $r ثانية" else ""
        ring.fraction = when {
            r < 0 -> 0f
            isSet -> if (Session.repTotal > 0) r.toFloat() / Session.repTotal else 0f
            else -> if (Session.clockTotal > 0) r.toFloat() / Session.clockTotal else 0f
        }
        setIfChanged(status, Session.status)
        setIfChanged(nextText, Session.next)
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
        setTitle("الحصة جارية") // window title: announced by TalkBack, no on-screen heading needed
        if (Prefs(this).keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctx = this
        val col = Ui.column(this)

        // ---------- information (top) ----------
        heroCard = Ui.card(this, Ui.MOVE)
        phaseLabel = Ui.styled(this, "", 17f, bold = true).also { heroCard.addView(it) }
        // exercise name with a small photo beside it (tap the photo to enlarge it)
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = Ui.styled(this, "", 26f, bold = true).also { names.addView(it) }
        subtitle = Ui.styled(this, "", 17f).also { names.addView(it) }
        nameRow.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = Ui.dp(ctx, 12).toFloat(); setColor(0xFFFFFFFF.toInt()) }
            setOnClickListener { enlargeImage() }
        }
        nameRow.addView(image, LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 92)).apply { marginStart = Ui.dp(ctx, 10) })
        heroCard.addView(nameRow)
        // big number inside a progress ring
        ringBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 170), Ui.dp(ctx, 170))
                .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(ctx, 6) }
        }
        ring = RingView(this).also { ringBox.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        big = Ui.styled(this, "", 56f, bold = true).also {
            Ui.fixedSize(it, 56f)
            it.gravity = Gravity.CENTER
            ringBox.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        heroCard.addView(ringBox)
        col.addView(heroCard)

        bar = BarView(this).also {
            col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10)).apply { topMargin = Ui.dp(ctx, 8) })
        }
        timeLine = Ui.styled(this, "", 17f, bold = true).also { col.addView(it) }
        nextText = Ui.styled(this, "", 17f, color = Ui.MUTED).also { col.addView(it) }
        status = Ui.styled(this, "", 17f).also {
            it.maxLines = 2; it.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(it)
        }

        // spacer: pushes the controls to the bottom when everything fits; collapses when it does not
        col.addView(View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---------- controls (bottom, always in the same place) ----------
        pauseBtn = Ui.button(this, "إيقاف مؤقت", primary = true, iconRes = R.drawable.ic_pause) { WorkoutService.send(this, WorkoutService.ACTION_PAUSE) }
        col.addView(pauseBtn)
        col.addView(Ui.row(this,
            Ui.button(this, "+30 ث", compact = true, iconRes = R.drawable.ic_add_time, desc = "أحتاج راحة أكثر، ثلاثين ثانية") { WorkoutService.send(this, WorkoutService.ACTION_MORE_REST) },
            Ui.button(this, "تخطي", compact = true, iconRes = R.drawable.ic_skip, desc = "تخطي الخطوة الحالية") { WorkoutService.send(this, WorkoutService.ACTION_SKIP) },
            Ui.button(this, "إعادة", compact = true, iconRes = R.drawable.ic_replay, desc = "إعادة آخر تعليمات") { WorkoutService.send(this, WorkoutService.ACTION_REPEAT) },
        ))
        col.addView(Ui.row(this,
            Ui.button(this, "التمرين التالي", compact = true, iconRes = R.drawable.ic_dumbbell, desc = "تخطي إلى التمرين التالي") { WorkoutService.send(this, WorkoutService.ACTION_SKIP_EXERCISE) },
            Ui.button(this, "أمر صوتي", compact = true, iconRes = R.drawable.ic_mic, desc = "أمر صوتي") { WorkoutService.send(this, WorkoutService.ACTION_VOICE) },
            Ui.button(this, "تسجيل وزن", compact = true, iconRes = R.drawable.ic_chart, desc = "تسجيل وزن") { logWeight() },
        ))
        // only when the app itself plays the music
        if (Prefs(this).musicMode != "none") col.addView(
            Ui.button(this, "أغنية أخرى", iconRes = R.drawable.ic_music, desc = "الأغنية التالية") { WorkoutService.send(this, WorkoutService.ACTION_NEXT_SONG) })
        col.addView(Ui.button(this, "إنهاء الحصة", danger = true, iconRes = R.drawable.ic_stop, desc = "إنهاء الحصة، اضغط مرتين") { WorkoutService.send(this, WorkoutService.ACTION_STOP) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
    }

    /** Which exercise a weight belongs to: during a set the current one; otherwise the one just finished. */
    private fun logWeight() {
        val ids = listOfNotNull(
            if (Session.phase == "set") Session.exId else Session.lastSetId,
            Session.exId,
        ).distinct().filter { Catalog.all.containsKey(it) }
        when (ids.size) {
            0 -> window.decorView.announceForAccessibility("لا يوجد تمرين بعد لتسجيل وزنه")
            1 -> WeightEntry.show(this, ids[0])
            else -> android.app.AlertDialog.Builder(this).setTitle("وزن أي تمرين؟")
                .setItems(ids.mapIndexed { i, id -> Catalog[id].nameAr + if (i == 0) "، الذي انتهيت منه" else "، الحالي" }.toTypedArray()) { _, w -> WeightEntry.show(this, ids[w]) }
                .setNegativeButton("إلغاء", null).show()
        }
    }

    private fun enlargeImage() {
        val bmp = Session.exId?.let { ExerciseImages.get(this, it) } ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp); adjustViewBounds = true; setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        android.app.AlertDialog.Builder(this).setView(iv).setPositiveButton("إغلاق", null).show()
    }

    override fun onResume() { super.onResume(); seen = -1; handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
