package dev.ziqeno.android

import java.net.URI
import java.security.MessageDigest

internal enum class SourceKind { SUBSCRIPTION, MANUAL }

internal const val maxReferenceLength = 8192
private val serverSchemes = setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2")

internal fun sourceKind(input: String): SourceKind? {
    val text = input.trim()
    if (text.length !in 1..maxReferenceLength || text.any(Char::isISOControl)) return null
    val scheme = text.substringBefore("://", "").lowercase()
    return when {
        scheme == "https" && runCatching { subscriptionOrigin(text) }.isSuccess -> SourceKind.SUBSCRIPTION
        scheme in serverSchemes && text.substringAfter("://").isNotBlank() -> SourceKind.MANUAL
        else -> null
    }
}

internal fun subscriptionUri(input: String): URI {
    require(input.length in 1..maxReferenceLength && input.none(Char::isISOControl))
    val uri = URI.create(input.substringBefore('#'))
    require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null)
    require(uri.port == -1 || uri.port in 1..65535)
    return uri
}

internal fun subscriptionOrigin(input: String): String {
    val uri = subscriptionUri(input)
    return "https://${uri.host.lowercase()}:${if (uri.port == -1) 443 else uri.port}"
}

internal fun deviceIdFor(seed: String, origin: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("ZIQENO/hwid/1\u0000$seed\u0000$origin".encodeToByteArray())
    return "and-" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
}
