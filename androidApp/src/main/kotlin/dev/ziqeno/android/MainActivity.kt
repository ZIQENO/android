package dev.ziqeno.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import dev.ziqeno.routing.NetworkKind
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private enum class Page { HOME, APPS, SETTINGS, PICKER, JOURNAL }

    private lateinit var ui: PixelUi
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var store: SourceStore
    private lateinit var appStore: AppTrackingStore
    private lateinit var routeLog: RouteLog

    private var page = Page.HOME
    private var scroll: ScrollView? = null
    private var pickerView: AppPickerView? = null
    private var trafficChart: SpeedChart? = null
    private var trafficScale: TextView? = null
    private var downloadSpeed: TextView? = null
    private var uploadSpeed: TextView? = null
    private var trafficHint: TextView? = null
    private var appInventory = emptyList<LaunchableApp>()
    private var appsLoading = false
    private var sourceCount = 0
    private var loading = true
    private var sourceMessage = "Читаем сохранённые источники…"
    private var state = VpnStatus(VpnPhase.OFF, "VPN выключен")
    private var lastOverview: RoutingOverview? = null
    private var keyboardVisible = false
    private var backCallback: OnBackInvokedCallback? = null
    private val controlIds = mutableMapOf<String, Int>()

    private val preferences by lazy { getSharedPreferences("display", Context.MODE_PRIVATE) }
    private val freshInstall by lazy {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }
    private val scaleFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
    private val uiTimer = Handler(Looper.getMainLooper())
    private val trafficTick = object : Runnable {
        override fun run() {
            showTraffic()
            uiTimer.postDelayed(this, trafficIntervalMillis)
        }
    }
    private val observer: (VpnStatus) -> Unit = { updated ->
        val previous = state
        state = updated
        updated.overview?.let { lastOverview = it }
        when (page) {
            Page.SETTINGS -> Unit
            Page.PICKER -> Unit
            Page.HOME -> if (previous != updated) renderPage(preserveScroll = true)
            Page.JOURNAL -> if (previous.overview?.changes != updated.overview?.changes) renderPage(preserveScroll = true)
            Page.APPS -> if (
                previous.phase != updated.phase || previous.overview?.apps != updated.overview?.apps ||
                previous.overview?.changes != updated.overview?.changes
            ) renderPage(preserveScroll = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SourceStore(applicationContext)
        appStore = AppTrackingStore(applicationContext)
        routeLog = RouteLog(applicationContext)
        val theme = preferences.getString("theme", "system")
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ui = PixelUi(this, theme == "dark" || theme == "system" && night)
        page = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME

        root = ui.column().apply { setBackgroundColor(ui.paper) }
        header = ui.row()
        body = FrameLayout(this)
        nav = ui.column()
        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(nav)
        setContentView(root)
        applyWindowInsets()
        renderPage()
        refreshApps()
        io.execute {
            TrafficHistory.load(applicationContext)
            runOnUiThread { if (!isDestroyed) showTraffic() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page.name)
    }

    override fun onStart() {
        super.onStart()
        VpnSession.observe(observer)
        trafficTick.run()
        loadSources()
    }

    override fun onResume() {
        super.onResume()
        if (page == Page.SETTINGS) renderPage(preserveScroll = true)
    }

    override fun onStop() {
        uiTimer.removeCallbacks(trafficTick)
        VpnSession.remove(observer)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) unregisterBack()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applyWindowInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (ui.dark) 0 else lightBars, lightBars)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                updateNavVisibility()
                insets
            }
        } else {
            window.statusBarColor = ui.paper
            window.navigationBarColor = ui.paper
            window.decorView.systemUiVisibility = if (ui.dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    @android.annotation.TargetApi(33)
    private fun unregisterBack() {
        backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
    }

    @android.annotation.TargetApi(33)
    private fun registerBack() {
        unregisterBack()
        if (page != Page.HOME) {
            val callback = OnBackInvokedCallback { go(if (page == Page.PICKER) Page.APPS else Page.HOME) }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    @Deprecated("Back handling on Android versions before 13")
    override fun onBackPressed() {
        if (page == Page.HOME) super.onBackPressed()
        else go(if (page == Page.PICKER) Page.APPS else Page.HOME)
    }

    private fun go(next: Page) {
        if (page == next) return
        hideKeyboard()
        page = next
        renderPage()
        if (next == Page.PICKER) refreshApps()
    }

    private fun renderPage(preserveScroll: Boolean = false) {
        val position = if (preserveScroll) scroll?.scrollY ?: 0 else 0
        val focusId = if (preserveScroll) body.findFocus()?.id ?: View.NO_ID else View.NO_ID
        val accessibilityFocusId = if (preserveScroll && getSystemService(AccessibilityManager::class.java).isEnabled) {
            controlIds.values.firstOrNull { id -> body.findViewById<View>(id)?.isAccessibilityFocused == true }
        } else null
        body.removeAllViews()
        scroll = null
        pickerView = null
        trafficChart = null
        trafficScale = null
        downloadSpeed = null
        uploadSpeed = null
        trafficHint = null
        renderHeader()
        if (!preserveScroll) {
            renderNav()
            if (Build.VERSION.SDK_INT >= 33) registerBack()
        }
        if (page == Page.PICKER) {
            val picker = AppPickerView(this, ui) { editApp(it.packageName, it.label, it.isBrowser) }
            pickerView = picker
            picker.showApps(appInventory, appsLoading)
            body.addView(picker, FrameLayout.LayoutParams(
                contentWidth(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL,
            ))
            return
        }
        val content = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(24))
            isFocusableInTouchMode = true
        }
        when (page) {
            Page.HOME -> showDashboard(content)
            Page.APPS -> showApps(content)
            Page.SETTINGS -> showSettings(content)
            Page.JOURNAL -> showJournal(content)
            Page.PICKER -> Unit
        }
        val view = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(contentWidth(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        body.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scroll = view
        if (!preserveScroll) content.requestFocus() else view.post {
            view.scrollTo(0, position)
            if (focusId != View.NO_ID) view.findViewById<View>(focusId)?.requestFocus()
            accessibilityFocusId?.let { id ->
                view.findViewById<View>(id)?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
        }
    }

    private fun contentWidth() =
        if (resources.configuration.screenWidthDp > 640) ui.dp(640) else FrameLayout.LayoutParams.MATCH_PARENT

    private fun renderHeader() {
        header.removeAllViews()
        header.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        header.minimumHeight = ui.dp(72)
        if (page == Page.PICKER || page == Page.JOURNAL) {
            header.addView(ui.navigationArrow(back = true).apply {
                contentDescription = "Назад"
                isFocusable = true
                background = ui.press()
                setOnClickListener { go(if (page == Page.PICKER) Page.APPS else Page.HOME) }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            val title = if (page == Page.PICKER) "Выбор приложения" else "Журнал смены серверов"
            header.addView(ui.text(title, 22), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
            })
            return
        }
        if (page == Page.HOME) {
            header.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_vpn)
                setColorFilter(ui.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)).apply { marginEnd = ui.dp(10) })
        }
        val title = when (page) {
            Page.HOME -> getString(R.string.app_name)
            Page.APPS -> "Приложения"
            else -> "Настройки"
        }
        header.addView(ui.text(title, 32), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (page == Page.HOME) {
            val connected = state.phase == VpnPhase.CONNECTED
            val pill = ui.row().apply {
                addView(ui.dot(connected))
                addView(ui.text(if (connected) "В сети" else "Офлайн", 16, if (connected) ui.accent else ui.ink),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = ui.dp(8)
                    })
            }
            header.addView(pill)
        }
    }

    private fun renderNav() {
        nav.removeAllViews()
        nav.addView(ui.divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1).coerceAtLeast(1)))
        val options = ui.row().apply { setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4)) }
        listOf(Page.HOME to "VPN", Page.APPS to "Приложения", Page.SETTINGS to "Настройки")
            .forEachIndexed { index, (destination, title) ->
                val selected = page == destination
                val color = if (selected) ui.accent else ui.ink
                val tab = ui.column().apply {
                    gravity = Gravity.CENTER
                    minimumHeight = ui.dp(60)
                    background = ui.press()
                    isSelected = selected
                    isFocusable = true
                    contentDescription = title
                    addView(ui.icon(index, color), LinearLayout.LayoutParams(ui.dp(24), ui.dp(23)))
                    addView(ui.text(title, 16, color).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = ui.dp(3)
                        })
                    setOnClickListener { go(destination) }
                }
                options.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        nav.addView(options)
        updateNavVisibility()
    }

    private fun updateNavVisibility() {
        nav.visibility = if (page == Page.PICKER || page == Page.JOURNAL || keyboardVisible) View.GONE else View.VISIBLE
    }

    private fun showDashboard(content: LinearLayout) {
        val active = state.overview
        val previous = active ?: lastOverview
        val hasDomainRules = DomainRuleStore(applicationContext).load().active.isNotEmpty()
        val card = ui.column().apply {
            background = ui.outline(if (state.phase == VpnPhase.CONNECTED) ui.accent else ui.line)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        val caption = active?.network?.let(::networkName)
        val stateTitle = when (state.phase) {
            VpnPhase.OFF -> "VPN выключен"
            VpnPhase.CONNECTED -> "VPN подключён"
            VpnPhase.CHECKING -> "Проверяем узлы"
            VpnPhase.STOPPING -> "Отключаем VPN"
            VpnPhase.ERROR -> "Проверьте VPN"
        }
        card.place(ui.text(stateTitle, 31))
        caption?.let { card.place(ui.text(it, 16, ui.accent), 7) }
        val explanation = when (state.phase) {
            VpnPhase.CONNECTED -> active?.routes?.firstOrNull()?.server.orEmpty()
            VpnPhase.CHECKING, VpnPhase.STOPPING, VpnPhase.ERROR -> state.message
            VpnPhase.OFF -> when {
                sourceCount > 0 || loading -> sourceMessage
                hasDomainRules -> "Можно применить доменные правила без общего узла."
                else -> "Добавьте подписку или отдельный сервер."
            }
        }
        if (explanation.isNotBlank()) card.place(ui.text(explanation, 18), 9)
        val action = when (state.phase) {
            VpnPhase.CHECKING -> "Отменить"
            VpnPhase.CONNECTED -> "Отключить"
            VpnPhase.STOPPING -> "Отключаем…"
            else -> if (sourceCount > 0 || hasDomainRules) "Подключить" else "Добавить источник"
        }
        val button = ui.button(action, primary = true) {
            if (idle() && sourceCount == 0 && !hasDomainRules) openSources() else toggleVpn()
        }
        button.id = controlId("vpn-toggle")
        ui.enabled(button, state.phase != VpnPhase.STOPPING && (!idle() || !loading))
        card.place(button, 16)
        content.place(card)
        if (freshInstall && !preferences.getBoolean(powerHintSeen, false)) showPowerHint(content)

        content.place(ui.section("Трафик VPN").apply {
            val scale = ui.text("", 14)
            trafficScale = scale
            addView(scale, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(8)
            })
        }, 24)
        content.place(ui.row().apply {
            gravity = Gravity.TOP
            for (download in listOf(true, false)) {
                val metric = ui.column().apply {
                    place(ui.text(if (download) "Загрузка" else "Отдача", 15, if (download) ui.accent else ui.ink))
                    val value = ui.text("—", 18)
                    if (download) downloadSpeed = value else uploadSpeed = value
                    place(value, 3)
                }
                addView(metric, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, 10)
        trafficChart = SpeedChart(this, ui).also { content.place(it, 8, ui.dp(92)) }
        content.place(ui.row().apply {
            addView(ui.text("−10 мин", 14), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("Сейчас", 14))
        })
        trafficHint = ui.text("", 15).also { content.place(it, 8) }
        showTraffic()

        content.place(ui.divider(), 22, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Маршруты"), 17)
        if (active == null) {
            content.place(ui.text("Назначения появятся после подключения.", 17), 9)
        } else {
            active.routes.drop(1).forEach { row ->
                content.place(ui.section(row.title, null).apply {
                    (getChildAt(0) as TextView).textSize = 17f
                    addView(ui.text(row.server, 17), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.9f))
                }, 10)
            }
            if (active.backups.isNotEmpty()) content.place(ui.text("Резерв · ${active.backups.joinToString()}", 16), 11)
        }
        content.place(ui.divider(), 22, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Смены сервера"), 17)
        val changes = active?.changes ?: routeLog.read()
        if (changes.isEmpty()) {
            content.place(ui.text("Пока серверы не менялись.", 17), 9)
        } else {
            changes.take(3).forEach { change ->
                val time = DateFormat.getTimeFormat(this).format(Date(change.at))
                content.place(ui.text("$time · ${change.route}", 15, ui.accent), 10)
                content.place(ui.text(change.next, 18), 4)
            }
        }
        content.place(ui.button("Открыть журнал") { go(Page.JOURNAL) }, 12)
    }

    private fun showJournal(content: LinearLayout) {
        val entries = routeLog.read()
        if (entries.isEmpty()) {
            content.place(ui.text("Здесь появятся смены общего сервера, маршрутов сайтов и приложений.", 18))
            return
        }
        val dates = DateFormat.getMediumDateFormat(this)
        val times = SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "HH:mm:ss" else "h:mm:ss a", Locale.getDefault())
        content.place(ui.section("Последние смены", "${entries.size} / ${RouteLog.limit}"))
        for (entry in entries) {
            val date = Date(entry.at)
            content.place(ui.divider(), 16, ui.dp(1).coerceAtLeast(1))
            content.place(ui.text(entry.route, 22), 12)
            content.place(ui.text("${dates.format(date)} · ${times.format(date)} · ${networkName(entry.network)}", 15), 5)
            entry.previous?.let { content.place(ui.text("Было · $it", 16), 8) }
            content.place(ui.text("Стало · ${entry.next}", 16), 4)
            content.place(ui.text(entry.reason.title, 15, ui.accent), 5)
        }
    }

    private fun showTraffic() {
        val chart = trafficChart ?: return
        val now = System.currentTimeMillis()
        val samples = TrafficHistory.recent(now)
        val live = state.phase == VpnPhase.CHECKING || state.phase == VpnPhase.CONNECTED
        val current = samples.lastOrNull()?.takeIf {
            live && it.at >= TrafficHistory.sessionStartedAt && now - it.at <= trafficGapMillis
        }
        fun value(rate: Double?) = rate?.let { "${"%.2f".format(it)} Мбит/с" } ?: "—"
        downloadSpeed?.let { if (it.text != value(current?.download)) it.text = value(current?.download) }
        uploadSpeed?.let { if (it.text != value(current?.upload)) it.text = value(current?.upload) }
        trafficHint?.let { hint ->
            val message = when {
                samples.isNotEmpty() -> ""
                live -> "Собираем данные туннеля…"
                else -> "История появится после подключения."
            }
            if (hint.text != message) hint.text = message
            hint.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        }
        val scale = chart.update(samples, now)?.let { "До ${scaleFormat.format(it)} Мбит/с" }
        trafficScale?.let {
            if (it.text != scale.orEmpty()) it.text = scale.orEmpty()
            it.visibility = if (scale == null) View.GONE else View.VISIBLE
        }
    }

    private fun showApps(content: LinearLayout) {
        val choices = appStore.choices()
        if (!AppTrackingStore.supported) {
            content.place(ui.text("Маршруты приложений доступны на Android 10 и новее.", 19), 8)
            return
        }
        content.place(ui.text("Выберите приложения для отдельного маршрута VPN.", 18))
        val add = ui.button("Добавить приложение", primary = true) { go(Page.PICKER) }
        add.id = controlId("add-app")
        ui.enabled(add, choices.size < AppTrackingStore.maxApps)
        content.place(add, 16)
        if (choices.isEmpty()) {
            content.place(ui.divider(), 23, ui.dp(1).coerceAtLeast(1))
            content.place(ui.section("Список пуст"), 16)
            content.place(ui.text("Добавьте приложение и откройте его с включённым VPN, чтобы запустить отдельную проверку.", 18), 8)
        } else {
            val names = appInventory.associate { it.packageName to it.label }
            val labels = choices.keys.groupingBy { names[it] ?: it }.eachCount()
            content.place(ui.section("Мои приложения", "${choices.size} / ${AppTrackingStore.maxApps}"), 22)
            choices.entries.sortedBy { names[it.key] ?: it.key }.forEach { (packageName, mode) ->
                val name = names[packageName] ?: packageName
                val assignment = state.overview?.apps?.get(packageName)
                val activeMode = assignment?.mode ?: mode
                val lastChange = state.overview?.changes?.firstOrNull {
                    assignment != null && it.scope == assignment.scope
                }
                content.place(ui.divider(), 0, ui.dp(1).coerceAtLeast(1))
                val details = ui.column().apply {
                    place(ui.text(name, 22))
                    if (labels.getValue(name) > 1) place(ui.text(packageName, 14).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }, 4)
                    place(ui.text(activeMode.title, 16, ui.accent), 4)
                    val server = assignment?.server
                    if (server != null) place(ui.text("Сервер · $server", 16), 4)
                    else if (state.phase == VpnPhase.CONNECTED) place(ui.text("Вступит при переподключении", 15), 4)
                    else if (state.phase == VpnPhase.CHECKING) place(ui.text("Подбираем сервер…", 15), 4)
                    if (assignment?.mode == AppRouteMode.AUTO) {
                        val check = when {
                            assignment.host == null -> "Ожидаем соединение приложения"
                            !assignment.fresh -> "Последняя проверка · ${assignment.host}"
                            assignment.reachable == true -> "${assignment.protocol?.title ?: "Адрес"} проверен · ${assignment.host}"
                            assignment.reachable == false -> "Проверка не прошла · ${assignment.host}"
                            else -> "Проверяем · ${assignment.host}"
                        }
                        place(ui.text(check, 15), 4)
                    }
                    if (assignment != null && mode != assignment.mode) {
                        place(ui.text("После переподключения · ${mode.title}", 15, ui.accent), 5)
                    }
                    lastChange?.let { change ->
                        val time = DateFormat.getTimeFormat(this@MainActivity).format(Date(change.at))
                        place(ui.text("Смена в $time · ${change.reason.title}", 15, ui.accent), 5)
                    }
                }
                val row = ui.row().apply {
                    id = controlId("app:$packageName")
                    minimumHeight = ui.dp(56)
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                    background = ui.press()
                    isFocusable = true
                    contentDescription = "Изменить маршрут. " + (0 until details.childCount)
                        .joinToString(". ") { (details.getChildAt(it) as TextView).text }
                    details.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(ui.navigationArrow(), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply {
                        marginStart = ui.dp(12)
                    })
                    setOnClickListener { editApp(packageName, name) }
                }
                content.place(row)
            }
        }
        if (!idle()) content.place(ui.text("Изменения списка и режимов применяются при следующем подключении.", 16), 20)
    }

    private fun showSettings(content: LinearLayout) {
        content.place(ui.section("Источники"))
        content.place(ui.text(sourceMessage, 17), 8)
        content.place(ui.button("Серверы и подписки", primary = true, action = ::openSources), 10)
        content.place(ui.divider(), 24, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Фоновая работа"), 17)
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val optimization = if (exempt) "Оптимизация батареи для VPN отключена."
            else "Если VPN отключается с погашенным экраном, исключите ZIQENO из оптимизации батареи."
        content.place(ui.text(optimization, 15), 6)
        content.place(ui.button("Оптимизация батареи") {
            openPowerSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails())
        }, 10)
        content.place(ui.text("В карточке приложения выберите «Батарея» → «Без ограничений» и разрешите автозапуск, если эти пункты доступны.", 15), 12)
        content.place(ui.button("Настройки приложения") {
            openPowerSettings(appDetails(), Intent(Settings.ACTION_SETTINGS))
        }, 10)
        content.place(ui.divider(), 24, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Маршрутизация"), 17)
        content.place(ui.button("Правила для доменов") {
            startActivity(Intent(this, DomainRulesActivity::class.java))
        }, 10)
        content.place(ui.divider(), 24, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Управление Android"), 17)
        content.place(ui.button("Добавить виджет VPN", action = ::addVpnWidget), 10)
        content.place(ui.button("Добавить плитку VPN", action = ::addVpnTile), 10)
        val detailed = preferences.getBoolean(RouterVpnService.detailsPreference, false)
        content.place(ui.check("Подробное уведомление", detailed, ::setNotificationDetails), 8)
        content.place(ui.text("Показывает текущий сервер и скорость VPN.", 15), 3)
        if (detailed && !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            content.place(ui.button("Разрешить уведомления") { openNotificationSettings() }, 8)
        }
        content.place(ui.divider(), 24, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Оформление"), 17)
        val theme = when (preferences.getString("theme", "system")) {
            "light" -> "Светлая тема"
            "dark" -> "Тёмная тема"
            else -> "Как в системе"
        }
        content.place(ui.button(theme, action = ::selectTheme), 11)
        content.place(ui.text("${getString(R.string.app_name)} · $clientVersion", 15), 25)
    }

    private fun showPowerHint(content: LinearLayout) {
        content.place(ui.divider(), 20, ui.dp(1).coerceAtLeast(1))
        content.place(ui.section("Фоновая работа VPN"), 12)
        content.place(ui.text("Android может ограничивать работу VPN, когда экран погашен.", 16), 6)
        content.place(ui.row().apply {
            addView(ui.button("Настроить питание") {
                preferences.edit().putBoolean(powerHintSeen, true).apply()
                go(Page.SETTINGS)
            }, LinearLayout.LayoutParams(0, -2, 2f))
            addView(ui.button("Позже") {
                preferences.edit().putBoolean(powerHintSeen, true).apply()
                renderPage(preserveScroll = true)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })
        }, 10)
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    private fun openPowerSettings(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // System settings screens vary by device.
            } catch (_: SecurityException) {
                // Some system apps restrict direct access to individual screens.
            }
        }
        dialog().setTitle("Фоновая работа")
            .setMessage("Откройте настройки Android, найдите ZIQENO в списке приложений и откройте раздел «Батарея».")
            .setPositiveButton("Закрыть", null).show()
    }

    private fun openSources() {
        startActivity(Intent(this, SourcesActivity::class.java))
    }

    private fun loadSources() {
        io.execute {
            val result = runCatching { store.load() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val sources = result.getOrDefault(emptyList())
                sourceCount = sources.size
                loading = false
                sourceMessage = when {
                    result.isFailure -> "Не удалось открыть сохранённые источники."
                    sources.isEmpty() -> "Подписок и серверов пока нет."
                    else -> "Подписок: ${sources.count { it.kind == SourceKind.SUBSCRIPTION }} · вручную: ${sources.count { it.kind == SourceKind.MANUAL }}"
                }
                if (page == Page.HOME || page == Page.SETTINGS) renderPage(preserveScroll = true)
            }
        }
    }

    private fun addVpnTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, VpnTileService::class.java),
                getString(R.string.vpn_tile), Icon.createWithResource(this, R.drawable.ic_vpn), mainExecutor,
            ) { result ->
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) return@requestAddTileService
                if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED &&
                    result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED && !isFinishing && !isDestroyed) {
                    tileInstructions()
                }
            }
        } else tileInstructions()
    }

    private fun addVpnWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val requested = try {
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(ComponentName(this, VpnWidget::class.java), null, null)
        } catch (_: IllegalStateException) {
            false
        }
        if (requested) return
        dialog().setTitle("Виджет VPN")
            .setMessage("Удерживайте пустое место на главном экране, откройте «Виджеты» и выберите ZIQENO.")
            .setPositiveButton("Закрыть", null).show()
    }

    private fun tileInstructions() {
        dialog().setTitle("Плитка VPN")
            .setMessage("Откройте быстрые настройки, нажмите «Изменить» и перетащите ZIQENO в список плиток.")
            .setPositiveButton("Закрыть", null).show()
    }

    private fun setNotificationDetails(enabled: Boolean) {
        preferences.edit().putBoolean(RouterVpnService.detailsPreference, enabled).apply()
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotification)
        } else renderPage(preserveScroll = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestNotification && page == Page.SETTINGS) renderPage(preserveScroll = true)
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun selectTheme() {
        val keys = listOf("system", "light", "dark")
        val labels = listOf("Как в системе", "Светлая тема", "Тёмная тема")
        ui.choose("Оформление", labels, keys.indexOf(preferences.getString("theme", "system"))) { index ->
            preferences.edit().putString("theme", keys[index]).apply()
            VpnWidget.refresh(this, VpnSession.status.phase)
            recreate()
        }
    }

    private fun editApp(packageName: String, name: String, isBrowser: Boolean = false) {
        hideKeyboard()
        val selected = appStore.choices()[packageName]
        val modes = AppRouteMode.entries
        fun save(mode: AppRouteMode?) {
            appStore.save(packageName, mode)
            if (page == Page.PICKER) go(Page.APPS) else renderPage(preserveScroll = true)
        }
        val warning = if (selected == null && isBrowser) {
            "В режиме «${AppRouteMode.AUTO.title}» VPN может проверить служебное соединение браузера вместо открытого сайта."
        } else null
        ui.confirmChoice(
            name,
            modes.map { it.title },
            modes.indexOf(selected ?: AppRouteMode.AUTO),
            if (selected == null) "Добавить" else "Сохранить",
            message = warning,
            remove = selected?.let { { save(null) } },
        ) { index ->
            save(modes[index])
        }
    }

    private fun dialog() = AlertDialog.Builder(ContextThemeWrapper(
        this, if (ui.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert,
    ))

    private fun refreshApps() {
        if (appsLoading || !AppTrackingStore.supported) return
        appsLoading = true
        pickerView?.showApps(appInventory, true)
        io.execute {
            val found = runCatching {
                AppTrackingStore.launchableApps(applicationContext, includeBrowsers = true)
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                appInventory = found
                appsLoading = false
                pickerView?.showApps(found, false)
                if (page == Page.APPS) renderPage(preserveScroll = true)
            }
        }
    }

    private fun hideKeyboard() {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun idle() = state.phase == VpnPhase.OFF || state.phase == VpnPhase.ERROR

    private fun toggleVpn() {
        val service = Intent(this, RouterVpnService::class.java)
        when (state.phase) {
            VpnPhase.CHECKING, VpnPhase.CONNECTED -> startService(service.setAction(RouterVpnService.disconnect))
            VpnPhase.OFF, VpnPhase.ERROR -> {
                if (loading || sourceCount == 0 && DomainRuleStore(applicationContext).load().active.isEmpty()) return
                val permission = VpnService.prepare(this)
                if (permission == null) connect() else startActivityForResult(permission, requestVpn)
            }
            VpnPhase.STOPPING -> Unit
        }
    }

    @Deprecated("Uses the platform permission activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestVpn) return
        if (resultCode == RESULT_OK) connect()
        else VpnSession.publish(VpnStatus(VpnPhase.ERROR, "Разрешите VPN в системном окне."))
    }

    private fun connect() {
        startForegroundService(Intent(this, RouterVpnService::class.java).setAction(RouterVpnService.connect))
    }

    private fun controlId(key: String): Int = controlIds.getOrPut(key, View::generateViewId)

    private fun networkName(network: NetworkKind) = when (network) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.MOBILE -> "Мобильная сеть"
        NetworkKind.OTHER -> "Другая сеть"
    }

    private fun LinearLayout.place(view: View, top: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = ui.dp(top)
        })
    }

    private companion object {
        const val powerHintSeen = "power-hint-seen"
        const val requestVpn = 21
        const val requestNotification = 22
        val io = Executors.newSingleThreadExecutor()
    }
}







