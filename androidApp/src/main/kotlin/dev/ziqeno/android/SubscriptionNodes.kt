package dev.ziqeno.android

import dev.ziqeno.routing.SubscriptionLinks
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

internal data class XrayNode(val id: String, val name: String, val outbounds: List<JSONObject>)

internal object SubscriptionNodes {
    const val defaultLimit = 250
    private val protocols = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")
    private val socketOptions = listOf(
        "tcpKeepAliveIdle", "tcpKeepAliveInterval", "tcpUserTimeout", "tcpFastOpen", "tcpNoDelay",
        "domainStrategy", "dialerProxy",
    )

    fun read(body: String, limit: Int = defaultLimit, convertLinks: (String) -> JSONArray): List<XrayNode> {
        val text = body.trim().trimStart('\uFEFF')
        val groups = when {
            text.startsWith("{") -> listOf(JSONTokener(text).nextValue() as JSONObject)
            text.startsWith("[") -> {
                val array = JSONTokener(text).nextValue() as JSONArray
                (0 until array.length()).mapNotNull(array::optJSONObject)
            }
            else -> {
                val links = SubscriptionLinks.read(text, limit)
                require(links.isNotEmpty())
                return links.asSequence().chunked(defaultLimit).flatMap { batch ->
                    val converted = try {
                        convertLinks(batch.joinToString("\n"))
                    } catch (_: IllegalStateException) {
                        // libXray rejects a batch when none of its links can be converted.
                        return@flatMap emptySequence()
                    }
                    (0 until converted.length()).asSequence().mapNotNull { index ->
                        val node = converted.optJSONObject(index) ?: return@mapNotNull null
                        readNode(node, node.optString("tag"), emptyMap())
                    }
                }.distinctBy(XrayNode::id).take(limit).toList().also { check(it.isNotEmpty()) }
            }
        }
        return groups.asSequence().flatMap { config ->
            val outbounds = config.optJSONArray("outbounds") ?: return@flatMap emptySequence()
            val entries = (0 until outbounds.length()).mapNotNull(outbounds::optJSONObject)
            val references = entries.filter { it.optString("tag").isNotEmpty() }.associateBy { it.getString("tag") }
            val label = config.optString("remarks")
            entries.asSequence().mapNotNull { raw ->
                readNode(raw, label.ifBlank { raw.optString("tag") }, references)
            }
        }.distinctBy(XrayNode::id).take(limit).toList().also { check(it.isNotEmpty()) }
    }

    private fun readNode(raw: JSONObject, label: String, references: Map<String, JSONObject>): XrayNode? {
        if (raw.optString("protocol") !in protocols) return null
        return runCatching {
            val chain = mutableListOf<JSONObject>()
            val primary = project(raw, references, chain, setOf(raw.optString("tag")), primary = true)
            val id = primary.getString("tag")
            val name = label.filterNot(Char::isISOControl).take(64).ifBlank { "Узел ${id.takeLast(6)}" }
            XrayNode(id, name, listOf(primary) + chain)
        }.getOrNull()
    }

    private fun project(
        raw: JSONObject,
        references: Map<String, JSONObject>,
        chain: MutableList<JSONObject>,
        seen: Set<String>,
        primary: Boolean,
    ): JSONObject {
        val protocol = raw.getString("protocol")
        require(seen.size <= 4 && (protocol in protocols || !primary && protocol == "freedom"))
        require(!raw.has("proxySettings"))
        val projected = json("protocol" to protocol)
        raw.optJSONObject("settings")?.let { settings ->
            projected.put("settings", if (protocol == "freedom") pick(settings, "fragment", "noises") else settings)
        }
        raw.optJSONObject("mux")?.let { projected.put("mux", it) }
        raw.optJSONObject("streamSettings")?.let { settings ->
            val stream = JSONObject(settings.toString())
            for (key in listOf("tlsSettings", "realitySettings")) {
                stream.optJSONObject(key)?.let { security ->
                    security.remove("certificates")
                    security.remove("masterKeyLog")
                    security.remove("keyLog")
                }
            }
            stream.optJSONObject("sockopt")?.let { options ->
                val sockopt = pick(options, *socketOptions.toTypedArray())
                val ref = sockopt.optString("dialerProxy")
                if (ref.isNotEmpty()) {
                    require(ref !in seen)
                    val dependency = project(requireNotNull(references[ref]), references, chain, seen + ref, primary = false)
                    chain += dependency
                    sockopt.put("dialerProxy", dependency.getString("tag"))
                }
                stream.put("sockopt", sockopt)
            }
            projected.put("streamSettings", stream)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical(projected).encodeToByteArray())
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return projected.put("tag", if (primary) "node-$hash" else "dep-$hash")
    }

    private fun pick(source: JSONObject, vararg keys: String) = JSONObject().apply {
        keys.forEach { key -> if (source.has(key)) put(key, source.get(key)) }
    }

    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}

