package dev.ziqeno.routing

data class NodeSamples(val nodeId: String, val latencyMillis: List<Long?>)

data class RouteChoice(val primary: String, val backups: List<String>) {
    val nodes: List<String> get() = listOf(primary) + backups
}

data class ServiceProfile(val id: String, val domains: List<String>, val endpoint: String)

object RouteSelection {
    val services = listOf(
        ServiceProfile("github", listOf("github.com", "githubusercontent.com", "githubassets.com"), "https://github.com/"),
        ServiceProfile("youtube", listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com"), "https://www.youtube.com/"),
    )

    fun shortlist(samples: List<NodeSamples>, limit: Int = 5): List<String> =
        samples.mapNotNull { sample ->
            val successes = sample.latencyMillis.filterNotNull().filter { it >= 0 }.sorted()
            if (successes.isEmpty() || successes.size * 2 < sample.latencyMillis.size) {
                null
            } else {
                val median = (successes[(successes.size - 1) / 2].toDouble() + successes[successes.size / 2]) / 2
                val failures = sample.latencyMillis.size - successes.size
                RankedNode(sample.nodeId, median + 2_000.0 * failures / sample.latencyMillis.size)
            }
        }.sortedWith(compareBy(RankedNode::cost, RankedNode::id))
            .map(RankedNode::id)
            .distinct()
            .take(limit)

    fun choose(samples: List<NodeSamples>): RouteChoice? {
        val candidates = shortlist(samples, limit = 3)
        val primary = candidates.firstOrNull() ?: return null
        return RouteChoice(primary, candidates.drop(1))
    }

    fun forService(samples: List<NodeSamples>, baseline: RouteChoice): RouteChoice =
        choose(samples) ?: baseline

    private data class RankedNode(val id: String, val cost: Double)
}