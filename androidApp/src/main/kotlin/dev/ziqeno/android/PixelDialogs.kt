package dev.ziqeno.android

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import kotlin.math.ceil

internal fun PixelUi.choose(title: String, labels: List<String>, selected: Int = -1, select: (Int) -> Unit) {
    showChoices(title, labels, selected, select = select)
}

internal fun PixelUi.confirmChoice(
    title: String,
    labels: List<String>,
    selected: Int,
    actionLabel: String,
    message: String? = null,
    remove: (() -> Unit)? = null,
    select: (Int) -> Unit,
) {
    showChoices(title, labels, selected, actionLabel, message, remove, select)
}

internal fun PixelUi.confirm(title: String, message: String, actionLabel: String, action: () -> Unit) {
    val body = ScrollView(context).apply {
        addView(text(message, 17))
    }
    showPixelDialog(title, body, listOf(context.getString(R.string.action_dialog_cancel) to {}, actionLabel to action), primary = true)
}

private fun PixelUi.showChoices(
    title: String,
    labels: List<String>,
    selected: Int,
    actionLabel: String? = null,
    message: String? = null,
    remove: (() -> Unit)? = null,
    select: (Int) -> Unit,
) {
    if (labels.isEmpty()) return
    var current = selected
    val choices = ListView(context).apply {
        choiceMode = ListView.CHOICE_MODE_SINGLE
        selector = RippleDrawable(ColorStateList.valueOf(PixelUi.fade(accent, 52)), null, fill(paper))
        divider = ColorDrawable(line)
        dividerHeight = dp(1).coerceAtLeast(1)
        setHeaderDividersEnabled(false)
        message?.let { value ->
            val note = text(value, 16).apply { setPadding(dp(8), dp(4), dp(8), dp(14)) }
            addHeaderView(note, null, false)
        }
        adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as CheckedTextView).apply {
                    typeface = face
                    textSize = 18f
                    setTextColor(ink)
                    includeFontPadding = false
                    minimumHeight = dp(48)
                    setPaddingRelative(dp(8), dp(10), dp(8), dp(10))
                    setCheckMarkDrawable(StateListDrawable().apply {
                        val size = dp(12)
                        addState(intArrayOf(android.R.attr.state_checked), fill(accent).apply { setSize(size, size) })
                        addState(intArrayOf(), outline().apply { setSize(size, size) })
                    })
                }
            }
        }
        if (selected in labels.indices) setItemChecked(selected + headerViewsCount, true)
    }
    val actions = buildList {
        remove?.let { add(context.getString(R.string.action_remove) to it) }
        add(context.getString(R.string.action_dialog_cancel) to {})
        actionLabel?.let { add(it to { select(current) }) }
    }
    val dialog = showPixelDialog(title, choices, actions, primary = actionLabel != null)
    choices.setOnItemClickListener { _, _, position, _ ->
        current = position - choices.headerViewsCount
        if (actionLabel == null) {
            dialog.dismiss()
            select(current)
        }
    }
    if (selected in labels.indices && message == null) choices.setSelection(selected)
}

private fun PixelUi.showPixelDialog(
    title: String,
    body: View,
    actions: List<Pair<String, () -> Unit>>,
    primary: Boolean,
): Dialog {
    val dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setTitle(title)
        setCanceledOnTouchOutside(true)
    }
    val width = dp((body.resources.configuration.screenWidthDp - 32).coerceIn(1, 420))
    val buttons = actions.mapIndexed { index, (label, action) ->
        button(label, primary && index == actions.lastIndex) {
            dialog.dismiss()
            action()
        }
    }
    val buttonWidth = buttons.maxOf { ceil(it.paint.measureText(it.text.toString())).toInt() + it.paddingLeft + it.paddingRight }
    val gap = dp(8)
    val stack = buttons.size * buttonWidth + (buttons.size - 1) * gap > width - dp(32)
    val footer = if (stack) column() else row()
    buttons.forEachIndexed { index, button ->
        val params = if (stack) LinearLayout.LayoutParams(-1, -2).apply {
            if (index > 0) topMargin = gap
        } else LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (index > 0) marginStart = gap
        }
        footer.addView(button, params)
    }

    dialog.setContentView(column().apply {
        background = outline()
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(text(title, 23).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        addView(body, LinearLayout.LayoutParams(-1, -2, 1f))
        addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    })
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.5f)
    }
    dialog.show()
    dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    return dialog
}
