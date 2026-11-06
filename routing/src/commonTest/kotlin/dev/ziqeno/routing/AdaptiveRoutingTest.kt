package dev.ziqeno.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveRoutingTest {
    private val time = 600_000L

    @Test
    fun keepsWifiAndMobileRankingsIndependent() {
        val history = MeasurementHistory()
        history.record(
            sample("a", NetworkKind.WIFI, 40.0) + sample("b", NetworkKind.WIFI, 700.0) +
                sample("a", NetworkKind.MOBILE, 650.0) + sample("b", NetworkKind.MOBILE, 80.0),
            time,
        )
        val restored = MeasurementHistory(history.samples())
        assertEquals("a", restored.rank(listOf("a", "b"), NetworkKind.WIFI, "default", ScoreProfile.INTERACTIVE, time).first().id)
        assertEquals("b", restored.rank(listOf("a", "b"), NetworkKind.MOBILE, "default", ScoreProfile.INTERACTIVE, time).first().id)
    }

    @Test
    fun favorsThroughputForStreamingAndResponsivenessForInteractiveTraffic() {
        val history = MeasurementHistory()
        history.record(
            sample("responsive", NetworkKind.WIFI, 35.0) + sample("bandwidth", NetworkKind.WIFI, 370.0) +
                listOf(
                    ProbeSample("responsive", NetworkKind.WIFI, "speed", "download", time, downloadMbps = 1.0),
                    ProbeSample("bandwidth", NetworkKind.WIFI, "speed", "download", time, downloadMbps = 25.0),
                ),
            time,
        )
        val nodes = listOf("responsive", "bandwidth")
        assertEquals("responsive", history.rank(nodes, NetworkKind.WIFI, "default", ScoreProfile.INTERACTIVE, time).first().id)
        assertEquals("bandwidth", history.rank(nodes, NetworkKind.WIFI, "default", ScoreProfile.STREAMING, time).first().id)
    }

    @Test
    fun appChecksRankTheSameNodesIndependentlyWithoutGenericSpeed() {
        val history = MeasurementHistory()
        val wifi = NetworkKind.WIFI
        val nodes = listOf("a", "b")
        history.record(
            listOf(
                ProbeSample("a", wifi, "app-one", "service", time, 40.0, 25.0),
                ProbeSample("b", wifi, "app-one", "service", time, 240.0, 150.0),
                ProbeSample("a", wifi, "app-two", "service", time, 240.0, 150.0),
                ProbeSample("b", wifi, "app-two", "service", time, 40.0, 25.0),
                ProbeSample("a", wifi, "speed", "download", time, downloadMbps = 30.0),
            ),
            time,
        )

        val first = history.rank(nodes, wifi, "app-one", ScoreProfile.INTERACTIVE, time, includeGenericSpeed = false)
        val second = history.rank(nodes, wifi, "app-two", ScoreProfile.INTERACTIVE, time, includeGenericSpeed = false)
        assertEquals(listOf("a", "b"), first.map(RatedNode::id))
        assertEquals(listOf("b", "a"), second.map(RatedNode::id))
        assertEquals(first.first().score, second.first().score)
        assertEquals(first.first().confidence, second.first().confidence)
    }

    @Test
    fun oldMeasurementsLoseConfidenceAndEventuallyExpire() {
        val history = MeasurementHistory()
        history.record(sample("a", NetworkKind.WIFI, 50.0), time)
        val now = history.rank(listOf("a"), NetworkKind.WIFI, "default", ScoreProfile.INTERACTIVE, time)
        val later = history.rank(listOf("a"), NetworkKind.WIFI, "default", ScoreProfile.INTERACTIVE, time + 3_600_000)
        assertTrue(later.first().confidence < now.first().confidence)
        assertTrue(history.rank(listOf("a"), NetworkKind.WIFI, "default", ScoreProfile.INTERACTIVE, time + 172_800_000).isEmpty())
    }

    @Test
    fun requiresThreeConsecutiveWinsAndMinimumHold() {
        val switch = RouteSwitch()
        switch.applied("a", 0)
        val ranking = listOf(RatedNode("b", 80.0, 1.0), RatedNode("a", 60.0, 1.0))
        repeat(3) { assertNull(switch.propose(ranking, setOf("a", "b"), false, 10_000L * it)) }
        assertEquals("b", switch.propose(ranking, setOf("a", "b"), false, 120_000))
        assertEquals("a", switch.current)
        switch.applied("b", 120_000)
        assertEquals("b", switch.current)
    }

    @Test
    fun resetsTheStreakAfterWeakOrDifferentNetworkRounds() {
        val switch = RouteSwitch()
        switch.applied("a", 0)
        val strong = listOf(RatedNode("b", 80.0, 1.0), RatedNode("a", 60.0, 1.0))
        val weak = listOf(RatedNode("b", 65.0, 1.0), RatedNode("a", 60.0, 1.0))
        assertNull(switch.propose(strong, setOf("a", "b"), false, 120_000))
        assertNull(switch.propose(weak, setOf("a", "b"), false, 140_000))
        assertNull(switch.propose(strong, setOf("a", "b"), false, 160_000))
        switch.networkChanged()
        assertNull(switch.propose(strong, setOf("a", "b"), false, 180_000))
        assertNull(switch.propose(strong, setOf("a", "b"), false, 200_000))
        assertEquals("b", switch.propose(strong, setOf("a", "b"), false, 220_000))
    }

    @Test
    fun criticalFailureBypassesHoldButSkipsFailedBackupDuringCooldown() {
        val switch = RouteSwitch()
        switch.applied("a", 0)
        val ranking = listOf(RatedNode("b", 80.0, 1.0), RatedNode("c", 70.0, 1.0))
        assertEquals("b", switch.propose(ranking, setOf("b", "c"), true, 500))
        switch.rejected("b", 500)
        assertEquals("c", switch.propose(ranking, setOf("b", "c"), true, 501))
    }

    @Test
    fun failedPrimaryDoesNotImmediatelyReturnAfterRecovery() {
        val switch = RouteSwitch()
        switch.applied("a", 0)
        val afterFailure = listOf(RatedNode("b", 85.0, 1.0), RatedNode("c", 70.0, 1.0))
        assertEquals("b", switch.propose(afterFailure, setOf("b", "c"), true, 500))
        switch.applied("b", 500)
        val afterRecovery = listOf(RatedNode("a", 95.0, 1.0), RatedNode("c", 70.0, 1.0))
        assertEquals("c", switch.propose(afterRecovery, setOf("a", "c"), true, 1_000))
    }

    @Test
    fun failedPrimaryAndActiveNodeAreExcludedFromNewReserves() {
        val route = RouteSwitch()
        route.applied("a", 0)
        val scores = listOf(RatedNode("b", 80.0, 1.0), RatedNode("c", 75.0, 1.0), RatedNode("d", 70.0, 1.0))
        assertEquals("b", route.propose(scores, setOf("b", "c", "d"), true, 500))
        route.applied("b", 500)
        assertEquals(listOf("c", "d"), route.verifiedReserves(listOf("a", "b", "c", "c", "d"), 501))
        assertEquals(listOf("a", "c"), route.verifiedReserves(listOf("a", "b", "c", "d"), 121_000))
    }

    private fun sample(id: String, kind: NetworkKind, ttfb: Double) = listOf(
        ProbeSample(id, kind, "default", "one", time, ttfb, ttfb / 2),
        ProbeSample(id, kind, "default", "two", time, ttfb + 10, ttfb / 2),
    )
}



