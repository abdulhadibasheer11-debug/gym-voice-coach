package com.abdelhadi.gymcoach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray

/** Illustrations bundled at build time from the wger.de open exercise library (CC-BY-SA). */
object ExerciseImages {
    private val cache = HashMap<String, Bitmap?>()

    fun get(ctx: Context, id: String): Bitmap? = cache.getOrPut(id) {
        runCatching { ctx.assets.open("images/$id.jpg").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    /** Decorative image view (hidden from TalkBack: the text already describes the exercise). */
    fun view(ctx: Context, id: String, heightDp: Int = 170): ImageView? {
        val bmp = get(ctx, id) ?: return null
        return ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, heightDp))
                .apply { setMargins(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 8)) }
        }
    }

    fun credits(ctx: Context): String = runCatching {
        val arr = JSONArray(ctx.assets.open("images/credits.json").bufferedReader().readText())
        val authors = (0 until arr.length()).map { arr.getJSONObject(it).optString("author") }.filter { it.isNotBlank() }.distinct()
        "الصور التوضيحية من مكتبة التمارين المفتوحة wger.de، برخصة المشاع الإبداعي CC-BY-SA. المساهمون: " + authors.joinToString("، ")
    }.getOrDefault("لا توجد صور في هذا الإصدار.")
}
