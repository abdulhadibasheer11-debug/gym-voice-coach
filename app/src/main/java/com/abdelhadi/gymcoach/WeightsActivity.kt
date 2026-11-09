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

/** Weights per exercise: last weight, progress, a small chart, and manual entry. */
class WeightsActivity : BaseActivity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Screens.page(this, "الأوزان والتقدّم") { c -> col = c }
        render()
    }

    private fun ids(): List<String> {
        val inProgram = Prefs(this).days().flatMap { d -> d.blocks.flatMap { b -> b.variants.flatten() } }
        return (inProgram + Weights.all(this).keys).distinct().filter { Catalog.all.containsKey(it) }
    }

    private fun render() {
        while (col.childCount > 1) col.removeViewAt(1)
        col.addView(Ui.text(this, "الأوزان تُسجّل بالصوت بعد أول جولة إذا فعّلت ذلك في الإعدادات، أو يدوياً من هنا. الأحدث أولاً.", 17f).apply { setTextColor(Ui.MUTED) })
        val fmt = SimpleDateFormat("d MMMM", Ui.AR)
        val (withData, without) = ids().partition { Weights.history(this, it).isNotEmpty() }
        withData.sortedByDescending { Weights.history(this, it).last().date }.forEach { id ->
            val e = Catalog[id]; val h = Weights.history(this, id)
            val card = Ui.card(this)
            card.addView(Ui.styled(this, e.nameAr, 20f, bold = true))
            val last = h.last(); val first = h.first()
            val diff = last.kg - first.kg
            val change = when {
                h.size < 2 -> "أول تسجيل."
                diff > 0 -> "زاد ${Weights.kgAr(diff)} منذ ${fmt.format(Date(first.date))}."
                diff < 0 -> "نقص ${Weights.kgAr(-diff)} منذ ${fmt.format(Date(first.date))}."
                else -> "نفس الوزن منذ ${fmt.format(Date(first.date))}."
            }
            card.addView(Ui.styled(this, "آخر وزن: ${Weights.kgAr(last.kg)}، يوم ${fmt.format(Date(last.date))}. $change", 18f))
            if (h.size >= 2) {
                val recent = h.takeLast(12)
                card.addView(WeightLine(this, recent.map { it.kg }), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 90)))
                card.addView(Ui.styled(this, "آخر الحصص: " + recent.joinToString("، ") { Weights.kgAr(it.kg) }, 15f, color = Ui.MUTED))
            }
            Weights.suggestion(this, id)?.let { card.addView(Ui.styled(this, it, 16f, color = Ui.ACCENT_TEXT)) }
            card.addView(Ui.button(this, "تسجيل وزن", desc = "تسجيل وزن ${e.nameAr}") { WeightEntry.show(this, id) { render() } })
            col.addView(card)
        }
        if (without.isNotEmpty()) {
            col.addView(Ui.heading(this, "تمارين بلا تسجيل بعد"))
            col.addView(Ui.button(this, "تسجيل وزن لتمرين آخر") {
                val names = without.map { Catalog[it].nameAr }.toTypedArray()
                AlertDialog.Builder(this).setTitle("اختر التمرين").setItems(names) { _, w -> WeightEntry.show(this, without[w]) { render() } }.setNegativeButton("إلغاء", null).show()
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
        var halfKg = ((Weights.last(a, id) ?: 20.0) * 2).toInt()
        val box = Ui.column(a)
        box.addView(Ui.styled(a, e.nameAr, 20f, bold = true))
        box.addView(Ui.styled(a, Weights.last(a, id)?.let { "آخر وزن: ${Weights.kgAr(it)}" } ?: "لا يوجد وزن سابق.", 17f, color = Ui.MUTED))
        box.addView(Ui.stepper(a, "الوزن", halfKg, 1, 1000, step = 5, format = { Weights.kgAr(it / 2.0) }) { halfKg = it })
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
                val kg = t ?: (halfKg / 2.0)
                Weights.add(a, id, kg)
                a.window.decorView.announceForAccessibility("تم حفظ ${Weights.kgAr(kg)} لـ ${e.nameAr}")
                onSaved(kg)
            }
            .setNegativeButton("إلغاء", null).create()
        // never pop the keyboard up by itself
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dlg.show()
    }
}
