package dev.ziqeno.android

import dev.ziqeno.routing.MeasurementHistory
import dev.ziqeno.routing.NetworkKind
import dev.ziqeno.routing.ProbeSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerMeasurementsTest {
    @Test
    fun displaysLatestGenericMetricsIndependentlyAndSeparatesNetworks() {
        val samples = listOf(
            ProbeSample("first", NetworkKind.WIFI, "default", "health", 5, ttfbMs = 20.0),
            ProbeSample("first", NetworkKind.WIFI, MeasurementHistory.speedScope, "speed", 9, downloadMbps = 12.5),
            ProbeSample("first", NetworkKind.MOBILE, "default", "health", 18, ttfbMs = 80.0),
            ProbeSample("first", NetworkKind.WIFI, "app-private", "tls", 25, ttfbMs = 55.0),
            ProbeSample("first", NetworkKind.WIFI, "default", "health", 17, ttfbMs = 37.0),
            ProbeSample("second", NetworkKind.WIFI, "default", "health", 7, ttfbMs = 30.0),
            ProbeSample("second", NetworkKind.WIFI, "default", "health", 26),
        )
        val wifi = latestServerMeasurements(samples, NetworkKind.WIFI)
        assertEquals(ServerMeasurements(37.0, 12.5), wifi["first"])
        assertEquals(ServerMeasurements(null, null), wifi["second"])
        val mobile = latestServerMeasurements(samples, NetworkKind.MOBILE)
        assertEquals(ServerMeasurements(80.0, null), mobile["first"])
        assertNull(mobile["second"])
    }
}
