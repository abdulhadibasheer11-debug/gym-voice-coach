package com.abdelhadi.gymcoach

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared helpers for the screens. */
object Screens {
    fun page(a: Activity, title: String, build: (LinearLayout) -> Unit) {
        val col = Ui.column(a)
        col.addView(Ui.heading(a, title, big = true))
        build(col)
        a.setContentView(ScrollView(a).apply { setBackgroundColor(Ui.BG); addView(col) })
    }

    fun endTime(sec: Int): String =
        SimpleDateFormat("h:mm a", Locale("ar")).format(Date(System.currentTimeMillis() + sec * 1000L))

    fun currentDay(p: Prefs): Day {
        val days = p.days()
        if (days.none { it.id == p.dayId }) p.dayId = p.suggestedDayId()
        return days.firstOrNull { it.id == p.dayId } ?: days[0]
    }

    fun checkUpdate(a: Activity, scope: CoroutineScope, manual: Boolean) {
        scope.launch {
            val r = Updater.latest()
            if (r == null) {
                if (manual) AlertDialog.Builder(a).setMessage("تعذر الوصول إلى صفحة التحديثات. تأكد من الإنترنت.").setPositiveButton("حسناً", null).show()
                return@launch
            }
            if (r.versionCode <= BuildConfig.VERSION_CODE) {
                if (manual) AlertDialog.Builder(a).setMessage("عندك أحدث إصدار.").setPositiveButton("حسناً", null).show()
                return@launch
            }
            AlertDialog.Builder(a)
                .setTitle("يوجد تحديث جديد")
                .setMessage("الإصدار ${r.name}.\n${r.notes}")
                .setPositiveButton("تنزيل وتثبيت") { _, _ ->
                    scope.launch {
                        a.window.decorView.announceForAccessibility("جاري تنزيل التحديث")
                        val f = Updater.download(a, r)
                        if (f != null) Updater.install(a, f)
                        else AlertDialog.Builder(a).setMessage("فشل التنزيل، حاول لاحقاً.").setPositiveButton("حسناً", null).show()
                    }
                }
                .setNegativeButton("لاحقاً", null)
                .show()
        }
    }
}

/** Home: short on purpose. Everything else lives on its own screen. */
class MainActivity : BaseActivity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var summary: TextView
    private lateinit var dayBtn: Button
    private lateinit var warmBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.dayId == null) prefs.dayId = prefs.suggestedDayId()
        Screens.page(this, getString(R.string.app_name)) { col ->
            val card = Ui.card(this)
            summary = Ui.styled(this, "", 21f).also { card.addView(it) }
            col.addView(card)
            col.addView(Ui.button(this, "ابدأ الحصة", primary = true, iconRes = R.drawable.ic_play) { startWorkout() })
            dayBtn = Ui.button(this, "", iconRes = R.drawable.ic_calendar) { pickDay() }.also { col.addView(it) }
            warmBtn = Ui.button(this, "", iconRes = R.drawable.ic_run) { startActivity(Intent(this, WarmupActivity::class.java)) }.also { col.addView(it) }
            col.addView(Ui.button(this, "خطة اليوم", iconRes = R.drawable.ic_list, desc = "خطة اليوم بالتفصيل") { startActivity(Intent(this, PlanActivity::class.java)) })
            col.addView(Ui.button(this, "تخصيص البرنامج", iconRes = R.drawable.ic_edit, desc = "تخصيص البرنامج والأيام") { startActivity(Intent(this, ProgramActivity::class.java)) })
            col.addView(Ui.button(this, "الموسيقى والصوت", iconRes = R.drawable.ic_music, desc = "الموسيقى وصوت المدرب") { startActivity(Intent(this, AudioActivity::class.java)) })
            col.addView(Ui.button(this, "الإعدادات", iconRes = R.drawable.ic_tune) { startActivity(Intent(this, SettingsActivity::class.java)) })
            col.addView(Ui.styled(this, "بعد البدء ضع الهاتف في جيبك. زر التشغيل في السماعة يوقف ويستأنف، والتالي يتخطى، والسابق يعيد آخر تعليمات.", 16f, color = Ui.MUTED))
        }
        Screens.checkUpdate(this, scope, manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (Session.running) { startActivity(Intent(this, WorkoutActivity::class.java)); return }
        refresh()
    }

    private fun refresh() {
        val day = Screens.currentDay(prefs)
        val s = prefs.settingsFor(day)
        val plan = buildPlan(day, s)
        val i = prefs.days().indexOfFirst { it.id == day.id } + 1
        summary.text = "اليوم $i: ${day.ar}. ${plan.blocks.size} تمارين، المدة ${plan.totalSec / 60} دقيقة. إذا بدأت الآن تنتهي حوالي ${Screens.endTime(plan.totalSec)}."
        dayBtn.text = "اليوم: ${day.ar}"
        dayBtn.contentDescription = "يوم التمرين: ${day.ar}${if (day.id == prefs.suggestedDayId()) "، المقترح" else ""}. تغيير"
        warmBtn.text = if (s.cardio == Cardio.NONE) "الإحماء: بدون كارديو" else "الإحماء: ${s.cardio.ar} ${s.cardioMinutes} د"
        warmBtn.contentDescription = if (s.cardio == Cardio.NONE) "الإحماء: بدون كارديو. تغيير"
            else "الإحماء: ${s.cardio.ar} ${s.cardioMinutes} دقيقة. تغيير"
    }

    private fun pickDay() {
        val days = prefs.days()
        val sug = prefs.suggestedDayId()
        val names = days.mapIndexed { i, d ->
            val mins = buildPlan(d, prefs.settingsFor(d)).totalSec / 60
            "اليوم ${i + 1}: ${d.ar}، حوالي $mins دقيقة${if (d.id == sug) "، المقترح" else ""}"
        }.toTypedArray()
        val cur = days.indexOfFirst { it.id == prefs.dayId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("اختر يوم التمرين")
            .setSingleChoiceItems(names, cur) { d, which -> prefs.dayId = days[which].id; d.dismiss(); refresh() }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun startWorkout() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) launchService()
    }

    private fun launchService() {
        startForegroundService(Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_START)
            .putExtra(WorkoutService.EXTRA_DAY, prefs.dayId))
        startActivity(Intent(this, WorkoutActivity::class.java))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Warm-up: cardio type and minutes, time to get to the machine, break before stretching. */
class WarmupActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        lateinit var box: LinearLayout
        fun minutes() {
            box.removeAllViews()
            if (p.cardio == Cardio.NONE) return
            box.addView(Ui.styled(this, "مدة ${p.cardio.ar}", 20f, bold = true))
            box.addView(Ui.stepper(this, "مدة ${p.cardio.ar}", p.cardioMinutes(p.cardio), 1, 60, format = { "$it دقيقة" }) { p.setCardioMinutes(p.cardio, it) })
        }
        Screens.page(this, "الإحماء") { col ->
            col.addView(Ui.radioGroup(this, "نوع الإحماء",
                listOf(Cardio.TREADMILL to "المشاية", Cardio.BIKE to "الدراجة", Cardio.NONE to "بدون كارديو"), p.cardio) { p.cardio = it; minutes() })
            box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(box); minutes()
            col.addView(Ui.styled(this, "وقت التوجه إلى المشاية أو الدراجة بعد الضغط على ابدأ", 20f, bold = true))
            col.addView(Ui.stepper(this, "وقت التوجه", p.prepBefore, 0, 300, step = 15, format = { if (it == 0) "بدون" else "$it ثانية" }) { p.prepBefore = it })
            col.addView(Ui.styled(this, "استراحة بعد الإحماء: ماء ونفَس والتوجه لمكان الإطالة", 20f, bold = true))
            col.addView(Ui.stepper(this, "الاستراحة بعد الإحماء", p.breakAfterWarmup, 0, 300, step = 15, format = { if (it == 0) "بدون" else "$it ثانية" }) { p.breakAfterWarmup = it })
        }
    }
}

/** Today's plan in detail, with exercise photos. */
class PlanActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        val day = Screens.currentDay(p)
        val s = p.settingsFor(day)
        val plan = buildPlan(day, s)
        Screens.page(this, "خطة اليوم: ${day.ar}") { col ->
            col.addView(Ui.styled(this, "المدة ${plan.totalSec / 60} دقيقة.", 19f, color = Ui.MUTED))
            var n = 0
            if (s.cardio != Cardio.NONE) col.addView(Ui.text(this, "${++n}. إحماء: ${s.cardio.ar} ${s.cardioMinutes} دقيقة.", 19f))
            if (day.armStretch) col.addView(Ui.text(this, "${++n}. إطالة مفاصل اليدين، حوالي 4 دقائق.", 19f))
            plan.blocks.forEach { b ->
                val card = Ui.card(this)
                n++
                if (b.superset) card.addView(Ui.styled(this, "سوبر سِت بالتبادل", 16f, bold = true, color = Ui.ACCENT_TEXT))
                b.ex.forEach { id ->
                    val e = Catalog[id]
                    ExerciseImages.view(this, id)?.let { card.addView(it) }
                    card.addView(Ui.styled(this, "$n. ${e.nameAr}", 21f, bold = true))
                    card.addView(Ui.styled(this, e.en, 17f, color = Ui.MUTED))
                    card.addView(Ui.styled(this, "${e.ar}.", 17f))
                    card.addView(Ui.styled(this, "${b.sets} جولات × ${b.repsText(e, s)} تكرار · راحة ${(b.rest ?: e.cat.rest) + (s.restAdj[id] ?: 0)} ثانية · العضلة: ${e.targetAr}", 16f, color = Ui.MUTED))
                }
                col.addView(card)
            }
        }
    }
}

/** Music and coach voice. */
class AudioActivity : BaseActivity() {
    private val scope = MainScope()
    private lateinit var p: Prefs
    private lateinit var libText: TextView
    private lateinit var folderText: TextView
    private lateinit var recText: TextView
    private lateinit var voiceStatus: TextView
    private var testSpeaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        Screens.page(this, "الموسيقى وصوت المدرب") { col ->
            col.addView(Ui.heading(this, "الموسيقى أثناء التمرين"))
            col.addView(Ui.radioGroup(this, "الموسيقى",
                listOf("none" to "بدون، أو من تطبيق آخر وتنخفض عند كلام المدرب", "phone" to "ملفات من هاتفي", "app" to "موسيقى حماسية من التطبيق"),
                p.musicMode) { p.musicMode = it; if (it == "phone" && p.musicFolder == null) pickFolder() })
            libText = Ui.text(this, "", 17f).also { col.addView(it) }
            col.addView(Ui.button(this, "تنزيل موسيقى التطبيق") { download() })
            folderText = Ui.text(this, "", 17f).also { col.addView(it) }
            col.addView(Ui.button(this, "اختيار مجلد الموسيقى من الهاتف") { pickFolder() })
            col.addView(Ui.styled(this, "مستوى صوت الموسيقى", 20f, bold = true))
            col.addView(Ui.stepper(this, "مستوى صوت الموسيقى", (p.musicVolume * 10).toInt(), 1, 10, format = { "$it من 10" }) { p.musicVolume = it / 10f })

            col.addView(Ui.heading(this, "صوت المدرب"))
            col.addView(Ui.button(this, "تجربة الصوت") { testVoice() })
            voiceStatus = Ui.text(this, "", 17f).also { col.addView(it) }
            col.addView(Ui.styled(this, "سرعة الكلام", 20f, bold = true))
            col.addView(Ui.radioGroup(this, "سرعة الكلام",
                listOf(1.0f to "هادئة", 1.2f to "عادية", 1.35f to "سريعة", 1.5f to "سريعة جداً"), p.speechRate) { p.speechRate = it })
            col.addView(Ui.styled(this, "لغة العدّ", 20f, bold = true))
            col.addView(Ui.radioGroup(this, "لغة العدّ", listOf("en" to "الإنجليزية", "ar" to "العربية"), p.countLang) { p.countLang = it })
            col.addView(Ui.heading(this, "الصوت البشري، تجريبي"))
            recText = Ui.text(this, "", 17f).also { col.addView(it) }
            col.addView(Ui.radioGroup(this, "صوت المدرب",
                listOf(false to "صوت الهاتف", true to "الصوت المسجّل، وصوت الهاتف لما لم يُسجَّل"), p.useRecorded) { p.useRecorded = it })
            col.addView(Ui.button(this, "استوديو التسجيل والمشاركة") { startActivity(Intent(this, StudioActivity::class.java)) })
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val n = MusicLibrary.downloaded(this).size
        libText.text = if (n == 0) "موسيقى التطبيق لم تُنزَّل بعد. بدونها تُستخدم إيقاعات بسيطة." else "موسيقى التطبيق: $n أغانٍ جاهزة."
        folderText.text = p.musicFolder?.let { "مجلد الموسيقى: " + android.net.Uri.decode(it).substringAfterLast(':').substringAfterLast('/') } ?: "لم يتم اختيار مجلد."
        recText.text = "الجمل المسجّلة: ${VoicePack.recordedCount(this)} من ${CoachLines.recordable().size}."
    }

    private fun download() {
        libText.text = "جاري التنزيل…"; libText.announceForAccessibility("بدأ تنزيل الموسيقى")
        scope.launch {
            val n = MusicLibrary.download(this@AudioActivity) { d, t -> libText.text = "جاري التنزيل: $d من $t" }
            refresh()
            libText.announceForAccessibility(if (n > 0) "اكتمل التنزيل، $n أغانٍ جاهزة" else "فشل التنزيل، تأكد من الإنترنت")
        }
    }

    private fun pickFolder() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 7)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 7 && resultCode == RESULT_OK && uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            p.musicFolder = uri.toString(); refresh()
        }
    }

    private fun testVoice() {
        scope.launch {
            val sp = testSpeaker ?: Speaker(this@AudioActivity).also {
                if (!it.init()) { voiceStatus.text = "تعذر تشغيل محرك النطق."; return@launch }
                testSpeaker = it
            }
            sp.rate = p.speechRate
            voiceStatus.text = "الصوت العربي: ${if (sp.arabicAvailable) "متوفر" else "غير متوفر"}. الصوت الإنجليزي: ${if (sp.englishAvailable) "متوفر" else "غير متوفر"}."
            sp.beep(Beep.GO)
            sp.say("هذا صوت العربية.", "And this is the English voice.", "One, two, three.", record = false, interrupt = true)
        }
    }

    override fun onDestroy() { testSpeaker?.shutdown(); scope.cancel(); super.onDestroy() }
}

/** General settings, updates and credits. */
class SettingsActivity : BaseActivity() {
    private val scope = MainScope()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs(this)
        Screens.page(this, "الإعدادات") { col ->
            col.addView(Ui.styled(this, "مظهر التطبيق", 20f, bold = true))
            col.addView(Ui.radioGroup(this, "مظهر التطبيق",
                listOf("system" to "تلقائي حسب الهاتف", "dark" to "داكن", "light" to "فاتح"), p.theme) {
                if (it != p.theme) { p.theme = it; recreate() }
            })
            col.addView(Ui.styled(this, "عدد الجولات الافتراضي", 20f, bold = true))
            col.addView(Ui.stepper(this, "عدد الجولات الافتراضي", p.defaultSets, 1, 10, format = { "$it جولات" }) { p.defaultSets = it })
            col.addView(Ui.styled(this, "عدد التكرارات الافتراضي", 20f, bold = true))
            col.addView(Ui.stepper(this, "عدد التكرارات الافتراضي", p.defaultReps, 1, 50, format = { "$it تكرار" }) { p.defaultReps = it })
            col.addView(Ui.styled(this, "تذكير شرب الماء", 20f, bold = true))
            col.addView(Ui.stepper(this, "تذكير شرب الماء", p.waterEvery, 0, 60, step = 5,
                format = { if (it == 0) "بدون تذكير" else "كل $it دقيقة" }) { p.waterEvery = it })
            col.addView(Ui.styled(this, "وقت التنقل بين الأجهزة", 20f, bold = true))
            col.addView(Ui.stepper(this, "وقت التنقل بين الأجهزة", (p.transMult * 100).toInt(), 50, 300, step = 25,
                format = { "$it بالمئة من الوقت العادي" }) { p.transMult = it / 100f })
            col.addView(Ui.button(this, "إرجاع أوقات الراحة للوضع الافتراضي") {
                p.restAdj = emptyMap(); col.announceForAccessibility("تم الإرجاع")
            })
            col.addView(Ui.heading(this, "التحديثات"))
            col.addView(Ui.text(this, "الإصدار الحالي ${BuildConfig.VERSION_NAME}", 18f))
            col.addView(Ui.button(this, "فحص التحديثات") { Screens.checkUpdate(this, scope, manual = true) })
            col.addView(Ui.heading(this, "الحقوق"))
            col.addView(Ui.text(this, ExerciseImages.credits(this), 15f))
            col.addView(Ui.text(this, "الخط: Tajawal، من Google Fonts، برخصة SIL Open Font License 1.1.", 15f))
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
