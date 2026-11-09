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
data class LogEntry(val date: Long, val dayId: String, val dayAr: String, val planned: Int, val actual: Int, val stopped: Boolean, val exercises: Int, val ids: List<String> = emptyList()) {
    /** When the session started (the log is written at the end). */
    val start get() = date - actual * 1000L
}

object History {
    fun entries(ctx: Context): List<LogEntry> {
        val names = runCatching { ProgramStore.days(ctx).associate { it.id to it.ar } }.getOrDefault(emptyMap())
        val arr = runCatching { JSONArray(ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE).getString("log", "[]")) }
            .getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("day")
            LogEntry(o.optLong("date"), id, o.optString("dayAr").ifBlank { names[id] ?: id },
                o.optInt("planned"), o.optInt("actual"), o.optBoolean("stopped"), o.optInt("exercises"),
                o.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
        }.filter { it.actual >= 60 } // ignore sessions ended within the first minute
            .sortedByDescending { it.date }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE)

    /** Deletes one session (by its end time); with [withWeights] also the weights logged during it. */
    fun delete(ctx: Context, e: LogEntry, withWeights: Boolean) {
        val arr = runCatching { JSONArray(prefs(ctx).getString("log", "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optLong("date") != e.date) keep.put(it) }
        prefs(ctx).edit().putString("log", keep.toString()).apply()
        if (withWeights) weightsDuring(ctx, e).forEach { (id, w) -> Weights.remove(ctx, id, w.date) }
    }

    fun deleteAll(ctx: Context) = prefs(ctx).edit().putString("log", "[]").apply()

    /** Weights saved between the start of the session and 30 minutes after it ended. */
    fun weightsDuring(ctx: Context, e: LogEntry): List<Pair<String, Weights.Entry>> =
        Weights.all(ctx).flatMap { (id, h) -> h.filter { it.date in e.start..(e.date + 30 * 60_000L) }.map { id to it } }

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
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.page(this, "السجل والإحصائيات") { c -> col = c }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        while (col.childCount > 1) col.removeViewAt(1)
        val list = History.entries(this)
        if (list.isEmpty()) {
            col.addView(Ui.text(this, "لا توجد حصص مسجلة بعد. بعد أول حصة ستظهر هنا مدتها وإحصائيات أسبوعك.", 19f))
            return
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
        stats.addView(Ui.styled(this, "مجموع الحصص: ${list.size}", 19f))
        col.addView(stats)

        col.addView(Ui.section(this, "الدقائق في آخر 8 أسابيع"))
        val mins = History.weeklyMinutes(list, 8)
        val labels = (7 downTo 0).map { if (it == 0) "هذا" else "-$it" }
        col.addView(WeeksChart(this, mins, labels), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 170)))
        col.addView(Ui.text(this, "من الأقدم للأحدث: " + mins.joinToString("، ") { "$it" } + " دقيقة. آخر رقم هو هذا الأسبوع.", 15f)
            .apply { setTextColor(Ui.MUTED) })

        val monthFmt = SimpleDateFormat("MMMM yyyy", Ui.AR)
        val dayFmt = SimpleDateFormat("EEEE d MMMM", Ui.AR)
        var lastMonth = ""
        list.take(60).forEach { e ->
            val m = monthFmt.format(Date(e.date))
            if (m != lastMonth) { lastMonth = m; col.addView(Ui.section(this, m)) }
            val sub = "${dayFmt.format(Date(e.date))} · ${(e.actual + 30) / 60} دقيقة${if (e.stopped) " · أُنهيت مبكراً" else ""}"
            col.addView(Ui.listItem(this, e.dayAr, sub) {
                startActivity(android.content.Intent(this, SessionDetailActivity::class.java).putExtra("date", e.date))
            })
        }
        if (list.size > 60) col.addView(Ui.text(this, "تظهر آخر 60 حصة.", 15f).apply { setTextColor(Ui.MUTED) })

        col.addView(Ui.button(this, "حذف كل السجل", danger = true) {
            Ui.confirm(this, "حذف كل الحصص المسجلة والإحصائيات؟ الأوزان لا تُحذف. لا يمكن التراجع.", "حذف الكل") {
                History.deleteAll(this); render(); col.announceForAccessibility("تم حذف السجل")
            }
        })
    }
}

/** One session: timing, exercises done, weights logged, and delete. */
class SessionDetailActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val date = intent.getLongExtra("date", 0)
        val e = History.entries(this).firstOrNull { it.date == date } ?: run { finish(); return }
        val fmt = SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Ui.AR)
        Screens.page(this, e.dayAr) { col ->
            col.addView(Ui.styled(this, "بدأت ${fmt.format(Date(e.start))}", 17f, color = Ui.MUTED))
            val card = Ui.card(this)
            val diff = (e.actual - e.planned) / 60
            card.addView(Ui.styled(this, "المدة: ${(e.actual + 30) / 60} دقيقة", 22f, bold = true))
            if (e.planned > 0) card.addView(Ui.styled(this, when {
                diff > 1 -> "أطول من الخطة بـ $diff دقيقة، الخطة ${(e.planned + 30) / 60} دقيقة."
                diff < -1 -> "أقصر من الخطة بـ ${-diff} دقيقة، الخطة ${(e.planned + 30) / 60} دقيقة."
                else -> "في الوقت المحدد."
            }, 18f))
            card.addView(Ui.styled(this, if (e.stopped) "أُنهيت قبل نهايتها." else "اكتملت.", 18f))
            col.addView(card)

            col.addView(Ui.section(this, "التمارين: ${History.nAr(e.exercises, "تمرين واحد", "تمرينان", "تمارين", "تمريناً")}"))
            if (e.ids.isEmpty()) col.addView(Ui.text(this, "تفاصيل التمارين متوفرة للحصص من الإصدار 0.1.14 وما بعده.", 16f).apply { setTextColor(Ui.MUTED) })
            val weights = History.weightsDuring(this, e).toMap()
            e.ids.filter { Catalog.all.containsKey(it) }.forEach { id ->
                val w = weights[id]?.let { " · ${Weights.kgAr(it.kg)}" } ?: ""
                col.addView(Ui.text(this, "• ${Catalog[id].nameAr}$w", 18f))
            }
            val extra = weights.keys.filter { it !in e.ids && Catalog.all.containsKey(it) }
            if (extra.isNotEmpty() || (e.ids.isEmpty() && weights.isNotEmpty())) {
                col.addView(Ui.section(this, "أوزان سُجّلت في هذه الحصة"))
                (if (e.ids.isEmpty()) weights.keys else extra).forEach { id -> col.addView(Ui.text(this, "• ${Catalog[id].nameAr}: ${Weights.kgAr(weights[id]!!.kg)}", 18f)) }
            }

            col.addView(Ui.button(this, "حذف هذه الحصة", danger = true) {
                val hasW = weights.isNotEmpty()
                val b = android.app.AlertDialog.Builder(this).setMessage("حذف حصة ${e.dayAr}، ${fmt.format(Date(e.start))}؟")
                    .setPositiveButton("حذف الحصة فقط") { _, _ -> History.delete(this, e, false); done() }
                    .setNegativeButton("إلغاء", null)
                if (hasW) b.setNeutralButton("الحصة وأوزانها") { _, _ -> History.delete(this, e, true); done() }
                b.show()
            })
        }
    }

    private fun done() {
        window.decorView.announceForAccessibility("تم حذف الحصة")
        finish()
    }
}
