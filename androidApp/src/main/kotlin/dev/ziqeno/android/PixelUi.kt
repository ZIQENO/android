package dev.ziqeno.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class PixelUi(val context: Context, val dark: Boolean) {
    val paper = Color.parseColor(if (dark) "#171922" else "#F5F2E9")
    val ink = Color.parseColor(if (dark) "#F0EEE7" else "#242632")
    val accent = Color.parseColor(if (dark) "#BFC7FF" else "#283CA9")
    val line = fade(ink, if (dark) 96 else 80)
    val face: Typeface = context.resources.getFont(R.font.cozette)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun text(value: String, size: Int, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = face
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun divider() = View(context).apply { setBackgroundColor(line) }

    fun outline(stroke: Int = line) = GradientDrawable().apply {
        setColor(paper)
        setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    fun fill(color: Int) = GradientDrawable().apply { setColor(color) }

    fun press(background: GradientDrawable = fill(paper)) =
        RippleDrawable(ColorStateList.valueOf(fade(accent, 52)), background, null)

    fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title
        textSize = 18f
        typeface = face
        isAllCaps = false
        includeFontPadding = false
        minHeight = dp(52)
        minimumHeight = dp(52)
        stateListAnimator = null
        setTextColor(if (primary) paper else ink)
        background = if (primary) press(fill(accent)) else press(outline())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { action() }
    }

    fun check(title: String, checked: Boolean, change: (Boolean) -> Unit) = CheckBox(context).apply {
        text = title
        textSize = 18f
        typeface = face
        setTextColor(ink)
        buttonTintList = ColorStateList.valueOf(accent)
        minimumHeight = dp(48)
        isChecked = checked
        setOnCheckedChangeListener { _, selected -> change(selected) }
    }

    fun field(prompt: String, url: Boolean = false) = EditText(context).apply {
        hint = prompt
        textSize = 18f
        typeface = face
        setTextColor(ink)
        setHintTextColor(ink)
        background = outline()
        minHeight = dp(52)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = InputType.TYPE_CLASS_TEXT or if (url) InputType.TYPE_TEXT_VARIATION_URI else 0
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        isSaveEnabled = !url
        if (url) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    fun section(title: String, value: String? = null) = row().apply {
        addView(text(title, 23), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        value?.let { addView(text(it, 16, accent)) }
    }

    fun dot(active: Boolean) = View(context).apply {
        background = if (active) fill(accent) else outline(ink)
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }

    fun icon(kind: Int, color: Int = ink): View = PixelIcon(context, kind, color)

    fun navigationArrow(back: Boolean = false): View = object : View(context) {
        private val paint = Paint().apply { color = if (back) ink else accent }
        override fun onDraw(canvas: Canvas) {
            val pixel = dp(2).coerceAtLeast(1)
            val left = (width - 4 * pixel) / 2
            val top = (height - 7 * pixel) / 2
            repeat(7) { row ->
                val step = 3 - kotlin.math.abs(3 - row)
                val x = left + (if (back) 3 - step else step) * pixel
                val y = top + row * pixel
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + pixel).toFloat(), (y + pixel).toFloat(), paint)
            }
        }
    }

    fun enabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.5f
    }

    companion object {
        fun fade(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}

private class PixelIcon(context: Context, private val kind: Int, color: Int) : View(context) {
    private val paint = Paint().apply { this.color = color }
    private val grids = listOf(
        listOf("..######..", ".#......#.", ".#.####.#.", ".#.#..#.#.", ".#.#..#.#.", ".#.####.#.", ".#......#.", "..######.."),
        listOf(".###..###.", ".#.#..#.#.", ".###..###.", "..........", "..........", ".###..###.", ".#.#..#.#.", ".###..###."),
        listOf("..........", ".########.", "....##....", ".########.", "......##..", ".########.", "..##......", ".........."),
    )

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val grid = grids[kind]
        val step = (minOf(width / 10, height / 8)).coerceAtLeast(1)
        val left = (width - 10 * step) / 2
        val top = (height - 8 * step) / 2
        grid.forEachIndexed { y, line ->
            line.forEachIndexed { x, cell ->
                if (cell == '#') {
                    canvas.drawRect(
                        (left + x * step).toFloat(), (top + y * step).toFloat(),
                        (left + (x + 1) * step).toFloat(), (top + (y + 1) * step).toFloat(), paint,
                    )
                }
            }
        }
    }
}

