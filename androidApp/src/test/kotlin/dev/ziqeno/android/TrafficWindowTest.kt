package dev.ziqeno.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrafficWindowTest {
    @Test
    fun subtractsDirectTrafficFromTunnelSamples() {
        val sampler = TrafficSampler()
        sampler.record(1_000, 10_000, TunnelBytes(100, 200, 50, 100))
        val point = sampler.record(3_000, 12_000, TunnelBytes(1_000_100, 500_200, 750_050, 250_100))
        assertEquals(TrafficPoint(12_000, 1.0, 1.0), point)
    }

    @Test
    fun computesRatesAndExpiresSamplesAfterTenMinutes() {
        val history = TrafficWindow()
        val sampler = TrafficSampler()
        assertNull(sampler.record(1_000, 10_000, TunnelBytes(0, 0)))
        history.add(checkNotNull(sampler.record(3_000, 12_000, TunnelBytes(1_000_000, 250_000))))
        assertEquals(listOf(TrafficPoint(12_000, 4.0, 1.0)), history.recent(611_999))
        assertEquals(emptyList(), history.recent(612_000))
    }

    @Test
    fun leavesGapsForSuspendedSamplingAndCounterResets() {
        val sampler = TrafficSampler()
        sampler.record(1_000, 10_000, TunnelBytes(100, 100))
        assertNull(sampler.record(30_000, 39_000, TunnelBytes(8_000_000, 8_000_000)))
        assertNull(sampler.record(32_000, 41_000, TunnelBytes(2, 3)))
        assertEquals(TrafficPoint(43_000, 0.0, 0.0), sampler.record(34_000, 43_000, TunnelBytes(2, 3)))
    }
}
