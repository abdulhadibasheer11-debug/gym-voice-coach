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

    fun credits(ctx: Context): String =
        "صور التمارين: مكتبة free-exercise-db، ملكية عامة، وصورة جهاز الرفرفة الجانبية من wger.de برخصة CC-BY-SA. " + MusicLibrary.CREDIT
}
