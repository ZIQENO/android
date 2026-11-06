package dev.ziqeno.android

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceInputTest {
    @Test
    fun subscriptionViewerCanReadPastTheRoutingLimitWithoutLosingOrder() {
        val links = (1..252).joinToString("\n") { "vless://fixture@server$it.example:443#server-$it" }
        fun convert(chunk: String) = JSONArray(chunk.lines().map { link ->
            json("tag" to link.substringAfter('#'), "protocol" to "vless",
                "settings" to json("address" to link.substringAfter('@').substringBefore(':'), "port" to 443))
        })

        assertEquals(250, SubscriptionNodes.read(links, convertLinks = ::convert).size)
        val all = SubscriptionNodes.read(links, limit = Int.MAX_VALUE, convertLinks = ::convert)
        assertEquals((1..252).map { "server-$it" }, all.map(XrayNode::name))
    }

    @Test
    fun unsupportedTailDoesNotHideValidSubscriptionServers() {
        val links = (1..250).joinToString("\n") { "vless://fixture@node$it.example:443" } + "\nvless://invalid"
        val nodes = SubscriptionNodes.read(links, limit = Int.MAX_VALUE) { chunk ->
            check(chunk != "vless://invalid")
            JSONArray(chunk.lines().map {
                json("protocol" to "vless", "settings" to json("address" to it.substringAfter('@').substringBefore(':')))
            })
        }
        assertEquals(250, nodes.size)
    }

    @Test
    fun hwidIsStableAcrossSubscriptionPathsAndChangesAcrossOrigins() {
        val first = subscriptionOrigin("https://Example.org/a")
        val second = subscriptionOrigin("https://example.org:443/b?secret=token")
        val third = subscriptionOrigin("https://other.example/b")
        val id = deviceIdFor("test-device", first)
        assertEquals(id, deviceIdFor("test-device", second))
        assertNotEquals(id, deviceIdFor("test-device", third))
        assertNotEquals(id, deviceIdFor("another-device", first))
        assertTrue(Regex("[a-zA-Z0-9=-]{10,64}").matches(id))
    }

    @Test
    fun acceptsOnlyHttpsUrlsWithoutUserInfoForSubscriptions() {
        assertFailsWith<IllegalArgumentException> { subscriptionOrigin("http://example.org/path") }
        assertFailsWith<IllegalArgumentException> { subscriptionOrigin("https://name:secret@example.org/path") }
    }

    @Test
    fun manualVlessSupportsIpv6AndRejectsAnAddressWithAPath() {
        val profile = ManualProfile(address = "2001:db8::1", secret = "61f222e9-9567-44f7-89bd-816c83342c4a")
        val node = org.json.JSONObject(profile.configuration()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("2001:db8::1", node.getJSONObject("settings").getString("address"))
        assertFailsWith<IllegalArgumentException> { profile.copy(address = "example.org/path").configuration() }
    }

    @Test
    fun recognizesOnlyOneSupportedServerLinkOrHttpsFromQr() {
        assertEquals(SourceKind.SUBSCRIPTION, sourceKind(" https://example.org/sub "))
        assertEquals(SourceKind.MANUAL, sourceKind("vless://secret@example.org:443"))
        assertEquals(null, sourceKind("https://example.org/sub\nhttps://another.example"))
        assertEquals(null, sourceKind("javascript:alert(1)"))
    }
}
