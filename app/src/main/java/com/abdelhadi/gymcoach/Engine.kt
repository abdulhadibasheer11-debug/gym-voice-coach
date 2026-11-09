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
    /** For the visual screen: phase = set / rest / move / warmup, title = current exercise (Arabic name). */
    @Volatile var phase = ""
    @Volatile var title = ""
    @Volatile var subtitle = ""
    @Volatile var exId: String? = null
    /** Visual progress: total seconds of the current countdown, reps of the current set. */
    @Volatile var clockTotal = 0
    @Volatile var repTotal = 0
    /** Wall-clock start and (moving) target end of the session, for the session bar. */
    @Volatile var startMs = 0L
    @Volatile var endMs = 0L
    /** What comes after the current step, and its exercise (for a small preview). */
    @Volatile var next = ""
    @Volatile var nextExId: String? = null
    /** The exercise whose set finished most recently (for logging its weight during rest/transition). */
    @Volatile var lastSetId: String? = null
    @Volatile var startedAt = 0L
    @Volatile var version = 0L
    fun touch() { version++ }
}

class Controls {
    @Volatile var paused = false
    @Volatile var stop = false
    @Volatile var skip = false
    @Volatile var skipExercise = false
    val addMs = AtomicLong(0)
    /** Extra rest the user asked for (consumed by the engine to move the end time). */
    val addedMs = AtomicLong(0)
}

data class Cue(val atMs: Long, val fn: suspend () -> Unit)

data class SessionResult(val plan: Plan, val elapsedSec: Int, val stopped: Boolean, val exercisesDone: Int, val plannedSec: Int, val doneIds: List<String> = emptyList())

class Engine(
    private val scope: CoroutineScope,
    private val sp: Speaker,
    private val c: Controls,
    private val prefs: Prefs,
    private val ctx: android.content.Context,
    /** Speech input for weight logging; null when that feature is off. */
    private val voice: VoiceInput? = null,
) {
    private val pendingWeightAsk = mutableListOf<String>()

    private val countArabic get() = prefs.countLang == "ar"

    private fun fire(vararg parts: String) { scope.launch { sp.say(*parts) } }

    /** Pause-aware wait. Cues fire once when the remaining time drops to atMs. */
    private suspend fun waitMs(ms: Long, cues: List<Cue> = emptyList(), showClock: Boolean = true) {
        var remaining = ms.toDouble()
        var last = SystemClock.elapsedRealtime()
        val pendingCues = cues.sortedByDescending { it.atMs }.toMutableList()
        if (showClock) Session.clockTotal = max(1, ceil(ms / 1000.0).toInt())
        while (remaining > 0) {
            if (c.stop || c.skip || c.skipExercise) return
            delay(50)
            val now = SystemClock.elapsedRealtime()
            if (!c.paused) {
                remaining -= (now - last)
                val add = c.addMs.getAndSet(0)
                if (add != 0L) {
                    remaining += add; c.addedMs.addAndGet(add)
                    if (showClock) Session.clockTotal += (add / 1000).toInt()
                }
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
        val (work, back) = PHASES[e.id] ?: ("الرفع" to "الإنزال")
        val sb = StringBuilder("$work ${secAr(u)}")
        if (t > 0) sb.append("، ثبات ${secAr(t)}")
        sb.append("، $back ${secAr(d)}")
        if (b > 0) sb.append("، توقف في آخر المدى ${secAr(b)}")
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
        // shift = planned seconds removed by skipping (minus extra rest added). The end time moves with it,
        // so skipping exercises shortens the session instead of being "caught up" later.
        var shift = 0.0
        fun targetEnd() = wallStart + ((plan.totalSec - shift) * 1000).toLong()
        fun elapsed() = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val done = mutableSetOf<String>()
        var lastWater = 0.0 // seconds since start when the last water reminder was given
        var transCount = 0
        val total = plan.blocks.size

        Session.startMs = wallStart
        Session.lastSetId = null
        for ((idx, st) in plan.steps.withIndex()) {
            if (c.stop) break
            c.skip = false
            if (c.skipExercise) {
                if (st !is TransitionStep) { shift += st.dur; continue }
                c.skipExercise = false
            }
            shift -= c.addedMs.getAndSet(0) / 1000.0
            val targetEnd = targetEnd()
            // time management: compress when behind, give a little more rest when ahead
            var dur = st.dur.toDouble()
            val drift = elapsed() - (st.plannedStart - shift)
            val stepStart = elapsed()
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
            Session.endMs = targetEnd
            Session.next = nextText(plan.steps.getOrNull(idx + 1))
            Session.nextExId = nextExercise(plan.steps.getOrNull(idx + 1))
            Session.exId = when (st) {
                is SetStep -> st.id; is RestStep -> st.nextId; is TransitionStep -> st.ex[0]; is PairSwitchStep -> st.to; else -> null
            }
            when (st) {
                is SetStep -> { Session.phase = "set"; Session.title = Catalog[st.id].nameAr; Session.subtitle = "${Catalog[st.id].en} · جولة ${st.setNo} من ${st.of}" }
                is RestStep -> { Session.phase = "rest"; Session.title = "راحة"; Session.subtitle = "التالي: ${Catalog[st.nextId].nameAr}، جولة ${st.nextSet} من ${st.of}" }
                is TransitionStep -> { Session.phase = "move"; Session.title = Catalog[st.ex[0]].nameAr; Session.subtitle = "${Catalog[st.ex[0]].en} · التمرين ${st.blockNo} من ${st.blockTotal}" }
                is PairSwitchStep -> { Session.phase = "move"; Session.title = Catalog[st.to].nameAr; Session.subtitle = "سوبر سِت · ${Catalog[st.to].en}" }
                is CardioStep -> { Session.phase = "warmup"; Session.title = "إحماء: ${st.cardio.ar}"; Session.subtitle = "${st.dur / 60} دقائق" }
                is StretchStep -> { Session.phase = "warmup"; Session.title = "إطالة اليدين"; Session.subtitle = "اتبع الصوت" }
                is IntroStep -> { Session.phase = "warmup"; Session.title = plan.day.ar; Session.subtitle = plan.day.en }
                is PrepStep -> { Session.phase = "move"; Session.title = "استعداد"; Session.subtitle = if (st.kind == "toCardio") "التوجه إلى ${settings.cardio.ar}" else "ماء ونفَس وتجهيز" }
            }
            Session.touch()

            when (st) {
                is IntroStep -> {
                    sp.say("حصة اليوم: ${plan.day.ar}.", plan.day.en,
                        "$total تمارين. المدة المتوقعة ${minAr(plan.totalSec.toDouble())}، وتنتهي حوالي ${clockSpeech(targetEnd)}.",
                        "Let's go!")
                    waitMs(2000)
                }
                is PrepStep -> {
                    val c = settings.cardio.ar
                    when (st.kind) {
                        "toCardio" -> sp.say("توجّه الآن إلى $c وجهّزها.", "عندك ${dur.roundToInt()} ثانية، وسأبدأ معك الإحماء بعدها.")
                        "afterCardio" -> sp.say("استراحة قصيرة: اشرب ماء، وخذ نفسك، وتوجّه لمكان مناسب لإطالة اليدين.", "عندك ${dur.roundToInt()} ثانية.")
                        "afterCardioNoStretch" -> sp.say("استراحة قصيرة: اشرب ماء وخذ نفسك قبل أول تمرين.")
                        else -> sp.say("توجّه لمكان مناسب لإطالة اليدين.", "عندك ${dur.roundToInt()} ثانية.")
                    }
                    waitMs(max(2000L, durMs - 6000), if (dur > 30) listOf(Cue(15_000) { sp.say("خمسة عشر ثانية.") }) else emptyList())
                }
                is CardioStep -> {
                    sp.say("الإحماء: ${st.cardio.ar} ${minAr(st.dur.toDouble())}.", "ابدأ بسرعة خفيفة وزِدها تدريجياً. سأنبهك عند النهاية.")
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
                    val parts = mutableListOf<String>()
                    if (transCount > 1) {
                        parts += when {
                            st.blockNo == st.blockTotal && st.blockTotal > 2 -> CoachLines.lastExercise()
                            st.blockNo == (st.blockTotal + 1) / 2 + 1 && st.blockTotal >= 4 -> CoachLines.halfway()
                            else -> CoachLines.transition()
                        }
                    }
                    parts += listOf(
                        if (transCount == 1) "التمرين الأول:" else "التمرين رقم ${st.blockNo} من ${st.blockTotal}:",
                        e.nameAr + ".", e.en, e.ar + ".", "العضلة المستهدفة: ${e.targetAr}.",
                        if (st.repsText.contains("،")) "${st.sets} جولات، التكرارات: ${st.repsText}." else "${st.sets} جولات، ${st.repsText} تكرار.", tempoAr(e),
                    )
                    if (st.superset && exs.size > 1) parts += listOf("سوبر سِت، بالتبادل مع", exs[1].nameAr + ".", exs[1].en, "بدون راحة بين التمرينين.")
                    exs.forEach { x ->
                        val last = Weights.last(ctx, x.id)
                        if (last != null) parts += (if (exs.size > 1) "آخر وزن في ${x.nameAr}: " else "آخر مرة استخدمت ") + Weights.kgAr(last) + "."
                        Weights.suggestion(ctx, x.id)?.let { parts += it }
                    }
                    if (transCount > 1 && remain > 60) parts += "باقي على نهاية الحصة حوالي ${minAr(remain)}."
                    if (drift > 60) parts += "نحن متأخرين ${minAr(drift)}، سأختصر الراحة قليلاً."
                    parts += "عندك ${dur.roundToInt()} ثانية لتجهيز الجهاز والوزن."
                    sp.say(parts)
                    waitMs(max(3000L, durMs - 4000), listOf(Cue(20_000) { sp.say("عشرين ثانية. ثبّت الوزن وجهّز وضعيتك.") }))
                }
                is PairSwitchStep -> {
                    val e = Catalog[st.to]
                    if (st.round == 0) sp.say("انتقل الآن إلى", e.nameAr + ".", e.en, e.ar + ".", tempoAr(e)) else sp.say("انتقل إلى", e.nameAr)
                    waitMs(max(2000L, durMs - 4000), if (st.dur > 25) listOf(Cue(15_000) { sp.say("خمسة عشر ثانية.") }) else emptyList())
                }
                is SetStep -> {
                    doSet(st)
                    if (!c.skip && !c.stop && !c.skipExercise) {
                        done += st.id
                        Session.lastSetId = st.id
                        if (st.setNo == 1 && voice != null && st.id !in pendingWeightAsk) pendingWeightAsk += st.id
                    }
                }
                is RestStep -> {
                    val e = Catalog[st.nextId]
                    sp.say("راحة ${dur.roundToInt()} ثانية.")
                    val askStart = elapsed()
                    if (voice != null && pendingWeightAsk.isNotEmpty() && dur >= 40) {
                        pendingWeightAsk.toList().forEach { if (!c.stop && !c.skip && !c.skipExercise) askWeight(it) }
                        pendingWeightAsk.clear()
                    }
                    val askedMs = ((elapsed() - askStart) * 1000).toLong()
                    if (settings.waterEveryMin > 0 && elapsed() - lastWater >= settings.waterEveryMin * 60 && dur >= 40) {
                        lastWater = elapsed(); sp.say(CoachLines.water())
                    }
                    val w = max(5000L, durMs - 3000 - askedMs)
                    val tip = e.tips[(st.nextSet - 2).mod(e.tips.size)]
                    val cues = mutableListOf(Cue(10_000) {
                        if (st.superset) sp.say("عشر ثواني. الجولة الجاية:", e.en) else sp.say("عشر ثواني، جهّز نفسك.")
                    })
                    if (w > 35_000) cues += Cue(w - 8000) {
                        sp.say(if (st.nextSet == st.of) CoachLines.restLastSet() else CoachLines.restMotivation())
                    }
                    if (w > 55_000) cues += Cue(w / 2 + 3000) { sp.say("نصيحة:", tip) }
                    if (w > 80_000) cues += Cue(25_000) { sp.say(CoachLines.fact()) }
                    waitMs(w, cues)
                }
            }
            // a skipped step gives its unused time back: the session end moves earlier
            if (c.skip || c.skipExercise) shift += max(0.0, dur - (elapsed() - stepStart))
        }
        return SessionResult(plan, elapsed().roundToInt(), c.stop, done.size, (plan.totalSec - shift).roundToInt(), done.toList())
    }

    private fun countWord(n: Int, extra: String?, fast: Boolean) {
        val r = min(1.8f, sp.rate * if (fast) 1.5f else 1.15f)
        sp.count(if (countArabic) n.toString() else NumberWords.en(n), countArabic, flush = fast, r = r, clipKey = n.toString())
        if (extra != null) sp.count(extra, extra.any { it in '؀'..'ۿ' }, flush = false, r = r)
    }

    private suspend fun doSet(st: SetStep) {
        val e = Catalog[st.id]
        Session.remainingSec = -1
        Session.repTotal = st.reps
        Session.subtitle = "${e.en} · جولة ${st.setNo} من ${st.of}"
        sp.say("جولة ${st.setNo} من ${st.of}.", e.nameAr)
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
            Session.status = "${e.nameAr}، جولة ${st.setNo} من ${st.of}، التكرار $i من ${st.reps}"
            Session.remainingSec = i
            Session.touch()
            val extra = when {
                i == st.reps -> CoachLines.lastRep().first()
                !fast && st.reps >= 8 && i == st.reps - 2 -> CoachLines.push().first()
                !fast && st.reps >= 10 && i == st.reps / 2 -> CoachLines.midSet().first()
                fast && i % 25 == 0 && i < st.reps -> CoachLines.midSet().first()
                else -> null
            }
            sp.onRep?.invoke()
            countWord(i, extra, fast)
            waitMs(((up + top) * 1000).toLong(), showClock = false)
            if (!fast) sp.beep(Beep.TICK)
            waitMs(((down + bot) * 1000).toLong(), showClock = false)
        }
        sp.beep(Beep.DONE)
        sp.say(CoachLines.setDone())
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
                    sp.count(if (countArabic) i.toString() else NumberWords.en(i), countArabic, flush = true, r = sp.rate * 1.1f, clipKey = i.toString())
                    waitMs((item.tempo * 1000).toLong(), showClock = false)
                }
            } else {
                waitMs(item.hold * 1000L, listOf(Cue(5000) { sp.say("خمسة.") }))
                sp.beep(Beep.TICK)
            }
        }
        if (!c.stop) sp.say("انتهت الإطالة.")
    }

    /** Ask for the weight just used; silence keeps last time's weight. */
    private suspend fun askWeight(id: String) {
        val v = voice ?: return
        val e = Catalog[id]
        val last = Weights.last(ctx, id)
        sp.say("كم الوزن في ${e.nameAr}؟", if (last != null) "قل الرقم بعد الصافرة، أو اسكت لأسجّل ${Weights.kgAr(last)}." else "قل الرقم بالكيلو بعد الصافرة.")
        for (attempt in 1..2) {
            sp.beep(Beep.TICK)
            val r = v.hear(5000)
            val kg = r.alts?.let { VoiceInput.number(it) }?.takeIf { it in 0.5..500.0 }
            when {
                kg != null -> { Weights.add(ctx, id, kg); sp.say("سجّلت ${Weights.kgAr(kg)}."); return }
                r.failed -> { sp.say("التعرّف على الصوت لا يعمل الآن، غالباً بسبب الإنترنت. سجّل الوزن من زر تسجيل الوزن."); return }
                // complete silence on the first try means "same as last time"
                r.silent && attempt == 1 && last != null -> {
                    Weights.add(ctx, id, last); sp.say("سجّلت ${Weights.kgAr(last)} مثل المرة الماضية."); return
                }
                attempt == 1 -> sp.say("ما سمعت جيداً، أعد الرقم.")
                else -> sp.say("ما سمعت جيداً. تقدر تسجّله الآن من زر تسجيل الوزن، أو بعد الحصة من شاشة الأوزان.")
            }
        }
    }

    private fun nextText(n: Step?): String = when (n) {
        null -> "هذه آخر خطوة في الحصة."
        is SetStep -> "بعدها: ${Catalog[n.id].nameAr}، جولة ${n.setNo} من ${n.of}."
        is RestStep -> "بعدها: راحة ${n.dur} ثانية."
        is TransitionStep -> "التمرين التالي: ${Catalog[n.ex[0]].nameAr}."
        is PairSwitchStep -> "بعدها: الانتقال إلى ${Catalog[n.to].nameAr}."
        is CardioStep -> "بعدها: إحماء على ${n.cardio.ar}."
        is StretchStep -> "بعدها: إطالة اليدين."
        is PrepStep -> "بعدها: استعداد قصير."
        is IntroStep -> ""
    }
    private fun nextExercise(n: Step?): String? = when (n) {
        is SetStep -> n.id; is TransitionStep -> n.ex[0]; is PairSwitchStep -> n.to; else -> null
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
            is PrepStep -> "استعداد"
        }
        return "$where. باقي على نهاية الحصة حوالي $remainMin دقيقة."
    }
}
