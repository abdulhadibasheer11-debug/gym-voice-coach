package com.abdelhadi.gymcoach

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout

/** Studio settings set once: recording quality, plus sharing and importing voice packs. */
class StudioSettingsActivity : BaseActivity() {
    private lateinit var col: LinearLayout
    private fun announce(t: String) = col.announceForAccessibility(t)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        Screens.page(this, "الجودة والمشاركة") { c ->
            col = c
            c.addView(Ui.section(this, "جودة التسجيل"))
            c.addView(Ui.styled(this, "تحسين الصوت تلقائياً", 20f, bold = true))
            c.addView(Ui.radioGroup(this, "تحسين الصوت تلقائياً", listOf(
                true to "نعم: قص الصمت، وتوحيد مستوى الصوت، وإزالة الطنين المنخفض",
                false to "لا، احفظ التسجيل كما هو"), prefs.studioEnhance) { prefs.studioEnhance = it })
            c.addView(Ui.styled(this, "الأصلي يُحفظ دائماً. من خيارات كل جملة تستطيع سماعه واختياره بدل المحسّن.", 16f, color = Ui.MUTED))
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                c.addView(Ui.styled(this, "كاتم الضوضاء من الهاتف", 20f, bold = true))
                c.addView(Ui.radioGroup(this, "كاتم الضوضاء من الهاتف أثناء التسجيل",
                    listOf(false to "لا، الأفضل في مكان هادئ", true to "نعم، للأماكن المزعجة"), prefs.studioNoise) { prefs.studioNoise = it })
                c.addView(Ui.styled(this, "قد يجعل الصوت معدنياً في بعض الهواتف. جرّبه على جملة واحدة واسمعها قبل اعتماده.", 16f, color = Ui.MUTED))
            }

            c.addView(Ui.section(this, "مشاركة الصوت مع الأصدقاء"))
            c.addView(Ui.styled(this, "أرسل كل تسجيلاتك في ملف واحد عبر واتساب أو غيره، ويستوردها صديقك من هذه الشاشة. استأذن صاحب الصوت قبل المشاركة.", 16f, color = Ui.MUTED))
            c.addView(Ui.button(this, "مشاركة حزمة الصوت", iconRes = R.drawable.ic_music) { sharePack() })
            c.addView(Ui.button(this, "استيراد حزمة صوت من ملف", iconRes = R.drawable.ic_list) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 9)
            })
        }
    }

    private fun sharePack() {
        val f = VoicePack.export(this, "صوت المدرب")
        if (f == null) { announce("لا توجد تسجيلات للمشاركة بعد"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "حزمة صوت لتطبيق مدرّب الجيم الصوتي. افتح التطبيق، ثم استوديو التسجيل، ثم الجودة والمشاركة، ثم استيراد حزمة صوت.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة حزمة الصوت"))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 9 && resultCode == RESULT_OK) {
            val (n, name) = runCatching { VoicePack.import(this, uri) }.getOrDefault(0 to null)
            if (n > 0) {
                Prefs(this).useRecorded = true
                announce("تم استيراد $n تسجيل${name?.let { "، $it" } ?: ""}. تم تفعيل الصوت البشري.")
            } else announce("هذا الملف ليس حزمة صوت صالحة")
        }
    }
}
