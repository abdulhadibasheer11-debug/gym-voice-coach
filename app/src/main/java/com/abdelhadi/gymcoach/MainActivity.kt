package com.abdelhadi.gymcoach

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var summary: TextView
    private lateinit var planBox: LinearLayout
    private lateinit var voiceStatus: TextView
    private lateinit var dayBox: LinearLayout
    private var testSpeaker: Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.dayId == null) prefs.dayId = prefs.suggestedDayId()
        val col = Ui.column(this)

        col.addView(Ui.heading(this, getString(R.string.app_name), big = true))
        summary = Ui.text(this, size = 21f).also { col.addView(it) }
        col.addView(Ui.button(this, "ابدأ الحصة", primary = true) { startWorkout() })
        col.addView(Ui.text(this, "بعد الضغط على ابدأ، ضع الهاتف في جيبك. الصوت يوجهك في كل خطوة حتى والشاشة مقفلة. زر التشغيل في السماعة يوقف ويستأنف، والتالي يتخطى، والسابق يعيد آخر تعليمات.", 18f))

        col.addView(Ui.heading(this, "يوم التمرين"))
        dayBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dayBox)

        col.addView(Ui.heading(this, "الإحماء"))
        col.addView(Ui.radioGroup(this, "الإحماء",
            listOf(Cardio.TREADMILL to "المشاية 7 دقائق", Cardio.BIKE to "الدراجة 5 دقائق", Cardio.NONE to "بدون كارديو"),
            prefs.cardio) { prefs.cardio = it; refresh() })

        col.addView(Ui.heading(this, "خطة اليوم"))
        planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(planBox)

        col.addView(Ui.heading(this, "الإعدادات"))
        col.addView(Ui.button(this, "تجربة الصوت") { testVoice() })
        voiceStatus = Ui.text(this, "", 18f).also { col.addView(it) }
        col.addView(Ui.text(this, "سرعة الكلام", 20f))
        col.addView(Ui.radioGroup(this, "سرعة الكلام",
            listOf(1.0f to "هادئة", 1.2f to "عادية", 1.35f to "سريعة", 1.5f to "سريعة جداً"),
            prefs.speechRate) { prefs.speechRate = it })
        col.addView(Ui.text(this, "لغة العدّ أثناء التكرار", 20f))
        col.addView(Ui.radioGroup(this, "لغة العدّ",
            listOf("en" to "الإنجليزية", "ar" to "العربية"), prefs.countLang) { prefs.countLang = it })
        col.addView(Ui.text(this, "وقت التنقل بين الأجهزة وتجهيز الوزن", 20f))
        col.addView(Ui.radioGroup(this, "وقت التنقل",
            listOf(1.0f to "عادي، حوالي دقيقتين للجهاز الجديد", 1.25f to "أطول بربع", 1.5f to "أطول بالنصف", 2.0f to "ضعف الوقت"),
            prefs.transMult) { prefs.transMult = it; refresh() })
        col.addView(Ui.button(this, "إرجاع أوقات الراحة للوضع الافتراضي") {
            prefs.restAdj = emptyMap(); refresh(); summary.announceForAccessibility("تم الإرجاع")
        })

        col.addView(Ui.heading(this, "التحديثات"))
        col.addView(Ui.text(this, "الإصدار الحالي ${BuildConfig.VERSION_NAME}", 18f))
        col.addView(Ui.button(this, "فحص التحديثات") { checkUpdate(manual = true) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(col) })
        refresh()
        checkUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (Session.running) startActivity(Intent(this, WorkoutActivity::class.java))
        else refresh()
    }

    private fun refresh() {
        val sug = prefs.suggestedDayId()
        dayBox.removeAllViews()
        val opts = DAYS.mapIndexed { i, d ->
            val mins = buildPlan(d, prefs.settingsFor(d)).totalSec / 60
            d.id to "اليوم ${i + 1}: ${d.ar}، ${d.en}، حوالي $mins دقيقة${if (d.id == sug) "، المقترح اليوم" else ""}"
        }
        dayBox.addView(Ui.radioGroup(this, "يوم التمرين", opts, prefs.dayId ?: sug) { prefs.dayId = it; refreshPlan() })
        refreshPlan()
    }

    private fun refreshPlan() {
        val day = DAYS.first { it.id == (prefs.dayId ?: DAYS[0].id) }
        val s = prefs.settingsFor(day)
        val plan = buildPlan(day, s)
        val end = SimpleDateFormat("h:mm a", Locale("ar")).format(Date(System.currentTimeMillis() + plan.totalSec * 1000L))
        summary.text = "${day.ar}: ${plan.blocks.size} تمارين، المدة المتوقعة ${plan.totalSec / 60} دقيقة. إذا بدأت الآن تنتهي حوالي $end."
        planBox.removeAllViews()
        var n = 0
        if (s.cardio != Cardio.NONE) planBox.addView(Ui.text(this, "${++n}. إحماء: ${s.cardio.ar} ${s.cardio.minutes} دقائق.", 19f))
        if (day.armStretch) planBox.addView(Ui.text(this, "${++n}. إطالة مفاصل اليدين، حوالي 4 دقائق.", 19f))
        plan.blocks.forEach { b ->
            val card = Ui.card(this)
            n++
            if (b.superset) card.addView(Ui.styled(this, "سوبر سِت بالتبادل", 16f, bold = true, color = Ui.ACCENT))
            b.ex.forEach { id ->
                val e = Catalog[id]
                card.addView(Ui.styled(this, "$n. ${e.nameAr}", 21f, bold = true))
                card.addView(Ui.styled(this, e.en, 17f, color = Ui.MUTED))
                card.addView(Ui.styled(this, "${e.ar}.", 17f))
                card.addView(Ui.styled(this, "${s.sets} جولات × ${repsOf(e, s)} تكرار · راحة ${e.cat.rest + (s.restAdj[id] ?: 0)} ثانية · العضلة: ${e.targetAr}", 16f, color = Ui.MUTED))
            }
            planBox.addView(card)
        }
    }

    private fun testVoice() {
        scope.launch {
            val sp = testSpeaker ?: Speaker(this@MainActivity).also {
                if (!it.init()) { voiceStatus.text = "تعذر تشغيل محرك النطق. ثبّت Google Text-to-Speech من المتجر."; return@launch }
                testSpeaker = it
            }
            sp.rate = prefs.speechRate
            voiceStatus.text = "الصوت العربي: ${if (sp.arabicAvailable) "متوفر" else "غير متوفر، ثبّت حزمة العربية من إعدادات تحويل النص إلى كلام"}. الصوت الإنجليزي: ${if (sp.englishAvailable) "متوفر" else "غير متوفر"}."
            sp.beep(Beep.GO)
            sp.say("هذا صوت العربية.", "And this is the English voice.", "One, two, three.", record = false, interrupt = true)
        }
    }

    private fun startWorkout() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return // continue in onRequestPermissionsResult
        }
        launchService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) launchService()
    }

    private fun launchService() {
        testSpeaker?.shutdown(); testSpeaker = null
        val i = Intent(this, WorkoutService::class.java).setAction(WorkoutService.ACTION_START)
            .putExtra(WorkoutService.EXTRA_DAY, prefs.dayId)
        startForegroundService(i)
        startActivity(Intent(this, WorkoutActivity::class.java))
    }

    private fun checkUpdate(manual: Boolean) {
        scope.launch {
            val r = Updater.latest()
            if (r == null) {
                if (manual) AlertDialog.Builder(this@MainActivity).setMessage("تعذر الوصول إلى صفحة التحديثات. تأكد من الإنترنت.").setPositiveButton("حسناً", null).show()
                return@launch
            }
            if (r.versionCode <= BuildConfig.VERSION_CODE) {
                if (manual) AlertDialog.Builder(this@MainActivity).setMessage("عندك أحدث إصدار.").setPositiveButton("حسناً", null).show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("يوجد تحديث جديد")
                .setMessage("الإصدار ${r.name}.\n${r.notes}")
                .setPositiveButton("تنزيل وتثبيت") { _, _ ->
                    scope.launch {
                        summary.announceForAccessibility("جاري تنزيل التحديث")
                        val f = Updater.download(this@MainActivity, r)
                        if (f != null) Updater.install(this@MainActivity, f)
                        else AlertDialog.Builder(this@MainActivity).setMessage("فشل التنزيل، حاول لاحقاً.").setPositiveButton("حسناً", null).show()
                    }
                }
                .setNegativeButton("لاحقاً", null)
                .show()
        }
    }

    override fun onDestroy() {
        testSpeaker?.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
