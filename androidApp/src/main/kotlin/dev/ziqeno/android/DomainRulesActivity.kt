package dev.ziqeno.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.window.OnBackInvokedCallback
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class DomainRulesActivity : Activity() {
    private class Editor(val existing: DomainRule?) {
        var kind = existing?.kind ?: DomainMatch.SUFFIX
        var pattern = existing?.pattern.orEmpty()
        var exit = existing?.exit ?: DomainExit.DIRECT
        var nodeId = existing?.nodeId
        var test = existing?.testHost != null
        var host = existing?.testHost.orEmpty()
    }

    private lateinit var ui: PixelUi
    private lateinit var root: LinearLayout
    private lateinit var store: DomainRuleStore
    private lateinit var scroll: ScrollView
    private lateinit var notice: TextView
    private var config = DomainRules()
    private var readFailed = false
    private var editor: Editor? = null
    private var nodes: List<XrayNode>? = null
    private var nodeReadFailed = false
    private var nodeButton: Button? = null
    private var listStatus: TextView? = null
    private var listPosition = 0
    private var backCallback: OnBackInvokedCallback? = null
    @Volatile private var destroyed = false
    private val checkRows = mutableMapOf<DomainRule, TextView>()
    private val observer: (VpnStatus) -> Unit = { status ->
        val applied = status.overview?.domainRules == config
        listStatus?.showText(when {
            !applied && status.domainUpdateFailed -> "Не удалось применить · повторяем"
            status.phase == VpnPhase.CONNECTED && !applied -> "Применяем правила…"
            config.active.isEmpty() -> null
            applied -> "Для новых соединений"
            status.phase == VpnPhase.CHECKING -> "Запускаем VPN…"
            else -> "При запуске VPN"
        })
        val checks = if (applied && config.enabled) status.overview?.domainChecks.orEmpty() else emptyMap()
        for ((rule, row) in checkRows) {
            val result = checks[rule.id]
            val summary = when {
                result == null -> null
                result.skipped -> "Действует правило выше"
                !result.tested -> "Ожидает проверки"
                result.latencyMs == null -> "Проверка не прошла"
                else -> listOfNotNull(
                    result.server?.takeIf { rule.exit == DomainExit.AUTO },
                    "${result.latencyMs.roundToInt()} мс",
                ).joinToString(" · ")
            }
            val host = rule.testHost?.takeUnless { it == rule.pattern }
            row.showText(summary?.let { listOfNotNull(it, host).joinToString(" · ") })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DomainRuleStore(applicationContext)
        runCatching { store.load() }.onSuccess { config = it }.onFailure { readFailed = true }
        editor = lastNonConfigurationInstance as? Editor
        val theme = getSharedPreferences("display", MODE_PRIVATE).getString("theme", "system")
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ui = PixelUi(this, theme == "dark" || theme == "system" && night)
        root = ui.column().apply { setBackgroundColor(ui.paper) }
        setContentView(root)
        applyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback(::back).also { onBackInvokedDispatcher.registerOnBackInvokedCallback(0, it) }
        }
        render()
        if (!readFailed) loadNodes()
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? = editor

    override fun onStart() {
        super.onStart()
        VpnSession.observe(observer)
    }

    override fun onStop() {
        VpnSession.remove(observer)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    @SuppressLint("GestureBackNavigation")
    @Deprecated("Handles Android versions before predictive Back")
    override fun onBackPressed() = back()

    private fun back() {
        if (editor == null) finish() else closeEditor()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (ui.dark) 0 else bars, bars)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                insets
            }
        } else {
            window.statusBarColor = ui.paper
            window.navigationBarColor = ui.paper
            window.decorView.systemUiVisibility = if (ui.dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun loadNodes() {
        val sourceStore = SourceStore(applicationContext)
        io.execute {
            val result = runCatching { SourceNodes.decode(sourceStore.load()) { !destroyed } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                nodes = result.getOrDefault(emptyList())
                nodeReadFailed = result.isFailure
                if (editor == null && config.rules.any { it.exit == DomainExit.NODE }) render(scroll.scrollY)
                editor?.let(::updateNodeButton)
            }
        }
    }

    private fun openEditor(rule: DomainRule?) {
        listPosition = scroll.scrollY
        editor = Editor(rule)
        render()
    }

    private fun closeEditor() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        editor = null
        render(listPosition)
    }

    private fun render(position: Int = 0) {
        root.removeAllViews()
        checkRows.clear()
        listStatus = null
        nodeButton = null
        val current = editor
        val title = when {
            current == null -> "Правила доменов"
            current.existing == null -> "Новое правило"
            else -> "Редактировать правило"
        }
        root.addView(ui.row().apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(20), ui.dp(4))
            addView(ui.navigationArrow(back = true).apply {
                contentDescription = "Назад"
                background = ui.press()
                isFocusable = true
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            addView(ui.text(title, 24).apply {
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = ui.dp(8)
            })
        })

        val content = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(24))
            isFocusableInTouchMode = true
        }
        notice = ui.text("", 16, ui.accent).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        when {
            readFailed -> content.place(ui.text("Не удалось открыть сохранённые правила.", 18))
            current == null -> renderList(content)
            else -> renderEditor(content, current)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            val width = if (resources.configuration.screenWidthDp > 640) ui.dp(640) else -1
            addView(content, FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (position > 0) scroll.run { post { scrollTo(0, position) } }
        content.requestFocus()
        observer(VpnSession.status)
    }

    private fun renderList(content: LinearLayout) {
        content.place(Switch(this).apply {
            text = "Применять правила"
            textSize = 18f
            typeface = ui.face
            setTextColor(ui.ink)
            minHeight = ui.dp(48)
            background = ui.press()
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ui.accent, ui.ink))
            trackTintList = ColorStateList(states, intArrayOf(PixelUi.fade(ui.accent, 105), ui.line))
            isChecked = config.enabled
            setOnCheckedChangeListener { _, enabled ->
                if (enabled == config.enabled) return@setOnCheckedChangeListener
                if (save(config.copy(enabled = enabled))) {
                    notice.showText(null)
                    observer(VpnSession.status)
                } else isChecked = config.enabled
            }
        })
        listStatus = ui.text("", 15, ui.accent).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }.also { content.place(it) }
        content.place(notice, 8)
        if (config.rules.size < DomainRuleStore.maxRules) {
            content.place(ui.button("Добавить правило", primary = true) { openEditor(null) }, 12)
        } else {
            content.place(ui.text("Можно сохранить до ${DomainRuleStore.maxRules} правил.", 16), 12)
        }
        if (config.rules.isEmpty()) {
            content.place(ui.text("Правил пока нет.", 18), 16)
            return
        }
        val ordered = config.rules.size > 1
        if (ordered) content.place(ui.text("Срабатывает первое совпадение сверху.", 15), 16)
        config.rules.forEachIndexed { index, rule ->
            content.place(ui.divider(), if (index == 0) 12 else 0, ui.dp(1).coerceAtLeast(1))
            val pattern = rule.pattern.ifEmpty { rule.kind.label }
            val label = if (ordered) "${index + 1}. $pattern" else pattern
            val route = if (rule.exit == DomainExit.NODE) {
                nodes?.firstOrNull { it.id == rule.nodeId }?.name ?: when {
                    nodes == null || nodeReadFailed -> rule.exit.label
                    else -> "Сервер недоступен"
                }
            } else rule.exit.label
            val condition = rule.kind.summary
            val subtitle = if (condition == null) route else "$condition · $route"
            content.place(ui.row().apply {
                val item = ui.column().apply {
                    setPadding(0, ui.dp(8), 0, ui.dp(8))
                    minimumHeight = ui.dp(52)
                    background = ui.press()
                    isFocusable = true
                    setOnClickListener { openEditor(rule) }
                    place(ui.text(label, 20).apply {
                        ellipsize = TextUtils.TruncateAt.END
                        maxLines = 2
                    })
                    place(ui.text(subtitle, 15, ui.accent), 3)
                    if (rule.testHost != null) {
                        val check = ui.text("", 15).apply {
                            visibility = View.GONE
                            ellipsize = TextUtils.TruncateAt.END
                            maxLines = 2
                        }
                        checkRows[rule] = check
                        place(check, 3)
                    }
                }
                addView(item, LinearLayout.LayoutParams(0, -2, 1f))
                if (index > 0) addView(moveControl(index, -1), moveParams())
                if (index < config.rules.lastIndex) addView(moveControl(index, 1), moveParams())
            })
        }
    }

    private fun moveParams() = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8) }

    private fun moveControl(index: Int, offset: Int) = ui.navigationArrow().apply {
        rotation = 90f * offset
        contentDescription = "Переместить правило ${index + 1} ${if (offset < 0) "выше" else "ниже"}"
        background = ui.press()
        isFocusable = true
        setOnClickListener {
            val moved = config.rules.toMutableList()
            moved.add(index + offset, moved.removeAt(index))
            if (save(config.copy(rules = moved))) render(scroll.scrollY)
        }
    }

    private fun TextView.showText(value: String?) {
        if (text != value.orEmpty()) text = value.orEmpty()
        visibility = if (value == null) View.GONE else View.VISIBLE
    }

    private fun renderEditor(content: LinearLayout, draft: Editor) {
        content.place(ui.section("Условие"))
        content.place(ui.button(draft.kind.label) {
            choose("Тип совпадения", DomainMatch.entries, draft.kind, { it.label }) {
                draft.kind = it
                render(scroll.scrollY)
            }
        }, 8)
        if (draft.kind == DomainMatch.MATCH) {
            content.place(ui.text("Совпадает с любым трафиком. Разместите в конце списка, чтобы сохранить остальные правила.", 16), 8)
        } else {
            content.place(ui.text(draft.kind.help, 16), 8)
            content.place(field("Шаблон", draft.pattern) { draft.pattern = it }, 8)
        }

        content.place(ui.section("Действие"), 20)
        content.place(ui.button(draft.exit.label) {
            choose("Маршрут", DomainExit.entries, draft.exit, { it.label }) {
                draft.exit = it
                render(scroll.scrollY)
            }
        }, 8)
        if (draft.exit == DomainExit.NODE) {
            nodeButton = ui.button("Выбрать сервер") {
                val choices = nodes.orEmpty()
                choose("Сервер VPN", choices, choices.firstOrNull { it.id == draft.nodeId }, ::choiceLabel) {
                    draft.nodeId = it.id
                    updateNodeButton(draft)
                }
            }.also { content.place(it, 8) }
            updateNodeButton(draft)
            content.place(ui.text("Если выбранный сервер недоступен, трафик блокируется.", 16), 8)
        }
        if (draft.exit == DomainExit.AUTO) {
            val explanation = if (draft.test) "Подбирает сервер VPN для хоста проверки."
                else "Использует общий сервер VPN."
            content.place(ui.text(explanation, 16), 8)
        }
        if (draft.exit != DomainExit.REJECT) {
            content.place(ui.check("Проверять доступность хоста", draft.test) { enabled ->
                draft.test = enabled
                if (enabled && draft.host.isBlank() && draft.kind in listOf(DomainMatch.EXACT, DomainMatch.SUFFIX)) {
                    draft.host = draft.pattern.trim().removePrefix(".")
                }
                render(scroll.scrollY)
            }, 12)
            if (draft.test) {
                content.place(ui.text("Введите хост, подходящий под правило. VPN проверит доступность по HTTPS.", 16), 4)
                content.place(field("Хост для проверки", draft.host) { draft.host = it }, 8)
            }
        }
        content.place(notice, 12)
        content.place(ui.button("Сохранить правило", primary = true) { saveEditor(draft) }, 16)
        if (draft.existing != null) {
            content.place(ui.button("Удалить правило") {
                ui.confirm("Удалить правило?", draft.existing.pattern.ifEmpty { draft.existing.kind.label }, "Удалить") {
                    if (save(config.copy(rules = config.rules.filterNot { it.id == draft.existing.id }))) closeEditor()
                }
            }, 8)
        }
    }

    private fun updateNodeButton(draft: Editor) {
        val button = nodeButton ?: return
        val choices = nodes.orEmpty()
        button.text = when {
            nodes == null -> "Читаем сохранённые серверы…"
            nodeReadFailed -> "Не удалось прочитать серверы"
            draft.nodeId != null -> choices.firstOrNull { it.id == draft.nodeId }?.let(::choiceLabel) ?: "Сервер недоступен"
            choices.isEmpty() -> "Добавьте сервер в настройках"
            else -> "Выбрать сервер"
        }
        ui.enabled(button, choices.isNotEmpty())
    }

    private fun choiceLabel(node: XrayNode) = if (nodes.orEmpty().count { it.name == node.name } > 1) {
        "${node.name} · ${node.id.removePrefix("node-").take(8)}"
    } else node.name

    private fun saveEditor(draft: Editor) {
        val test = draft.test && draft.exit != DomainExit.REJECT
        if (test && draft.host.isBlank()) {
            showError("Введите хост для проверки.")
            return
        }
        if (draft.exit == DomainExit.NODE && draft.nodeId == null) {
            showError("Выберите сервер VPN.")
            return
        }
        val candidate = try {
            DomainRule(
                draft.existing?.id ?: UUID.randomUUID().toString(),
                draft.kind, draft.pattern, draft.exit, draft.nodeId, draft.host.takeIf { test },
            ).normalized()
        } catch (error: IllegalArgumentException) {
            showError(error.message ?: "Проверьте условие правила.")
            return
        }
        val rules = config.rules.toMutableList()
        val index = rules.indexOfFirst { it.id == candidate.id }
        if (index < 0) rules.add(candidate)
        else if (rules[index] != candidate) rules[index] = candidate.copy(id = UUID.randomUUID().toString())
        if (save(config.copy(rules = rules))) closeEditor()
    }

    private fun save(next: DomainRules): Boolean = try {
        store.save(next)
        config = next
        true
    } catch (_: Exception) {
        showError(saveError)
        false
    }

    private fun showError(message: String) {
        notice.apply {
            text = message
            visibility = View.VISIBLE
            post { requestRectangleOnScreen(Rect(0, 0, width, height)) }
        }
    }

    private fun field(label: String, value: String, change: (String) -> Unit): EditText = ui.field(label, url = true).apply {
        setText(value)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = change(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun <T> choose(title: String, items: List<T>, selected: T?, label: (T) -> String, select: (T) -> Unit) {
        ui.choose(title, items.map(label), items.indexOfFirst { it == selected }) { select(items[it]) }
    }

    private fun LinearLayout.place(view: View, top: Int = 0, height: Int = -2) {
        addView(view, LinearLayout.LayoutParams(-1, height).apply { topMargin = ui.dp(top) })
    }

    private companion object {
        val io = Executors.newSingleThreadExecutor()
        const val saveError = "Не удалось сохранить правила. Попробуйте ещё раз."
    }
}

private val DomainMatch.label: String get() = when (this) {
    DomainMatch.EXACT -> "Точный домен"
    DomainMatch.SUFFIX -> "Домен и поддомены"
    DomainMatch.WILDCARD -> "Маска"
    DomainMatch.KEYWORD -> "Ключевое слово"
    DomainMatch.REGEX -> "Регулярное выражение"
    DomainMatch.MATCH -> "Любой трафик"
}

private val DomainMatch.help: String get() = when (this) {
    DomainMatch.EXACT -> "Например, www.example.com. Совпадает только этот хост."
    DomainMatch.SUFFIX -> "Например, example.com. Совпадает сам домен и его поддомены."
    DomainMatch.WILDCARD -> "Например, *.example.com. Знак * заменяет любое число символов, а ? заменяет один."
    DomainMatch.KEYWORD -> "Например, video. Совпадает с частью имени хоста."
    DomainMatch.REGEX -> "Например, ^cdn[0-9]+\\.example\\.com$"
    DomainMatch.MATCH -> ""
}

private val DomainMatch.summary: String? get() = when (this) {
    DomainMatch.EXACT -> "Точный"
    DomainMatch.SUFFIX -> "С поддоменами"
    DomainMatch.REGEX -> "Выражение"
    DomainMatch.MATCH -> null
    else -> label
}

private val DomainExit.label: String get() = when (this) {
    DomainExit.DIRECT -> "Напрямую"
    DomainExit.AUTO -> "Автовыбор VPN"
    DomainExit.NODE -> "Конкретный сервер VPN"
    DomainExit.REJECT -> "Заблокировать"
}
