package dev.ziqeno.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppTargetsTest {
    private val first = LaunchableApp("dev.example.one", "First", 10101)
    private val second = LaunchableApp("dev.example.two", "Second", 10102)

    @Test
    fun telegramUsesObservedDataCentersAndIgnoresGoogleApis() {
        val telegram = LaunchableApp("org.telegram.messenger", "Telegram", 10103)
        val targets = AppTargets(listOf(telegram))
        targets.observe(telegram.scope, "firebaseinstallations.googleapis.com", "142.250.74.10", 0)
        targets.observe(telegram.scope, "", "149.154.175.50", 1, port = 5222)
        targets.observe(telegram.scope, "dns.google", "8.8.8.8", 2)
        val chosen = targets.snapshot(3).getValue(telegram.scope)
        assertEquals(AppProbeProtocol.MTPROTO, chosen.protocol)
        assertEquals("149.154.175.50:5222", chosen.authority)
    }

    @Test
    fun browserCanChooseAnyPublicTlsHost() {
        val edge = LaunchableApp("com.microsoft.emmx", "Edge", 10104)
        val targets = AppTargets(listOf(edge))
        targets.observe(edge.scope, "telemetry.example.org", "93.184.216.34", 0)
        assertEquals("telemetry.example.org", targets.snapshot(0)[edge.scope]?.host)
    }

    @Test
    fun browserWaitsForTheSameIdleWindowAsOtherApps() {
        val edge = LaunchableApp("com.microsoft.emmx", "Edge", 10104)
        val targets = AppTargets(listOf(edge))
        targets.observe(edge.scope, "collect.example.org", "93.184.216.34", 0)
        targets.observe(edge.scope, "www.example.net", "93.184.216.35", 8_000)
        assertEquals("collect.example.org", targets.active(8_000).getValue(edge.scope).host)
        targets.observe(edge.scope, "www.example.net", "93.184.216.35", 60_000)
        assertEquals("www.example.net", targets.active(60_000).getValue(edge.scope).host)
    }

    @Test
    fun tracksRecentPublicTlsTargetsByPackage() {
        val targets = AppTargets(listOf(first, second))
        targets.observe(first.scope, "first.example.org", "93.184.216.34", 0)
        targets.observe(second.scope, "second.example.org", "2606:4700::1111", 1)
        assertTrue(targets.takePending())
        assertFalse(targets.takePending())
        assertEquals(
            mapOf(first.scope to "first.example.org", second.scope to "second.example.org"),
            targets.snapshot(1).mapValues { it.value.host },
        )
        targets.observe(first.scope, "unrelated.example.org", "93.184.216.35", 2)
        assertEquals("first.example.org", targets.snapshot(2).getValue(first.scope).host)
        targets.observe(first.scope, "replacement.example.org", "93.184.216.35", 61_000)
        assertEquals(setOf(first.scope), targets.snapshot(600_001).keys)
    }

    @Test
    fun favorsRepeatedForegroundTargetsAndSeparatesNewAddresses() {
        val targets = AppTargets(listOf(first))
        targets.observe(first.scope, "metrics.example.org", "93.184.216.34", 0)
        targets.observe(first.scope, "metrics.example.org", "93.184.216.34", 25_000)
        for (time in listOf(30_000L, 32_000L, 34_000L)) {
            targets.observe(first.scope, "api.example.org", "93.184.216.35", time)
        }
        val original = targets.snapshot(35_000).getValue(first.scope)
        assertEquals("api.example.org", original.host)
        targets.observe(first.scope, original.host, "93.184.216.36", 65_000)
        assertTrue(targets.takePending())
        val updated = targets.snapshot(65_000).getValue(first.scope)
        assertEquals("93.184.216.36", updated.address)
        assertTrue(original.historyKey != updated.historyKey)
    }

    @Test
    fun rejectsUnknownOwnersAndInternalOrMalformedAddresses() {
        val targets = AppTargets(listOf(first))
        targets.observe(second.scope, "service.example.org", "93.184.216.34", 0)
        targets.observe(first.scope, "service.example.org", "192.168.1.1", 0)
        targets.observe(first.scope, "service.example.org", "::ffff:127.0.0.1", 0)
        targets.observe(first.scope, "service.example.org", "fd00::1", 0)
        targets.observe(first.scope, "service.local", "93.184.216.34", 0)
        targets.observe(first.scope, "service.example.org/path", "93.184.216.34", 0)
        assertTrue(targets.snapshot(1).isEmpty())
    }
}
