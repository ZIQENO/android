package dev.ziqeno.routing

import kotlin.math.abs
import kotlin.math.ln

enum class NetworkKind { WIFI, MOBILE, OTHER }

enum class ScoreProfile { INTERACTIVE, STREAMING }

data class ProbeSample(
    val node: String,
    val network: NetworkKind,
    val scope: String,
    val endpoint: String,
    val time: Long,
    val ttfbMs: Double? = null,
    val transportMs: Double? = null,
    val downloadMbps: Double? = null,
    val bytes: Int = 0,
)

data class RatedNode(val id: String, val score: Double, val confidence: Double)

class MeasurementHistory(initial: List<ProbeSample> = emptyList()) {
    private var observations = initial.toList()

    fun record(samples: List<ProbeSample>, now: Long) {
        observations = (observations + samples).filter { now - it.time in 0..retentionMillis }
            .groupBy { Triple(it.network, it.node, it.scope) }
            .values.flatMap { series -> series.sortedByDescending { it.time }.take(if (series.first().scope == speedScope) 4 else 8) }
    }

    fun samples(): List<ProbeSample> = observations.toList()

    fun rank(
        ids: List<String>, network: NetworkKind, scope: String, profile: ScoreProfile, now: Long,
        includeGenericSpeed: Boolean = true,
    ): List<RatedNode> {
        val available = observations.filter {
            it.network == network && now - it.time in 0..retentionMillis &&
                (it.scope == scope || includeGenericSpeed && it.scope == speedScope)
        }
            .groupBy(ProbeSample::node)
        return ids.distinct().mapNotNull { id ->
            val series = available[id].orEmpty()
            val probes = series.filter { it.scope == scope }.sortedBy { it.time }
            val valid = probes.filter { it.ttfbMs != null }
            if (valid.isEmpty() || valid.size * 2 < probes.size) return@mapNotNull null
            val ttfb = valid.mapNotNull { it.ttfbMs }
            val transport = valid.mapNotNull { it.transportMs }
            val jitter = ttfb.zipWithNext { first, second -> abs(second - first) }
            val speed = series.filter { includeGenericSpeed && it.scope == speedScope && now - it.time <= speedRetentionMillis }
                .mapNotNull { it.downloadMbps }
            val streaming = profile == ScoreProfile.STREAMING
            val metrics = listOf(
                (if (streaming) 15 else 35) to latency(median(ttfb), 1_000.0),
                (if (streaming) 10 else 20) to latency(median(transport), 500.0),
                15 to latency(median(jitter), 250.0),
                20 to (valid.size + 1.0) / (probes.size + 2.0),
                (if (streaming) 40 else 10) to median(speed)?.let { (ln(1 + it) / ln(31.0)).coerceIn(0.0, 1.0) },
            )
            val measured = metrics.mapNotNull { (weight, value) -> value?.let { weight to it } }
            val totalWeight = measured.sumOf { it.first }
            val score = 100 * measured.sumOf { (weight, value) -> weight * value } / totalWeight
            val age = now - valid.last().time
            val recency = (1 - age.toDouble() / retentionMillis).coerceIn(0.0, 1.0)
            val evidence = (valid.size / 5.0).coerceAtMost(1.0)
            val coverage = (valid.map { it.endpoint }.distinct().size / 2.0).coerceAtMost(1.0)
            val stability = latency(median(jitter), 500.0) ?: 0.5
            val confidence = recency * (0.5 * evidence + 0.2 * coverage + 0.15 * stability + 0.15 * totalWeight / 100)
            RatedNode(id, score, confidence)
        }.filter { it.confidence >= 0.25 }.sortedWith(
            compareByDescending<RatedNode> { it.score * (0.75 + 0.25 * it.confidence) }
                .thenByDescending { it.confidence }.thenBy { it.id },
        )
    }

    private fun latency(value: Double?, limit: Double) = value?.let { (1 - it / limit).coerceIn(0.0, 1.0) }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    companion object {
        const val profileVersion = 1
        const val speedScope = "speed"
        private const val retentionMillis = 86_400_000L
        private const val speedRetentionMillis = 1_800_000L
    }
}

data class SwitchPolicy(
    val advantage: Double = 0.15,
    val confirmations: Int = 3,
    val holdMillis: Long = 120_000,
    val cooldownMillis: Long = 120_000,
)

class RouteSwitch(private val policy: SwitchPolicy = SwitchPolicy()) {
    var current: String? = null
        private set
    private var changedAt = 0L
    private var challenger: String? = null
    private var wins = 0
    private val cooldowns = mutableMapOf<String, Long>()

    fun propose(ranking: List<RatedNode>, healthy: Set<String>, critical: Boolean, now: Long): String? {
        cooldowns.values.removeAll { it <= now }
        val active = current
        val eligible = ranking.filter { it.id in healthy && it.id !in cooldowns }
        if (critical) return emergencyTarget(eligible.map(RatedNode::id), now)
        val best = eligible.firstOrNull()
        if (active == null) {
            clearStreak()
            return best?.id
        }
        val currentScore = ranking.firstOrNull { it.id == active }?.score
        if (best == null || best.id == active || currentScore == null ||
            best.score <= currentScore || best.score < currentScore * (1 + policy.advantage)
        ) {
            clearStreak()
            return null
        }
        wins = if (challenger == best.id) (wins + 1).coerceAtMost(policy.confirmations) else 1
        challenger = best.id
        return best.id.takeIf { wins >= policy.confirmations && now - changedAt >= policy.holdMillis }
    }

    fun emergencyTarget(healthyInOrder: List<String>, now: Long): String? {
        clearStreak()
        val active = current
        val best = verifiedReserves(healthyInOrder, now).firstOrNull()
        if (active != null && best != null && (cooldowns[active] ?: 0L) <= now) {
            cooldowns[active] = now + policy.cooldownMillis
        }
        return best
    }

    fun verifiedReserves(ordered: List<String>, now: Long): List<String> =
        ordered.distinct().filter { it != current && (cooldowns[it] ?: 0L) <= now }.take(2)

    fun initialTarget(healthyInOrder: List<String>, now: Long): String? =
        healthyInOrder.firstOrNull { (cooldowns[it] ?: 0L) <= now }

    fun applied(target: String, now: Long) {
        current = target
        changedAt = now
        clearStreak()
    }

    fun rejected(target: String, now: Long) {
        cooldowns[target] = now + policy.cooldownMillis
        clearStreak()
    }

    fun networkChanged() {
        cooldowns.clear()
        clearStreak()
    }

    private fun clearStreak() {
        challenger = null
        wins = 0
    }
}




