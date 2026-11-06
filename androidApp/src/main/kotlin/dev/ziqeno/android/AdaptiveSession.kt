package dev.ziqeno.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import dev.ziqeno.routing.RouteSelection
import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample
import dev.ziqeno.routing.RatedNode
import dev.ziqeno.routing.RouteSwitch
import dev.ziqeno.routing.ScoreProfile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal data class LinkContext(val kind: NetworkKind, val handle: Long, val metered: Boolean, val vpn: Boolean)

internal fun ConnectivityManager.linkContext(): LinkContext? {
    val active = activeNetwork ?: return null
    val caps = getNetworkCapabilities(active) ?: return null
    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val mobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val kind = when {
        wifi && !mobile -> NetworkKind.WIFI
        mobile && !wifi -> NetworkKind.MOBILE
        else -> NetworkKind.OTHER
    }
    return LinkContext(kind, active.networkHandle, !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
}

internal data class RoutingUpdate(val overview: RoutingOverview?, val revision: Long = 0) {
    val ready get() = overview != null
}

internal class AdaptiveSession(
    private val context: Context,
    nodes: List<XrayNode>,
    private val tracked: List<TrackedApp>,
    private val targets: AppTargets,
    domains: DomainRules,
    private val store: MeasurementStore,
    private val routeLog: RouteLog,
    private val probes: RouteProbes,
    private val control: RoutingControl,
    private val currentLink: () -> LinkContext?,
    private val conservingPower: () -> Boolean,
) : AutoCloseable {
    private val nodesById = nodes.associateBy(XrayNode::id)
    private val ids = nodesById.keys.toList()
    private val history = store.load()
    private val defaultRoute = XrayEngine.defaultRoute
    private val presets = tracked.map(TrackedApp::mode).distinct().filter { it.profile != null }
    private val automatic = tracked.filter { it.mode == AppRouteMode.AUTO }.associateBy(TrackedApp::scope)
    private val appNames = automatic.values.groupingBy { it.app.label }.eachCount()
    private class DomainPlan(val config: DomainRules) {
        val routes = config.tested.filter { it.exit == DomainExit.AUTO }.associateBy(DomainRule::scope)
        val endpoints = config.tested.associate { rule ->
            val host = checkNotNull(rule.testHost)
            rule.id to ProbeEndpoint("domain-check", "https://$host/", (200..499).toSet() - 407)
        }
    }
    @Volatile private var domainPlan = DomainPlan(domains)
    private val builtInScopes = RouteSelection.services.map { it.id }.toSet()
    private val routes = (listOf(defaultRoute) + builtInScopes + tracked.map(TrackedApp::scope) + domainPlan.routes.keys)
        .associateWith { RouteSwitch() }.toMutableMap()
    private val routeLock = Any()
    private val revision = AtomicLong()
    private val urgentProbe = AtomicBoolean()
    private val changes = ArrayDeque(routeLog.read())
    private val appChecks = mutableMapOf<String, Pair<AppTarget, Boolean?>>()
    private val domainChecks = mutableMapOf<String, ProbeSample?>()
    private val reserves = mutableMapOf<String, List<String>>()
    private val lastRankings = mutableMapOf<String, List<String>>()
    private val provisionalRoutes = mutableSetOf<String>()
    private val lastSpeedAttempt = mutableMapOf<NetworkKind, Long>()
    private var discoveryCursor = 0
    private var domainCursor = 0
    private var testedPlan = domainPlan
    @Volatile private var monitoredLink: LinkContext? = null
    private var lastQuickCheck = -5_000L
    private var lastUrgentRound = -10_000L
    private val appWatchTimes = mutableMapOf<String, Long>()
    private val domainWatchTimes = mutableMapOf<String, Long>()

    private fun current(scope: String): String? = synchronized(routeLock) { routes[scope]?.current }

    fun applyDomains(config: DomainRules, force: Boolean, valid: () -> Boolean): RoutingUpdate? = synchronized(routeLock) {
        val before = domainPlan
        if (!valid() || !force && before.config == config) return@synchronized null
        val next = DomainPlan(config)
        val retained = before.routes.keys.filter { before.routes[it] == next.routes[it] }.toSet()
        val payload = config.livePayload(nodesById.keys) { rule -> routes[rule.scope]?.current.takeIf { rule.scope in retained } }
        control.validateDomains(payload)
        if (!valid()) return@synchronized null
        control.selectDomains(payload)
        for (scope in before.routes.keys - retained) {
            routes.remove(scope)
            reserves.remove(scope)
            lastRankings.remove(scope)
            provisionalRoutes.remove(scope)
        }
        for (scope in next.routes.keys - retained) routes[scope] = RouteSwitch()
        val unchangedTests = next.config.tested.filter { it in before.config.tested }.map(DomainRule::id)
        domainChecks.keys.retainAll(unchangedTests.toSet())
        domainPlan = next
        val version = revision.incrementAndGet()
        monitoredLink?.let { RoutingUpdate(describe(it), version) }
    }

    // Call while holding routeLock. Built-in site states are retained while user rules take precedence.
    private fun activeRoutes(plan: DomainPlan) =
        routes.filterKeys { plan.config.active.isEmpty() || it !in builtInScopes }

    fun linkChanged(): Boolean = monitoredLink != currentLink()
    fun isCurrent(update: RoutingUpdate) = revision.get() == update.revision

    fun localizedOverview(): RoutingUpdate? = synchronized(routeLock) {
        monitoredLink?.let { RoutingUpdate(describe(it), revision.get()) }
    }

    fun nextCheckMillis(): Long = when {
        ids.isNotEmpty() && current(defaultRoute) == null -> 5_000
        monitoredLink?.kind == NetworkKind.MOBILE || monitoredLink?.metered != false || conservingPower() -> 120_000
        else -> 60_000
    }

    fun update(keepRunning: () -> Boolean, publish: (RoutingUpdate) -> Unit): RoutingUpdate? {
        val link = currentLink() ?: return null
        val changedNetwork = monitoredLink != link
        if (changedNetwork) synchronized(routeLock) {
            if (monitoredLink != null) targets.clear()
            appChecks.clear()
            domainChecks.clear()
            domainCursor = 0
            routes.values.forEach(RouteSwitch::networkChanged)
            reserves.clear()
            lastRankings.clear()
            monitoredLink = link
            revision.incrementAndGet()
        }
        val epoch = revision.get()
        val plan = domainPlan
        val domains = plan.config
        val valid = { keepRunning() && currentLink() == link && revision.get() == epoch && domainPlan === plan }
        val slow = link.kind == NetworkKind.MOBILE || link.metered || conservingPower()
        val parallel = if (slow) 2 else 4
        if (ids.isEmpty()) {
            checkDomains(plan, emptyList(), emptyList(), link, parallel, valid)
            return snapshot(link, valid)
        }
        val now = System.currentTimeMillis()
        val initial = current(defaultRoute) == null
        val activeAndBackup = synchronized(routeLock) {
            activeRoutes(plan).flatMap { (scope, route) -> listOfNotNull(route.current) + reserves[scope].orEmpty() }
        }
        val urgent = urgentProbe.getAndSet(false)
        val candidateLimit = if (initial || changedNetwork || urgent) 12 else 6
        val cached = history.rank(ids, link.kind, defaultRoute, ScoreProfile.INTERACTIVE, now).take(candidateLimit).map(RatedNode::id)
        val discovery = discover(when {
            initial -> 30
            urgent -> 10
            slow -> 2
            else -> 4
        })
        val candidates = (activeAndBackup + discovery + cached).distinct()
        if (initial) {
            val warm = cached.take(parallel)
            val reachable = probes.firstReachable(warm, link.kind, valid)
            val first = reachable ?: discovery.filterNot { it in warm }.chunked(parallel)
                .firstNotNullOfOrNull { batch -> if (valid()) probes.firstReachable(batch, link.kind, valid) else null }
            if (!valid()) return null
            if (first != null) {
                startProvisional(first, link, valid)?.let {
                    Log.i("ZiqenoRouting", "startup phase=${if (reachable != null) "warm" else "cold"} route=ready")
                    publish(it)
                }
            }
        }
        val baseline = probes.run(candidates.flatMap { id ->
            RouteProbes.global.map { ProbeJob(id, defaultRoute, it) }
        }, link.kind, parallel, valid)
        if (!valid()) return null
        history.record(baseline, System.currentTimeMillis())
        val baselineHealthy = healthy(baseline)
        var baselineRank = rank(candidates, baseline, link, defaultRoute, ScoreProfile.INTERACTIVE)
        val shortlist = baselineRank.filter { it.id in baselineHealthy }.take(candidateLimit).map(RatedNode::id)
        if (current(defaultRoute) == null) {
            shortlist.firstOrNull()?.let { node -> startProvisional(node, link, valid)?.let(publish) }
        }
        val activeDefault = current(defaultRoute)
        val activeFailed = baseline.any { it.node == activeDefault } && activeDefault !in baselineHealthy
        if (activeFailed) select(defaultRoute, baselineRank, baseline, baselineHealthy, valid)
        val speedJobs = speedJobs(shortlist, link, slow, now)
        val speed = probes.run(speedJobs, link.kind, 1, valid)
        if (!valid()) return null
        if (speedJobs.isNotEmpty()) lastSpeedAttempt[link.kind] = now
        history.record(speed, System.currentTimeMillis())
        baselineRank = rank(candidates, baseline, link, defaultRoute, ScoreProfile.INTERACTIVE)
        if (!valid()) return null
        if (!activeFailed) select(defaultRoute, baselineRank, baseline, baselineHealthy, valid)
        val selected = current(defaultRoute)
        if (selected == null) {
            if (domains.active.isNotEmpty()) checkDomains(plan, shortlist, discovery, link, parallel, valid)
            runCatching { store.save(history) }
            Log.i("ZiqenoRouting", "network=${link.kind} checks=${baseline.size} reachable=${baselineHealthy.size} routes=0")
            return if (!valid()) null else if (domains.active.isEmpty()) RoutingUpdate(null) else snapshot(link, valid)
        }

        for (preset in presets) {
            val scope = preset.presetScope
            val ranked = rank(candidates, baseline, link, defaultRoute, checkNotNull(preset.profile))
            select(scope, ranked, baseline, baselineHealthy, valid)
            if (current(scope) == null) setRoute(scope, selected, valid = valid)
        }

        checkDomains(plan, shortlist, discovery, link, parallel, valid)
        if (!valid()) return null
        val appCount = checkApps(shortlist, discovery, link, parallel, valid)
        if (!valid()) return null
        val recorded = mutableListOf<ProbeSample>()
        for (service in domains.builtInServices) {
            if (!valid()) return null
            val endpoint = RouteProbes.pages.getValue(service.id)
            val active = current(service.id)
            val tested = (listOfNotNull(active) + synchronized(routeLock) { reserves[service.id].orEmpty() } + shortlist).distinct().take(12)
            val serviceChecks = probes.run(
                tested.flatMap { id ->
                    List(2) { ProbeJob(id, service.id, endpoint) }
                }, link.kind, parallel, valid,
            )
            if (!valid()) return null
            recorded += serviceChecks
            history.record(serviceChecks, System.currentTimeMillis())
            val profile = if (service.id == "youtube") ScoreProfile.STREAMING else ScoreProfile.INTERACTIVE
            val ranked = rank(tested, serviceChecks, link, service.id, profile)
            val serviceHealthy = healthy(serviceChecks)
            val fallback = if (active == null && serviceHealthy.isEmpty()) baselineRank else ranked
            val healthy = if (active == null && serviceHealthy.isEmpty()) baselineHealthy else serviceHealthy
            select(service.id, fallback, serviceChecks, healthy, valid)
            if (current(service.id) == null) {
                setRoute(service.id, selected, valid = valid)
            }
        }
        if (!valid()) return null
        runCatching { store.save(history) }
        val measuredSpeed = speed.filter { it.downloadMbps != null }
        Log.i("ZiqenoRouting", "network=${link.kind} checks=${baseline.size + recorded.size} appChecks=$appCount speed=${measuredSpeed.size} bytes=${speed.sumOf { it.bytes }}")
        return snapshot(link, valid)
    }

    private fun snapshot(link: LinkContext, valid: () -> Boolean): RoutingUpdate? = synchronized(routeLock) {
        if (valid()) RoutingUpdate(describe(link), revision.get()) else null
    }

    private fun checkDomains(
        plan: DomainPlan, shortlist: List<String>, discovery: List<String>, link: LinkContext, parallel: Int, valid: () -> Boolean,
    ) {
        val tested = plan.config.tested
        if (tested.isEmpty()) return
        if (testedPlan !== plan) {
            testedPlan = plan
            domainCursor = 0
        }
        val batch = List(minOf(4, tested.size)) { tested[(domainCursor + it) % tested.size] }
        domainCursor = (domainCursor + batch.size) % tested.size
        for (rule in batch) {
            if (!valid()) return
            val endpoint = plan.endpoints.getValue(rule.id)
            val scoped = "${rule.scope}-${routeDigest(checkNotNull(rule.testHost))}"
            val active = domainNode(rule)
            val candidates = if (rule.exit == DomainExit.AUTO) {
                val backups = synchronized(routeLock) { reserves[rule.scope].orEmpty() }
                val warm = history.rank(ids, link.kind, scoped, ScoreProfile.INTERACTIVE, System.currentTimeMillis(), false)
                val known = listOfNotNull(active, backups.firstOrNull(), warm.firstOrNull()?.id)
                (known + listOfNotNull(discovery.firstOrNull { it !in known && it !in shortlist }) + shortlist + discovery)
                    .distinct().take(4)
            } else listOfNotNull(active)
            val jobs = candidates.map { ProbeJob(it, scoped, endpoint) }
            val first = probes.run(jobs, link.kind, parallel, valid)
            val retry = first.any { it.node == active && it.ttfbMs == null } && rule.exit == DomainExit.AUTO
            val samples = first + if (retry && active != null) probes.quick(listOf(ProbeJob(active, scoped, endpoint)), link.kind, valid) else emptyList()
            if (!valid()) return
            if (rule.exit == DomainExit.AUTO) {
                history.record(samples, System.currentTimeMillis())
                val ranked = rank(candidates, samples, link, scoped, ScoreProfile.INTERACTIVE, includeGenericSpeed = false)
                select(rule.scope, ranked, samples, healthy(samples), valid, RouteReason.DOMAIN_FAILURE)
            }
            synchronized(routeLock) {
                if (valid()) domainChecks[rule.id] = samples.lastOrNull { it.node == domainNode(rule) }
            }
        }
    }

    private fun domainNode(rule: DomainRule): String? = when (rule.exit) {
        DomainExit.DIRECT -> XrayEngine.directProbe
        DomainExit.AUTO -> current(rule.scope) ?: current(defaultRoute)
        DomainExit.NODE -> rule.nodeId?.takeIf { it in nodesById }
        DomainExit.REJECT -> null
    }

    private fun checkApps(
        shortlist: List<String>, discovery: List<String>, link: LinkContext, parallel: Int, valid: () -> Boolean,
    ): Int {
        val observed = targets.snapshot(SystemClock.elapsedRealtime())
        val checked = synchronized(routeLock) {
            for (scope in appChecks.keys - observed.keys) {
                lastRankings.remove(scope)
                reserves.remove(scope)
            }
            observed.mapValues { (scope, target) ->
                if (appChecks[scope]?.first != target) {
                    routes.getValue(scope).networkChanged()
                    lastRankings.remove(scope)
                    reserves.remove(scope)
                    appChecks[scope] = target to null
                }
                current(scope) to reserves[scope].orEmpty()
            }
        }
        val now = System.currentTimeMillis()
        val jobs = observed.flatMap { (scope, target) ->
            val (active, backups) = checked.getValue(scope)
            val historyScope = "$scope-${target.historyKey}"
            val warm = history.rank(ids, link.kind, historyScope, ScoreProfile.INTERACTIVE, now, includeGenericSpeed = false)
                .take(2).map(RatedNode::id)
            val known = listOfNotNull(active, backups.firstOrNull(), warm.firstOrNull())
            val rotating = discovery.firstOrNull { it !in known && it !in shortlist }
                ?: discovery.firstOrNull { it !in known }
            (known + listOfNotNull(rotating) + shortlist + warm)
                .distinct().take(4).map { AppProbeJob(it, scope, target) }
        }
        val first = probes.apps(jobs, link.kind, parallel, valid)
        val failedActive = first.filter { it.ttfbMs == null && checked[it.scope]?.first == it.node }
            .map { AppProbeJob(it.node, it.scope, observed.getValue(it.scope)) }
        val results = first + probes.apps(failedActive, link.kind, parallel, valid)
        if (results.isEmpty() || !valid()) return 0
        val byScope = results.groupBy(ProbeSample::scope)
        val stored = results.map { sample ->
            sample.copy(scope = "${sample.scope}-${observed.getValue(sample.scope).historyKey}")
        }.groupBy(ProbeSample::scope)
        history.record(stored.values.flatten(), System.currentTimeMillis())
        val stillObserved = targets.active(SystemClock.elapsedRealtime())
        for ((scope, target) in observed) {
            if (stillObserved[scope] != target) continue
            val checks = byScope[scope].orEmpty()
            if (checks.isEmpty()) continue
            val historyScope = "$scope-${target.historyKey}"
            val ranked = rank(
                checks.map(ProbeSample::node), stored.getValue(historyScope), link, historyScope, ScoreProfile.INTERACTIVE,
                includeGenericSpeed = false,
            )
            val available = healthy(checks)
            select(scope, ranked, checks, available, valid, RouteReason.SERVICE_FAILURE)
            synchronized(routeLock) {
                if (valid()) appChecks[scope] = target to (current(scope) in available)
            }
        }
        return results.size
    }

    private fun startProvisional(node: String, link: LinkContext, valid: () -> Boolean): RoutingUpdate? =
        synchronized(routeLock) {
            if (!setRoute(defaultRoute, node, valid = valid)) return@synchronized null
            provisionalRoutes.add(defaultRoute)
            val plan = domainPlan
            for (scope in activeRoutes(plan).keys.filterNot { it == defaultRoute || it in automatic || it in plan.routes }) {
                if (current(scope) == null && setRoute(scope, node, valid = valid)) provisionalRoutes.add(scope)
            }
            if (valid()) RoutingUpdate(describe(link), revision.get()) else null
        }

    private fun discover(count: Int): List<String> {
        val found = List(minOf(count, ids.size)) { ids[(discoveryCursor + it) % ids.size] }
        discoveryCursor = (discoveryCursor + found.size) % ids.size
        return found
    }

    private fun speedJobs(shortlist: List<String>, link: LinkContext, slow: Boolean, now: Long): List<ProbeJob> {
        if (shortlist.isEmpty() || conservingPower()) return emptyList()
        val previous = lastSpeedAttempt[link.kind] ?: history.samples().asSequence()
            .filter { it.network == link.kind && it.scope == MeasurementHistory.speedScope }
            .maxOfOrNull { it.time }
        if (previous != null && now - previous in 0 until 900_000) return emptyList()
        val budget = if (slow) 65_536 else 262_144
        return shortlist.take(if (slow) 2 else 3).map { RouteProbes.speed(it, budget) }
    }

    private fun rank(
        ids: List<String>, fresh: List<ProbeSample>, link: LinkContext, scope: String, profile: ScoreProfile,
        includeGenericSpeed: Boolean = true,
    ): List<RatedNode> {
        val now = System.currentTimeMillis()
        val ranked = history.rank(ids, link.kind, scope, profile, now, includeGenericSpeed)
        val recovered = MeasurementHistory(fresh).rank(ids, link.kind, scope, profile, now, includeGenericSpeed)
        return (ranked + recovered).distinctBy(RatedNode::id)
    }

    private fun select(
        scope: String, ranking: List<RatedNode>, samples: List<ProbeSample>, healthy: Set<String>, valid: () -> Boolean,
        failure: RouteReason = RouteReason.FAILURE,
    ) = synchronized(routeLock) {
        if (!valid()) return@synchronized
        lastRankings[scope] = ranking.filter { it.id in healthy }.map(RatedNode::id)
        val route = routes.getValue(scope)
        val activeChecks = samples.filter { it.node == route.current }
        val critical = activeChecks.size >= 2 && activeChecks.all { it.ttfbMs == null }
        if (!critical && scope in provisionalRoutes && lastRankings.getValue(scope).isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val preferred = route.initialTarget(lastRankings.getValue(scope), now)
            if (preferred != null && (preferred == route.current || setRoute(scope, preferred, valid = valid))) {
                provisionalRoutes.remove(scope)
            }
            reserves[scope] = route.verifiedReserves(lastRankings.getValue(scope), now)
            return@synchronized
        }
        val attempts = if (critical) 3 else 1
        repeat(attempts) {
            if (!valid()) return@synchronized
            val target = route.propose(ranking, healthy, critical, SystemClock.elapsedRealtime()) ?: return@repeat
            if (setRoute(scope, target, critical, valid, failure)) {
                if (critical) Log.i("ZiqenoRouting", "scope=$scope changed=critical")
                reserves[scope] = route.verifiedReserves(lastRankings[scope].orEmpty(), SystemClock.elapsedRealtime())
                return@synchronized
            }
        }
        reserves[scope] = route.verifiedReserves(lastRankings[scope].orEmpty(), SystemClock.elapsedRealtime())
    }

    private fun setRoute(
        scope: String, target: String, critical: Boolean = false, valid: () -> Boolean = { true },
        failure: RouteReason = RouteReason.FAILURE,
    ): Boolean = synchronized(routeLock) {
        if (!valid()) return@synchronized false
        val route = routes.getValue(scope)
        val previous = route.current
        val plan = domainPlan
        val previousEffective = previous ?: if (scope in automatic || scope in plan.routes) current(defaultRoute) else null
        val applied = runCatching {
            if (scope in plan.routes) {
                val payload = plan.config.livePayload(nodesById.keys) { rule ->
                    if (rule.scope == scope) target else routes[rule.scope]?.current
                }
                control.selectDomains(payload)
            } else {
                control.select(scope, target)
            }
        }.isSuccess
        val now = SystemClock.elapsedRealtime()
        if (applied) {
            route.applied(target, now)
            if (critical) provisionalRoutes.remove(scope)
            val reason = when {
                critical -> failure
                previous == null -> RouteReason.CONNECTED
                else -> RouteReason.QUALITY
            }
            val link = monitoredLink
            if (link != null && previousEffective != null && previousEffective != target) {
                if (changes.size == RouteLog.limit) changes.removeLast()
                changes.addFirst(RouteChange(
                    System.currentTimeMillis(), scope, journalTitle(scope), link.kind, nodeName(previousEffective),
                    nodeName(target), reason,
                ))
                routeLog.write(changes.toList())
            }
            val from = previous?.let { ids.indexOf(it) + 1 } ?: 0
            Log.i("ZiqenoRouting", "route=$scope reason=${reason.name} from=$from to=${ids.indexOf(target) + 1}")
        } else {
            route.rejected(target, now)
            Log.w("ZiqenoRouting", "Route update failed for $scope")
        }
        applied
    }

    fun checkFast(keepRunning: () -> Boolean, publish: (RoutingUpdate) -> Unit): Boolean {
        val link = monitoredLink ?: return false
        val now = SystemClock.elapsedRealtime()
        val interval = if (link.metered || link.kind == NetworkKind.MOBILE || conservingPower()) 4_000 else 2_000
        if (now - lastQuickCheck < interval) return false
        val plan = domainPlan
        val domainRoutes = plan.routes
        val valid = { keepRunning() && monitoredLink == link && currentLink() == link && domainPlan === plan }
        if (!valid()) return false
        val active = synchronized(routeLock) {
            activeRoutes(plan).mapNotNull { (scope, state) -> state.current?.let { scope to it } }.toMap()
        }
        if (active.isEmpty()) return false
        lastQuickCheck = now
        val refreshApps = targets.takePending()
        fun check(ids: Collection<String>, endpoint: ProbeEndpoint) = probes.quick(
            ids.distinct().map { id -> ProbeJob(id, "watchdog", endpoint) }, link.kind, valid,
        )
        val failed = check(active.values, RouteProbes.global[0]).filter { it.ttfbMs == null }.map(ProbeSample::node)
        if (failed.isEmpty() || !valid()) return refreshApps
        val affected = active.filterValues { it in failed }
        val fallbackOrder = synchronized(routeLock) {
            affected.keys.associateWith { scope ->
                (reserves[scope].orEmpty() + lastRankings[scope].orEmpty() + ids)
                    .distinct().filterNot { it in failed }.take(3)
            }
        }
        val backups = fallbackOrder.values.flatten().distinct().take(6)
        val confirmations = check(failed + backups, RouteProbes.global[1])
        if (!valid()) return false
        val confirmed = confirmations.filter { it.node in failed && it.ttfbMs == null }.mapTo(mutableSetOf(), ProbeSample::node)
        if (confirmed.isEmpty()) return refreshApps
        val checkedBackups = healthy(confirmations) - failed.toSet()
        val ready = checkedBackups.ifEmpty { healthy(check(backups, RouteProbes.global[0])) }
        val activeTargets = targets.active(now)
        val failedRoutes = affected.filterValues { it in confirmed }
        val order = failedRoutes.keys.associateWith { scope ->
            (fallbackOrder.getValue(scope) + backups).distinct().filter { it in ready }
        }
        val generic = failedRoutes.filterKeys { it !in activeTargets && it !in domainRoutes }
        val genericUpdate = switchFailed(generic, order, link, valid)
        genericUpdate?.let(publish)
        val appRoutes = failedRoutes.filterKeys { it in activeTargets }.entries
            .sortedBy { appWatchTimes[it.key] ?: Long.MIN_VALUE }.take(2)
        appRoutes.forEach { appWatchTimes[it.key] = now }
        val jobs = appRoutes.flatMap { (scope, node) ->
            (listOf(node) + order.getValue(scope).take(2)).map { AppProbeJob(it, scope, activeTargets.getValue(scope)) }
        }
        val checks = probes.quickApps(jobs, link.kind, valid).groupBy(ProbeSample::scope)
        val stillObserved = targets.active(SystemClock.elapsedRealtime())
        val appOrder = appRoutes.mapNotNull { (scope, node) ->
            val results = checks[scope].orEmpty()
            val healthy = healthy(results)
            if (results.none { it.node == node } || node in healthy || stillObserved[scope] != activeTargets[scope]) {
                null
            } else scope to order.getValue(scope).filter { it in healthy }
        }.toMap()
        val appUpdate = switchFailed(failedRoutes.filterKeys { it in appOrder }, appOrder, link, valid, activeTargets)
        appUpdate?.let(publish)
        val watchedDomains = failedRoutes.filterKeys { it in domainRoutes }.entries
            .sortedBy { domainWatchTimes[it.key] ?: Long.MIN_VALUE }.take(2)
        domainWatchTimes.keys.retainAll(domainRoutes.keys)
        watchedDomains.forEach { domainWatchTimes[it.key] = now }
        val domainJobs = watchedDomains.flatMap { (scope, node) ->
            val endpoint = plan.endpoints.getValue(domainRoutes.getValue(scope).id)
            (listOf(node) + order.getValue(scope).take(2)).map { ProbeJob(it, scope, endpoint) }
        }
        val domainSamples = probes.quick(domainJobs, link.kind, valid).groupBy(ProbeSample::scope)
        val domainOrder = watchedDomains.mapNotNull { (scope, node) ->
            val samples = domainSamples[scope].orEmpty()
            val healthy = healthy(samples)
            if (samples.none { it.node == node } || node in healthy) null
            else scope to order.getValue(scope).filter { it in healthy }
        }.toMap()
        val domainUpdate = switchFailed(
            failedRoutes.filterKeys { it in domainOrder }, domainOrder, link, valid, domainSamples = domainSamples,
        )
        domainUpdate?.let(publish)
        val changed = genericUpdate != null || appUpdate != null || domainUpdate != null
        if (changed) Log.i("ZiqenoRouting", "watchdog switched failedNodes=${confirmed.size} verifiedCandidates=${ready.size}")
        if (!changed && generic.isEmpty() && appOrder.isEmpty() && domainOrder.isEmpty() &&
            appRoutes.size + watchedDomains.size == failedRoutes.size) return refreshApps
        if (!changed && now - lastUrgentRound < 10_000) return refreshApps
        lastUrgentRound = now
        urgentProbe.set(true)
        return true
    }

    private fun switchFailed(
        failed: Map<String, String>, ordered: Map<String, List<String>>, link: LinkContext, valid: () -> Boolean,
        verifiedTargets: Map<String, AppTarget> = emptyMap(),
        domainSamples: Map<String, List<ProbeSample>> = emptyMap(),
    ): RoutingUpdate? = synchronized(routeLock) {
        var changed = false
        for ((scope, node) in failed) {
            if (!valid()) break
            val route = routes.getValue(scope)
            if (route.current != node) continue
            val candidates = ordered.getValue(scope)
            val checked = verifiedTargets[scope]
            if (scope in automatic && targets.active(SystemClock.elapsedRealtime())[scope] != checked) continue
            for (attempt in 1..2) {
                val target = route.emergencyTarget(candidates, SystemClock.elapsedRealtime()) ?: break
                if (setRoute(scope, target, critical = true, valid = valid)) {
                    if (checked != null) appChecks[scope] = checked to true else appChecks.remove(scope)
                    domainPlan.routes[scope]?.let { rule ->
                        domainChecks[rule.id] = domainSamples[scope]?.firstOrNull { it.node == target }
                    }
                    changed = true
                    break
                }
            }
            reserves[scope] = route.verifiedReserves(candidates, SystemClock.elapsedRealtime())
        }
        if (changed) RoutingUpdate(describe(link), revision.incrementAndGet()) else null
    }

    private fun describe(link: LinkContext): RoutingOverview {
        val domains = domainPlan.config
        val observed = targets.active(SystemClock.elapsedRealtime())
        return RoutingOverview(
            link.kind,
            (listOf(defaultRoute) + domains.builtInServices.map { it.id }).mapNotNull { scope ->
                routes.getValue(scope).current?.let { RouteRow(title(scope), nodeName(it)) }
                    ?: if (scope == defaultRoute && (domains.active.isNotEmpty() || ids.isEmpty())) {
                        RouteRow(title(scope), context.getString(R.string.routing_default_server_unavailable))
                    } else null
            },
            reserves[defaultRoute].orEmpty().map(::nodeName),
            tracked.associate { rule ->
                val check = appChecks[rule.scope]
                val node = current(rule.scope) ?: if (rule.mode == AppRouteMode.AUTO) current(defaultRoute) else null
                rule.app.packageName to AppAssignment(
                    rule.mode, rule.scope, node?.let(::nodeName), check?.first?.identity, check?.second,
                    check != null && observed[rule.scope] == check.first, check?.first?.protocol,
                )
            },
            changes.toList(),
            domains.active.associate { rule ->
                val node = domainNode(rule)
                val server = when (rule.exit) {
                    DomainExit.DIRECT -> context.getString(R.string.domain_exit_direct)
                    DomainExit.REJECT -> context.getString(R.string.routing_blocked)
                    DomainExit.AUTO -> node?.let(::nodeName)
                    DomainExit.NODE -> node?.let(::nodeName) ?: context.getString(R.string.domain_server_unavailable)
                }
                val sample = domainChecks[rule.id]
                rule.id to DomainCheck(
                    server, sample?.takeIf { it.node == node }?.ttfbMs, rule.id in domainChecks,
                    rule.testHost != null && rule !in domains.tested,
                )
            },
            domains,
        )
    }

    private fun nodeName(id: String): String = nodesById.getValue(id).displayName(context)

    private fun journalTitle(scope: String): String =
        if (domainPlan.routes[scope]?.kind == DomainMatch.MATCH) "" else title(scope)

    private fun title(scope: String): String = when (scope) {
        defaultRoute -> context.getString(R.string.routing_default_route)
        "github" -> "GitHub"
        "youtube" -> "YouTube"
        else -> domainPlan.routes[scope]?.title(context) ?: automatic[scope]?.app?.let { app ->
            if (appNames.getValue(app.label) == 1) app.label else "${app.label} · ${app.packageName}"
        } ?: presets.firstOrNull { it.presetScope == scope }?.title(context) ?: scope
    }

    private fun healthy(samples: List<ProbeSample>) =
        samples.filter { it.ttfbMs != null }.mapTo(mutableSetOf(), ProbeSample::node)



    override fun close() {
        probes.close()
        control.close()
    }
}










