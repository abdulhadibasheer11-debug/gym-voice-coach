package com.abdelhadi.gymcoach

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small line chart of an exercise's weight over sessions. Visual only (the numbers are also in text). */
class WeightLine(ctx: Context, private val values: List<Double>) : View(ctx) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(ctx, 3).toFloat(); strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val c = if (Ui.light) 0xFF8A5A00.toInt() else Ui.ACCENT
        line.color = c; dot.color = c
        val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val pad = Ui.dp(context, 8).toFloat()
        val w = width - 2 * pad; val h = height - 2 * pad
        val path = Path()
        values.forEachIndexed { i, v ->
            // RTL reading: oldest on the right, newest on the left
            val x = pad + w - w * i / (values.size - 1)
            val y = pad + h - (h * ((v - lo) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, Ui.dp(context, 4).toFloat(), dot)
        }
        canvas.drawPath(path, line)
    }
}

/** Change since the first entry, short form for list rows. */
private fun changeShort(h: List<Weights.Entry>): String {
    if (h.size < 2) return ""
    val d = h.last().kg - h.first().kg
    return when { d > 0 -> " · ↑ ${Weights.kgAr(d)}"; d < 0 -> " · ↓ ${Weights.kgAr(-d)}"; else -> " · ثابت" }
}

private fun changeLong(h: List<Weights.Entry>, fmt: SimpleDateFormat): String {
    if (h.size < 2) return "أول تسجيل."
    val d = h.last().kg - h.first().kg
    val since = fmt.format(Date(h.first().date))
    return when { d > 0 -> "زاد ${Weights.kgAr(d)} منذ $since."; d < 0 -> "نقص ${Weights.kgAr(-d)} منذ $since."; else -> "نفس الوزن منذ $since." }
}

/** Weights by training day: one short row per exercise; tap a row for its chart and entries. */
class WeightsActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.page(this, "الأوزان والتقدّم") { c -> col = c }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        while (col.childCount > 1) col.removeViewAt(1)
        val fmt = SimpleDateFormat("d MMMM", Ui.AR)
        val all = Weights.all(this)
        col.addView(Ui.text(this, if (all.isEmpty()) "لم تسجّل أوزاناً بعد. اختر تمريناً لتسجيل وزنه، أو فعّل سؤال الوزن بالصوت من الإعدادات."
            else "اختر تمريناً لترى تطوّره وتعدّل تسجيلاته.", 17f).apply { setTextColor(Ui.MUTED) })
        val seen = HashSet<String>()
        Prefs(this).days().forEach { day ->
            val ids = day.blocks.flatMap { b -> b.variants.flatten() }.distinct()
                .filter { Catalog.all.containsKey(it) && seen.add(it) }
            if (ids.isEmpty()) return@forEach
            col.addView(Ui.section(this, day.ar))
            ids.forEach { addRow(it, fmt) }
        }
        val others = all.keys.filter { Catalog.all.containsKey(it) && seen.add(it) }
        if (others.isNotEmpty()) {
            col.addView(Ui.section(this, "تمارين أخرى"))
            others.forEach { addRow(it, fmt) }
        }
    }

    private fun addRow(id: String, fmt: SimpleDateFormat) {
        val e = Catalog[id]; val h = Weights.history(this, id)
        val sub = if (h.isEmpty()) "لا يوجد تسجيل" else "آخر وزن ${Weights.kgAr(h.last().kg)}، ${fmt.format(Date(h.last().date))}${changeShort(h)}"
        val desc = if (h.isEmpty()) "${e.nameAr}، لا يوجد تسجيل" else "${e.nameAr}، آخر وزن ${Weights.kgAr(h.last().kg)}، ${changeLong(h, fmt)}"
        col.addView(Ui.listItem(this, e.nameAr, sub, desc) {
            startActivity(android.content.Intent(this, WeightDetailActivity::class.java).putExtra("id", id))
        })
    }
}

/** One exercise: summary, chart, suggestion, add a weight, and every entry with its own delete button. */
class WeightDetailActivity : BaseActivity() {
    private lateinit var col: LinearLayout
    private lateinit var id: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("id")?.takeIf { Catalog.all.containsKey(it) } ?: run { finish(); return }
        Screens.page(this, Catalog[id].nameAr) { c -> col = c }
        render()
    }

    private fun render() {
        while (col.childCount > 1) col.removeViewAt(1)
        val e = Catalog[id]
        val h = Weights.history(this, id)
        val fmt = SimpleDateFormat("d MMMM", Ui.AR)
        col.addView(Ui.styled(this, e.en, 17f, color = Ui.MUTED).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
        val card = Ui.card(this)
        if (h.isEmpty()) card.addView(Ui.styled(this, "لا يوجد تسجيل بعد.", 19f))
        else {
            card.addView(Ui.styled(this, "آخر وزن: ${Weights.kgAr(h.last().kg)}", 24f, bold = true))
            card.addView(Ui.styled(this, changeLong(h, fmt), 18f))
            if (h.size >= 2) {
                val recent = h.takeLast(12)
                card.addView(WeightLine(this, recent.map { it.kg }), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 120)))
                card.addView(Ui.styled(this, "الأحدث على اليسار. أعلى وزن ${Weights.kgAr(recent.maxOf { it.kg })}، وأقل وزن ${Weights.kgAr(recent.minOf { it.kg })}.", 15f, color = Ui.MUTED))
            }
        }
        col.addView(card)
        Weights.suggestion(this, id)?.let { col.addView(Ui.card(this).apply { addView(Ui.styled(this@WeightDetailActivity, it, 17f, color = Ui.ACCENT_TEXT)) }) }
        col.addView(Ui.button(this, "تسجيل وزن", primary = true, iconRes = R.drawable.ic_edit) { WeightEntry.show(this, id) { render() } })

        if (h.isNotEmpty()) {
            col.addView(Ui.section(this, "كل التسجيلات، الأحدث أولاً"))
            val full = SimpleDateFormat("EEEE d MMMM yyyy", Ui.AR)
            h.reversed().forEach { entry ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                val label = "${Weights.kgAr(entry.kg)}، ${full.format(Date(entry.date))}"
                row.addView(Ui.styled(this, label, 18f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Ui.button(this, "حذف", compact = true, desc = "حذف $label") {
                    Ui.confirm(this, "حذف $label؟") {
                        Weights.remove(this, id, entry.date); render(); col.announceForAccessibility("تم الحذف")
                    }
                }, LinearLayout.LayoutParams(Ui.dp(this, 96), LinearLayout.LayoutParams.WRAP_CONTENT))
                col.addView(row)
            }
            col.addView(Ui.button(this, "حذف كل تسجيلات هذا التمرين", danger = true) {
                Ui.confirm(this, "حذف كل أوزان ${e.nameAr}؟ لا يمكن التراجع.", "حذف الكل") {
                    Weights.remove(this, id, null); render(); col.announceForAccessibility("تم حذف كل التسجيلات")
                }
            })
        }
    }
}

/**
 * Weight entry used from the weights screen and during a session: −/+ in 2.5 kg steps (swipe up/down
 * with TalkBack), or type any number. The keyboard only opens if the text field is tapped.
 */
object WeightEntry {
    fun show(a: android.app.Activity, id: String, onSaved: (Double) -> Unit = {}) {
        val e = Catalog[id]
        // value in quarter-kilos so every step (0.5, 1, 1.25, 2.5, 5) is exact
        val step = (Prefs(a).weightStep * 4).toInt().coerceAtLeast(1)
        var q = ((Weights.last(a, id) ?: 20.0) * 4).toInt()
        val box = Ui.column(a)
        box.addView(Ui.styled(a, e.nameAr, 20f, bold = true))
        box.addView(Ui.styled(a, Weights.last(a, id)?.let { "آخر وزن: ${Weights.kgAr(it)}" } ?: "لا يوجد وزن سابق.", 17f, color = Ui.MUTED))
        box.addView(Ui.stepper(a, "الوزن", q, 1, 2000, step = step, format = { Weights.kgAr(it / 4.0) }) { q = it })
        box.addView(Ui.styled(a, "الخطوة ${Weights.kgAr(step / 4.0)}، وتتغير من الإعدادات.", 15f, color = Ui.MUTED))
        box.addView(Ui.styled(a, "أو اكتب الوزن بالكيلو", 17f, bold = true))
        val typed = android.widget.EditText(a).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "مثلاً 47.5"
            textSize = 22f
            setTextColor(Ui.FG); setHintTextColor(Ui.MUTED)
            contentDescription = "اكتب الوزن بالكيلو، اختياري"
            minHeight = Ui.dp(a, 60)
        }
        box.addView(typed)
        val dlg = AlertDialog.Builder(a).setTitle("تسجيل وزن").setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val t = VoiceInput.normalize(typed.text.toString()).toDoubleOrNull()?.takeIf { it in 0.5..500.0 }
                val kg = t ?: (q / 4.0)
                Weights.add(a, id, kg)
                a.window.decorView.announceForAccessibility("تم حفظ ${Weights.kgAr(kg)} لـ ${e.nameAr}")
                onSaved(kg)
            }
            .setNegativeButton("إلغاء", null)
            .apply {
                Weights.today(a, id)?.let { today ->
                    setNeutralButton("حذف وزن اليوم") { _, _ ->
                        Weights.remove(a, id, today.date)
                        a.window.decorView.announceForAccessibility("تم حذف ${Weights.kgAr(today.kg)} من ${e.nameAr}")
                        onSaved(0.0)
                    }
                }
            }.create()
        // never pop the keyboard up by itself
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dlg.show()
    }
}
