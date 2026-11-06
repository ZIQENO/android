package dev.ziqeno.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Base64
import java.util.UUID
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

internal enum class ManualProtocol(val title: String, val key: String, val defaultPort: String) {
    VLESS("VLESS", "vless", "443"),
    VMESS("VMess", "vmess", "443"),
    TROJAN("Trojan", "trojan", "443"),
    SHADOWSOCKS("Shadowsocks", "shadowsocks", "8388"),
    HYSTERIA("Hysteria 2", "hysteria", "443");

    val usesUuid get() = this == VLESS || this == VMESS
    val credentialRes get() = if (usesUuid) R.string.source_manual_uuid else R.string.source_manual_password
    val transports get() = when (this) {
        HYSTERIA -> listOf(ManualTransport.HYSTERIA)
        SHADOWSOCKS -> listOf(ManualTransport.RAW)
        else -> ManualTransport.entries - ManualTransport.HYSTERIA
    }
    val defaultProtection get() = if (this == SHADOWSOCKS) ManualProtection.NONE else ManualProtection.TLS
}

internal enum class ManualTransport(val title: String, val key: String) {
    RAW("TCP (RAW)", "raw"), WS("WebSocket", "ws"), GRPC("gRPC", "grpc"), XHTTP("XHTTP", "xhttp"),
    UPGRADE("HTTPUpgrade", "httpupgrade"), KCP("mKCP", "kcp"), HYSTERIA("QUIC", "hysteria");

    val allowsReality get() = this in listOf(RAW, GRPC, XHTTP)
}

internal enum class ManualProtection(val titleRes: Int, val key: String) {
    NONE(R.string.source_manual_none, "none"), TLS(R.string.source_manual_tls, "tls"), REALITY(R.string.source_manual_reality, "reality")
}

internal enum class ManualOption(
    val titleRes: Int, val limit: Int = 255, val choices: List<String> = emptyList(), val fallback: String = "",
) {
    ENCRYPTION(R.string.source_manual_option_encryption, 4096, fallback = "none"),
    FLOW(R.string.source_manual_option_flow, choices = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")),
    VMESS_CIPHER(R.string.source_manual_option_vmess_cipher, choices = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"), fallback = "auto"),
    SS_CIPHER(R.string.source_manual_option_ss_cipher, choices = listOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    ), fallback = "aes-256-gcm"),
    HOST(R.string.source_manual_option_host),
    PATH(R.string.source_manual_option_path, 1024),
    HEADERS(R.string.source_manual_option_headers, 2048),
    EARLY(R.string.source_manual_option_early, 5),
    HEARTBEAT(R.string.source_manual_option_heartbeat, 4),
    SERVICE(R.string.source_manual_option_service, 512),
    AUTHORITY(R.string.source_manual_option_authority),
    MULTI(R.string.source_manual_option_multi, choices = listOf("false", "true"), fallback = "false"),
    XHTTP_MODE(R.string.source_manual_option_xhttp_mode, choices = listOf("auto", "packet-up", "stream-up", "stream-one"), fallback = "auto"),
    XHTTP_PADDING(R.string.source_manual_option_xhttp_padding, 16),
    KCP_MTU(R.string.source_manual_option_kcp_mtu, 5),
    KCP_TTI(R.string.source_manual_option_kcp_tti, 4),
    KCP_UP(R.string.source_manual_option_kcp_up, 6),
    KCP_DOWN(R.string.source_manual_option_kcp_down, 6),
    SNI(R.string.source_manual_option_sni),
    ALPN(R.string.source_manual_option_alpn),
    FINGERPRINT(R.string.source_manual_option_fingerprint, choices = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")),
    TLS_PIN(R.string.source_manual_option_tls_pin, 512),
    ECH(R.string.source_manual_option_ech, 3072),
    REALITY_KEY(R.string.source_manual_option_reality_key),
    SHORT_ID(R.string.source_manual_option_short_id, 16),
    SPIDER(R.string.source_manual_option_spider, 512),
    HYSTERIA_OBFS(R.string.source_manual_option_hysteria_obfs, 512),
    HYSTERIA_IDLE(R.string.source_manual_option_hysteria_idle, 3),
    HYSTERIA_CC(R.string.source_manual_option_hysteria_cc, choices = listOf("", "bbr", "reno", "brutal", "force-brutal")),
    HYSTERIA_UP(R.string.source_manual_option_hysteria_up, 6),
    HYSTERIA_DOWN(R.string.source_manual_option_hysteria_down, 6),
    MUX(R.string.source_manual_option_mux, 3),
}

internal data class ManualProfile(
    val protocol: ManualProtocol = ManualProtocol.VLESS,
    val address: String = "",
    val port: String = protocol.defaultPort,
    val secret: String = "",
    val transport: ManualTransport = protocol.transports.first(),
    val protection: ManualProtection = protocol.defaultProtection,
    val options: Map<ManualOption, String> = emptyMap(),
) {
    val protections get() = when (protocol) {
        ManualProtocol.HYSTERIA -> listOf(ManualProtection.TLS)
        ManualProtocol.SHADOWSOCKS -> listOf(ManualProtection.NONE)
        else -> if (transport.allowsReality) ManualProtection.entries
            else listOf(ManualProtection.NONE, ManualProtection.TLS)
    }

    val supportsVision get() = protocol == ManualProtocol.VLESS && transport == ManualTransport.RAW &&
        protection != ManualProtection.NONE

    val protocolOptions get() = when (protocol) {
        ManualProtocol.VLESS -> listOf(ManualOption.ENCRYPTION) + listOfNotNull(ManualOption.FLOW.takeIf { supportsVision })
        ManualProtocol.VMESS -> listOf(ManualOption.VMESS_CIPHER)
        ManualProtocol.SHADOWSOCKS -> listOf(ManualOption.SS_CIPHER)
        else -> emptyList()
    }

    val transportOptions get() = when (transport) {
        ManualTransport.RAW -> emptyList()
        ManualTransport.WS -> listOf(ManualOption.HOST, ManualOption.PATH, ManualOption.HEADERS, ManualOption.EARLY, ManualOption.HEARTBEAT)
        ManualTransport.GRPC -> listOf(ManualOption.SERVICE, ManualOption.AUTHORITY, ManualOption.MULTI)
        ManualTransport.XHTTP -> listOf(ManualOption.HOST, ManualOption.PATH, ManualOption.XHTTP_MODE, ManualOption.HEADERS, ManualOption.XHTTP_PADDING)
        ManualTransport.UPGRADE -> listOf(ManualOption.HOST, ManualOption.PATH, ManualOption.HEADERS, ManualOption.EARLY)
        ManualTransport.KCP -> listOf(ManualOption.KCP_MTU, ManualOption.KCP_TTI, ManualOption.KCP_UP, ManualOption.KCP_DOWN)
        ManualTransport.HYSTERIA -> listOf(ManualOption.HYSTERIA_OBFS, ManualOption.HYSTERIA_IDLE, ManualOption.HYSTERIA_CC) +
            if (value(ManualOption.HYSTERIA_CC) in listOf("", "brutal", "force-brutal")) {
                listOf(ManualOption.HYSTERIA_UP, ManualOption.HYSTERIA_DOWN)
            } else emptyList()
    }

    val protectionOptions get() = when (protection) {
        ManualProtection.NONE -> emptyList()
        ManualProtection.TLS -> listOf(ManualOption.SNI, ManualOption.ALPN) +
            listOfNotNull(ManualOption.FINGERPRINT.takeUnless { protocol == ManualProtocol.HYSTERIA }) +
            listOf(ManualOption.TLS_PIN, ManualOption.ECH)
        ManualProtection.REALITY -> listOf(
            ManualOption.SNI, ManualOption.FINGERPRINT, ManualOption.REALITY_KEY, ManualOption.SHORT_ID, ManualOption.SPIDER,
        )
    }

    val allowsMux get() = protocol in listOf(ManualProtocol.VLESS, ManualProtocol.VMESS, ManualProtocol.TROJAN) &&
        (!supportsVision || value(ManualOption.FLOW).isEmpty())

    private val activeOptions get() = protocolOptions + transportOptions + protectionOptions +
        listOfNotNull(ManualOption.MUX.takeIf { allowsMux })

    fun value(option: ManualOption): String = options[option] ?: option.fallback

    fun withOption(option: ManualOption, value: String) = copy(options = options + (option to value))

    fun reference(): String {
        val values = JSONObject()
        activeOptions.forEach { option ->
            val value = value(option).trim()
            if (value != option.fallback) values.put(option.name, value)
        }
        return json(
            "manualVersion" to 1, "protocol" to protocol.name, "address" to address.trim(), "port" to port.trim(),
            "secret" to secret, "transport" to transport.name, "protection" to protection.name, "options" to values,
        ).toString()
    }

    fun configuration(): String {
        requireField(transport in protocol.transports && protection in protections, R.string.source_manual_bad_transport)
        activeOptions.forEach { option ->
            val value = value(option)
            requireField(value.length <= option.limit && value.none(Char::isISOControl), R.string.source_manual_check_field, option)
            if (option.choices.isNotEmpty()) requireField(value in option.choices, R.string.source_manual_choose_field, option)
        }
        val host = normalizedHost(address)
        val number = port.trim().toIntOrNull()
        requireField(number != null && number in 1..65535, R.string.source_manual_port_range)
        val credential = if (protocol.usesUuid) secret.trim().lowercase() else secret
        requireField(
            credential.isNotBlank() && credential.length <= 512 && credential.none(Char::isISOControl),
            if (protocol.usesUuid) R.string.source_manual_enter_uuid else R.string.source_manual_enter_password,
        )
        if (protocol.usesUuid) requireField(
            runCatching { UUID.fromString(credential).toString() == credential }.getOrDefault(false),
            R.string.source_manual_uuid_format,
        )
        val settings = json("address" to host, "port" to number)
        when (protocol) {
            ManualProtocol.VLESS -> settings.put("id", credential).put("encryption", text(ManualOption.ENCRYPTION).ifBlank { "none" }).apply {
                if (supportsVision) putText("flow", ManualOption.FLOW)
            }
            ManualProtocol.VMESS -> settings.put("id", credential).put("security", value(ManualOption.VMESS_CIPHER))
            ManualProtocol.TROJAN -> settings.put("password", credential)
            ManualProtocol.SHADOWSOCKS -> settings.put("method", value(ManualOption.SS_CIPHER)).put("password", credential)
            ManualProtocol.HYSTERIA -> settings.put("version", 2)
        }

        val stream = json("network" to transport.key, "security" to protection.key)
        buildTransport(stream, credential)
        buildProtection(stream)
        val outbound = json("protocol" to protocol.key, "settings" to settings, "streamSettings" to stream)
        if (allowsMux) optionalNumber(ManualOption.MUX, 1..128)?.let {
            outbound.put("mux", json("enabled" to true, "concurrency" to it))
        }
        return json("remarks" to "$host:$number", "outbounds" to JSONArray(listOf(outbound))).toString()
    }

    private fun buildTransport(stream: JSONObject, credential: String) {
        when (transport) {
            ManualTransport.RAW -> Unit
            ManualTransport.WS, ManualTransport.UPGRADE, ManualTransport.XHTTP -> {
                var path = text(ManualOption.PATH).ifBlank { "/" }
                val pathUri = runCatching { URI.create(path) }.getOrNull()
                requireField(
                    path.startsWith('/') && !path.startsWith("//") && pathUri != null && pathUri.rawFragment == null,
                    R.string.source_manual_path_format,
                )
                if (transport != ManualTransport.XHTTP) optionalNumber(ManualOption.EARLY, 0..65535)?.let {
                    requireField(
                        pathUri.rawQuery.orEmpty().split('&').none { item -> item.substringBefore('=') == "ed" },
                        R.string.source_manual_duplicate_early,
                    )
                    path += if ('?' in path) "&ed=$it" else "?ed=$it"
                }
                val config = json("path" to path).apply { putText("host", ManualOption.HOST) }
                headers()?.let { config.put("headers", it) }
                when (transport) {
                    ManualTransport.WS -> {
                        optionalNumber(ManualOption.HEARTBEAT, 0..3600)?.let { config.put("heartbeatPeriod", it) }
                        stream.put("wsSettings", config)
                    }
                    ManualTransport.UPGRADE -> stream.put("httpupgradeSettings", config)
                    else -> {
                        config.put("mode", value(ManualOption.XHTTP_MODE))
                        val padding = text(ManualOption.XHTTP_PADDING)
                        if (padding.isNotEmpty()) {
                            requireField(
                                Regex("[0-9]{1,6}(-[0-9]{1,6})?").matches(padding),
                                R.string.source_manual_xhttp_padding_format,
                            )
                            config.put("xPaddingBytes", padding)
                        }
                        stream.put("xhttpSettings", config)
                    }
                }
            }
            ManualTransport.GRPC -> stream.put("grpcSettings", json("multiMode" to (value(ManualOption.MULTI) == "true")).apply {
                putText("serviceName", ManualOption.SERVICE)
                putText("authority", ManualOption.AUTHORITY)
            })
            ManualTransport.KCP -> stream.put("kcpSettings", JSONObject().apply {
                optionalNumber(ManualOption.KCP_MTU, 576..1460)?.let { put("mtu", it) }
                optionalNumber(ManualOption.KCP_TTI, 10..100)?.let { put("tti", it) }
                optionalNumber(ManualOption.KCP_UP, 0..100_000)?.let { put("uplinkCapacity", it) }
                optionalNumber(ManualOption.KCP_DOWN, 0..100_000)?.let { put("downlinkCapacity", it) }
            })
            ManualTransport.HYSTERIA -> {
                stream.put("hysteriaSettings", json("version" to 2, "auth" to credential).apply {
                    optionalNumber(ManualOption.HYSTERIA_IDLE, 2..600)?.let { put("udpIdleTimeout", it) }
                })
                val mask = JSONObject()
                if (text(ManualOption.HYSTERIA_OBFS).isNotEmpty()) {
                    mask.put("udp", JSONArray(listOf(json("type" to "salamander",
                        "settings" to json("password" to value(ManualOption.HYSTERIA_OBFS))))))
                }
                val quic = JSONObject().apply { putText("congestion", ManualOption.HYSTERIA_CC) }
                if (ManualOption.HYSTERIA_UP in transportOptions) {
                    val up = optionalNumber(ManualOption.HYSTERIA_UP, 1..100_000)
                    if (text(ManualOption.HYSTERIA_CC) == "force-brutal") requireField(up != null, R.string.source_manual_brutal_uplink)
                    up?.let { quic.put("brutalUp", "$it mbps") }
                    optionalNumber(ManualOption.HYSTERIA_DOWN, 1..100_000)?.let { quic.put("brutalDown", "$it mbps") }
                }
                if (quic.length() > 0) mask.put("quicParams", quic)
                if (mask.length() > 0) stream.put("finalmask", mask)
            }
        }
    }

    private fun buildProtection(stream: JSONObject) {
        if (protection == ManualProtection.NONE) return
        val sni = text(ManualOption.SNI).takeIf(String::isNotEmpty)?.let(::normalizedHost)
        if (sni != null) requireField(':' !in sni, R.string.source_manual_sni_domain)
        val config = JSONObject()
        sni?.let { config.put("serverName", it) }
        if (ManualOption.FINGERPRINT in protectionOptions) config.putText("fingerprint", ManualOption.FINGERPRINT)
        if (protection == ManualProtection.TLS) {
            val alpn = text(ManualOption.ALPN).split(',').map(String::trim).filter(String::isNotEmpty)
            requireField(
                alpn.all { protocol -> protocol.length <= 32 && protocol.none(Char::isWhitespace) },
                R.string.source_manual_alpn_format,
            )
            if (alpn.isNotEmpty()) config.put("alpn", JSONArray(alpn))
            val pins = text(ManualOption.TLS_PIN).split(',').map(String::trim).filter(String::isNotEmpty)
            requireField(pins.all { Regex("[0-9a-fA-F]{64}").matches(it.replace(":", "")) }, R.string.source_manual_pin_format)
            if (pins.isNotEmpty()) config.put("pinnedPeerCertSha256", pins.joinToString(","))
            config.putText("echConfigList", ManualOption.ECH)
            stream.put("tlsSettings", config)
        } else {
            requireField(sni != null, R.string.source_manual_reality_sni)
            val key = text(ManualOption.REALITY_KEY)
            requireField(
                runCatching { Base64.getUrlDecoder().decode(key).size == 32 }.getOrDefault(false),
                R.string.source_manual_reality_key_format,
            )
            val shortId = text(ManualOption.SHORT_ID)
            requireField(Regex("([0-9a-fA-F]{2}){0,8}").matches(shortId), R.string.source_manual_short_id_format)
            config.put("fingerprint", text(ManualOption.FINGERPRINT).ifBlank { "chrome" })
                .put("password", key.trimEnd('=')).put("shortId", shortId)
            config.putText("spiderX", ManualOption.SPIDER)
            stream.put("realitySettings", config)
        }
    }

    private fun headers(): JSONObject? {
        val raw = text(ManualOption.HEADERS)
        if (raw.isEmpty()) return null
        val headers = runCatching { JSONObject(raw) }.getOrNull()
        requireField(
            headers != null && headers.length() <= 20 && headers.keys().asSequence().all { key ->
                Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}").matches(key) && !key.equals("host", true) &&
                    (headers.opt(key) as? String)?.let { it.length <= 512 && it.none(Char::isISOControl) } == true
            },
            R.string.source_manual_headers_format,
        )
        return headers
    }

    private fun text(option: ManualOption) = value(option).trim()

    private fun JSONObject.putText(key: String, option: ManualOption) {
        text(option).takeIf(String::isNotEmpty)?.let { put(key, it) }
    }

    private fun optionalNumber(option: ManualOption, range: IntRange): Int? {
        val value = text(option)
        if (value.isEmpty()) return null
        val number = value.toIntOrNull()
        requireField(number != null && number in range, R.string.source_manual_number_range, option, range.first, range.last)
        return number
    }

    companion object {
        fun read(reference: String): ManualProfile? {
            if (!reference.startsWith('{')) return null
            return runCatching {
                val data = JSONObject(reference)
                require(data.getInt("manualVersion") == 1)
                val options = data.getJSONObject("options")
                ManualProfile(
                    ManualProtocol.valueOf(data.getString("protocol")), data.getString("address"), data.getString("port"),
                    data.getString("secret"), ManualTransport.valueOf(data.getString("transport")),
                    ManualProtection.valueOf(data.getString("protection")),
                    ManualOption.entries.filter { options.has(it.name) }.associateWith { options.getString(it.name) },
                )
            }.getOrNull()
        }
    }
}

private fun normalizedHost(address: String): String {
    val raw = address.trim()
    requireField(raw.isNotEmpty() && raw.length <= 253 && raw.none { it.isWhitespace() || it in "/@?#%" }, R.string.source_manual_host_format)
    val formatted = runCatching {
        when {
            raw.startsWith("[") -> raw
            ':' in raw -> "[$raw]"
            else -> IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES)
        }
    }.getOrNull()
    val uri = formatted?.let { runCatching { URI.create("https://$it") }.getOrNull() }
    requireField(uri?.host != null && uri.port == -1 && uri.rawPath.isEmpty(), R.string.source_manual_host_format)
    return uri.host.removeSurrounding("[", "]")
}

internal class ManualFailure(private val resource: Int, private vararg val args: Any) : IllegalArgumentException() {
    fun message(context: Context): String {
        val values = args.map { if (it is ManualOption) context.getString(it.titleRes) else it }
        return context.getString(resource, *values.toTypedArray())
    }
}

@OptIn(ExperimentalContracts::class)
private fun requireField(condition: Boolean, resource: Int, vararg args: Any) {
    contract { returns() implies condition }
    if (!condition) throw ManualFailure(resource, *args)
}
