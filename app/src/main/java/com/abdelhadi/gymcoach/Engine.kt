package com.abdelhadi.gymcoach

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared, observable session state (read by the screen and the notification). */
object Session {
    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var status = ""
    @Volatile var progress = ""
    @Volatile var remainingSec = -1
    @Volatile var restId: String? = null
    @Volatile var version = 0L
    fun touch() { version++ }
}

class Controls {
    @Volatile var paused = false
    @Volatile var stop = false
    @Volatile var skip = false
    @Volatile var skipExercise = false
    val addMs = AtomicLong(0)
}

data class Cue(val atMs: Long, val fn: suspend () -> Unit)

data class SessionResult(val plan: Plan, val elapsedSec: Int, val stopped: Boolean, val exercisesDone: Int)

class Engine(
    private val scope: CoroutineScope,
    private val sp: Speaker,
    private val c: Controls,
    private val prefs: Prefs,
) {
    private val countArabic get() = prefs.countLang == "ar"
    private var motIdx = (0..6).random()
    private val motivation = listOf(
        listOf("Keep going!", "أنت أقوى من التعب."),
        listOf("Nice work.", "كل جولة تقرّبك من هدفك."),
        listOf("تنفّس بعمق، شهيق من الأنف وزفير من الفم."),
        listOf("Stay focused.", "جودة الحركة أهم من الوزن."),
        listOf("اشرب رشفة ماء الآن."),
        listOf("Strong set!", "خلّ الجولة الجاية بنفس التركيز."),
        listOf("You got this.", "ركّز على العضلة المستهدفة وأنت تتحرك."),
    )
    private fun nextMotivation() = motivation[motIdx++ % motivation.size]

    private fun fire(vararg parts: String) { scope.launch { sp.say(*parts) } }

    /** Pause-aware wait. Cues fire once when the remaining time drops to atMs. */
    private suspend fun waitMs(ms: Long, cues: List<Cue> = emptyList(), showClock: Boolean = true) {
        var remaining = ms.toDouble()
        var last = SystemClock.elapsedRealtime()
        val pendingCues = cues.sortedByDescending { it.atMs }.toMutableList()
        while (remaining > 0) {
            if (c.stop || c.skip || c.skipExercise) return
            delay(50)
            val now = SystemClock.elapsedRealtime()
            if (!c.paused) {
                remaining -= (now - last)
                remaining += c.addMs.getAndSet(0)
                while (pendingCues.isNotEmpty() && remaining <= pendingCues[0].atMs) {
                    val cue = pendingCues.removeAt(0)
                    scope.launch { cue.fn() }
                }
                if (showClock) {
                    val s = max(0, ceil(remaining / 1000).toInt())
                    if (s != Session.remainingSec) { Session.remainingSec = s; Session.touch() }
                }
            }
            last = now
        }
    }

    private fun secAr(n: Double) = when (n) {
        0.5 -> "نص ثانية"; 1.0 -> "ثانية"; 2.0 -> "ثانيتين"
        else -> "${if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()} ثواني"
    }
    private fun tempoAr(e: Exercise): String {
        val (u, t, d, b) = e.tempo.toList()
        val sb = StringBuilder("الرفع ${secAr(u)}")
        if (t > 0) sb.append("، تثبيت ${secAr(t)}")
        sb.append("، الإنزال ${secAr(d)}")
        if (b > 0) sb.append("، توقف تحت ${secAr(b)}")
        return sb.append(".").toString()
    }
    private fun minAr(sec: Double): String {
        val m = (sec / 60).roundToInt()
        return when { m <= 1 -> "دقيقة واحدة"; m == 2 -> "دقيقتين"; m in 3..10 -> "$m دقائق"; else -> "$m دقيقة" }
    }
    private fun clockSpeech(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val h = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val m = cal.get(Calendar.MINUTE)
        return if (m == 0) "الساعة $h" else "الساعة $h و $m دقيقة"
    }

    suspend fun run(plan: Plan, settings: Settings): SessionResult {
        val t0 = SystemClock.elapsedRealtime()
        val wallStart = System.currentTimeMillis()
        val targetEnd = wallStart + plan.totalSec * 1000L
        fun elapsed() = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val done = mutableSetOf<String>()
        var transCount = 0
        val total = plan.blocks.size

        for (st in plan.steps) {
            if (c.stop) break
            c.skip = false
            if (c.skipExercise) {
                if (st !is TransitionStep) continue
                c.skipExercise = false
            }
            // time management: compress when behind, give a little more rest when ahead
            var dur = st.dur.toDouble()
            val drift = elapsed() - st.plannedStart
            if (st is RestStep || st is TransitionStep) {
                val floor = if (st is RestStep) 40.0 else 30.0
                if (drift > 20) dur = max(floor, dur - min(drift, dur * 0.3))
                else if (drift < -30 && st is RestStep) dur += min(-drift, 15.0)
            }
            val durMs = (dur * 1000).toLong()
            val remainMin = max(0, ((targetEnd - System.currentTimeMillis()) / 60000.0).roundToInt())
            Session.progress = progressText(st, total, remainMin)
            Session.remainingSec = -1
            Session.restId = (st as? RestStep)?.nextId
            Session.touch()

            when (st) {
                is IntroStep -> {
                    sp.say("حصة اليوم: ${plan.day.ar}.", plan.day.en,
                        "$total تمارين. المدة المتوقعة ${minAr(plan.totalSec.toDouble())}، وتنتهي حوالي ${clockSpeech(targetEnd)}.",
                        "Let's go!")
                    waitMs(2000)
                }
                is CardioStep -> {
                    sp.say("الإحماء: ${st.cardio.ar} ${st.cardio.minutes} دقائق.", "ابدأ بسرعة خفيفة وزِدها تدريجياً. سأنبهك عند النهاية.")
                    val half = durMs / 2
                    waitMs(durMs - 3000, listOf(
                        Cue(half) { sp.say("نص وقت الإحماء. باقي ${minAr(half / 1000.0)}.") },
                        Cue(60_000) { sp.say("باقي دقيقة واحدة على نهاية الإحماء.") },
                        Cue(10_000) { sp.say("عشر ثواني. خفّف السرعة.") },
                    ))
                    if (!c.stop) sp.say("انتهى الإحماء. ممتاز.")
                }
                is StretchStep -> doStretch(st)
                is TransitionStep -> {
                    transCount++
                    val exs = st.ex.map { Catalog[it] }
                    val e = exs[0]
                    val remain = (targetEnd - System.currentTimeMillis()) / 1000.0
                    val parts = mutableListOf(
                        if (transCount == 1) "التمرين الأول." else "التمرين التالي، رقم ${st.blockNo} من ${st.blockTotal}.",
                        e.en, e.ar + ".", "العضلة المستهدفة: ${e.targetAr}.",
                        "${settings.sets} جولات، ${repsOf(e, settings)} تكرار.", tempoAr(e),
                    )
                    if (st.superset && exs.size > 1) parts += listOf("سوبر سِت، بالتبادل مع", exs[1].en, exs[1].ar + ".", "بدون راحة بين التمرينين.")
                    if (transCount > 1 && remain > 60) parts += "باقي على نهاية الحصة حوالي ${minAr(remain)}."
                    if (drift > 60) parts += "نحن متأخرين ${minAr(drift)}، سأختصر الراحة قليلاً."
                    parts += "عندك ${dur.roundToInt()} ثانية لتجهيز الجهاز والوزن."
                    sp.say(parts)
                    waitMs(max(3000L, durMs - 4000), listOf(Cue(20_000) { sp.say("عشرين ثانية. ثبّت الوزن وجهّز وضعيتك.") }))
                }
                is PairSwitchStep -> {
                    val e = Catalog[st.to]
                    if (st.round == 0) sp.say("انتقل الآن إلى", e.en, e.ar + ".", tempoAr(e)) else sp.say("انتقل إلى", e.en)
                    waitMs(max(2000L, durMs - 4000), if (st.dur > 25) listOf(Cue(15_000) { sp.say("خمسة عشر ثانية.") }) else emptyList())
                }
                is SetStep -> { doSet(st); if (!c.skip && !c.stop) done += st.id }
                is RestStep -> {
                    val e = Catalog[st.nextId]
                    sp.say("راحة ${dur.roundToInt()} ثانية.")
                    val w = durMs - 3000
                    val tip = e.tips[(st.nextSet - 2).mod(e.tips.size)]
                    val cues = mutableListOf(Cue(10_000) {
                        if (st.superset) sp.say("عشر ثواني. الجولة الجاية:", e.en) else sp.say("عشر ثواني، جهّز نفسك.")
                    })
                    if (w > 35_000) cues += Cue(w - 8000) {
                        val m = nextMotivation()
                        if (st.nextSet == st.of) sp.say(listOf("آخر جولة جاية!") + m) else sp.say(m)
                    }
                    if (w > 55_000) cues += Cue(w / 2 - 2000) { sp.say("نصيحة:", tip) }
                    waitMs(w, cues)
                }
            }
        }
        return SessionResult(plan, elapsed().roundToInt(), c.stop, done.size)
    }

    private fun countWord(n: Int, extra: String?, fast: Boolean) {
        val r = min(1.8f, sp.rate * if (fast) 1.5f else 1.15f)
        sp.count(n.toString(), countArabic, flush = fast, r = r)
        if (extra != null) sp.count(extra, countArabic || extra.any { it in '؀'..'ۿ' }, flush = false, r = r)
    }

    private suspend fun doSet(st: SetStep) {
        val e = Catalog[st.id]
        Session.remainingSec = -1
        sp.say("جولة ${st.setNo} من ${st.of}.", e.en)
        repeat(3) { if (c.stop || c.skip) return; sp.beep(Beep.COUNTDOWN); waitMs(800, showClock = false) }
        if (c.stop || c.skip) return
        sp.beep(Beep.GO)
        sp.count(if (countArabic) "ابدأ!" else "Go!", countArabic, flush = true, r = 1.2f)
        waitMs(700, showClock = false)
        val (up, top, down, bot) = e.tempo.toList()
        val fast = e.repSeconds < 2.0
        for (i in 1..st.reps) {
            if (c.stop || c.skip || c.skipExercise) return
            while (c.paused) delay(100)
            Session.status = "${e.en}، جولة ${st.setNo} من ${st.of}، التكرار $i من ${st.reps}"
            Session.touch()
            val extra = when {
                i == st.reps -> if (countArabic) "الأخيرة!" else "Last one!"
                !fast && i == st.reps - 2 -> if (countArabic) "يلا!" else "Push!"
                else -> null
            }
            countWord(i, extra, fast)
            waitMs(((up + top) * 1000).toLong(), showClock = false)
            if (!fast) sp.beep(Beep.TICK)
            waitMs(((down + bot) * 1000).toLong(), showClock = false)
        }
        sp.beep(Beep.DONE)
        sp.say(if (countArabic) "ممتاز، انتهت الجولة." else "Done!", if (countArabic) "" else "ممتاز.")
    }

    private suspend fun doStretch(st: StretchStep) {
        sp.say("إطالة لمفاصل اليدين، حوالي ${minAr(st.dur.toDouble())}. اتبع صوتي.")
        for (item in st.items) {
            if (c.stop || c.skip || c.skipExercise) return
            sp.say(item.ar + ".")
            if (item.count > 0) {
                for (i in 1..item.count) {
                    if (c.stop || c.skip) return
                    while (c.paused) delay(100)
                    sp.count(i.toString(), countArabic, flush = true, r = sp.rate * 1.1f)
                    waitMs((item.tempo * 1000).toLong(), showClock = false)
                }
            } else {
                waitMs(item.hold * 1000L, listOf(Cue(5000) { sp.say("خمسة.") }))
                sp.beep(Beep.TICK)
            }
        }
        if (!c.stop) sp.say("انتهت الإطالة.")
    }

    private fun progressText(st: Step, total: Int, remainMin: Int): String {
        val where = when (st) {
            is SetStep -> "${Catalog[st.id].en}، جولة ${st.setNo} من ${st.of}"
            is RestStep -> "راحة"
            is TransitionStep -> "التنقل إلى التمرين ${st.blockNo} من $total"
            is PairSwitchStep -> "تبديل السوبر سِت"
            is CardioStep -> "الإحماء على ${st.cardio.ar}"
            is StretchStep -> "إطالة اليدين"
            is IntroStep -> "البداية"
        }
        return "$where. باقي على نهاية الحصة حوالي $remainMin دقيقة."
    }
}
