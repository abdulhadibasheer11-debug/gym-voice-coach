package com.abdelhadi.gymcoach

import kotlin.math.roundToInt

enum class Cat(val rest: Int) { HEAVY(120), COMPOUND(90), ISOLATION(75), SMALL(60), ABS(45) }
enum class Equip { MACHINE, CABLE, FREE }

/** tempo = lift, hold at top, lower, pause at bottom (seconds). Research: 0.5–8 s per rep give similar hypertrophy. */
data class Exercise(
    val id: String, val en: String, val ar: String, val targetAr: String,
    val equip: Equip, val station: String, val cat: Cat,
    val tempo: DoubleArray, val tips: List<String>, val reps: Int? = null,
) {
    val repSeconds get() = tempo.sum()
    /** Common Arabic gym name of the machine/exercise (the `ar` field is a spoken description). */
    val nameAr: String get() = AR_NAMES[id] ?: ar
}

val AR_NAMES = mapOf(
    "chest_press_high" to "جهاز ضغط الصدر", "smith_bench" to "بنش سميث مستوي", "incline_machine" to "جهاز الصدر العلوي المائل",
    "seated_dip" to "جهاز الديبس بالجلوس", "pec_deck" to "جهاز الفراشة، البترفلاي", "tri_rope" to "ترايسبس حبل على الكيبل",
    "tri_bar" to "ترايسبس بار على الكيبل", "tri_db_overhead" to "ترايسبس دمبل خلف الرأس", "tri_plate" to "ترايسبس طارة خلف الرأس",
    "pulldown_wide" to "سحب علوي واسع", "cable_row" to "سحب أرضي، روينج كيبل", "pulldown_neutral" to "سحب علوي بمسكة متوازية",
    "machine_row" to "جهاز التجديف بالجلوس", "tbar_row" to "تي بار، الطرمبة", "back_ext" to "الظهر السفلي، الكتونية",
    "biceps_machine" to "جهاز الباي", "barbell_curl" to "باي بار حر", "cable_curl_rope" to "باي حبل هامر على الكيبل",
    "cable_curl_bar" to "باي بار على الكيبل", "wrist_curl" to "جهاز الرست للسواعد",
    "db_lateral" to "رفرفة جانبي دمبل", "db_press" to "ضغط أكتاف دمبل", "machine_shoulder_press" to "جهاز ضغط الأكتاف",
    "machine_lateral" to "جهاز الرفرفة الجانبية", "reverse_pec_deck" to "فراشة عكسي للكتف الخلفي", "face_pull" to "فيس بول بالحبل",
    "db_shrug" to "ترابيس دمبل", "leg_ext" to "جهاز الرجل الأمامية، ليج اكستنشن", "leg_curl" to "جهاز الرجل الخلفية، ليج كيرل",
    "leg_press" to "جهاز دفع الأرجل، ليج برس", "squat_machine" to "جهاز السكوات", "adductor" to "جهاز الضم",
    "abductor" to "جهاز الفتح", "calf_seated" to "جهاز السمانة بالجلوس",
    "abs_machine" to "جهاز البطن، كرنش", "knee_raise" to "رفع الركب على جهاز الكابتن", "cable_crunch" to "كرنش بالكيبل",
    "decline_crunch" to "بطن على البنش المائل", "torso_rotation" to "جهاز دوران الخصر",
)

private fun ex(
    id: String, en: String, ar: String, targetAr: String, equip: Equip, station: String, cat: Cat,
    tempo: DoubleArray, vararg tips: String, reps: Int? = null,
) = Exercise(id, en, ar, targetAr, equip, station, cat, tempo, tips.toList(), reps)

private fun t(a: Double, b: Double, c: Double, d: Double) = doubleArrayOf(a, b, c, d)

object Catalog {
    val all: Map<String, Exercise> = listOf(
        // ---- Chest & triceps
        ex("chest_press_high", "Seated Chest Press, high grip", "جهاز دفع الصدر وأنت جالس، المسكة المرتفعة", "أعلى الصدر", Equip.MACHINE, "chest_press", Cat.COMPOUND, t(1.0, 0.0, 2.0, 0.0),
            "ثبّت ظهرك على المسند، وارجع كتفيك للخلف.", "لا تقفل الكوع تماماً في نهاية الدفع."),
        ex("smith_bench", "Smith Machine Flat Bench Press", "بار السميث على السكة، استلقِ على البنش المستوي", "منتصف الصدر", Equip.MACHINE, "smith", Cat.HEAVY, t(1.0, 0.0, 2.0, 0.5),
            "انزل بالبار لمنتصف الصدر بهدوء.", "قدماك ثابتتان على الأرض، وظهرك ملتصق بالبنش."),
        ex("incline_machine", "Seated Incline Press Machine", "جهاز تجلس عليه وتدفع للأعلى", "أعلى الصدر والكتف الأمامي", Equip.MACHINE, "incline_press", Cat.COMPOUND, t(1.0, 0.0, 2.0, 0.0),
            "ادفع للأعلى بدون ما ترفع ظهرك عن المسند."),
        ex("seated_dip", "Seated Dip Machine", "جهاز تجلس عليه وتدفع للأسفل", "أسفل الصدر والترايسبس", Equip.MACHINE, "seated_dip", Cat.COMPOUND, t(1.0, 1.0, 2.0, 0.0),
            "ادفع للأسفل حتى تفرد ذراعك، وارجع ببطء.", "خلّي مرفقيك قريبين من جسمك."),
        ex("pec_deck", "Pec Deck Fly", "جهاز البترفلاي، ضم اليدين للأمام", "الصدر", Equip.MACHINE, "pec_deck", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "اعصر الصدر ثانية كاملة لما تلتقي اليدين.", "ارجع ببطء، لا تخلّي الوزن يسحبك."),
        ex("tri_rope", "Cable Rope Pushdown", "الكيبل من الأعلى بالحبل، ادفع للأسفل وأنت واقف مائل قليلاً", "الترايسبس", Equip.CABLE, "cable_high", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "افتح الحبل للجانبين في آخر الحركة.", "المرفق ثابت بجانب جسمك، فقط الساعد يتحرك."),
        ex("tri_bar", "Cable Bar Pushdown", "الكيبل من الأعلى بالحديدة المنحنية أو حديدة الثمانية", "الترايسبس", Equip.CABLE, "cable_high", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "لا تميل بجسمك فوق البار، خلّ الشغل على الترايسبس."),
        ex("tri_db_overhead", "Dumbbell Overhead Triceps Extension", "دمبل بيدين خلف الرأس", "الرأس الطويلة للترايسبس", Equip.FREE, "dumbbells", Cat.ISOLATION, t(1.0, 0.0, 2.0, 0.5),
            "انزل خلف الرأس لأقصى مدى مريح، والمرفقين للأعلى."),
        ex("tri_plate", "Plate Overhead Triceps Extension", "الطارة بيدين خلف الرأس", "الرأس الطويلة للترايسبس", Equip.FREE, "plates", Cat.ISOLATION, t(1.0, 0.0, 2.0, 0.5),
            "امسك الطارة من الحواف بقوة، وحافظ على ثبات الكوعين."),
        // ---- Back
        ex("pulldown_wide", "Wide-Grip Lat Pulldown", "السحب العلوي بالبار العريض", "عضلة الظهر العريضة", Equip.MACHINE, "lat_pulldown", Cat.COMPOUND, t(1.0, 1.0, 2.0, 0.0),
            "اسحب البار لأعلى صدرك، واعصر الظهر.", "لا ترجع بجسمك للخلف كثير."),
        ex("cable_row", "Seated Cable Row", "السحب الأرضي بالكيبل، السحب موازي لبطنك", "منتصف الظهر", Equip.MACHINE, "cable_row", Cat.COMPOUND, t(1.0, 1.0, 2.0, 0.0),
            "صدرك للأمام، واسحب بالمرفقين مو باليدين."),
        ex("pulldown_neutral", "Neutral-Grip Pulldown", "السحب العلوي بالمسكة الرأسية", "عضلة الظهر العريضة", Equip.MACHINE, "pulldown_2", Cat.COMPOUND, t(1.0, 1.0, 2.0, 0.0),
            "فكّر إنك تنزّل مرفقيك لجيوبك."),
        ex("machine_row", "Seated Row Machine", "جهاز السحب بالجلوس بمقبضين، اليدين تحت الكتف بقليل", "أعلى ومنتصف الظهر", Equip.MACHINE, "row_machine", Cat.COMPOUND, t(1.0, 1.0, 2.0, 0.0),
            "اعصر لوحي الكتف مع بعض في نهاية السحب."),
        ex("tbar_row", "Chest-Supported T-Bar Row", "جهاز الطرمبة، تستلقي على بطنك بزاوية خمسة وأربعين", "سماكة الظهر", Equip.MACHINE, "tbar", Cat.HEAVY, t(1.0, 1.0, 2.0, 0.0),
            "صدرك ملتصق بالمسند طول الوقت.", "انزل لآخر مدى حتى تحس بتمدد الظهر."),
        ex("back_ext", "Back Extension", "تمرين الكتونية، تمدد أسفل الظهر", "أسفل الظهر", Equip.MACHINE, "back_ext", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "اطلع لحد ما يصير جسمك مستقيم فقط، لا تزيد."),
        // ---- Arms
        ex("biceps_machine", "Seated Biceps Curl Machine", "جهاز الباي بالجلوس", "البايسبس", Equip.MACHINE, "biceps_machine", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "ثبّت ذراعك على المسند، وانزل لآخر مدى."),
        ex("barbell_curl", "Barbell Curl", "رفع البار الحر للبايسبس", "البايسبس", Equip.FREE, "barbell", Cat.ISOLATION, t(1.0, 0.5, 2.0, 0.0),
            "لا تهز جسمك، المرفقين ثابتين بجانبك."),
        ex("cable_curl_rope", "Cable Rope Hammer Curl", "الكيبل من الأسفل بالحبل للبايسبس", "البايسبس والساعد", Equip.CABLE, "cable_low", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "اعصر في الأعلى ثانية كاملة."),
        ex("cable_curl_bar", "Cable EZ-Bar Curl", "الكيبل من الأسفل بالحديدة المنحنية للبايسبس", "البايسبس", Equip.CABLE, "cable_low", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "انزل ببطء، النزول نص التمرين."),
        ex("abs_machine", "Ab Crunch Machine", "تجلس وتثني جسمك للأمام وتعصر البطن", "البطن", Equip.MACHINE, "abs", Cat.ABS, t(1.0, 1.0, 1.5, 0.0),
            "ازفر الهواء وأنت تعصر البطن.", "لا تسحب بيديك، خلّ البطن هو اللي يشتغل."),
        ex("knee_raise", "Captain's Chair Knee Raise", "تسند ذراعيك على الجهاز وظهرك على المسند، وترفع ركبتيك لصدرك", "أسفل البطن", Equip.MACHINE, "captain", Cat.ABS, t(1.0, 1.0, 1.5, 0.0),
            "ارفع ركبتيك ببطء ولا تتأرجح.", "ميّل حوضك للأمام في الأعلى."),
        ex("cable_crunch", "Cable Crunch", "تركع أمام الكيبل العالي، تمسك الحبل بجانب رأسك وتنثني للأسفل", "البطن", Equip.CABLE, "cable_high", Cat.ABS, t(1.0, 1.0, 1.5, 0.0),
            "الحوض ثابت، فقط ظهرك ينثني للأسفل."),
        ex("decline_crunch", "Decline Bench Crunch", "تستلقي على البنش المائل للأسفل وقدماك مثبتتان، وترفع جسمك", "البطن", Equip.FREE, "decline_bench", Cat.ABS, t(1.0, 1.0, 1.5, 0.0),
            "لا تشد رقبتك بيديك."),
        ex("torso_rotation", "Rotary Torso Machine", "تجلس وتدور بجسمك لجهة واحدة، ثم تبدّل الجهة", "جوانب البطن", Equip.MACHINE, "torso", Cat.ABS, t(1.0, 0.5, 1.5, 0.0),
            "الحركة من الخصر، والحوض ثابت."),
        ex("wrist_curl", "Wrist Curl Machine", "جهاز الرست للسواعد", "السواعد", Equip.MACHINE, "wrist", Cat.SMALL, t(0.6, 0.0, 0.8, 0.0),
            "حركة سريعة ومنتظمة، الساعد ثابت والرسغ فقط يتحرك.", reps = 100),
        // ---- Shoulders
        ex("db_lateral", "Dumbbell Lateral Raise", "رفرفة جانبية بالدمبل", "الكتف الجانبي", Equip.FREE, "dumbbells", Cat.ISOLATION, t(1.0, 0.5, 2.0, 0.0),
            "ارفع لمستوى الكتف فقط، والمرفق مثني قليلاً."),
        ex("db_press", "Dumbbell Shoulder Press", "دفع الدمبل للأعلى وأنت جالس", "الكتف الأمامي والجانبي", Equip.FREE, "dumbbells", Cat.COMPOUND, t(1.0, 0.0, 2.0, 0.0),
            "انزل حتى مستوى الأذن تقريباً."),
        ex("machine_shoulder_press", "Machine Shoulder Press", "جهاز الكتف، الدفع الأمامي", "الكتف الأمامي", Equip.MACHINE, "shoulder_machine", Cat.COMPOUND, t(1.0, 0.0, 2.0, 0.0),
            "ظهرك ملتصق بالمسند، لا تقوّس أسفل الظهر."),
        ex("machine_lateral", "Machine Lateral Raise", "جهاز الكتف، الرفرفة الجانبية", "الكتف الجانبي", Equip.MACHINE, "shoulder_machine", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "ادفع بالمرفقين للجانب مو باليدين."),
        ex("reverse_pec_deck", "Reverse Pec Deck", "جهاز البترفلاي بالعكس، للكتف الخلفي", "الكتف الخلفي", Equip.MACHINE, "pec_deck", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "افتح للخلف وخلّ الشغل على الكتف الخلفي."),
        ex("face_pull", "Cable Rope Face Pull", "الكيبل في المنتصف بالحبل، اسحب للخلف باتجاه وجهك", "الكتف الخلفي", Equip.CABLE, "cable_mid", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "افتح الحبل للجانبين وأنت تسحب."),
        ex("db_shrug", "Dumbbell Shrugs", "الترابيس بالدمبل، ارفع كتفيك للأعلى", "الترابيس", Equip.FREE, "dumbbells", Cat.SMALL, t(1.0, 1.0, 1.0, 0.0),
            "ارفع كتفيك لأذنيك مباشرة، بدون تدوير."),
        // ---- Legs
        ex("leg_ext", "Leg Extension", "جهاز تجلس عليه وتدفع قدميك للأمام", "عضلة الفخذ الأمامية", Equip.MACHINE, "leg_ext", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "اعصر الفخذ ثانية في الأعلى."),
        ex("leg_curl", "Lying Leg Curl", "جهاز تستلقي على بطنك وتسحب رجليك للخلف", "الفخذ الخلفي", Equip.MACHINE, "leg_curl", Cat.ISOLATION, t(1.0, 0.5, 2.0, 0.0),
            "حوضك ملتصق بالجهاز، لا ترفعه."),
        ex("leg_press", "Leg Press", "جهاز تستلقي فيه على ظهرك وتدفع بقدميك للأعلى", "الفخذ والمؤخرة", Equip.MACHINE, "leg_press", Cat.HEAVY, t(1.0, 0.0, 2.5, 0.0),
            "لا تقفل الركبة في الأعلى.", "ظهرك السفلي ملتصق بالمسند."),
        ex("squat_machine", "Squat Machine", "جهاز السكوات، تقف عليه وتنزل كأنك تجلس", "الفخذ والمؤخرة", Equip.MACHINE, "squat", Cat.HEAVY, t(1.5, 0.0, 2.0, 0.0),
            "انزل لحد ما يصير الفخذ موازي تقريباً.", "اضغط بكعبك وأنت تطلع."),
        ex("adductor", "Hip Adduction", "جهاز الضم، ضم الرجلين", "الفخذ الداخلي", Equip.MACHINE, "hip_machine", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "ارجع ببطء حتى تحس بالتمدد."),
        ex("abductor", "Hip Abduction", "جهاز الفتح، فتح الرجلين", "جانب الحوض", Equip.MACHINE, "hip_machine", Cat.ISOLATION, t(1.0, 1.0, 2.0, 0.0),
            "ميّل جسمك للأمام قليلاً لشغل أقوى على المؤخرة."),
        ex("calf_seated", "Seated Calf Raise", "جهاز السمانة بالجلوس، أمشاط القدم فقط على الجهاز", "السمانة", Equip.MACHINE, "calf", Cat.SMALL, t(1.0, 1.0, 2.0, 1.0),
            "انزل لآخر تمدد وتوقف ثانية تحت."),
    ).associateBy { it.id }

    operator fun get(id: String) = all.getValue(id)
}

/** A block: straight sets of one or more exercises, or an antagonist superset. variants alternate each session. */
data class Block(val variants: List<List<String>>, val superset: Boolean = false)

private fun b(vararg ids: String, superset: Boolean = false) = Block(listOf(ids.toList()), superset)
private fun v(vararg options: List<String>, superset: Boolean = false) = Block(options.toList(), superset)

/** Abs slot: a different abs machine each session. */
private val ABS = v(listOf("abs_machine"), listOf("knee_raise"), listOf("cable_crunch"), listOf("decline_crunch"), listOf("torso_rotation"))

data class Day(val id: String, val ar: String, val en: String, val armStretch: Boolean, val blocks: List<Block>)

val DAYS = listOf(
    Day("chest", "الصدر والترايسبس", "Chest and Triceps", true, listOf(
        b("chest_press_high"), b("smith_bench"), b("incline_machine"), b("seated_dip"), b("pec_deck"),
        v(listOf("tri_rope", "tri_bar"), listOf("tri_db_overhead", "tri_plate")),
        ABS,
    )),
    Day("back", "الظهر", "Back", true, listOf(
        b("pulldown_wide"), b("cable_row"), b("pulldown_neutral"), b("machine_row"), b("tbar_row"), b("back_ext"),
    )),
    Day("arms", "الذراعين", "Arms", true, listOf(
        b("biceps_machine"),
        b("tri_rope", "barbell_curl", superset = true),
        v(listOf("cable_curl_rope", "tri_db_overhead"), listOf("cable_curl_bar", "tri_plate"), superset = true),
        ABS, b("wrist_curl"),
    )),
    Day("shoulders", "الكتف والترابيس", "Shoulders and Traps", true, listOf(
        b("db_lateral"), b("db_press"), b("machine_shoulder_press"), b("machine_lateral"),
        b("reverse_pec_deck"), b("face_pull"), b("db_shrug"), ABS,
    )),
    Day("legs", "الأرجل", "Legs", false, listOf(
        b("leg_ext"), b("leg_curl"), b("leg_press"), b("squat_machine"), b("adductor"), b("abductor"), b("calf_seated"),
    )),
)

// ---------------- Warm-up and stretching ----------------
enum class Cardio(val ar: String, val minutes: Int) { TREADMILL("المشاية", 7), BIKE("الدراجة", 5), NONE("بدون كارديو", 0) }

/** count = reps at tempo seconds each; hold = seconds */
data class StretchItem(val ar: String, val count: Int = 0, val tempo: Double = 2.0, val hold: Int = 0) {
    val seconds get() = if (count > 0) (count * tempo).roundToInt() else hold
}

val ARM_STRETCH = listOf(
    StretchItem("دوران الكتفين للأمام", count = 10, tempo = 2.0),
    StretchItem("دوران الكتفين للخلف", count = 10, tempo = 2.0),
    StretchItem("دوران الذراعين بدوائر كبيرة", count = 10, tempo = 2.0),
    StretchItem("اسحب ذراعك اليمنى أمام صدرك باليد اليسرى، واثبت", hold = 20),
    StretchItem("بدّل، اسحب ذراعك اليسرى أمام صدرك، واثبت", hold = 20),
    StretchItem("اليد اليمنى خلف الرأس، واسحب الكوع باليد الأخرى، واثبت", hold = 20),
    StretchItem("بدّل، اليد اليسرى خلف الرأس، واثبت", hold = 20),
    StretchItem("ثني وفرد الكوعين", count = 10, tempo = 2.0),
    StretchItem("دوران الرسغين", count = 10, tempo = 1.5),
)
const val STRETCH_ANNOUNCE = 5

// ---------------- Timing rules ----------------
object Timing {
    const val TRANS_SAME = 45
    const val TRANS_FREE = 75
    const val TRANS_MACHINE = 105
    const val PAIR_SWITCH_FIRST = 45
    const val PAIR_SWITCH = 20
    const val SET_CUE = 6
    const val SET_END = 3
    const val INTRO = 15
}

data class Settings(
    val sets: Int = 3,
    val defaultReps: Int = 12,
    val transMult: Double = 1.0,
    val cardio: Cardio = Cardio.TREADMILL,
    val restAdj: Map<String, Int> = emptyMap(),
    val variantIdx: Int = 0,
)

fun restFor(id: String, setIdx: Int, s: Settings): Int {
    var r = Catalog[id].cat.rest + (s.restAdj[id] ?: 0)
    if (setIdx >= 2) r = (r * 1.1).roundToInt() // extra fatigue before the last set
    return r
}

fun transFor(prev: Exercise?, e: Exercise, s: Settings): Int {
    val base = when {
        prev != null && prev.station == e.station -> Timing.TRANS_SAME
        e.equip == Equip.FREE -> Timing.TRANS_FREE
        else -> Timing.TRANS_MACHINE
    }
    return (base * s.transMult).roundToInt()
}

fun repsOf(e: Exercise, s: Settings) = e.reps ?: s.defaultReps
fun setSeconds(e: Exercise, s: Settings) = (Timing.SET_CUE + repsOf(e, s) * e.repSeconds + Timing.SET_END).roundToInt()

// ---------------- Steps ----------------
sealed class Step {
    abstract val dur: Int
    var plannedStart: Int = 0
}
class IntroStep(override val dur: Int) : Step()
class CardioStep(val cardio: Cardio, override val dur: Int) : Step()
class StretchStep(val items: List<StretchItem>, override val dur: Int) : Step()
class TransitionStep(val ex: List<String>, val superset: Boolean, val blockNo: Int, val blockTotal: Int, override val dur: Int) : Step()
class PairSwitchStep(val to: String, val round: Int, override val dur: Int) : Step()
class SetStep(val id: String, val setNo: Int, val of: Int, val reps: Int, override val dur: Int) : Step()
class RestStep(val nextId: String, val nextSet: Int, val of: Int, val superset: Boolean, override val dur: Int) : Step()

data class ResolvedBlock(val ex: List<String>, val superset: Boolean)
data class Plan(val day: Day, val steps: List<Step>, val totalSec: Int, val blocks: List<ResolvedBlock>)

fun buildPlan(day: Day, s: Settings): Plan {
    val blocks = day.blocks.map { ResolvedBlock(it.variants[s.variantIdx % it.variants.size], it.superset) }
    val steps = mutableListOf<Step>()
    steps += IntroStep(Timing.INTRO)
    if (s.cardio != Cardio.NONE) steps += CardioStep(s.cardio, s.cardio.minutes * 60)
    if (day.armStretch) steps += StretchStep(ARM_STRETCH, ARM_STRETCH.sumOf { it.seconds + STRETCH_ANNOUNCE })
    var prev: Exercise? = null
    blocks.forEachIndexed { bi, b ->
        val first = Catalog[b.ex[0]]
        steps += TransitionStep(b.ex, b.superset, bi + 1, blocks.size, transFor(prev, first, s))
        if (b.superset) {
            for (r in 0 until s.sets) {
                b.ex.forEachIndexed { k, id ->
                    val e = Catalog[id]
                    if (k > 0) steps += PairSwitchStep(id, r, if (r == 0) Timing.PAIR_SWITCH_FIRST else Timing.PAIR_SWITCH)
                    steps += SetStep(id, r + 1, s.sets, repsOf(e, s), setSeconds(e, s))
                }
                if (r < s.sets - 1) steps += RestStep(b.ex[0], r + 2, s.sets, true, restFor(b.ex.last(), r + 1, s))
            }
            prev = Catalog[b.ex.last()]
        } else {
            b.ex.forEachIndexed { k, id ->
                val e = Catalog[id]
                if (k > 0) steps += TransitionStep(listOf(id), false, bi + 1, blocks.size, transFor(Catalog[b.ex[k - 1]], e, s))
                for (r in 0 until s.sets) {
                    steps += SetStep(id, r + 1, s.sets, repsOf(e, s), setSeconds(e, s))
                    if (r < s.sets - 1) steps += RestStep(id, r + 2, s.sets, false, restFor(id, r + 1, s))
                }
                prev = e
            }
        }
    }
    var t = 0
    steps.forEach { it.plannedStart = t; t += it.dur }
    return Plan(day, steps, t, blocks)
}
