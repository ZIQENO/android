package dev.ziqeno.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class VpnPhase { OFF, CHECKING, CONNECTED, STOPPING, ERROR }

internal data class VpnStatus(
    val phase: VpnPhase, val messageRes: Int, val overview: RoutingOverview? = null,
    val domainUpdateFailed: Boolean = false,
)

internal object VpnSession {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(VpnStatus) -> Unit>()

    var status = VpnStatus(VpnPhase.OFF, R.string.routing_vpn_off)
        private set

    fun observe(listener: (VpnStatus) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun remove(listener: (VpnStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: VpnStatus) {
        check(Looper.myLooper() == main.looper)
        status = value
        listeners.forEach { it(value) }
    }
}

class RouterVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val wanted = AtomicBoolean()
    private var tunnel: ParcelFileDescriptor? = null
    private var coreTouched = false
    @Volatile private var session: AdaptiveSession? = null
    private var nextRound: ScheduledFuture<*>? = null
    private var watchdogTask: ScheduledFuture<*>? = null
    private var trafficTask: ScheduledFuture<*>? = null
    private var controlSocket: File? = null
    private var startupFailures = 0
    private var listening = false
    private val display by lazy { getSharedPreferences("display", Context.MODE_PRIVATE) }
    private val domainStore by lazy { DomainRuleStore(this) }
    private val domainReloadPending = AtomicBoolean()
    @Volatile private var domainUpdateFailed = false
    private val domainsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadDomains() }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    @Volatile private var showDetails = false
    private var recentSpeed: TrafficPoint? = null
    private var notificationText: String? = null
    private var notificationLanguageTags = ""
    private val displayChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == detailsPreference) {
            showDetails = display.getBoolean(detailsPreference, false)
            updateNotification(VpnSession.status)
        }
    }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = checkNetwork()
        override fun onLost(network: Network) = checkNetwork()
    }

    override fun onCreate() {
        super.onCreate()
        notificationLanguageTags = resources.configuration.locales.toLanguageTags()
        showDetails = display.getBoolean(detailsPreference, false)
        display.registerOnSharedPreferenceChangeListener(displayChanged)
        domainStore.observe(domainsChanged)
        notifications.createNotificationChannel(NotificationChannel(channel, "VPN", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val languageTags = newConfig.locales.toLanguageTags()
        if (languageTags == notificationLanguageTags) return
        notificationLanguageTags = languageTags
        notificationText = null
        val status = VpnSession.status
        if (status.phase == VpnPhase.CONNECTED) {
            val adaptive = session ?: return
            domainWorker.execute {
                adaptive.localizedOverview()?.let { publishUpdate(adaptive, it, alreadyConnected = true) }
            }
        } else updateNotification(status)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            disconnect -> requestStop()
            connect -> if (wanted.compareAndSet(false, true)) {
                val checking = VpnStatus(VpnPhase.CHECKING, R.string.routing_vpn_checking)
                domainUpdateFailed = false
                recentSpeed = null
                notificationText = summary(checking)
                startForeground(notificationId, notification(checkNotNull(notificationText)))
                VpnSession.publish(checking)
                if (!listening) {
                    connectivity.registerDefaultNetworkCallback(networkCallback, main)
                    listening = true
                }
                worker.execute { startTunnel() }
            }
            else -> if (!wanted.get()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        try {
            startupFailures = 0
            val domains = domainStore.load()
            val sources = SourceStore(this).load()
            if (sources.isEmpty() && domains.active.isEmpty()) throw StartFailure(R.string.routing_vpn_add_source)
            val nodes = XrayEngine.validated(SourceNodes.decode(sources, wanted::get), wanted::get)
            if (!wanted.get()) return
            if (nodes.isEmpty() && domains.active.isEmpty()) throw StartFailure(R.string.routing_vpn_no_compatible_servers)
            val interfaceFd = Builder().setSession(getString(R.string.app_name))
                .setMtu(XrayEngine.mtu)
                .addAddress("10.71.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd71:5e1:100::1", 126)
                .addRoute("::", 0)
                .addDnsServer(XrayEngine.vpnDns)
                .setBlocking(false)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish() ?: throw StartFailure(R.string.routing_vpn_access_failed)
            tunnel = interfaceFd
            if (!wanted.get()) return
            val socket = File(filesDir, "xr-${UUID.randomUUID().toString().take(12)}.sock")
            controlSocket = socket
            coreTouched = true
            val tracked = AppTrackingStore(this).configured(this)
            val targets = AppTargets(tracked.filter { it.mode == AppRouteMode.AUTO }.map(TrackedApp::app))
            val probes = XrayEngine.start(nodes, tracked, targets, domains, interfaceFd.fd, socket.path, connectivity, packageManager, ::protect)
            val control = RoutingControl(socket.path)
            val adaptive = AdaptiveSession(applicationContext, nodes, tracked, targets, domains, MeasurementStore(this), RouteLog(this), probes, control,
                connectivity::linkContext, { power.isPowerSaveMode })
            session = adaptive
            reloadDomains()
            startTrafficSampling(adaptive, control)
            watchdogTask = watchdog.scheduleWithFixedDelay({ checkFast(adaptive) }, 1, 1, TimeUnit.SECONDS)
            scheduleRound(0)
        } catch (failure: Exception) {
            failStartup(failure)
        }
    }

    private fun reloadDomains(delay: Long = 0) {
        if (!wanted.get() || !domainReloadPending.compareAndSet(false, true)) return
        // Policy edits must not wait behind a network probe round on the main worker.
        domainWorker.schedule({
            domainReloadPending.set(false)
            val adaptive = session ?: return@schedule
            val valid = { wanted.get() && session === adaptive }
            val result = runCatching { adaptive.applyDomains(domainStore.load(), domainUpdateFailed, valid) }
            if (!valid()) return@schedule
            val statusChanged = domainUpdateFailed != result.isFailure
            domainUpdateFailed = result.isFailure
            if (statusChanged) main.post {
                if (valid()) VpnSession.publish(VpnSession.status.copy(domainUpdateFailed = domainUpdateFailed))
            }
            if (result.isFailure) {
                Log.w("ZiqenoRouting", "Domain policy update failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                reloadDomains(3_000)
            } else {
                result.getOrNull()?.let { update ->
                    publishUpdate(adaptive, update, alreadyConnected = true)
                    worker.execute { if (valid()) scheduleRound(0) }
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun startTrafficSampling(adaptive: AdaptiveSession, control: RoutingControl) {
        val sampler = TrafficSampler()
        TrafficHistory.load(this)
        TrafficHistory.startSession(System.currentTimeMillis())
        trafficTask = trafficWorker.scheduleWithFixedDelay({
            val bytes = runCatching { control.traffic() }.getOrNull()
            if (bytes != null && wanted.get() && session === adaptive) {
                val now = SystemClock.elapsedRealtime()
                sampler.record(now, System.currentTimeMillis(), bytes)?.let { point ->
                    TrafficHistory.add(point, now)
                    if (showDetails) main.post {
                        if (wanted.get() && session === adaptive && showDetails) {
                            recentSpeed = point
                            updateNotification(VpnSession.status)
                        }
                    }
                }
            }
        }, 0, trafficIntervalMillis, TimeUnit.MILLISECONDS)
    }

    private fun checkFast(adaptive: AdaptiveSession) {
        val valid = { wanted.get() && session === adaptive }
        if (!valid()) return
        val refresh = runCatching { adaptive.checkFast(valid) { update -> publishUpdate(adaptive, update) } }.getOrDefault(false)
        if (refresh) worker.execute { if (valid()) scheduleRound(0) }
    }

    private fun checkNetwork() {
        if (!wanted.get()) return
        worker.execute {
            if (wanted.get() && session?.linkChanged() == true) scheduleRound(250)
        }
    }

    private fun scheduleRound(delay: Long) {
        nextRound?.cancel(false)
        nextRound = worker.schedule(::monitor, delay, TimeUnit.MILLISECONDS)
    }

    private fun monitor() {
        val adaptive = session ?: return
        if (!wanted.get()) return
        val update = try {
            adaptive.update(wanted::get) { publishUpdate(adaptive, it) }
        } catch (failure: Exception) {
            Log.w("ZiqenoRouting", "Probe round failed: ${failure.javaClass.simpleName}")
            null
        }
        if (!wanted.get()) return
        if (update?.ready == true) {
            startupFailures = 0
            publishUpdate(adaptive, update)
            scheduleRound(adaptive.nextCheckMillis())
        } else if (update?.ready == false && VpnSession.status.phase == VpnPhase.CHECKING && ++startupFailures >= 3) {
            failStartup(StartFailure(R.string.routing_vpn_no_https_response))
        } else {
            scheduleRound(if (adaptive.linkChanged()) 500 else 4_000)
        }
    }

    private fun publishUpdate(adaptive: AdaptiveSession, update: RoutingUpdate, alreadyConnected: Boolean = false) {
        main.post {
            if (wanted.get() && session === adaptive && update.ready && adaptive.isCurrent(update) &&
                (!alreadyConnected || VpnSession.status.phase == VpnPhase.CONNECTED)) {
                val status = VpnStatus(VpnPhase.CONNECTED, R.string.routing_vpn_connected, update.overview, domainUpdateFailed)
                VpnSession.publish(status)
                updateNotification(status)
            }
        }
    }

    private fun failStartup(failure: Exception) {
        Log.w("ZiqenoRouting", "VPN startup failed: ${failure.javaClass.simpleName}")
        releaseTunnel()
        main.post {
            if (wanted.getAndSet(false)) {
                val messageRes = (failure as? StartFailure)?.messageRes ?: R.string.routing_vpn_start_failed
                VpnSession.publish(VpnStatus(VpnPhase.ERROR, messageRes))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun requestStop() {
        if (!wanted.getAndSet(false)) return
        VpnSession.publish(VpnStatus(VpnPhase.STOPPING, R.string.routing_vpn_stopping))
        worker.execute {
            releaseTunnel()
            main.post {
                VpnSession.publish(VpnStatus(VpnPhase.OFF, R.string.routing_vpn_off))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        main.post { requestStop() }
    }

    override fun onDestroy() {
        display.unregisterOnSharedPreferenceChangeListener(displayChanged)
        domainStore.remove(domainsChanged)
        if (listening) {
            connectivity.unregisterNetworkCallback(networkCallback)
            listening = false
        }
        if (wanted.getAndSet(false)) {
            VpnSession.publish(VpnStatus(VpnPhase.OFF, R.string.routing_vpn_off))
            worker.execute { releaseTunnel() }
        }
        super.onDestroy()
    }

    private fun releaseTunnel() {
        nextRound?.cancel(false)
        nextRound = null
        watchdogTask?.cancel(false)
        watchdogTask = null
        trafficTask?.let {
            it.cancel(false)
            trafficWorker.execute { TrafficHistory.save(SystemClock.elapsedRealtime()) }
        }
        trafficTask = null
        val closing = session
        session = null
        runCatching { closing?.close() }
        try {
            if (coreTouched) runCatching { XrayEngine.stop() }
        } finally {
            coreTouched = false
            runCatching { tunnel?.close() }
            tunnel = null
            controlSocket?.let { socket ->
                socket.delete()
                File(socket.path + ".lock").delete()
            }
            controlSocket = null
        }
    }

    private fun updateNotification(status: VpnStatus) {
        if (!wanted.get() || status.phase !in listOf(VpnPhase.CHECKING, VpnPhase.CONNECTED)) return
        if (!notifications.areNotificationsEnabled()) return
        val text = summary(status)
        if (text == notificationText) return
        notificationText = text
        notifications.notify(notificationId, notification(text))
    }

    private fun summary(status: VpnStatus): String {
        if (!showDetails || status.phase != VpnPhase.CONNECTED) return getString(status.messageRes)
        val server = status.overview?.routes?.firstOrNull()?.server ?: return getString(status.messageRes)
        val current = recentSpeed?.takeIf { System.currentTimeMillis() - it.at in 0..trafficGapMillis }
        return if (current == null) server else {
            val down = "%.2f".format(current.download)
            val up = "%.2f".format(current.upload)
            getString(R.string.routing_vpn_notification_speed, server, down, up)
        }
    }

    private fun notification(status: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RouterVpnService::class.java).setAction(disconnect), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channel)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.routing_vpn_disconnect), stop).build())
            .build()
    }

    private class StartFailure(val messageRes: Int) : Exception()

    companion object {
        internal const val connect = "dev.ziqeno.android.CONNECT"
        internal const val disconnect = "dev.ziqeno.android.DISCONNECT"
        internal const val detailsPreference = "vpn_notification_details"
        private const val channel = "vpn"
        private const val notificationId = 10
        private val worker = Executors.newSingleThreadScheduledExecutor()
        private val watchdog = Executors.newSingleThreadScheduledExecutor()
        private val trafficWorker = Executors.newSingleThreadScheduledExecutor()
        private val domainWorker = Executors.newSingleThreadScheduledExecutor()
    }
}





