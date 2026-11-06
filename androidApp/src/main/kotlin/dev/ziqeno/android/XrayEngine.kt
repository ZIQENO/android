package dev.ziqeno.android

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Build
import android.system.OsConstants
import dev.ziqeno.routing.RouteSelection
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal object XrayEngine {
    const val mtu = 1280
    const val vpnDns = "1.1.1.1"
    const val blocked = "blocked"
    const val defaultRoute = "default"
    const val direct = "direct"
    const val directProbe = "domain-direct-probe"
    const val domainPolicy = "domain-policy"
    const val domainValidation = "domain-policy-validation"
    const val domainVerification = "domain-policy-verification"
    const val domainDnsFallback = "domain-dns-fallback"
    private const val probeTag = "benchmarks"
    private val control = AtomicReference<((Int) -> Boolean)?>(null)
    private val socketProtection = DialerController { fd -> control.get()?.invoke(fd.toInt()) ?: true }
    private var controllerRegistered = false
    private var observer: XrayAppObserver? = null

    fun decodeNodes(body: String, all: Boolean = false): List<XrayNode> =
        SubscriptionNodes.read(body, if (all) Int.MAX_VALUE else SubscriptionNodes.defaultLimit) { links ->
            invoke("convertShareLinksToXrayJson", json("text" to links)).getJSONArray("outbounds")
        }

    fun validated(nodes: List<XrayNode>, active: () -> Boolean): List<XrayNode> {
        if (testOutbounds(nodes)) return nodes
        return nodes.takeWhile { active() }.filter { active() && testOutbounds(listOf(it)) }
    }

    private fun testOutbounds(nodes: List<XrayNode>) = runCatching {
        val config = json("log" to json("loglevel" to "none"), "outbounds" to allOutbounds(nodes))
        invoke("testXray", json("xrayJson" to config.toString()))
    }.isSuccess

    fun start(
        nodes: List<XrayNode>,
        tracked: List<TrackedApp>,
        targets: AppTargets,
        domains: DomainRules,
        tunFd: Int,
        controlPath: String,
        connectivity: ConnectivityManager,
        packages: PackageManager,
        protect: (Int) -> Boolean,
    ): RouteProbes {
        configureDialer(protect)
        if (tracked.isNotEmpty() && Build.VERSION.SDK_INT >= 29) {
            registerProcessFinder(connectivity, packages, tracked)
        }
        LibXray.setDNS(socketProtection, "$vpnDns:53")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val password = UUID.randomUUID().toString()
        val routing = XrayAppObserver(File(controlPath).parentFile!!, nodes, tracked, targets, packages, domains)
        observer = routing
        invoke("runXray", json("xrayJson" to config(nodes, tracked, tunFd, controlPath, port, password, routing).toString()))
        return RouteProbes(port, password)
    }

    // The check service owns a separate Android process and its own libXray lifecycle.
    fun <T> withBenchmarks(nodes: List<XrayNode>, protect: (Int) -> Boolean, protectedDns: Boolean, action: (RouteProbes) -> T): T {
        configureDialer(protect)
        try {
            if (protectedDns) LibXray.setDNS(socketProtection, "$vpnDns:53")
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val password = UUID.randomUUID().toString()
            val rules = nodes.map {
                json("type" to "field", "inboundTag" to JSONArray(listOf(probeTag)),
                    "user" to JSONArray(listOf(it.id)), "outboundTag" to it.id)
            }
            val config = json(
                "log" to json("loglevel" to "none", "access" to "none"),
                "inbounds" to JSONArray(listOf(probeInbound(port, password, nodes.map(XrayNode::id)))),
                "outbounds" to allOutbounds(nodes),
                "routing" to json("rules" to JSONArray(rules)),
            )
            invoke("runXray", json("xrayJson" to config.toString()))
            return RouteProbes(port, password).use(action)
        } finally {
            try {
                invoke("stopXray")
            } finally {
                LibXray.resetDNS()
                control.set(null)
            }
        }
    }

    private fun configureDialer(protect: (Int) -> Boolean) {
        if (!controllerRegistered) {
            LibXray.registerDialerController(socketProtection)
            controllerRegistered = true
        }
        control.set(protect)
    }

    fun stop() {
        try {
            invoke("stopXray")
        } finally {
            observer?.close()
            observer = null
            LibXray.registerProcessFinder(null, Build.VERSION.SDK_INT.toLong())
            LibXray.resetDNS()
            control.set(null)
        }
    }

    @android.annotation.TargetApi(29)
    private fun registerProcessFinder(connectivity: ConnectivityManager, packages: PackageManager, tracked: List<TrackedApp>) {
        val identities = tracked.associate { it.app.uid to it.app.packageName }
        LibXray.registerProcessFinder({ network, source, sourcePort, target, targetPort ->
            runCatching {
                val protocol = when (network) {
                    "tcp" -> OsConstants.IPPROTO_TCP
                    "udp" -> OsConstants.IPPROTO_UDP
                    else -> return@registerProcessFinder -1L
                }
                val local = InetSocketAddress(InetAddresses.parseNumericAddress(source), sourcePort.toInt())
                val remote = InetSocketAddress(InetAddresses.parseNumericAddress(target), targetPort.toInt())
                val uid = connectivity.getConnectionOwnerUid(protocol, local, remote)
                val expected = identities[uid]
                // An uninstalled package's UID can later be assigned to another app.
                if (expected != null && packages.getPackagesForUid(uid)?.singleOrNull() == expected) uid.toLong() else -1L
            }.getOrDefault(-1L)
        }, Build.VERSION.SDK_INT.toLong())
    }

    private fun allOutbounds(nodes: List<XrayNode>) = JSONArray(
        listOf(json("tag" to blocked, "protocol" to "blackhole")) +
            nodes.flatMap(XrayNode::outbounds).distinctBy { it.getString("tag") },
    )

    private fun config(
        nodes: List<XrayNode>, tracked: List<TrackedApp>, tunFd: Int, path: String, port: Int, password: String,
        observer: XrayAppObserver,
    ): JSONObject {
        val outbounds = allOutbounds(nodes).put(json("tag" to "tunnel-dns", "protocol" to "dns"))
            .put(json("tag" to direct, "protocol" to "freedom"))
            .put(json("tag" to directProbe, "protocol" to "freedom"))
        val balancers = (RouteSelection.services.map { it.id } + listOf(defaultRoute, domainPolicy) + tracked.map(TrackedApp::scope))
            .distinct().map { id ->
            json("tag" to id, "selector" to JSONArray(listOf(blocked)))
        }
        val tunnel = json(
            "tag" to "vpn", "protocol" to "tun",
            "settings" to json("name" to "ziqeno", "mtu" to mtu),
            "sniffing" to json("enabled" to true, "routeOnly" to true, "destOverride" to JSONArray(listOf("http", "tls", "quic"))),
        )
        val probe = probeInbound(port, password, nodes.map(XrayNode::id) + directProbe)
        val dns = listOf("https://1.1.1.1/dns-query", json("address" to "https://1.0.0.1/dns-query", "tag" to domainDnsFallback))
        return json(
            "env" to json("xray.tun.fd" to tunFd.toString()),
            "log" to json("loglevel" to "warning", "access" to "none", "error" to observer.eventPath),
            "inbounds" to JSONArray(listOf(tunnel, probe)),
            "outbounds" to outbounds,
            "dns" to json("servers" to JSONArray(if (nodes.isEmpty()) dns.reversed() else dns)),
            "api" to json("tag" to "controller", "listen" to path, "services" to JSONArray(listOf("RoutingService", "StatsService"))),
            "stats" to JSONObject(),
            "policy" to json("system" to json(
                "statsInboundUplink" to true, "statsInboundDownlink" to true,
                "statsOutboundUplink" to true, "statsOutboundDownlink" to true,
            )),
            "routing" to json("script" to observer.scriptPath, "balancers" to JSONArray(balancers)),
        )
    }

    private fun probeInbound(port: Int, password: String, users: List<String>) = json(
        "tag" to probeTag, "listen" to "127.0.0.1", "port" to port, "protocol" to "http",
        "settings" to json("accounts" to JSONArray(users.map { json("user" to it, "pass" to password) })),
    )

    private fun invoke(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val request = json("apiVersion" to 3, "method" to method, "payload" to payload)
        val response = JSONObject(LibXray.invoke(request.toString()))
        check(response.optBoolean("success")) { "Xray operation failed: $method" }
        return response.optJSONObject("data") ?: JSONObject()
    }
}

internal fun json(vararg entries: Pair<String, Any>): JSONObject = JSONObject().apply {
    for ((key, value) in entries) put(key, value)
}





