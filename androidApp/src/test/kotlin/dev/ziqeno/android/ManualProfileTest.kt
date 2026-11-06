package dev.ziqeno.android

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ManualProfileTest {
    private val server = ManualProfile(address = "198.51.100.1", secret = "61f222e9-9567-44f7-89bd-816c83342c4a")
    private val key = "cIK8BT3WdX3e89SYlzJVWjYRchZhpA7EUFhWKLkTnCE"

    @Test
    fun xhttpRealityFieldsReachXrayAndSurviveEditing() {
        val profile = server.copy(transport = ManualTransport.XHTTP, protection = ManualProtection.REALITY)
            .withOption(ManualOption.PATH, "/front?campaign=1")
            .withOption(ManualOption.HOST, "cdn.example")
            .withOption(ManualOption.XHTTP_MODE, "packet-up")
            .withOption(ManualOption.SNI, "cover.example")
            .withOption(ManualOption.REALITY_KEY, key)
            .withOption(ManualOption.SHORT_ID, "a1b2")
            .withOption(ManualOption.SPIDER, "/robots.txt")
        val restored = assertNotNull(ManualProfile.read(profile.reference()))
        assertEquals(profile, restored)
        val stream = outbound(restored).getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        assertEquals("/front?campaign=1", stream.getJSONObject("xhttpSettings").getString("path"))
        assertEquals("cdn.example", stream.getJSONObject("xhttpSettings").getString("host"))
        assertEquals("packet-up", stream.getJSONObject("xhttpSettings").getString("mode"))
        assertEquals("cover.example", stream.getJSONObject("realitySettings").getString("serverName"))
        assertEquals(key, stream.getJSONObject("realitySettings").getString("password"))
        assertEquals("a1b2", stream.getJSONObject("realitySettings").getString("shortId"))
        assertEquals("/robots.txt", stream.getJSONObject("realitySettings").getString("spiderX"))
    }

    @Test
    fun webSocketPassesHeadersAndEarlyDataWithoutLeakingOtherTransportOptions() {
        val profile = server.copy(transport = ManualTransport.WS)
            .withOption(ManualOption.PATH, "/ws?foo=1")
            .withOption(ManualOption.EARLY, "1024")
            .withOption(ManualOption.HEADERS, """{"X-Profile":"test"}""")
            .withOption(ManualOption.ALPN, "http/1.1")
            .withOption(ManualOption.REALITY_KEY, key)
        val stream = outbound(profile).getJSONObject("streamSettings")
        assertEquals("/ws?foo=1&ed=1024", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("test", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("X-Profile"))
        assertEquals("http/1.1", stream.getJSONObject("tlsSettings").getJSONArray("alpn").getString(0))
        assertFalse(profile.reference().contains(key))
        assertFailsWith<IllegalArgumentException> {
            profile.withOption(ManualOption.HEADERS, """{"Host":"duplicate.example"}""").configuration()
        }
    }

    @Test
    fun visionKeepsTheFlowAndSuppressesMuxForThatProfile() {
        val profile = server.withOption(ManualOption.FLOW, "xtls-rprx-vision").withOption(ManualOption.MUX, "8")
        val node = outbound(profile)
        assertEquals("xtls-rprx-vision", node.getJSONObject("settings").getString("flow"))
        assertFalse(node.has("mux"))
    }

    @Test
    fun rejectsUnsupportedRealityAndInvalidShortId() {
        val real = server.copy(protection = ManualProtection.REALITY)
            .withOption(ManualOption.SNI, "cover.example")
            .withOption(ManualOption.REALITY_KEY, key)
        assertFailsWith<IllegalArgumentException> { real.copy(transport = ManualTransport.WS).configuration() }
        assertFailsWith<IllegalArgumentException> { real.withOption(ManualOption.SHORT_ID, "123").configuration() }
    }

    @Test
    fun hysteriaMapsObfuscationAndBandwidthToFinalMask() {
        val profile = ManualProfile(protocol = ManualProtocol.HYSTERIA, address = "hysteria.example", secret = "auth")
            .withOption(ManualOption.HYSTERIA_IDLE, "42")
            .withOption(ManualOption.HYSTERIA_OBFS, "mask")
            .withOption(ManualOption.HYSTERIA_CC, "force-brutal")
            .withOption(ManualOption.HYSTERIA_UP, "16")
            .withOption(ManualOption.HYSTERIA_DOWN, "50")
        val stream = outbound(profile).getJSONObject("streamSettings")
        assertEquals("auth", stream.getJSONObject("hysteriaSettings").getString("auth"))
        assertEquals(42, stream.getJSONObject("hysteriaSettings").getInt("udpIdleTimeout"))
        val mask = stream.getJSONObject("finalmask")
        assertEquals("salamander", mask.getJSONArray("udp").getJSONObject(0).getString("type"))
        assertEquals("16 mbps", mask.getJSONObject("quicParams").getString("brutalUp"))
        assertEquals("50 mbps", mask.getJSONObject("quicParams").getString("brutalDown"))
    }

    @Test
    fun oldTcpLinkOpensInTheFormWithoutDiscardingUnrecognizedLinkOptions() {
        val link = "vless://${server.secret}@vpn.example:443?type=tcp&encryption=none&security=tls&sni=cover.example"
        val restored = assertNotNull(readBasicServerLink("$link#Old"))
        assertEquals("vpn.example", restored.address)
        assertEquals("cover.example", restored.value(ManualOption.SNI))
        assertEquals(ManualTransport.RAW, restored.transport)
        assertEquals(null, readBasicServerLink("$link&fp=firefox#Advanced"))
    }

    private fun outbound(profile: ManualProfile) = JSONObject(profile.configuration()).getJSONArray("outbounds").getJSONObject(0)
}
