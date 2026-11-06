package dev.ziqeno.android

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.content.pm.PackageManager
import dev.ziqeno.routing.RouteSelection
import java.io.File

internal class XrayAppObserver(
    directory: File, nodes: List<XrayNode>, apps: List<TrackedApp>, targets: AppTargets, packages: PackageManager,
    domains: DomainRules,
) : AutoCloseable {
    private val script = File.createTempFile("app-routes-", ".lua", directory)
    private val pipe = ParcelFileDescriptor.createPipe()
    val scriptPath get() = script.path
    val eventPath get() = "/proc/self/fd/${pipe[1].fd}"

    init {
        try {
            script.writeText(routeScript(nodes, apps, domains))
            val prefix = "[Warning] ${script.name}: ziqeno-app-target "
            val owners = apps.filter { it.mode == AppRouteMode.AUTO }.associate { it.scope to it.app }
            Thread({
                runCatching {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val fields = line.substringAfter(prefix, "").split(' ', limit = 4)
                            if (fields.size != 4) continue
                            val owner = owners[fields[0]] ?: continue
                            val port = fields[3].toIntOrNull() ?: continue
                            if (packages.getPackagesForUid(owner.uid)?.singleOrNull() == owner.packageName) {
                                targets.observe(fields[0], fields[1].takeUnless { it == "-" }.orEmpty(), fields[2],
                                    SystemClock.elapsedRealtime(), port)
                            }
                        }
                    }
                }
            }, "ZiqenoAppTargets").apply { isDaemon = true }.start()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    override fun close() {
        runCatching { pipe[1].close() }
        runCatching { pipe[0].close() }
        script.delete()
    }

    private fun routeScript(nodes: List<XrayNode>, apps: List<TrackedApp>, domains: DomainRules): String {
        val probeNodes = nodes.joinToString(", ") { "[\"${it.id}\"]=true" }
        val appScopes = apps.joinToString(", ") { "[\"${it.app.uid}\"]=\"${it.scope}\"" }
        val observed = apps.filter { it.mode == AppRouteMode.AUTO }.joinToString(", ") { "[\"${it.scope}\"]=true" }
        val telegram = apps.filter { it.mode == AppRouteMode.AUTO && isTelegram(it.app.packageName) }
            .joinToString(", ") { "[\"${it.scope}\"]=true" }
        val services = RouteSelection.services.flatMap { service ->
            service.domains.map { "[\"$it\"]=\"${service.id}\"" }
        }.joinToString(", ")
        val available = nodes.mapTo(mutableSetOf(), XrayNode::id)
        val initial = luaString(domains.livePayload(available))
        return """
            local router = require("xray.router")
            local geodata = require("xray.geodata")
            local log = require("xray.log")
            local probes = { $probeNodes }
            local apps = { $appScopes }
            local automatic = { $observed }
            local telegram = { $telegram }
            local services = { $services }
            local last = {}
            local initial = $initial

            local function decodePolicy(payload)
                assert(payload:sub(1, 3) == "v1\n")
                local patterns = {}
                local policy = { rules = {}, count = 0 }
                for line in payload:sub(4):gmatch("[^\n]+") do
                    local tag, pattern = line:match("^([a-z0-9-]+)\t(.*)$")
                    assert(tag and (tag == "default" or tag == "direct" or tag == "blocked" or probes[tag]))
                    policy.count = policy.count + 1
                    if tag == "direct" then policy.hasDirect = true end
                    local rule = { order = policy.count, tag = tag }
                    if pattern ~= "" then
                        patterns[#patterns + 1] = pattern
                        policy.rules[#policy.rules + 1] = rule
                    elseif not policy.matchAll then
                        policy.matchAll = rule
                    end
                end
                if #patterns > 0 then policy.matcher = geodata.BuildDomainMatcher(unpack(patterns)) end
                return policy
            end

            local lastPayload, lastPolicy = initial, decodePolicy(initial)
            local function readPolicy()
                local payload, err = router.PickOutbound("${XrayEngine.domainPolicy}")
                if err then error(err) end
                return payload == "blocked" and initial or payload
            end

            local function currentPolicy()
                local payload = readPolicy()
                if payload ~= lastPayload then
                    lastPolicy = decodePolicy(payload)
                    lastPayload = payload
                end
                return lastPolicy
            end

            local function observe(ctx, scope, domain, port)
                if telegram[scope] then
                    domain = "-"
                elseif #domain > 253 or not domain:match("^[a-z0-9.-]+$") then
                    return
                end
                local now = os.time()
                local recent = last[scope]
                local targets = ctx:GetTargetIPs()
                if not targets or not targets[1] then return end
                local ip = targets[1]:String()
                local key = telegram[scope] and ip .. ":" .. port or domain
                if recent and recent.time == now and (recent.count >= 4 or recent.hosts[key]) then return end
                if not recent or recent.time ~= now then
                    recent = { time = now, count = 0, hosts = {} }
                    last[scope] = recent
                end
                recent.count = recent.count + 1
                recent.hosts[key] = true
                log.Warning("ziqeno-app-target ", scope, " ", domain, " ", ip, " ", port)
            end

            local function pick(scope)
                local node, err = router.PickOutbound(scope)
                return node, scope, err
            end

            local function domainRule(policy, domain)
                local best = policy.matchAll
                if policy.matcher and domain ~= "" then
                    local matches = policy.matcher:Match(domain)
                    if matches then
                        for i = 1, #matches do
                            local rule = policy.rules[matches[i] + 1]
                            if not best or rule.order < best.order then best = rule end
                        end
                    end
                end
                return best
            end

            function HandleRoute(ctx, inbound, sourcePort, port, localPort, domain, network, protocol, user)
                if inbound == "${XrayEngine.domainValidation}" then
                    decodePolicy(user)
                    return "blocked"
                end
                if inbound == "${XrayEngine.domainVerification}" then
                    assert(readPolicy() == user, "Domain policy changed")
                    return "blocked"
                end
                if inbound == "benchmarks" then
                    if user == "${XrayEngine.directProbe}" then return user end
                    if probes[user] then return user end
                    return "blocked"
                end
                if port == 53 then return "tunnel-dns" end
                if inbound == "${XrayEngine.domainDnsFallback}" then
                    if currentPolicy().hasDirect then return "direct" end
                    return "blocked"
                end
                if inbound == "vpn" then
                    local policy = currentPolicy()
                    local rule = domainRule(policy, domain:gsub("%.$", ""))
                    if rule then
                        if rule.tag == "default" then return pick("default") end
                        return rule.tag
                    end
                    if ${apps.isNotEmpty()} then
                        local _, uid = router.FindProcess(ctx)
                        local scope = apps[uid]
                        if scope then
                            if automatic[scope] and network == router.NetworkTCP and
                                (telegram[scope] or port == 443 and protocol == "tls") then
                                pcall(observe, ctx, scope, domain, port)
                            end
                            local node = router.PickOutbound(scope)
                            if node and node ~= "blocked" then return node, scope end
                            return pick("default")
                        end
                    end
                    if policy.count == 0 then
                        while domain ~= "" do
                            local scope = services[domain]
                            if scope then
                                local node = router.PickOutbound(scope)
                                if node and node ~= "blocked" then return node, scope end
                                break
                            end
                            domain = domain:match("^[^.]+%.(.+)$") or ""
                        end
                    end
                end
                if network == router.NetworkTCP or network == router.NetworkUDP then return pick("default") end
                return "blocked"
            end
        """.trimIndent()
    }

    private fun luaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\t", "\\t") + "\""
}
