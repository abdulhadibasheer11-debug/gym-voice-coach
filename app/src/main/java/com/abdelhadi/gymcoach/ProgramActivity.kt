package com.abdelhadi.gymcoach

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView

/** Program overview: any number of days, reorder, delete, add, or start from a ready-made template. */
class ProgramActivity : BaseActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "تخصيص البرنامج", big = true))
        col.addView(Ui.styled(this, "رتّب أيامك كما تريد: من يوم واحد إلى أي عدد. كل تعديل يُحفظ مباشرة.", 18f, color = Ui.MUTED))
        col.addView(Ui.heading(this, "أيامك"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(Ui.button(this, "إضافة يوم جديد", primary = true) {
            val days = ProgramStore.days(this).toMutableList()
            val d = Day(ProgramStore.newDayId(), "يوم ${days.size + 1}", "", true, emptyList())
            days += d; ProgramStore.save(this, days)
            startActivity(Intent(this, DayEditActivity::class.java).putExtra("day", d.id))
        })
        col.addView(Ui.heading(this, "برامج جاهزة"))
        col.addView(Ui.styled(this, "اختيار برنامج جاهز يستبدل أيامك الحالية، ثم تستطيع تعديله.", 18f, color = Ui.MUTED))
        col.addView(Ui.button(this, "اختيار برنامج جاهز") {
            AlertDialog.Builder(this).setTitle("البرامج الجاهزة").setItems(Templates.all.map { it.first }.toTypedArray()) { _, w ->
                val (name, make) = Templates.all[w]
                AlertDialog.Builder(this).setTitle("استبدال البرنامج؟")
                    .setMessage("سيُستبدل برنامجك الحالي بـ: $name")
                    .setPositiveButton("نعم، استبدل") { _, _ ->
                        ProgramStore.save(this, make()); Prefs(this).apply { lastDay = null; dayId = null }
                        render(0)
                    }
                    .setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إغلاق", null).show()
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    override fun onResume() { super.onResume(); render(-1) }

    private fun render(focus: Int) {
        list.removeAllViews()
        val days = ProgramStore.days(this)
        days.forEachIndexed { i, d ->
            val card = Ui.card(this)
            if (android.os.Build.VERSION.SDK_INT >= 28) card.isScreenReaderFocusable = false
            val mins = buildPlan(d, Prefs(this).settingsFor(d)).totalSec / 60
            card.addView(Ui.styled(this, "اليوم ${i + 1}: ${d.ar}", 22f, bold = true))
            card.addView(Ui.styled(this, "${d.blocks.size} تمارين، حوالي $mins دقيقة", 17f, color = Ui.MUTED))
            card.addView(Ui.button(this, "خيارات ${d.ar}") {
                val acts = mutableListOf<Pair<String, () -> Unit>>(
                    "تعديل التمارين والاسم" to { startActivity(Intent(this, DayEditActivity::class.java).putExtra("day", d.id)) })
                if (i > 0) acts += "تحريك للأعلى" to { swap(i, i - 1) }
                if (i < days.size - 1) acts += "تحريك للأسفل" to { swap(i, i + 1) }
                if (days.size > 1) acts += "حذف هذا اليوم" to {
                    AlertDialog.Builder(this).setMessage("حذف ${d.ar}؟").setPositiveButton("حذف") { _, _ ->
                        ProgramStore.save(this, days.filterIndexed { j, _ -> j != i }); render(maxOf(0, i - 1))
                    }.setNegativeButton("إلغاء", null).show()
                }
                AlertDialog.Builder(this).setTitle(d.ar).setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
                    .setNegativeButton("إغلاق", null).show()
            })
            list.addView(card)
        }
        if (focus >= 0 && focus < list.childCount) {
            val v = (list.getChildAt(focus) as LinearLayout).getChildAt(0)
            v.post { v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); v.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
        }
    }

    private fun swap(a: Int, b: Int) {
        val days = ProgramStore.days(this).toMutableList()
        val t = days[a]; days[a] = days[b]; days[b] = t
        ProgramStore.save(this, days); render(b)
    }
}

/** Edit one day: name, arm stretch, exercises (add, remove, reorder, sets/reps, superset). */
class DayEditActivity : BaseActivity() {
    private lateinit var dayId: String
    private lateinit var list: LinearLayout
    private lateinit var nameField: android.widget.EditText
    private lateinit var armBox: android.widget.CheckBox

    private fun day() = ProgramStore.days(this).first { it.id == dayId }
    private fun update(f: (Day) -> Day) {
        ProgramStore.save(this, ProgramStore.days(this).map { if (it.id == dayId) f(it) else it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dayId = intent.getStringExtra("day") ?: run { finish(); return }
        if (ProgramStore.days(this).none { it.id == dayId }) { finish(); return }
        val col = Ui.column(this)
        col.addView(Ui.heading(this, "تعديل اليوم", big = true))
        col.addView(Ui.styled(this, "اسم اليوم", 18f, bold = true))
        nameField = android.widget.EditText(this).apply {
            setText(day().ar); textSize = 21f; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED)
            hint = "مثلاً: صدر وترايسبس"; contentDescription = "اسم اليوم"
            setOnFocusChangeListener { _, has -> if (!has) saveName() }
        }
        col.addView(nameField)
        armBox = android.widget.CheckBox(this).apply {
            text = "إطالة مفاصل اليدين بعد الإحماء"; textSize = 20f; setTextColor(Ui.FG); isChecked = day().armStretch
            setOnCheckedChangeListener { _, c -> update { it.copy(armStretch = c) } }
        }
        col.addView(armBox)
        col.addView(Ui.heading(this, "التمارين"))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(Ui.button(this, "إضافة تمرين", primary = true) { pickExercise() })
        col.addView(Ui.button(this, "إضافة بطن يتبدل كل حصة") { update { it.copy(blocks = it.blocks + ABS) }; render(day().blocks.size - 1) })
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        render(-1)
    }

    private fun saveName() {
        val n = nameField.text.toString().trim()
        if (n.isNotEmpty()) update { it.copy(ar = n, en = "") }
    }

    override fun onPause() { saveName(); super.onPause() }

    private fun render(focus: Int) {
        list.removeAllViews()
        val blocks = day().blocks
        if (blocks.isEmpty()) list.addView(Ui.styled(this, "لا توجد تمارين بعد. اضغط إضافة تمرين.", 19f))
        blocks.forEachIndexed { i, bl ->
            val card = Ui.card(this)
            if (android.os.Build.VERSION.SDK_INT >= 28) card.isScreenReaderFocusable = false
            val label = "${i + 1}. ${blockLabel(bl)}"
            card.addView(Ui.styled(this, label, 20f, bold = true))
            card.addView(Ui.button(this, "خيارات التمرين ${i + 1}") {
                val acts = mutableListOf<Pair<String, () -> Unit>>("الجولات والتكرار والراحة" to { editSetsReps(i) })
                if (i > 0) acts += "تحريك للأعلى" to { move(i, i - 1) }
                if (i < blocks.size - 1) acts += "تحريك للأسفل" to { move(i, i + 1) }
                val single = bl.variants.size == 1 && bl.variants[0].size == 1
                val next = blocks.getOrNull(i + 1)
                if (single && next != null && next.variants.size == 1 && next.variants[0].size == 1)
                    acts += "سوبر سِت: دمج مع التمرين التالي" to { mergeWithNext(i) }
                if (bl.superset && bl.variants.size == 1 && bl.variants[0].size > 1) acts += "فصل السوبر سِت" to { split(i) }
                acts += "حذف التمرين" to { update { d -> d.copy(blocks = d.blocks.filterIndexed { j, _ -> j != i }) }; render(maxOf(0, i - 1)) }
                AlertDialog.Builder(this).setTitle(blockLabel(bl)).setItems(acts.map { it.first }.toTypedArray()) { _, w -> acts[w].second() }
                    .setNegativeButton("إغلاق", null).show()
            })
            list.addView(card)
        }
        if (focus >= 0 && focus < list.childCount) {
            val v = (list.getChildAt(focus) as? LinearLayout)?.getChildAt(0) ?: return
            v.post { v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    private fun move(a: Int, b: Int) {
        update { d -> val l = d.blocks.toMutableList(); val t = l[a]; l[a] = l[b]; l[b] = t; d.copy(blocks = l) }
        render(b)
    }

    private fun mergeWithNext(i: Int) {
        update { d ->
            val l = d.blocks.toMutableList()
            val merged = Block(listOf(l[i].variants[0] + l[i + 1].variants[0]), superset = true, sets = l[i].sets, reps = l[i].reps)
            l[i] = merged; l.removeAt(i + 1); d.copy(blocks = l)
        }
        render(i)
    }

    private fun split(i: Int) {
        update { d ->
            val l = d.blocks.toMutableList()
            val parts = l[i].variants[0].map { Block(listOf(listOf(it)), sets = l[i].sets, reps = l[i].reps) }
            l.removeAt(i); l.addAll(i, parts); d.copy(blocks = l)
        }
        render(i)
    }

    private fun pickExercise() {
        val groups = GROUPS.map { it.first }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اختر العضلة").setItems(groups) { _, gi ->
            val ids = GROUPS[gi].second
            val names = ids.map { "${Catalog[it].nameAr}، ${Catalog[it].en}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("اختر التمرين").setItems(names) { _, ei ->
                update { it.copy(blocks = it.blocks + Block(listOf(listOf(ids[ei])))) }
                render(day().blocks.size - 1)
            }.setNegativeButton("رجوع", null).show()
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun editSetsReps(i: Int) {
        val bl = day().blocks[i]
        val st = Prefs(this)
        val box = Ui.column(this)
        fun field(label: String, value: String, numeric: Boolean, hintText: String = ""): android.widget.EditText {
            box.addView(Ui.styled(this, label, 18f, bold = true))
            return android.widget.EditText(this).apply {
                inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
                setText(value); hint = hintText; textSize = 22f; setTextColor(Ui.FG); setHintTextColor(Ui.MUTED); contentDescription = label
                also { box.addView(it) }
            }
        }
        val defReps = Catalog[bl.variants[0][0]].reps ?: st.defaultReps
        val sets = field("عدد الجولات", (bl.sets ?: bl.repsList?.size ?: st.defaultSets).toString(), true)
        val reps = field("عدد التكرارات في كل جولة", (bl.reps ?: defReps).toString(), true)
        val list = field("أو تكرارات مختلفة لكل جولة، اختياري", bl.repsList?.joinToString(" ") ?: "", false, "مثال: 12 10 8")
        val rest = field("الراحة بين الجولات بالثواني، اتركها فارغة للحساب التلقائي", bl.rest?.toString() ?: "", true,
            "تلقائي: ${Catalog[bl.variants[0][0]].cat.rest}")
        AlertDialog.Builder(this).setTitle(blockLabel(bl)).setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                val rl = list.text.toString().split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull()?.coerceIn(1, 200) }.takeIf { it.isNotEmpty() }
                val s = if (rl != null) rl.size.coerceAtMost(10) else sets.text.toString().toIntOrNull()?.coerceIn(1, 10)
                val r = reps.text.toString().toIntOrNull()?.coerceIn(1, 200)
                val rs = rest.text.toString().toIntOrNull()?.coerceIn(10, 600)
                update { d -> d.copy(blocks = d.blocks.mapIndexed { j, b -> if (j == i) b.copy(sets = s, reps = r, repsList = rl, rest = rs) else b }) }
                render(i)
            }
            .setNeutralButton("الرجوع للافتراضي") { _, _ ->
                update { d -> d.copy(blocks = d.blocks.mapIndexed { j, b -> if (j == i) b.copy(sets = null, reps = null, repsList = null, rest = null) else b }) }
                render(i)
            }
            .setNegativeButton("إلغاء", null).show()
    }
}
