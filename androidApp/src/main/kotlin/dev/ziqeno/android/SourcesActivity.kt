package dev.ziqeno.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import dev.ziqeno.routing.NetworkKind
import java.text.NumberFormat
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SourcesActivity : Activity() {
    private data class ServerRow(val id: String, val name: String, val details: String)

    private class Editor(val kind: SourceKind, val existing: VpnSource? = null) {
        var name = existing?.name.orEmpty()
        var reference = existing?.reference.orEmpty()
        var hwid = existing?.hwid ?: true
        val savedProfile = existing?.takeIf { it.kind == SourceKind.MANUAL }?.let {
            ManualProfile.read(it.reference) ?: readBasicServerLink(it.reference)
        }
        var byFields = kind == SourceKind.MANUAL && (existing == null || savedProfile != null)
        var profile = savedProfile ?: ManualProfile()
        var viewingServers = false
        var servers: Result<List<ServerRow>>? = null
    }

    private lateinit var ui: PixelUi
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var footer: LinearLayout
    private var scroll: ScrollView? = null
    private lateinit var store: SourceStore
    private var sources = emptyList<VpnSource>()
    private var editor: Editor? = null
    private var waiting = true
    private var readFailed = false
    private var message: String? = null
    private var notice: TextView? = null
    private var controls = mutableListOf<View>()
    private var backCallback: OnBackInvokedCallback? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val measurementObserver: (VpnStatus) -> Unit = { refreshMeasurements() }
    private val networkObserver = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshMeasurements()
        override fun onLost(network: Network) = refreshMeasurements()
    }
    private val speedFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private var measurements = emptyMap<String, ServerMeasurements>()
    private var measurementRevision = 0
    private var serverAdapter: ArrayAdapter<ServerRow>? = null
    private var serverNetwork: TextView? = null
    private var checkAction: Button? = null
    private var checkStatus: TextView? = null
    private var checkMessage = ""
    private var check: SubscriptionCheck? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editor = lastNonConfigurationInstance as? Editor
        store = SourceStore(applicationContext)
        val theme = getSharedPreferences("display", Context.MODE_PRIVATE).getString("theme", "system")
        val systemDark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        ui = PixelUi(this, theme == "dark" || theme == "system" && systemDark)
        root = ui.column().apply { setBackgroundColor(ui.paper) }
        header = ui.row()
        body = FrameLayout(this)
        footer = ui.column()
        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer)
        setContentView(root)
        applyInsets()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback(::back).also { onBackInvokedDispatcher.registerOnBackInvokedCallback(0, it) }
        }
        render()
        runWork("Читаем сохранённые источники…", "Не удалось открыть сохранённые источники.", {
            store.load()
        }) { result ->
            readFailed = result.isFailure
            result.getOrNull()?.let { sources = it }
            render()
            editor?.takeIf(Editor::viewingServers)?.let(::loadServers)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? = editor

    override fun onStart() {
        super.onStart()
        VpnSession.observe(measurementObserver)
        connectivity.registerDefaultNetworkCallback(networkObserver, Handler(Looper.getMainLooper()))
    }

    override fun onStop() {
        connectivity.unregisterNetworkCallback(networkObserver)
        VpnSession.remove(measurementObserver)
        super.onStop()
    }

    @Deprecated("Handles Android versions before predictive Back")
    override fun onBackPressed() = back()

    override fun onDestroy() {
        check?.close()
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        super.onDestroy()
    }

    private fun back() {
        val draft = editor
        if (draft?.viewingServers == true) {
            check?.close()
            check = null
            checkMessage = ""
            draft.viewingServers = false
            render()
        } else if (draft == null || waiting) finish() else open(null)
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
            window.decorView.systemUiVisibility = if (ui.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun open(value: Editor?) {
        editor = value
        message = null
        render()
    }

    private fun render(preserveScroll: Boolean = false) {
        val position = if (preserveScroll) scroll?.scrollY ?: 0 else 0
        serverAdapter = null
        serverNetwork = null
        checkAction = null
        checkStatus = null
        controls = mutableListOf()
        header.removeAllViews()
        footer.removeAllViews()
        footer.visibility = View.GONE
        header.setPadding(ui.dp(12), ui.dp(8), ui.dp(20), ui.dp(8))
        header.minimumHeight = ui.dp(64)
        header.addView(ui.navigationArrow(back = true).apply {
            background = ui.press()
            contentDescription = "Назад"
            isFocusable = true
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        val current = editor
        val title = when {
            current == null -> "Серверы и подписки"
            current.viewingServers -> "Серверы подписки"
            current.existing != null -> if (current.kind == SourceKind.SUBSCRIPTION) "Редактировать подписку" else "Редактировать сервер"
            current.kind == SourceKind.SUBSCRIPTION -> "Новая подписка"
            else -> "Новый сервер"
        }
        header.addView(ui.text(title, 26), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(8) })

        if (current?.viewingServers == true) {
            notice = null
            scroll = null
            renderServers(current)
            return
        }
        val content = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(24))
            isFocusableInTouchMode = true
        }
        notice = ui.text("", 16, ui.accent).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (current != null) renderEditor(content, current) else {
            content.place(checkNotNull(notice), bottom = 12)
            renderCatalog(content)
        }
        val viewport = ScrollView(this).apply {
            isFillViewport = true
            val width = if (resources.configuration.screenWidthDp > 640) ui.dp(640) else -1
            addView(content, FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL))
        }
        body.removeAllViews()
        body.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        scroll = viewport
        content.requestFocus()
        if (position > 0) viewport.post { viewport.scrollTo(0, position) }
        updateControls()
    }

    private fun renderCatalog(content: LinearLayout) {
        if (readFailed) return
        content.place(button("Добавить подписку", primary = true) { open(Editor(SourceKind.SUBSCRIPTION)) })
        content.place(button("Добавить сервер") { open(Editor(SourceKind.MANUAL)) }, top = 8)
        content.place(button("Импорт из QR") { chooseQr() }, top = 8)
        if (sources.isEmpty() && !waiting) {
            content.place(ui.text("Добавьте ссылку провайдера или отдельный сервер.", 18), top = 20)
        }
        if (sources.isNotEmpty() && VpnSession.status.phase in listOf(VpnPhase.CHECKING, VpnPhase.CONNECTED)) {
            content.place(ui.text("Изменения вступят в силу при следующем подключении.", 16), top = 18)
        }
        for ((kind, section) in listOf(SourceKind.SUBSCRIPTION to "Подписки", SourceKind.MANUAL to "Ручные серверы")) {
            val entries = sources.filter { it.kind == kind }
            if (entries.isEmpty()) continue
            content.place(ui.section(section, entries.size.toString()), top = 22, bottom = 8)
            for (source in entries) {
                content.place(ui.divider(), height = ui.dp(1).coerceAtLeast(1))
                val subtitle = if (kind == SourceKind.SUBSCRIPTION) {
                    val count = source.nodeCount?.let { "Узлов: $it" } ?: "Сохранена"
                    if (source.hwid) "$count · HWID" else count
                } else ManualProfile.read(source.reference)?.let {
                    "${it.protocol.title} · ${it.transport.title} · ${it.protection.title}"
                } ?: source.reference.substringBefore("://").uppercase()
                val details = ui.column().apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    place(ui.text(source.title, 21))
                    place(ui.text(subtitle, 15, ui.accent), top = 4)
                }
                content.place(ui.row().apply {
                    minimumHeight = ui.dp(58)
                    setPadding(0, ui.dp(10), 0, ui.dp(10))
                    background = ui.press()
                    contentDescription = "Редактировать. ${source.title}. $subtitle"
                    isFocusable = true
                    addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.navigationArrow(), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(10) })
                    setOnClickListener { open(Editor(source.kind, source)) }
                    controls.add(this)
                })
            }
        }
    }

    private fun renderEditor(content: LinearLayout, draft: Editor) {
        field(content, "Название", draft.name, 64) { draft.name = it }
        if (draft.kind == SourceKind.SUBSCRIPTION) {
            field(content, "HTTPS-ссылка", draft.reference, maxReferenceLength, url = true) { draft.reference = it }
            content.place(ui.check("Передавать HWID", draft.hwid) { draft.hwid = it }.also(controls::add), top = 12)
            content.place(ui.text("Провайдер может использовать HWID для ограничения числа устройств.", 15), top = 3)
        } else if (draft.byFields) {
            renderManualProfile(content, draft)
        } else {
            field(content, "Ссылка на сервер", draft.reference, maxReferenceLength, url = true) { draft.reference = it }
            content.place(ui.text("VLESS, VMess, Trojan, Shadowsocks или Hysteria 2", 15), top = 6)
            if (draft.existing == null) {
                content.place(button("Ввести параметры") { draft.byFields = true; render() }, top = 12)
            }
        }
        val save = button("Сохранить", primary = true) { save(draft, refresh = false) }
        if (draft.byFields && draft.kind == SourceKind.MANUAL) {
            footer.visibility = View.VISIBLE
            footer.place(ui.divider(), height = ui.dp(1).coerceAtLeast(1))
            val actions = ui.column().apply {
                setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
                place(checkNotNull(notice), bottom = 8)
                place(save)
            }
            val width = if (resources.configuration.screenWidthDp > 640) ui.dp(640) else -1
            footer.addView(actions, LinearLayout.LayoutParams(width, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            content.place(checkNotNull(notice), top = 12)
            content.place(save, top = 16)
        }
        if (draft.existing != null) {
            if (draft.kind == SourceKind.SUBSCRIPTION) {
                content.place(button("Серверы подписки") {
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
                    draft.viewingServers = true
                    render()
                    loadServers(draft)
                }, top = 8)
                content.place(button("Обновить список узлов") {
                    val saved = draft.existing
                    if (draft.name.trim() != saved.name || draft.reference.trim() != saved.reference || draft.hwid != saved.hwid) {
                        message = "Сначала сохраните изменения подписки."
                        updateControls()
                    } else save(draft, refresh = true)
                }, top = 8)
            }
            content.place(button("Удалить источник") { confirmDelete(draft.existing) }, top = 8)
        }
    }

    private fun loadServers(draft: Editor) {
        val payload = draft.existing?.cachedBody ?: return
        if (draft.servers != null) return
        io.execute {
            val rows = runCatching { XrayEngine.decodeNodes(payload, all = true).map(::serverRow) }
            runOnUiThread {
                draft.servers = rows
                if (!isDestroyed && !isFinishing && editor === draft && draft.viewingServers) render()
            }
        }
    }

    private fun serverRow(node: XrayNode): ServerRow {
        val outbound = node.outbounds.first()
        val protocol = outbound.getString("protocol").let { if (it == "hysteria") "Hysteria 2" else it.uppercase() }
        val settings = outbound.optJSONObject("settings")
        val address = settings?.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings?.optJSONArray("servers")?.optJSONObject(0) ?: settings
        val host = address?.optString("address")?.takeIf {
            it.length in 1..253 && it.all { char -> char.isLetterOrDigit() || char in ".-:[]_%" }
        }
        val port = address?.optInt("port")?.takeIf { it in 1..65535 }
        val endpoint = when {
            host == null || port == null -> host
            ':' in host && !host.startsWith('[') -> "[$host]:$port"
            else -> "$host:$port"
        }
        return ServerRow(node.id, node.name, listOfNotNull(protocol, endpoint).joinToString(" · "))
    }

    private fun refreshMeasurements() {
        val draft = editor?.takeIf { it.viewingServers && it.servers?.isSuccess == true } ?: return
        val revision = ++measurementRevision
        val active = connectivity.linkContext()?.kind
        io.execute {
            val samples = MeasurementStore(applicationContext).load().samples() +
                MeasurementStore(applicationContext, manual = true).load().samples()
            val network = active ?: samples.maxByOrNull { it.time }?.network
            val latest = network?.let { latestServerMeasurements(samples, it) }.orEmpty()
            runOnUiThread {
                if (isDestroyed || editor !== draft || !draft.viewingServers || revision != measurementRevision) return@runOnUiThread
                measurements = latest
                val label = when (network) {
                    NetworkKind.WIFI -> "Wi-Fi"
                    NetworkKind.MOBILE -> "Мобильная сеть"
                    NetworkKind.OTHER -> "Другая сеть"
                    null -> "Нет сети"
                }
                serverNetwork?.text = if (active == null && network != null) "Последняя сеть · $label" else label
                serverAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun toggleCheck(source: VpnSource) {
        val running = check
        if (running != null) {
            running.close()
            check = null
            checkMessage = "Проверка отменена."
            updateCheckControls()
            refreshMeasurements()
            return
        }
        val client = SubscriptionCheck(this, source.id) { progress ->
            checkMessage = when {
                progress.error != null -> progress.error
                progress.finished -> "Проверка завершена."
                else -> "Проверено ${progress.checked} / ${progress.total}"
            }
            if (progress.finished) check = null
            updateCheckControls()
            refreshMeasurements()
        }
        if (client.start()) {
            check = client
            checkMessage = "Запускаем проверку…"
        } else checkMessage = "Не удалось запустить проверку."
        updateCheckControls()
    }

    private fun updateCheckControls() {
        checkAction?.text = if (check == null) "Проверить серверы" else "Отменить проверку"
        checkStatus?.apply {
            text = checkMessage
            visibility = if (checkMessage.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun renderServers(draft: Editor) {
        val source = checkNotNull(draft.existing)
        val result = draft.servers
        val rows = result?.getOrNull()
        val panel = ui.column().apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12))
            place(ui.section(source.title, rows?.size?.toString()))
        }
        if (rows == null) {
            val info = when {
                source.cachedBody == null -> "Список ещё не загружен. Вернитесь к подписке и обновите список узлов."
                result?.isFailure == true -> "Не удалось прочитать сохранённый список. Обновите подписку."
                else -> "Читаем сохранённый список…"
            }
            panel.place(ui.text(info, 17).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }, top = 12)
        } else {
            serverNetwork = ui.text("", 15, ui.accent).also { panel.place(it, top = 4) }
            checkAction = ui.button("Проверить серверы") { toggleCheck(source) }.also { panel.place(it, top = 10) }
            panel.place(ui.text("До 256 КиБ на сервер.", 14), top = 4)
            checkStatus = ui.text("", 15, ui.accent).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }.also { panel.place(it, top = 5) }
            updateCheckControls()
            val list = ListView(this).apply {
                divider = null
                selector = ColorDrawable(android.graphics.Color.TRANSPARENT)
                adapter = object : ArrayAdapter<ServerRow>(this@SourcesActivity, 0, rows) {
                    override fun areAllItemsEnabled() = false
                    override fun isEnabled(position: Int) = false

                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val item = convertView as? LinearLayout ?: ui.column().apply {
                            place(ui.divider(), height = ui.dp(1).coerceAtLeast(1))
                            place(ui.text("", 20).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, top = 10)
                            place(ui.text("", 15, ui.accent).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                                top = 4)
                            place(ui.text("", 15), top = 5, bottom = 10)
                        }
                        val row = rows[position]
                        (item.getChildAt(1) as TextView).text = row.name
                        (item.getChildAt(2) as TextView).text = row.details
                        val sample = measurements[row.id]
                        val ping = sample?.latencyMs?.let { "${it.roundToInt()} мс" } ?: "—"
                        val speed = sample?.downloadMbps?.let { "${speedFormat.format(it)} Мбит/с" } ?: "—"
                        (item.getChildAt(3) as TextView).text = "Пинг $ping · Загрузка $speed"
                        return item
                    }
                }.also { serverAdapter = it }
            }
            panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(10) })
            refreshMeasurements()
        }
        val width = if (resources.configuration.screenWidthDp > 640) ui.dp(640) else -1
        body.removeAllViews()
        body.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL))
    }

    private fun renderManualProfile(content: LinearLayout, draft: Editor) {
        val profile = draft.profile
        if (draft.existing == null) {
            content.place(button("Вставить ссылку") { draft.byFields = false; render() }, top = 12)
        }
        content.place(button("Протокол · ${profile.protocol.title}") {
            choose("Протокол", ManualProtocol.entries, profile.protocol, ManualProtocol::title) { next ->
                val current = draft.profile
                val base = current.copy(
                    protocol = next,
                    port = if (current.port == current.protocol.defaultPort) next.defaultPort else current.port,
                    secret = current.secret.takeIf { next.usesUuid == current.protocol.usesUuid }.orEmpty(),
                    transport = current.transport.takeIf { it in next.transports } ?: next.transports.first(),
                )
                val preferred = if (current.protocol.transports.size == 1) next.defaultProtection else current.protection
                draft.profile = base.copy(protection = preferred.takeIf { it in base.protections } ?: next.defaultProtection)
            }
        }, top = 12)
        field(content, "Адрес сервера", profile.address, 253, url = true) { draft.profile = draft.profile.copy(address = it) }
        field(content, "Порт", profile.port, 5, type = InputType.TYPE_CLASS_NUMBER) { draft.profile = draft.profile.copy(port = it) }
        val credentialType = InputType.TYPE_CLASS_TEXT or if (profile.protocol.usesUuid) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else InputType.TYPE_TEXT_VARIATION_PASSWORD
        field(content, profile.protocol.credential, profile.secret, 512, type = credentialType) {
            draft.profile = draft.profile.copy(secret = it)
        }
        profile.protocolOptions.forEach { addManualOption(content, draft, it) }

        manualSection(content, "Транспорт")
        if (profile.protocol.transports.size == 1) content.place(ui.text(profile.transport.title, 18, ui.accent), top = 8)
        else content.place(button(profile.transport.title) {
            choose("Транспорт", profile.protocol.transports, profile.transport, ManualTransport::title) { next ->
                val updated = draft.profile.copy(transport = next)
                draft.profile = updated.copy(protection = updated.protection.takeIf { it in updated.protections } ?: ManualProtection.TLS)
            }
        }, top = 8)
        profile.transportOptions.forEach { addManualOption(content, draft, it) }

        manualSection(content, "Защита соединения")
        if (profile.protections.size == 1) content.place(ui.text(profile.protection.title, 18, ui.accent), top = 8)
        else content.place(button(profile.protection.title) {
            choose("Защита соединения", profile.protections, profile.protection, ManualProtection::title) { next ->
                draft.profile = draft.profile.copy(protection = next)
            }
        }, top = 8)
        profile.protectionOptions.forEach { addManualOption(content, draft, it) }
        if (profile.allowsMux) {
            manualSection(content, "Мультиплексирование")
            addManualOption(content, draft, ManualOption.MUX)
        }
    }

    private fun manualSection(content: LinearLayout, title: String) {
        content.place(ui.divider(), top = 20, height = ui.dp(1).coerceAtLeast(1))
        content.place(ui.section(title), top = 10)
    }

    private fun addManualOption(content: LinearLayout, draft: Editor, option: ManualOption) {
        val value = draft.profile.value(option)
        if (option.choices.isNotEmpty()) {
            fun label(item: String) = when (item) {
                "" -> if (option == ManualOption.FLOW) "Без Flow" else "По умолчанию"
                "true" -> "Включён"
                "false" -> "Выключен"
                else -> item
            }
            content.place(button("${option.title} · ${label(value)}") {
                choose(option.title, option.choices, draft.profile.value(option), ::label) { selected ->
                    draft.profile = draft.profile.withOption(option, selected)
                }
            }, top = 8)
        } else {
            val type = when (option) {
                ManualOption.HYSTERIA_OBFS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ManualOption.EARLY, ManualOption.HEARTBEAT, ManualOption.KCP_MTU, ManualOption.KCP_TTI,
                ManualOption.KCP_UP, ManualOption.KCP_DOWN, ManualOption.HYSTERIA_IDLE,
                ManualOption.HYSTERIA_UP, ManualOption.HYSTERIA_DOWN, ManualOption.MUX -> InputType.TYPE_CLASS_NUMBER
                else -> null
            }
            val placeholder = if (option == ManualOption.PATH) "/" else ""
            field(content, option.title, value, option.limit, type = type, placeholder = placeholder) {
                draft.profile = draft.profile.withOption(option, it)
            }
        }
    }

    private fun <T> choose(title: String, items: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit) {
        ui.choose(title, items.map(label), items.indexOf(selected)) { index ->
            change(items[index])
            render(preserveScroll = true)
        }
    }

    private fun save(draft: Editor, refresh: Boolean) {
        val name = draft.name.trim().filterNot(Char::isISOControl).take(64)
        val manual = draft.profile.takeIf { draft.kind == SourceKind.MANUAL && draft.byFields }
        var config: String? = null
        val input = try {
            when {
                draft.kind == SourceKind.SUBSCRIPTION -> subscriptionUri(draft.reference.trim()).toASCIIString()
                manual != null && manual == draft.savedProfile && draft.existing != null -> {
                    config = draft.existing.cachedBody
                    draft.existing.reference
                }
                manual != null -> {
                    config = manual.configuration()
                    manual.reference()
                }
                else -> draft.reference.trim().also {
                    if (sourceKind(it) != SourceKind.MANUAL) throw SourceFailure("Вставьте поддерживаемую ссылку на один сервер.")
                }
            }
        } catch (failure: Exception) {
            message = when {
                failure is SourceFailure -> failure.message
                draft.kind == SourceKind.SUBSCRIPTION -> "Введите HTTPS-ссылку подписки."
                else -> failure.message ?: "Проверьте параметры сервера."
            }
            updateControls()
            return
        }
        val id = draft.existing?.id ?: UUID.randomUUID().toString()
        if (sources.any { it.id != id && it.kind == draft.kind && it.reference == input }) {
            message = "Этот источник уже добавлен."
            updateControls()
            return
        }
        val useHwid = draft.hwid
        val kind = draft.kind
        val previous = draft.existing
        runWork("Проверяем источник…", "Не удалось сохранить источник. Проверьте ссылку и сеть.", {
            if (kind == SourceKind.MANUAL) {
                val node = runCatching { XrayEngine.decodeNodes(config ?: input).single() }.getOrElse {
                    throw SourceFailure("Конфигурация сервера не распознана.")
                }
                if (config != null && XrayEngine.validated(listOf(node)) { true }.isEmpty()) {
                    throw SourceFailure("Xray отклонил конфигурацию. Проверьте параметры транспорта и защиты.")
                }
                store.save(VpnSource(id, kind, name.ifBlank { node.name }, input, config, 1, false), name.isBlank())
            } else {
                val reused = previous?.takeIf { it.reference == input && !refresh }
                val payload = reused?.cachedBody ?: SubscriptionDownload.fetch(applicationContext, input, useHwid)
                val count = reused?.nodeCount ?: runCatching { XrayEngine.decodeNodes(payload).size }.getOrElse {
                    throw SourceFailure("В подписке нет поддерживаемых конфигураций.")
                }
                val label = name.ifBlank { subscriptionUri(input).host }
                store.save(VpnSource(id, kind, label, input, payload, count, useHwid), name.isBlank())
            }
        }) { result ->
            result.getOrNull()?.let {
                sources = it
                editor = null
                message = if (refresh) "Подписка обновлена." else "Источник сохранён."
                render()
            }
        }
    }

    private fun confirmDelete(source: VpnSource) {
        ui.confirm("Удалить источник?", source.title, "Удалить") {
            runWork("Удаляем источник…", "Не удалось удалить источник.", { store.delete(source.id) }) { result ->
                result.getOrNull()?.let {
                    sources = it
                    editor = null
                    message = "Источник удалён."
                    render()
                }
            }
        }
    }

    private fun chooseQr() {
        ui.choose("Импорт из QR", listOf("Открыть камеру", "Выбрать изображение")) { index ->
            if (index == 0) {
                startActivityForResult(Intent(this, QrCameraActivity::class.java), requestCamera)
            } else {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
                runCatching { startActivityForResult(picker, requestImage) }.onFailure {
                    message = "На устройстве нет приложения для выбора изображения."
                    updateControls()
                }
            }
        }
    }

    @Deprecated("Uses platform activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCamera) {
            if (resultCode == RESULT_OK) data?.getStringExtra(QrCameraActivity.resultText)?.let(::reviewQr)
            else data?.getStringExtra(QrCameraActivity.errorText)?.let {
                message = it
                updateControls()
            }
        } else if (requestCode == requestImage && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            if (uri.scheme != "content") return
            runWork("Читаем QR-код…", "QR-код не найден или изображение не открывается.", {
                QrReader.image(applicationContext.contentResolver, uri)
                    ?: throw SourceFailure("QR-код не найден на изображении.")
            }) { result -> result.getOrNull()?.let(::reviewQr) }
        }
    }

    private fun reviewQr(raw: String) {
        val kind = sourceKind(raw)
        if (kind == null) {
            message = "QR-код должен содержать HTTPS-подписку или ссылку на один сервер."
            updateControls()
        } else open(Editor(kind).apply { reference = raw.trim(); byFields = false })
    }

    private fun <T> runWork(working: String, failure: String, action: () -> T, completion: (Result<T>) -> Unit) {
        waiting = true
        message = working
        updateControls()
        io.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                waiting = false
                val error = result.exceptionOrNull()
                message = when (error) {
                    null -> null
                    is SourceFailure -> error.message
                    else -> failure
                }
                updateControls()
                completion(result)
            }
        }
    }

    private fun updateControls() {
        controls.forEach { ui.enabled(it, !waiting) }
        notice?.apply {
            text = message.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
    }

    private fun button(title: String, primary: Boolean = false, action: () -> Unit) =
        ui.button(title, primary, action).also(controls::add)

    private fun field(
        container: LinearLayout, label: String, initial: String, limit: Int,
        url: Boolean = false, type: Int? = null, placeholder: String = "", onChange: (String) -> Unit,
    ): EditText {
        val input = ui.field(placeholder, url).apply {
            id = View.generateViewId()
            if (type != null) inputType = type
            typeface = ui.face
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(InputFilter.LengthFilter(limit))
            setText(initial)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = onChange(text?.toString().orEmpty())
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }
        container.place(ui.text(label, 16).apply { labelFor = input.id }, top = 10, bottom = 4)
        container.place(input)
        controls.add(input)
        return input
    }

    private fun LinearLayout.place(view: View, top: Int = 0, bottom: Int = 0, height: Int = -2) {
        addView(view, LinearLayout.LayoutParams(-1, height).apply {
            topMargin = ui.dp(top)
            bottomMargin = ui.dp(bottom)
        })
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor()
        private const val requestCamera = 41
        private const val requestImage = 42
    }
}
