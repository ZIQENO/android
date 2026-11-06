package dev.ziqeno.android

import java.net.InetAddress

internal enum class AppProbeProtocol(val title: String) { TLS("TLS"), MTPROTO("MTProto") }

internal data class AppTarget(
    val host: String, val address: String, val port: Int = 443, val protocol: AppProbeProtocol = AppProbeProtocol.TLS,
) {
    val authority get() = if (':' in address) "[$address]:$port" else "$address:$port"
    val identity get() = if (protocol == AppProbeProtocol.TLS) host else authority
    val historyKey by lazy {
        if (protocol == AppProbeProtocol.TLS && port == 443) routeDigest("$host\u0000$address")
        else routeDigest("$protocol\u0000$authority")
    }
}

internal class AppTargets(apps: List<LaunchableApp>) {
    private val scopes = apps.associate { it.scope to it.packageName }
    private val windows = mutableMapOf<String, Window>()
    private var pending = false

    @Synchronized
    fun observe(scope: String, host: String, ip: String, now: Long, port: Int = 443) {
        val owner = scopes[scope] ?: return
        if (port !in 1..65535) return
        val address = publicAddress(ip) ?: return
        val telegram = isTelegram(owner)
        if (telegram && !TelegramDataCenters.contains(address)) return
        if (!telegram && (port != 443 || !validHost(host))) return
        val target = if (telegram) AppTarget("", ip, port, AppProbeProtocol.MTPROTO) else AppTarget(host, ip)
        val key = target.identity
        val window = windows.getOrPut(scope) { Window() }
        val active = window.target
        val seen = window.hosts.getOrPut(key) { Seen() }
        seen.record(now)
        if (window.hosts.size > 4) {
            window.hosts.entries.filter { it.key != active?.identity && it.key != key }
                .minByOrNull { it.value.last }?.let { window.hosts.remove(it.key) }
        }
        val previous = window.hosts[active?.identity]
        val replace = active == null || previous == null ||
            active.identity != key && (now - previous.last >= 60_000 ||
                now - window.changed >= 30_000 && seen.hits.size >= 3 && seen.hits.size > previous.count(now)) ||
            active.identity == key && active.address != ip && now - window.changed >= 30_000
        if (replace) {
            window.target = target
            window.changed = now
            pending = true
        }
    }

    @Synchronized
    fun snapshot(now: Long): Map<String, AppTarget> {
        pending = false
        return active(now)
    }

    @Synchronized
    fun active(now: Long): Map<String, AppTarget> {
        windows.values.removeAll { window -> now - (window.hosts[window.target?.identity]?.last ?: 0L) >= 600_000 }
        return windows.mapNotNull { (scope, window) -> window.target?.let { scope to it } }.toMap()
    }

    @Synchronized
    fun takePending(): Boolean = pending.also { pending = false }

    @Synchronized
    fun clear() {
        windows.clear()
        pending = false
    }

    private class Window {
        val hosts = mutableMapOf<String, Seen>()
        var target: AppTarget? = null
        var changed = 0L
    }

    private class Seen {
        val hits = ArrayDeque<Long>()
        var last = 0L
            private set

        fun record(now: Long) {
            if (hits.isEmpty() || now - hits.last() >= 1_000) {
                hits.addLast(now)
                if (hits.size > 6) hits.removeFirst()
            }
            last = now
            count(now)
        }

        fun count(now: Long): Int {
            while (hits.isNotEmpty() && now - hits.first() >= 60_000) hits.removeFirst()
            return hits.size
        }
    }

    private companion object {
        val domain = Regex("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?")
        val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        val ipv6 = Regex("[0-9a-fA-F:.]{2,45}")
        val localSuffixes = listOf(".local", ".localhost", ".internal", ".home.arpa", ".onion", ".test", ".invalid")
        fun validHost(host: String) = domain.matches(host) && localSuffixes.none(host::endsWith)

        fun publicAddress(ip: String): ByteArray? {
            if (!ipv4.matches(ip) && !(':' in ip && ipv6.matches(ip))) return null
            if (':' !in ip && ip.split('.').any { it.toInt() > 255 }) return null
            val bytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return null
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            if (bytes.size == 16) {
                return bytes.takeIf {
                    a in 32..63 && !(a == 32 && (b == 2 || b == 1 && (c == 0 || c == 13 && bytes[3] == 0xb8.toByte())))
                }
            }
            return bytes.takeIf { a in 1..223 && a !in listOf(10, 127) &&
                !(a == 100 && b in 64..127 || a == 169 && b == 254 || a == 172 && b in 16..31 ||
                    a == 192 && (b == 168 || b == 0 && c in setOf(0, 2)) ||
                    a == 198 && (b in 18..19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113)
            }
        }
    }
}
