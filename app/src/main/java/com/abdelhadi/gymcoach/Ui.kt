package com.abdelhadi.gymcoach

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Small helpers to build large, high-contrast, screen-reader friendly views in code. */
object Ui {
    const val BG = 0xFF101318.toInt()
    const val CARD = 0xFF1C2129.toInt()
    const val FG = Color.WHITE
    const val MUTED = 0xFFB8BEC9.toInt()
    const val ACCENT = 0xFFFFD400.toInt()
    const val SET = 0xFFFF8A00.toInt()
    const val REST = 0xFF1E8E5A.toInt()
    const val MOVE = 0xFF2F6FDE.toInt()

    /** Rounded card; read by TalkBack as one item. */
    fun card(ctx: Context, color: Int = CARD) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16); setPadding(p, p, p, p)
        background = GradientDrawable().apply { cornerRadius = dp(ctx, 18).toFloat(); setColor(color) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(ctx, 6), 0, dp(ctx, 6)) }
        if (android.os.Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
    }

    fun styled(ctx: Context, text: String, size: Float, bold: Boolean = false, color: Int = FG) = TextView(ctx).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        val p = dp(ctx, 16); setPadding(p, p, p, p)
        setBackgroundColor(BG)
    }

    fun heading(ctx: Context, text: String, big: Boolean = false) = TextView(ctx).apply {
        this.text = text
        textSize = if (big) 28f else 23f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(FG)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        setPadding(0, dp(ctx, 20), 0, dp(ctx, 8))
    }

    fun text(ctx: Context, text: String = "", size: Float = 20f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(FG)
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
    }

    fun button(ctx: Context, text: String, primary: Boolean = false, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = if (primary) 26f else 21f
        setTextColor(if (primary) Color.BLACK else FG)
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(if (primary) ACCENT else 0xFF1E222B.toInt())
            setStroke(dp(ctx, 2), if (primary) ACCENT else FG)
        }
        minHeight = dp(ctx, if (primary) 96 else 72)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(ctx, 8), 0, dp(ctx, 8)) }
    }

    /** Radio group with a visible label used as the group's accessible name. */
    fun <T> radioGroup(
        ctx: Context, label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit,
    ): RadioGroup = RadioGroup(ctx).apply {
        contentDescription = label
        options.forEachIndexed { i, (value, title) ->
            addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                text = title
                textSize = 20f
                setTextColor(FG)
                minHeight = dp(ctx, 60)
                isChecked = value == selected
                tag = i
                setOnCheckedChangeListener { _, checked -> if (checked) onSelect(value) }
            })
        }
    }
}
