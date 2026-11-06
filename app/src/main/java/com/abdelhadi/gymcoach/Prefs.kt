package com.abdelhadi.gymcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE)

    var dayId: String?
        get() = p.getString("dayId", null)
        set(v) = p.edit().putString("dayId", v).apply()
    var lastDay: String?
        get() = p.getString("lastDay", null)
        set(v) = p.edit().putString("lastDay", v).apply()
    var speechRate: Float
        get() = p.getFloat("rate", 1.0f)
        set(v) = p.edit().putFloat("rate", v).apply()
    var countLang: String
        get() = p.getString("countLang", "en") ?: "en"
        set(v) = p.edit().putString("countLang", v).apply()
    var transMult: Float
        get() = p.getFloat("transMult", 1.0f)
        set(v) = p.edit().putFloat("transMult", v).apply()
    var cardio: Cardio
        get() = runCatching { Cardio.valueOf(p.getString("cardio", "TREADMILL")!!) }.getOrDefault(Cardio.TREADMILL)
        set(v) = p.edit().putString("cardio", v.name).apply()

    fun variant(dayId: String) = p.getInt("variant.$dayId", 0)
    fun bumpVariant(dayId: String) = p.edit().putInt("variant.$dayId", variant(dayId) + 1).apply()

    var restAdj: Map<String, Int>
        get() {
            val o = runCatching { JSONObject(p.getString("restAdj", "{}")!!) }.getOrDefault(JSONObject())
            return o.keys().asSequence().associateWith { o.optInt(it, 0) }
        }
        set(v) = p.edit().putString("restAdj", JSONObject(v).toString()).apply()

    fun addLog(entry: JSONObject) {
        val arr = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
        arr.put(entry)
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 60)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        p.edit().putString("log", trimmed.toString()).apply()
    }

    fun suggestedDayId(): String {
        val last = lastDay ?: return DAYS[0].id
        val i = DAYS.indexOfFirst { it.id == last }
        return DAYS[(i + 1).mod(DAYS.size)].id
    }

    fun settingsFor(day: Day) = Settings(
        transMult = transMult.toDouble(),
        cardio = cardio,
        restAdj = restAdj,
        variantIdx = variant(day.id),
    )
}
