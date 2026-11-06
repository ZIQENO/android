package dev.ziqeno.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteSelectionTest {
    @Test
    fun prefersReliableServersAndKeepsTwoDistinctBackups() {
        val measurements = listOf(
            NodeSamples("fast", listOf(20, null, null)),
            NodeSamples("stable", listOf(110, 120, 125)),
            NodeSamples("backup", listOf(150, 155, 160)),
            NodeSamples("third", listOf(180, 185, 190)),
        )
        assertEquals(
            RouteChoice("stable", listOf("backup", "third")),
            RouteSelection.choose(measurements),
        )
    }

    @Test
    fun rejectsAListWithNoReachableServers() {
        assertNull(RouteSelection.choose(listOf(NodeSamples("dead", listOf(null, null)))))
    }

    @Test
    fun prefersServiceMeasurementsOverBaseline() {
        val baseline = RouteChoice("baseline", listOf("reserve"))
        val results = listOf(
            NodeSamples("baseline", listOf(320, 330)),
            NodeSamples("reserve", listOf(85, 86)),
        )
        assertEquals(
            RouteChoice("reserve", listOf("baseline")),
            RouteSelection.forService(results, baseline),
        )
    }

    @Test
    fun fallsBackToBaselineWhenServiceCannotBeMeasured() {
        val baseline = RouteChoice("baseline", listOf("reserve"))
        assertEquals(baseline, RouteSelection.forService(emptyList(), baseline))
    }
}