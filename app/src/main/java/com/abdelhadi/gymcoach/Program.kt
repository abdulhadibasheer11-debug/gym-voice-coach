package com.abdelhadi.gymcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The user's own program (any number of days, fully editable), stored as JSON. */
object ProgramStore {
    private const val KEY = "program"

    fun days(ctx: Context): List<Day> {
        val raw = ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE).getString(KEY, null)
        val parsed = raw?.let { runCatching { fromJson(it) }.getOrNull() }
        return if (parsed.isNullOrEmpty()) DEFAULT_DAYS else parsed
    }

    fun save(ctx: Context, days: List<Day>) {
        ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE).edit().putString(KEY, toJson(days)).apply()
    }

    fun newDayId() = "d" + System.currentTimeMillis().toString(36)

    private fun toJson(days: List<Day>): String = JSONArray().apply {
        days.forEach { d ->
            put(JSONObject().apply {
                put("id", d.id); put("ar", d.ar); put("en", d.en); put("arm", d.armStretch)
                put("blocks", JSONArray().apply {
                    d.blocks.forEach { b ->
                        put(JSONObject().apply {
                            put("variants", JSONArray().apply { b.variants.forEach { put(JSONArray(it)) } })
                            put("superset", b.superset)
                            b.sets?.let { put("sets", it) }
                            b.reps?.let { put("reps", it) }
                        })
                    }
                })
            })
        }
    }.toString()

    private fun fromJson(s: String): List<Day> {
        val arr = JSONArray(s)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val bl = o.getJSONArray("blocks")
            Day(
                o.getString("id"), o.getString("ar"), o.optString("en", ""), o.optBoolean("arm", true),
                (0 until bl.length()).mapNotNull { j ->
                    val bo = bl.getJSONObject(j)
                    val va = bo.getJSONArray("variants")
                    val variants = (0 until va.length()).map { k ->
                        val ids = va.getJSONArray(k)
                        (0 until ids.length()).map { ids.getString(it) }.filter { it in Catalog.all }
                    }.filter { it.isNotEmpty() }
                    if (variants.isEmpty()) null
                    else Block(variants, bo.optBoolean("superset", false),
                        if (bo.has("sets")) bo.getInt("sets") else null,
                        if (bo.has("reps")) bo.getInt("reps") else null)
                },
            )
        }
    }
}

/** Ready-made programs the user can start from, then edit freely. */
object Templates {
    private fun d(id: String, ar: String, arm: Boolean, vararg blocks: Block) = Day(id, ar, "", arm, blocks.toList())

    val all: List<Pair<String, () -> List<Day>>> = listOf(
        "برنامجك الأصلي، 5 أيام: صدر، ظهر، ذراعين، كتف، أرجل" to { DEFAULT_DAYS },
        "يوم واحد: الجسم كامل" to { listOf(
            d("full", "الجسم كامل", true, b("leg_press"), b("chest_press_high"), b("pulldown_wide"), b("machine_shoulder_press"),
                b("cable_row"), b("leg_curl"), b("tri_rope", "biceps_machine", superset = true), ABS),
        ) },
        "يومين: الجسم كامل أ و ب" to { listOf(
            d("fa", "الجسم كامل أ", true, b("leg_press"), b("chest_press_high"), b("pulldown_wide"), b("db_lateral"),
                b("leg_curl"), b("tri_rope", "barbell_curl", superset = true), ABS),
            d("fb", "الجسم كامل ب", true, b("squat_machine"), b("incline_machine"), b("cable_row"), b("machine_shoulder_press"),
                b("leg_ext"), b("cable_curl_rope", "tri_db_overhead", superset = true), b("calf_seated")),
        ) },
        "3 أيام: دفع، سحب، أرجل" to { listOf(
            d("push", "دفع: صدر وكتف وترايسبس", true, b("chest_press_high"), b("smith_bench"), b("pec_deck"),
                b("machine_shoulder_press"), b("db_lateral"), b("tri_rope"), ABS),
            d("pull", "سحب: ظهر وبايسبس", true, b("pulldown_wide"), b("cable_row"), b("tbar_row"), b("reverse_pec_deck"),
                b("db_shrug"), b("biceps_machine"), b("cable_curl_rope")),
            d("legs3", "أرجل", false, b("leg_ext"), b("leg_curl"), b("leg_press"), b("squat_machine"), b("calf_seated"), ABS),
        ) },
        "4 أيام: علوي وسفلي مرتين" to { listOf(
            d("up1", "علوي 1", true, b("chest_press_high"), b("pulldown_wide"), b("machine_shoulder_press"), b("cable_row"),
                b("tri_rope", "barbell_curl", superset = true), ABS),
            d("lo1", "سفلي 1", false, b("leg_press"), b("leg_curl"), b("leg_ext"), b("adductor"), b("calf_seated")),
            d("up2", "علوي 2", true, b("incline_machine"), b("machine_row"), b("db_lateral"), b("pec_deck"),
                b("cable_curl_rope", "tri_db_overhead", superset = true), ABS),
            d("lo2", "سفلي 2", false, b("squat_machine"), b("leg_curl"), b("abductor"), b("leg_ext"), b("calf_seated")),
        ) },
        "6 أيام: دفع، سحب، أرجل مرتين" to { listOf(
            d("p1", "دفع 1", true, b("chest_press_high"), b("smith_bench"), b("machine_shoulder_press"), b("db_lateral"), b("tri_rope"), ABS),
            d("l1", "سحب 1", true, b("pulldown_wide"), b("cable_row"), b("reverse_pec_deck"), b("db_shrug"), b("biceps_machine")),
            d("g1", "أرجل 1", false, b("leg_press"), b("leg_curl"), b("leg_ext"), b("calf_seated")),
            d("p2", "دفع 2", true, b("incline_machine"), b("seated_dip"), b("pec_deck"), b("machine_lateral"), b("tri_db_overhead"), ABS),
            d("l2", "سحب 2", true, b("pulldown_neutral"), b("tbar_row"), b("machine_row"), b("face_pull"), b("cable_curl_rope")),
            d("g2", "أرجل 2", false, b("squat_machine"), b("leg_curl"), b("adductor"), b("abductor"), b("calf_seated")),
        ) },
    )
}

/** A short readable description of a block, for lists and TalkBack. */
fun blockLabel(bl: Block): String {
    val first = bl.variants[0]
    val names = first.joinToString(" مع ") { Catalog[it].nameAr }
    val kind = when {
        bl.variants.size > 1 -> "بالتناوب كل حصة بين ${bl.variants.size} تمارين، مثل $names"
        bl.superset && first.size > 1 -> "سوبر سِت: $names"
        else -> names
    }
    val sr = buildString {
        append("، ${bl.sets ?: 3} جولات")
        append("، ${bl.reps ?: (Catalog[first[0]].reps ?: 12)} تكرار")
    }
    return kind + sr
}
