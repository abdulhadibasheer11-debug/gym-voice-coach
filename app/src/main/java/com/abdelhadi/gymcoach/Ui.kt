package com.abdelhadi.gymcoach

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Every screen extends this: applies the light/dark theme before anything is drawn. */
open class BaseActivity : Activity() {
    private var createdLight = false
    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.init(this)
        createdLight = Ui.light
        setTheme(if (Ui.light) android.R.style.Theme_DeviceDefault_Light_NoActionBar else android.R.style.Theme_DeviceDefault_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        if (Ui.light) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    override fun onResume() {
        super.onResume()
        // the theme was changed in Settings while this screen was in the background
        Ui.init(this)
        if (Ui.light != createdLight) recreate()
    }
}

/** Small helpers to build large, high-contrast, screen-reader friendly views in code. */
object Ui {
    // Theme-dependent palette (set by init). All text/background pairs meet WCAG AA (4.5:1 or better).
    var light = false; private set
    var BG = 0xFF101318.toInt(); private set
    var CARD = 0xFF1C2129.toInt(); private set
    var FG = Color.WHITE; private set
    var MUTED = 0xFFB8BEC9.toInt(); private set
    var BTN = 0xFF1E222B.toInt(); private set
    /** Accent used as a text colour (yellow on dark, dark amber on light). */
    var ACCENT_TEXT = 0xFFFFD400.toInt(); private set

    // Fixed colours (same in both themes).
    const val ACCENT = 0xFFFFD400.toInt()      // primary button, black text: 14.7:1
    const val DANGER = 0xFFB91C1C.toInt()      // end button, white text: 6.5:1
    // Phase colours of the big card, with the text colour that reads on each.
    const val SET = 0xFFFF8A00.toInt()         // black text 8.9:1
    const val REST = 0xFF15803D.toInt()        // white text 5.0:1
    const val MOVE = 0xFF1D4ED8.toInt()        // white text 6.7:1
    const val WARM = 0xFF7A5C00.toInt()        // white text 6.3:1
    const val PAUSED = 0xFF555555.toInt()      // white text 7.5:1
    fun onPhase(bg: Int) = if (bg == SET) Color.BLACK else Color.WHITE

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun init(ctx: Context) {
        val mode = Prefs(ctx).theme
        val systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        light = when (mode) { "light" -> true; "dark" -> false; else -> !systemDark }
        if (light) {
            BG = 0xFFF4F5F7.toInt(); CARD = Color.WHITE; FG = 0xFF111111.toInt(); MUTED = 0xFF4A505C.toInt()
            BTN = 0xFFE6E8EC.toInt(); ACCENT_TEXT = 0xFF8A5A00.toInt()
        } else {
            BG = 0xFF101318.toInt(); CARD = 0xFF1C2129.toInt(); FG = Color.WHITE; MUTED = 0xFFB8BEC9.toInt()
            BTN = 0xFF1E222B.toInt(); ACCENT_TEXT = 0xFFFFD400.toInt()
        }
        if (regular == null) {
            regular = runCatching { ctx.resources.getFont(R.font.tajawal_regular) }.getOrNull()
            bold = runCatching { ctx.resources.getFont(R.font.tajawal_bold) }.getOrNull()
        }
    }

    fun font(v: TextView, isBold: Boolean) {
        val tf = if (isBold) bold else regular
        if (tf != null) v.typeface = tf else if (isBold) v.setTypeface(v.typeface, Typeface.BOLD)
    }

    /** Vector icon tinted to [color]; null if it can't be loaded (never crashes a screen). */
    fun icon(ctx: Context, res: Int, color: Int, sizeDp: Int = 28): Drawable? = runCatching {
        ctx.getDrawable(res)!!.mutate().apply {
            setTint(color); val s = dp(ctx, sizeDp); setBounds(0, 0, s, s)
        }
    }.getOrNull()

    /** Rounded card; read by TalkBack as one item. */
    fun card(ctx: Context, color: Int = CARD) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, 16); setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 18).toFloat(); setColor(color)
            if (light && color == CARD) setStroke(dp(ctx, 1), 0xFFD5D9E0.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(ctx, 6), 0, dp(ctx, 6)) }
        if (android.os.Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true
    }

    fun styled(ctx: Context, text: String, size: Float, bold: Boolean = false, color: Int = FG) = TextView(ctx).apply {
        this.text = text; textSize = size; setTextColor(color)
        font(this, bold)
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
    }

    /** Arabic dates and times with Western digits (3:09, not ٣:٠٩), matching the rest of the app. */
    val AR: java.util.Locale = java.util.Locale.forLanguageTag("ar-u-nu-latn")

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
        font(this, true)
        setTextColor(FG)
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        setPadding(0, dp(ctx, if (big) 12 else 20), 0, dp(ctx, 8))
    }

    fun text(ctx: Context, text: String = "", size: Float = 20f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(FG)
        font(this, false)
        setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
    }

    /**
     * Large button. [text] is what is shown; [desc] (optional) is the full label TalkBack reads, so the
     * visible text can stay short without losing information for screen-reader users.
     */
    fun button(
        ctx: Context, text: String, primary: Boolean = false, iconRes: Int? = null, desc: String? = null,
        danger: Boolean = false, compact: Boolean = false, onClick: () -> Unit,
    ) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = when { primary -> 26f; compact -> 19f; else -> 21f }
        font(this, primary || compact)
        val fg = when { primary -> Color.BLACK; danger -> Color.WHITE; else -> FG }
        setTextColor(fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(when { primary -> ACCENT; danger -> DANGER; else -> BTN })
            setStroke(dp(ctx, 2), when { primary -> if (light) Color.BLACK else ACCENT; danger -> DANGER; else -> if (light) 0xFF9AA1AD.toInt() else FG })
        }
        minHeight = dp(ctx, when { primary -> 96; compact -> 76; else -> 72 })
        val pad = dp(ctx, 12); setPadding(pad, pad / 2, pad, pad / 2)
        if (iconRes != null) {
            val d = icon(ctx, iconRes, fg, if (compact) 26 else 30)
            if (compact) { setCompoundDrawablesRelative(null, d, null, null) } else setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(ctx, if (compact) 2 else 12)
            gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        }
        if (desc != null) contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(ctx, 8), 0, dp(ctx, 8)) }
    }

    /** Buttons side by side with equal widths (RTL: the first is on the right). */
    fun row(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(if (i == 0) 0 else dp(ctx, 4), dp(ctx, 6), if (i == views.size - 1) 0 else dp(ctx, 4), dp(ctx, 6))
            })
        }
    }

    /**
     * Number control: [−] value [+]. With TalkBack the value itself is "adjustable":
     * swipe up/down (or volume keys) to change it; the new value is announced.
     */
    fun stepper(
        ctx: Context, label: String, value: Int, min: Int, max: Int, step: Int = 1,
        format: (Int) -> String = { it.toString() }, onChange: (Int) -> Unit,
    ): LinearLayout {
        var v = value.coerceIn(min, max)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(ctx, 4), 0, dp(ctx, 10)) }
        }
        val valueView = TextView(ctx).apply {
            textSize = 24f; setTextColor(FG); font(this, true)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 64)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isFocusable = true
        }
        fun show(announce: Boolean) {
            valueView.text = format(v)
            valueView.contentDescription = "$label: ${format(v)}"
            if (announce) valueView.announceForAccessibility(format(v))
        }
        fun change(d: Int) {
            val nv = (v + d).coerceIn(min, max)
            if (nv != v) { v = nv; show(true); onChange(v) } else valueView.announceForAccessibility("${format(v)}، الحد ${if (d > 0) "الأعلى" else "الأدنى"}")
        }
        valueView.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.SeekBar"
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { change(step); true }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { change(-step); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
        fun small(text: String, desc: String, d: Int) = Button(ctx).apply {
            this.text = text; textSize = 28f; setTextColor(Color.BLACK); contentDescription = desc
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 14).toFloat(); setColor(ACCENT) }
            minHeight = dp(ctx, 64)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 72), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { change(d) }
        }
        // RTL: the first child is on the right. Put "+" on the right, "−" on the left.
        row.addView(small("+", "زيادة $label", step))
        row.addView(valueView)
        row.addView(small("−", "إنقاص $label", -step))
        show(false)
        return row
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
                font(this, false)
                buttonTintList = ColorStateList.valueOf(if (light) 0xFF8A5A00.toInt() else ACCENT)
                minHeight = dp(ctx, 60)
                isChecked = value == selected
                tag = i
                setOnCheckedChangeListener { _, checked -> if (checked) onSelect(value) }
            })
        }
    }

    /** Text size in dp (does not grow with the system font size) for the giant counter. */
    fun fixedSize(v: TextView, dpSize: Float) = v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dpSize)
}

/** Circular progress around the big number. Purely visual: hidden from TalkBack. */
class RingView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    var color = Color.WHITE
        set(v) { if (v != field) { field = v; invalidate() } }
    private val stroke = Ui.dp(ctx, 12).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val l = (width - s) / 2 + stroke / 2; val t = (height - s) / 2 + stroke / 2
        r.set(l, t, l + s - stroke, t + s - stroke)
        track.color = (color and 0x00FFFFFF) or 0x40000000
        arc.color = color
        canvas.drawArc(r, 0f, 360f, false, track)
        if (fraction > 0f) canvas.drawArc(r, -90f, 360f * fraction, false, arc)
    }
}

/** Thin horizontal progress bar for the whole session. Purely visual. */
class BarView(ctx: Context) : View(ctx) {
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val rad = h / 2
        bg.color = if (Ui.light) 0xFFD5D9E0.toInt() else 0xFF2A303A.toInt()
        fg.color = if (Ui.light) 0xFF8A5A00.toInt() else Ui.ACCENT
        r.set(0f, 0f, width.toFloat(), h); canvas.drawRoundRect(r, rad, rad, bg)
        val w = width * fraction
        // RTL: fill from the right edge
        if (w > 0) { r.set(width - w, 0f, width.toFloat(), h); canvas.drawRoundRect(r, rad, rad, fg) }
    }
}
