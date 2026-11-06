package com.abdelhadi.gymcoach

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("gymcoach", Context.MODE_PRIVATE)

    var dayId: String?
        get() = p.getString("dayId", null)
        set(v) = p.edit().putString("dayId", v).apply()
    var lastDay: String?
        get() = p.getString("lastDay", null)
        set(v) = p.edit().putString("lastDay", v).apply()
    var speechRate: Float
        get() = p.getFloat("rate2", 1.2f)
        set(v) = p.edit().putFloat("rate2", v).apply()
    var countLang: String
        get() = p.getString("countLang", "en") ?: "en"
        set(v) = p.edit().putString("countLang", v).apply()
    var transMult: Float
        get() = p.getFloat("transMult", 1.0f)
        set(v) = p.edit().putFloat("transMult", v).apply()
    var cardio: Cardio
        get() = runCatching { Cardio.valueOf(p.getString("cardio", "TREADMILL")!!) }.getOrDefault(Cardio.TREADMILL)
        set(v) = p.edit().putString("cardio", v.name).apply()

    fun cardioMinutes(c: Cardio) = p.getInt("cardioMin.${c.name}", c.minutes)
    fun setCardioMinutes(c: Cardio, m: Int) = p.edit().putInt("cardioMin.${c.name}", m).apply()
    var defaultSets: Int
        get() = p.getInt("defaultSets", 3)
        set(v) = p.edit().putInt("defaultSets", v).apply()
    var defaultReps: Int
        get() = p.getInt("defaultReps", 12)
        set(v) = p.edit().putInt("defaultReps", v).apply()
    var waterEvery: Int
        get() = p.getInt("waterEvery", 15)
        set(v) = p.edit().putInt("waterEvery", v).apply()

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

    fun days() = ProgramStore.days(ctx)

    fun suggestedDayId(): String {
        val days = days()
        val last = lastDay ?: return days[0].id
        val i = days.indexOfFirst { it.id == last }
        return days[(i + 1).mod(days.size)].id
    }

    var musicMode: String
        get() = p.getString("musicMode", "none") ?: "none"
        set(v) = p.edit().putString("musicMode", v).apply()
    var musicFolder: String?
        get() = p.getString("musicFolder", null)
        set(v) = p.edit().putString("musicFolder", v).apply()
    var musicVolume: Float
        get() = p.getFloat("musicVolume", 0.5f)
        set(v) = p.edit().putFloat("musicVolume", v).apply()
    var useRecorded: Boolean
        get() = p.getBoolean("useRecorded", false)
        set(v) = p.edit().putBoolean("useRecorded", v).apply()

    fun settingsFor(day: Day) = Settings(
        sets = defaultSets,
        defaultReps = defaultReps,
        cardioMinutes = cardioMinutes(cardio),
        waterEveryMin = waterEvery,
        transMult = transMult.toDouble(),
        cardio = cardio,
        restAdj = restAdj,
        variantIdx = variant(day.id),
    )
}
