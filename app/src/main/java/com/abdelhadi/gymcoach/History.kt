package com.abdelhadi.gymcoach

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One finished (or ended early) session, as saved in the log. */
data class LogEntry(val date: Long, val dayId: String, val dayAr: String, val planned: Int, val actual: Int, val stopped: Boolean, val exercises: Int)

object History {
    fun entries(ctx: Context): List<LogEntry> {
        val names = runCatching { ProgramStore.days(ctx).associate { it.id to it.ar } }.getOrDefault(emptyMap())
        val arr = runCatching { JSONArray(ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE).getString("log", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("day")
            LogEntry(o.optLong("date"), id, o.optString("dayAr").ifBlank { names[id] ?: id },
                o.optInt("planned"), o.optInt("actual"), o.optBoolean("stopped"), o.optInt("exercises"))
        }.filter { it.actual >= 60 } // ignore sessions ended within the first minute
            .sortedByDescending { it.date }
    }

    /** Arabic count phrase: nAr(3, "حصة واحدة", "حصتان", "حصص", "حصة") = "3 حصص". */
    fun nAr(n: Int, one: String, two: String, few: String, many: String) = when {
        n == 1 -> one; n == 2 -> two; n in 3..10 -> "$n $few"; else -> "$n $many"
    }
    fun sessionsAr(n: Int) = if (n == 0) "لا حصص" else nAr(n, "حصة واحدة", "حصتان", "حصص", "حصة")

    /** Start of the week (Monday, matching the UAE working week) containing [t]. */
    fun weekStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        val back = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        add(Calendar.DAY_OF_MONTH, -back)
    }.timeInMillis

    private const val WEEK = 7L * 24 * 3600 * 1000

    /** Minutes trained in each of the last [n] weeks, oldest first (the last one is this week). */
    fun weeklyMinutes(list: List<LogEntry>, n: Int, now: Long = System.currentTimeMillis()): List<Int> {
        val w0 = weekStart(now)
        return (n - 1 downTo 0).map { k ->
            val from = w0 - k * WEEK; val to = from + WEEK
            list.filter { it.date in from until to }.sumOf { it.actual } / 60
        }
    }

    /** Number of consecutive weeks (ending this week, or last week if none yet this week) with at least one session. */
    fun weekStreak(list: List<LogEntry>, now: Long = System.currentTimeMillis()): Int {
        var w = weekStart(now)
        if (list.none { it.date >= w }) w -= WEEK
        var n = 0
        while (list.any { it.date in w until w + WEEK }) { n++; w -= WEEK }
        return n
    }

    fun sessionsThisWeek(list: List<LogEntry>, now: Long = System.currentTimeMillis()) = list.count { it.date >= weekStart(now) }

    fun ordinalAr(n: Int) = when (n) {
        1 -> "الأولى"; 2 -> "الثانية"; 3 -> "الثالثة"; 4 -> "الرابعة"; 5 -> "الخامسة"; 6 -> "السادسة"; 7 -> "السابعة"
        else -> "رقم $n"
    }
}

/** Bars for the last weeks. Visual only; the same numbers are given as text for TalkBack. */
class WeeksChart(ctx: Context, private val values: List<Int>, private val labels: List<String>) : View(ctx) {
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = Ui.dp(ctx, 13).toFloat() }
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val n = values.size; if (n == 0) return
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(60)
        val labelH = txt.textSize * 2.6f
        val slot = width.toFloat() / n
        val bw = slot * 0.6f
        txt.color = Ui.FG
        values.forEachIndexed { i, v ->
            // RTL: oldest on the left, this week on the right
            val cx = slot * i + slot / 2
            val h = (height - labelH) * v / max
            val top = height - labelH - h
            bar.color = if (i == n - 1) (if (Ui.light) 0xFF8A5A00.toInt() else Ui.ACCENT) else (if (Ui.light) 0xFF9AA1AD.toInt() else 0xFF5B6473.toInt())
            r.set(cx - bw / 2, top, cx + bw / 2, height - labelH)
            if (v > 0) canvas.drawRoundRect(r, 8f, 8f, bar)
            canvas.drawText("$v", cx, height - labelH + txt.textSize * 1.1f, txt)
            canvas.drawText(labels[i], cx, height - labelH + txt.textSize * 2.3f, txt)
        }
    }
}

class HistoryActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = History.entries(this)
        Screens.page(this, "السجل والإحصائيات") { col ->
            if (list.isEmpty()) {
                col.addView(Ui.text(this, "لا توجد حصص مسجلة بعد. بعد أول حصة ستظهر هنا مدتها وإحصائيات أسبوعك.", 19f))
                return@page
            }
            val week = History.sessionsThisWeek(list)
            val weekMin = History.weeklyMinutes(list, 1)[0]
            val streak = History.weekStreak(list)
            val monthFrom = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
            val month = list.count { it.date >= monthFrom }
            val stats = Ui.card(this)
            stats.addView(Ui.styled(this, "هذا الأسبوع: ${History.sessionsAr(week)}، $weekMin دقيقة", 22f, bold = true))
            stats.addView(Ui.styled(this, "هذا الشهر: ${History.sessionsAr(month)}", 19f))
            stats.addView(Ui.styled(this, "أسابيع متتالية فيها تمرين: $streak", 19f))
            stats.addView(Ui.styled(this, "مجموع الحصص المسجلة: ${list.size}", 19f))
            col.addView(stats)

            col.addView(Ui.heading(this, "الدقائق في آخر 8 أسابيع"))
            val mins = History.weeklyMinutes(list, 8)
            val labels = (7 downTo 0).map { if (it == 0) "هذا" else "-$it" }
            col.addView(WeeksChart(this, mins, labels), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 180)))
            col.addView(Ui.text(this, "من الأقدم للأحدث: " + mins.joinToString("، ") { "$it" } + " دقيقة. آخر رقم هو هذا الأسبوع.", 16f)
                .apply { setTextColor(Ui.MUTED) })

            col.addView(Ui.heading(this, "آخر الحصص"))
            val fmt = SimpleDateFormat("EEEE d MMMM، h:mm a", Locale("ar"))
            list.take(40).forEach { e ->
                val card = Ui.card(this)
                card.addView(Ui.styled(this, e.dayAr, 20f, bold = true))
                card.addView(Ui.styled(this, fmt.format(Date(e.date)), 17f, color = Ui.MUTED))
                val diff = (e.actual - e.planned) / 60
                val timing = when { e.planned <= 0 -> ""; diff > 1 -> "، أطول من الخطة بـ $diff دقيقة"; diff < -1 -> "، أقصر من الخطة بـ ${-diff} دقيقة"; else -> "، في الوقت المحدد" }
                card.addView(Ui.styled(this, "${(e.actual + 30) / 60} دقيقة$timing. ${History.nAr(e.exercises, "تمرين واحد", "تمرينان", "تمارين", "تمريناً")}. ${if (e.stopped) "أُنهيت قبل نهايتها." else "كاملة."}", 17f))
                col.addView(card)
            }
        }
    }
}
