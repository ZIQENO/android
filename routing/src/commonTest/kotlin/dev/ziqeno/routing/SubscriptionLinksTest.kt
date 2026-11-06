package dev.ziqeno.routing

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionLinksTest {
    private val link = "vless://00000000-0000-4000-8000-000000000001@example.com:443#test"

    @Test
    fun readsUnpaddedBase64SubscriptionWithLineBreaks() {
        val encoded = Base64.UrlSafe.encode("# comment\r\n$link\r\n".encodeToByteArray())
            .trimEnd('=').chunked(17).joinToString("\r\n")
        assertEquals(listOf(link), SubscriptionLinks.read(encoded))
    }

    @Test
    fun filtersUnknownSchemesAndRemovesDuplicateLines() {
        assertEquals(listOf(link), SubscriptionLinks.read("$link\r\nhttp://example.org\r\n$link"))
    }

    @Test
    fun rejectsRawXrayConfigurationEvenWhenBase64Encoded() {
        val document = """{"outbounds":[{"protocol":"freedom"}]}"""
        assertTrue(SubscriptionLinks.read(document).isEmpty())
        assertTrue(SubscriptionLinks.read(Base64.encode(document.encodeToByteArray())).isEmpty())
    }
}