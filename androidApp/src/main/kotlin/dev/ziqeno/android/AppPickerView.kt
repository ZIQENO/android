package dev.ziqeno.android

import android.content.Context
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

internal class AppPickerView(
    context: Context,
    private val ui: PixelUi,
    choose: (LaunchableApp) -> Unit,
) : LinearLayout(context) {
    private val search = ui.field(context.getString(R.string.apps_search))
    private val empty = ui.text("", 17)
    private var catalog = emptyList<LaunchableApp>()
    private var filtered = emptyList<LaunchableApp>()
    private var loading = false

    private val adapter = object : BaseAdapter() {
        override fun getCount() = filtered.size
        override fun getItem(position: Int) = filtered[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled as? LinearLayout ?: ui.column().apply {
                minimumHeight = ui.dp(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.text("", 20).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(ui.text("", 14).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
            }
            val item = getItem(position)
            (row.getChildAt(0) as TextView).text = item.label
            (row.getChildAt(1) as TextView).text =
                if (item.isBrowser) context.getString(R.string.apps_browser_package, item.packageName) else item.packageName
            return row
        }
    }

    init {
        orientation = VERTICAL
        setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = ui.dp(12)
        })
        empty.setPadding(0, ui.dp(12), 0, ui.dp(20))
        val list = ListView(context).apply {
            divider = ui.fill(ui.line)
            dividerHeight = ui.dp(1).coerceAtLeast(1)
            clipToPadding = false
            setPadding(0, 0, 0, ui.dp(12))
            selector = ui.press()
        }
        val body = FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        list.adapter = adapter
        list.emptyView = empty
        list.setOnItemClickListener { _, _, index, _ -> choose(adapter.getItem(index)) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        isFocusableInTouchMode = true
        requestFocus()
    }

    fun showApps(apps: List<LaunchableApp>, loading: Boolean) {
        if (catalog == apps && this.loading == loading && empty.text.isNotEmpty()) return
        catalog = apps
        this.loading = loading
        filter()
    }

    private fun filter() {
        val query = search.text.toString().trim()
        filtered = catalog.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        empty.text = if (loading) context.getString(R.string.apps_search_loading) else context.getString(R.string.apps_search_empty)
        adapter.notifyDataSetChanged()
    }
}
