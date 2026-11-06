package dev.ziqeno.android

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

internal fun readBasicServerLink(reference: String): ManualProfile? = runCatching {
    val uri = URI.create(reference)
    if (uri.scheme == "vmess") return@runCatching readBasicVmess(uri.rawSchemeSpecificPart)
    val address = uri.host ?: return null
    require(uri.port in 1..65535 && uri.rawPath.isEmpty() && uri.fragment != null)
    val parameters = uri.rawQuery?.split('&')?.associate { item ->
        decodeLinkValue(item.substringBefore('=')) to decodeLinkValue(item.substringAfter('=', ""))
    }.orEmpty()
    if (uri.rawQuery != null) require(parameters.size == uri.rawQuery.split('&').size)
    val password = decodeLinkValue(requireNotNull(uri.rawUserInfo))
    val options = parameters["sni"]?.takeIf { it.isNotEmpty() }?.let { mapOf(ManualOption.SNI to it) }.orEmpty()
    val port = uri.port.toString()
    when (uri.scheme) {
        "vless" -> {
            require(parameters.keys.all { it in setOf("type", "encryption", "security", "sni") } &&
                parameters["type"] == "tcp" && parameters["encryption"] == "none")
            val protection = when (parameters["security"]) {
                "tls" -> ManualProtection.TLS
                "none" -> ManualProtection.NONE
                else -> return null
            }
            ManualProfile(ManualProtocol.VLESS, address, port, password, protection = protection, options = options)
        }
        "trojan" -> {
            require(parameters.keys.all { it in setOf("security", "sni") } && parameters["security"] == "tls")
            ManualProfile(ManualProtocol.TROJAN, address, port, password, options = options)
        }
        "ss" -> {
            require(parameters.isEmpty())
            val auth = Base64.getUrlDecoder().decode(password).decodeToString(throwOnInvalidSequence = true)
            val method = auth.substringBefore(':')
            require(':' in auth && method in ManualOption.SS_CIPHER.choices)
            ManualProfile(ManualProtocol.SHADOWSOCKS, address, port, auth.substringAfter(':'))
                .withOption(ManualOption.SS_CIPHER, method)
        }
        "hysteria2" -> {
            require(parameters.keys.all { it == "sni" })
            ManualProfile(ManualProtocol.HYSTERIA, address, port, password, options = options)
        }
        else -> null
    }
}.getOrNull()

private fun readBasicVmess(encoded: String): ManualProfile {
    val data = JSONObject(Base64.getDecoder().decode(encoded.removePrefix("//")).decodeToString(throwOnInvalidSequence = true))
    val keys = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "tls", "sni")
    require(data.keys().asSequence().all { it in keys } && data.getString("v") == "2" &&
        data.getString("aid") == "0" && data.getString("net") == "tcp" && data.getString("type") == "none")
    val cipher = data.getString("scy")
    require(cipher in ManualOption.VMESS_CIPHER.choices)
    val protection = when (data.optString("tls")) {
        "tls" -> ManualProtection.TLS
        "" -> ManualProtection.NONE
        else -> error("Unsupported VMess protection")
    }
    return ManualProfile(ManualProtocol.VMESS, data.getString("add"), data.getString("port"), data.getString("id"),
        protection = protection, options = mapOf(ManualOption.VMESS_CIPHER to cipher, ManualOption.SNI to data.optString("sni")))
}

private fun decodeLinkValue(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
