package com.abdelhadi.gymcoach

/**
 * The coach's phrase bank. Each line is a list of segments; a segment is spoken in its own language.
 * Lines are picked at random without repeating the previous pick, so the coach does not sound looped.
 * Later, every line can be matched to a recorded human clip (voice pack).
 */
object CoachLines {
    private val lastPick = mutableMapOf<String, Int>()
    private fun pick(key: String, list: List<List<String>>): List<String> {
        var i = list.indices.random()
        if (list.size > 1 && i == lastPick[key]) i = (i + 1) % list.size
        lastPick[key] = i
        return list[i]
    }
    private fun l(vararg s: String) = s.toList()

    /** Short word said together with a rep number (must stay short to keep the tempo). */
    private val midSet = listOf(l("عاش!"), l("نص الطريق!"), l("كمّل!"), l("Keep it up!"), l("ممتاز!"), l("حلو!"))
    private val pushSet = listOf(l("هيا ارفع!"), l("Push!"), l("شد حيلك!"), l("قوّة!"), l("Come on!"), l("لا توقف!"))
    private val lastRep = listOf(l("الأخيرة!"), l("Last one!"), l("آخر وحدة!"))
    private val setDone = listOf(
        l("عاش يا وحش!"), l("Beast mode!", "ممتاز!"), l("جولة نظيفة!"), l("Great set!"), l("أداء قوي، هكذا أريدك!"),
        l("ولا أروع!"), l("Nice!", "هذي هي الروح."), l("تمام التمام!"), l("بطل!"),
    )
    private val restMotivation = listOf(
        l("Keep going!", "أنت أقوى من التعب."), l("كل جولة تقرّبك من هدفك."), l("تنفّس بعمق، شهيق من الأنف وزفير من الفم."),
        l("Stay focused.", "جودة الحركة أهم من الوزن."), l("اشرب رشفة ماء الآن."), l("خلّ الجولة الجاية بنفس التركيز."),
        l("You got this.", "ركّز على العضلة وأنت تتحرك."), l("العضلة تنبني بالاستمرار، وأنت مستمر. عاش."),
        l("رخّي كتفيك وهزّ يديك شوي."), l("التعب الذي تحس به الآن هو الذي يبني العضلة."), l("No pain, no gain.", "بس بدون إصابات."),
        l("أنا معك لآخر جولة."), l("الجولة الجاية أقوى من اللي قبلها، صح؟"),
    )
    private val facts = listOf(
        l("معلومة: الراحة بين الجولات تساعد العضلة ترجع قوتها، فلا تستعجل."),
        l("معلومة: النزول البطيء بالوزن يعطي العضلة شغل أكثر من الرفع السريع."),
        l("معلومة: النوم الجيد نص التمرين. العضلة تكبر وأنت نايم."),
        l("معلومة: البروتين بعد التمرين يساعد العضلة على البناء."),
        l("معلومة: شرب الماء قبل ما تعطش يحافظ على قوتك طول الحصة."),
        l("معلومة: الثبات على البرنامج أهم من أي تمرين سحري."),
    )
    private val restLastSet = listOf(l("آخر جولة جاية!", "خلّها أقوى وحدة."), l("Last set coming!", "عطني كل اللي عندك."), l("باقي جولة وحدة بس، يلا!"))
    private val transition = listOf(
        l("يلا على التمرين اللي بعده."), l("ما في وقت نضيعه، يلا!"), l("Next one!", "يلا يا بطل."),
        l("خلصنا هذا، ننتقل."), l("تمرين ممتاز، اللي بعده أحلى."),
    )
    private val water = listOf(l("وقت الماء!", "خذ كم رشفة صغيرة."), l("Hydration time.", "اشرب شوية ماء."),
        l("جسمك يحتاج ماء، اشرب رشفات قليلة الآن."), l("استراحة ماء سريعة، رشفتين أو ثلاث."))
    private val halfway = listOf(l("خلصنا نص الحصة!", "كمّل بنفس الحماس."), l("Halfway done!", "أنت ماشي صح."))
    private val lastExercise = listOf(l("آخر تمرين اليوم!", "خلّينا نختم بقوة."), l("Final exercise!", "آخر شوط يا وحش."))

    /** Every fixed sentence the engine speaks (must match the engine's strings exactly). */
    val fixed = listOf(
        "Let's go!", "Go!", "ابدأ!", "نصيحة:", "خمسة.", "نكمل.",
        "عشر ثواني، جهّز نفسك.", "عشرين ثانية. ثبّت الوزن وجهّز وضعيتك.", "خمسة عشر ثانية.",
        "باقي دقيقة واحدة على نهاية الإحماء.", "عشر ثواني. خفّف السرعة.", "انتهى الإحماء. ممتاز.", "انتهت الإطالة.",
        "انتهت الحصة!", "Great workout!", "لا تنسَ تشرب ماء.",
    )

    /** All segments that can be recorded in the voice studio: counts 1–30, then coach lines. */
    fun recordable(): List<String> = ((1..30).map { it.toString() } + fixed +
        listOf(midSet, pushSet, lastRep, setDone, restMotivation, restLastSet, transition, halfway, lastExercise, facts, water)
            .flatten().flatten()).distinct()

    fun midSet() = pick("mid", midSet)
    fun push() = pick("push", pushSet)
    fun lastRep() = pick("last", lastRep)
    fun setDone() = pick("done", setDone)
    fun restMotivation() = pick("rest", restMotivation)
    fun fact() = pick("fact", facts)
    fun restLastSet() = pick("restLast", restLastSet)
    fun transition() = pick("trans", transition)
    fun halfway() = pick("half", halfway)
    fun water() = pick("water", water)
    fun lastExercise() = pick("lastEx", lastExercise)
}
