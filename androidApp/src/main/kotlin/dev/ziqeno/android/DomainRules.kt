package dev.ziqeno.android

import android.content.Context
import com.google.re2j.Pattern
import dev.ziqeno.routing.RouteSelection
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

internal enum class DomainMatch { EXACT, SUFFIX, WILDCARD, KEYWORD, REGEX, MATCH }
internal enum class DomainExit { DIRECT, AUTO, NODE, REJECT }

internal data class DomainRule(
    val id: String,
    val kind: DomainMatch,
    val pattern: String,
    val exit: DomainExit,
    val nodeId: String? = null,
    val testHost: String? = null,
) {
    val scope by lazy { "domain-${routeDigest(id)}" }
    fun title(context: Context) = if (kind == DomainMatch.MATCH) context.getString(R.string.routing_all_connections) else pattern

    val xrayPattern: String? get() = when (kind) {
        DomainMatch.EXACT -> "full:$pattern"
        DomainMatch.SUFFIX -> "domain:$pattern"
        DomainMatch.WILDCARD -> "regexp:${wildcardRegex(pattern)}"
        DomainMatch.KEYWORD -> "keyword:$pattern"
        DomainMatch.REGEX -> "regexp:$pattern"
        DomainMatch.MATCH -> null
    }

    private val matcher by lazy {
        when (kind) {
            DomainMatch.WILDCARD -> Pattern.compile(wildcardRegex(pattern))
            DomainMatch.REGEX -> Pattern.compile(pattern)
            else -> null
        }
    }

    fun matches(host: String): Boolean = when (kind) {
        DomainMatch.EXACT -> host == pattern
        DomainMatch.SUFFIX -> host == pattern || host.endsWith(".$pattern")
        DomainMatch.WILDCARD, DomainMatch.REGEX -> checkNotNull(matcher).matcher(host).find()
        DomainMatch.KEYWORD -> pattern in host
        DomainMatch.MATCH -> true
    }

    fun outbound(available: Set<String>): String? = when (exit) {
        DomainExit.DIRECT -> XrayEngine.direct
        DomainExit.AUTO -> null
        DomainExit.NODE -> nodeId?.takeIf { it in available } ?: XrayEngine.blocked
        DomainExit.REJECT -> XrayEngine.blocked
    }

}

internal data class DomainRules(val enabled: Boolean = false, val rules: List<DomainRule> = emptyList()) {
    val active get() = if (enabled) rules else emptyList()
    val builtInServices get() = if (active.isEmpty()) RouteSelection.services else emptyList()
    val tested by lazy {
        active.filter { rule -> rule.testHost?.let { host -> active.firstOrNull { it.matches(host) }?.id == rule.id } == true }
    }

    // This is passed as data through a private routing balancer, never evaluated as Lua.
    fun livePayload(available: Set<String>, selected: (DomainRule) -> String? = { null }): String = buildString {
        append("v1\n")
        for (rule in active) {
            val node = rule.outbound(available)
                ?: selected(rule).takeIf { rule in tested && it in available }
                ?: XrayEngine.defaultRoute
            append(node).append('\t').append(rule.xrayPattern.orEmpty()).append('\n')
        }
    }
}

internal data class DomainCheck(val server: String?, val latencyMs: Double?, val tested: Boolean, val skipped: Boolean = false)

internal class DomainValidationException(val messageRes: Int) : IllegalArgumentException()

private fun requireDomain(condition: Boolean, messageRes: Int) {
    if (!condition) throw DomainValidationException(messageRes)
}

internal fun DomainRule.normalized(): DomainRule {
    requireDomain(id.matches(Regex("[A-Za-z0-9-]{1,64}")), R.string.domain_invalid_id)
    val value = pattern.trim()
    val normalized = when (kind) {
        DomainMatch.EXACT, DomainMatch.SUFFIX -> normalizeDomain(value)
        DomainMatch.WILDCARD -> value.lowercase().also {
            requireDomain(
                it.matches(Regex("[a-z0-9*?.-]{1,253}")) && it.any { c -> c == '*' || c == '?' },
                R.string.domain_invalid_wildcard,
            )
        }
        DomainMatch.KEYWORD -> value.lowercase().also {
            requireDomain(it.matches(Regex("[a-z0-9.-]{1,253}")), R.string.domain_invalid_keyword)
        }
        DomainMatch.REGEX -> value.also {
            requireDomain(it.length in 1..256 && it.all { char -> char in ' '..'~' }, R.string.domain_invalid_regex_length)
            requireDomain(runCatching { Pattern.compile(it) }.isSuccess, R.string.domain_invalid_regex_syntax)
        }
        DomainMatch.MATCH -> ""
    }
    val target = nodeId.takeIf { exit == DomainExit.NODE }
    requireDomain(exit != DomainExit.NODE || target?.matches(Regex("node-[0-9a-f]{32}")) == true, R.string.domain_vpn_server_required)
    val host = testHost?.takeIf { exit != DomainExit.REJECT }?.let(::normalizeDomain)
    val result = copy(pattern = normalized, nodeId = target, testHost = host)
    requireDomain(host == null || result.matches(host), R.string.domain_invalid_test_host)
    return result
}

private fun normalizeDomain(input: String): String {
    val domain = runCatching { IDN.toASCII(input.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull()
    if (domain == null || domain.length !in 3..253 || '.' !in domain ||
        domain.substringAfterLast('.').none { it in 'a'..'z' } ||
        domain.split('.').any { it.length !in 1..63 }
    ) throw DomainValidationException(R.string.domain_invalid_hostname)
    return domain
}

private fun wildcardRegex(pattern: String): String = buildString {
    append('^')
    for (char in pattern) append(when (char) {
        '*' -> ".*"
        '?' -> "."
        '.' -> "\\."
        else -> char.toString()
    })
    append('$')
}

internal class DomainRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("domain-rules", Context.MODE_PRIVATE)

    fun load(): DomainRules {
        val data = runCatching { JSONObject(prefs.getString("rules", null) ?: "{}") }.getOrElse { JSONObject() }
        val items = data.optJSONArray("items") ?: JSONArray()
        val rules = (0 until minOf(items.length(), maxRules)).mapNotNull { index ->
            runCatching {
                val item = items.getJSONObject(index)
                DomainRule(
                    item.getString("id"), DomainMatch.valueOf(item.getString("kind")), item.getString("pattern"),
                    DomainExit.valueOf(item.getString("exit")),
                    item.optString("node").takeUnless { item.isNull("node") },
                    item.optString("test").takeUnless { item.isNull("test") },
                ).normalized()
            }.getOrNull()
        }.distinctBy(DomainRule::id)
        return DomainRules(data.optBoolean("enabled"), rules)
    }

    fun save(config: DomainRules) {
        require(config.rules.size <= maxRules)
        require(config.rules.map(DomainRule::id).distinct().size == config.rules.size)
        val items = config.rules.map { raw ->
            val rule = raw.normalized()
            json("id" to rule.id, "kind" to rule.kind.name, "pattern" to rule.pattern, "exit" to rule.exit.name)
                .apply {
                    put("node", rule.nodeId ?: JSONObject.NULL)
                    put("test", rule.testHost ?: JSONObject.NULL)
                }
        }
        prefs.edit().putString("rules", json("enabled" to config.enabled, "items" to JSONArray(items)).toString()).apply()
    }

    fun observe(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun remove(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val maxRules = 32
    }
}
