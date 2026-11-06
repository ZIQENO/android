package dev.ziqeno.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Base64
import java.util.UUID

internal enum class ManualProtocol(val title: String, val key: String, val credential: String, val defaultPort: String) {
    VLESS("VLESS", "vless", "UUID", "443"),
    VMESS("VMess", "vmess", "UUID", "443"),
    TROJAN("Trojan", "trojan", "Пароль", "443"),
    SHADOWSOCKS("Shadowsocks", "shadowsocks", "Пароль", "8388"),
    HYSTERIA("Hysteria 2", "hysteria", "Пароль", "443");

    val usesUuid get() = this == VLESS || this == VMESS
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

internal enum class ManualProtection(val title: String, val key: String) {
    NONE("Без TLS", "none"), TLS("TLS", "tls"), REALITY("REALITY", "reality")
}

internal enum class ManualOption(
    val title: String, val limit: Int = 255, val choices: List<String> = emptyList(), val fallback: String = "",
) {
    ENCRYPTION("VLESS Encryption", 4096, fallback = "none"),
    FLOW("Flow", choices = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")),
    VMESS_CIPHER("Шифрование VMess", choices = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero"), fallback = "auto"),
    SS_CIPHER("Шифр Shadowsocks", choices = listOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    ), fallback = "aes-256-gcm"),
    HOST("Host"),
    PATH("Path", 1024),
    HEADERS("HTTP-заголовки (JSON)", 2048),
    EARLY("Ранние данные, байт", 5),
    HEARTBEAT("WebSocket heartbeat, с", 4),
    SERVICE("Service name", 512),
    AUTHORITY("Authority"),
    MULTI("gRPC Multi mode", choices = listOf("false", "true"), fallback = "false"),
    XHTTP_MODE("Режим XHTTP", choices = listOf("auto", "packet-up", "stream-up", "stream-one"), fallback = "auto"),
    XHTTP_PADDING("XHTTP padding, байт или диапазон", 16),
    KCP_MTU("MTU", 5),
    KCP_TTI("TTI, мс", 4),
    KCP_UP("Uplink capacity", 6),
    KCP_DOWN("Downlink capacity", 6),
    SNI("SNI"),
    ALPN("ALPN через запятую"),
    FINGERPRINT("Fingerprint", choices = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized")),
    TLS_PIN("SHA-256 сертификата", 512),
    ECH("ECH Config List (Base64)", 3072),
    REALITY_KEY("Публичный ключ REALITY"),
    SHORT_ID("Short ID", 16),
    SPIDER("SpiderX", 512),
    HYSTERIA_OBFS("Пароль Salamander", 512),
    HYSTERIA_IDLE("Таймаут UDP, с", 3),
    HYSTERIA_CC("Контроль перегрузки", choices = listOf("", "bbr", "reno", "brutal", "force-brutal")),
    HYSTERIA_UP("Отдача Brutal, Мбит/с", 6),
    HYSTERIA_DOWN("Загрузка Brutal, Мбит/с", 6),
    MUX("Параллельные потоки Mux", 3),
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
        require(transport in protocol.transports && protection in protections) { "Транспорт и защита несовместимы." }
        activeOptions.forEach { option ->
            val value = value(option)
            require(value.length <= option.limit && value.none(Char::isISOControl)) { "Проверьте поле «${option.title}»." }
            if (option.choices.isNotEmpty()) require(value in option.choices) { "Выберите значение поля «${option.title}»." }
        }
        val host = normalizedHost(address)
        val number = port.trim().toIntOrNull()
        require(number != null && number in 1..65535) { "Укажите порт от 1 до 65535." }
        val credential = if (protocol.usesUuid) secret.trim().lowercase() else secret
        require(credential.isNotBlank() && credential.length <= 512 && credential.none(Char::isISOControl)) {
            "Введите ${protocol.credential.lowercase()}."
        }
        if (protocol.usesUuid) require(runCatching { UUID.fromString(credential).toString() == credential }.getOrDefault(false)) {
            "Введите UUID в стандартном формате."
        }
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
                require(path.startsWith('/') && !path.startsWith("//") && pathUri != null && pathUri.rawFragment == null) {
                    "Path должен быть относительным путём, начинающимся с /."
                }
                if (transport != ManualTransport.XHTTP) optionalNumber(ManualOption.EARLY, 0..65535)?.let {
                    require(pathUri.rawQuery.orEmpty().split('&').none { item -> item.substringBefore('=') == "ed" }) {
                        "Укажите ранние данные в отдельном поле или в Path."
                    }
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
                            require(Regex("[0-9]{1,6}(-[0-9]{1,6})?").matches(padding)) { "Укажите число или диапазон XHTTP padding." }
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
                    if (text(ManualOption.HYSTERIA_CC) == "force-brutal") require(up != null) {
                        "Для force-brutal укажите скорость отдачи."
                    }
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
        if (sni != null) require(':' !in sni) { "Укажите доменное имя в поле SNI." }
        val config = JSONObject()
        sni?.let { config.put("serverName", it) }
        if (ManualOption.FINGERPRINT in protectionOptions) config.putText("fingerprint", ManualOption.FINGERPRINT)
        if (protection == ManualProtection.TLS) {
            val alpn = text(ManualOption.ALPN).split(',').map(String::trim).filter(String::isNotEmpty)
            require(alpn.all { protocol -> protocol.length <= 32 && protocol.none(Char::isWhitespace) }) { "Проверьте список ALPN." }
            if (alpn.isNotEmpty()) config.put("alpn", JSONArray(alpn))
            val pins = text(ManualOption.TLS_PIN).split(',').map(String::trim).filter(String::isNotEmpty)
            require(pins.all { Regex("[0-9a-fA-F]{64}").matches(it.replace(":", "")) }) {
                "SHA-256 сертификата должен содержать 64 шестнадцатеричных символа."
            }
            if (pins.isNotEmpty()) config.put("pinnedPeerCertSha256", pins.joinToString(","))
            config.putText("echConfigList", ManualOption.ECH)
            stream.put("tlsSettings", config)
        } else {
            require(sni != null) { "Укажите SNI для REALITY." }
            val key = text(ManualOption.REALITY_KEY)
            require(runCatching { Base64.getUrlDecoder().decode(key).size == 32 }.getOrDefault(false)) {
                "Укажите публичный ключ REALITY в формате Base64URL."
            }
            val shortId = text(ManualOption.SHORT_ID)
            require(Regex("([0-9a-fA-F]{2}){0,8}").matches(shortId)) { "Short ID должен содержать до 16 символов hex парами." }
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
        require(headers != null && headers.length() <= 20 && headers.keys().asSequence().all { key ->
            Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,80}").matches(key) && !key.equals("host", true) &&
                (headers.opt(key) as? String)?.let { it.length <= 512 && it.none(Char::isISOControl) } == true
        }) { "Укажите HTTP-заголовки как JSON-объект со строковыми значениями. Host вводится отдельно." }
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
        require(number != null && number in range) { "Поле «${option.title}» принимает числа от ${range.first} до ${range.last}." }
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
    require(raw.isNotEmpty() && raw.length <= 253 && raw.none { it.isWhitespace() || it in "/@?#%" }) {
        "Укажите домен или IP сервера."
    }
    val formatted = runCatching {
        when {
            raw.startsWith("[") -> raw
            ':' in raw -> "[$raw]"
            else -> IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES)
        }
    }.getOrNull()
    val uri = formatted?.let { runCatching { URI.create("https://$it") }.getOrNull() }
    require(uri?.host != null && uri.port == -1 && uri.rawPath.isEmpty()) { "Укажите домен или IP сервера." }
    return uri.host.removeSurrounding("[", "]")
}
