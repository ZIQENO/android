package dev.ziqeno.routing

import kotlin.io.encoding.Base64

object SubscriptionLinks {
    private val schemes = setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2")

    fun read(text: String): List<String> = read(text, 250)

    fun read(text: String, limit: Int): List<String> {
        val plain = links(text, limit)
        if (plain.isNotEmpty()) return plain
        val compact = text.trim().trimStart('\uFEFF').filterNot(Char::isWhitespace)
        val padded = compact.padEnd((compact.length + 3) / 4 * 4, '=')
        val encoding = if ('-' in compact || '_' in compact) Base64.UrlSafe else Base64.Default
        val decoded = runCatching { encoding.decode(padded).decodeToString(throwOnInvalidSequence = true) }
            .getOrNull() ?: return emptyList()
        return links(decoded, limit)
    }

    private fun links(text: String, limit: Int): List<String> = text.lineSequence()
        .map { it.trim().trimStart('\uFEFF') }
        .filter { "://" in it && it.substringBefore("://").lowercase() in schemes }
        .distinct()
        .take(limit)
        .toList()
}
