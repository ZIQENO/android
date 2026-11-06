package dev.ziqeno.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainRulesTest {
    private val node = "node-0123456789abcdef0123456789abcdef"

    @Test
    fun livePolicyPublishesOrderedRulesAndExplicitlyClearsDisabledRules() {
        val deny = DomainRule("deny", DomainMatch.EXACT, "private.example", DomainExit.REJECT)
        val automatic = DomainRule("auto", DomainMatch.WILDCARD, "*.example", DomainExit.AUTO, testHost = "public.example")
        val pinned = DomainRule("pin", DomainMatch.SUFFIX, "other.example", DomainExit.NODE, "node-${"f".repeat(32)}")
        val config = DomainRules(true, listOf(deny, automatic, pinned))
        assertEquals(
            "v1\nblocked\tfull:private.example\n$node\tregexp:^.*\\.example$\nblocked\tdomain:other.example\n",
            config.livePayload(setOf(node)) { if (it == automatic) node else null },
        )
        assertEquals("v1\n", config.copy(enabled = false).livePayload(setOf(node)) { node })
    }

    @Test
    fun normalizesExactSuffixAndMasksWithoutAmbiguousUrls() {
        val exact = DomainRule("exact", DomainMatch.EXACT, " ПРИМЕР.РФ. ", DomainExit.DIRECT).normalized()
        assertEquals("full:xn--e1afmkfd.xn--p1ai", exact.xrayPattern)
        val suffix = DomainRule("suffix", DomainMatch.SUFFIX, "EXAMPLE.ORG", DomainExit.AUTO, testHost = "www.example.org")
            .normalized()
        assertEquals("domain:example.org", suffix.xrayPattern)
        assertFailsWith<IllegalArgumentException> { suffix.copy(testHost = "notexample.org").normalized() }
        assertFailsWith<IllegalArgumentException> { exact.copy(pattern = "https://example.org/").normalized() }
        val wildcard = DomainRule("mask", DomainMatch.WILDCARD, "*.example.org", DomainExit.AUTO, testHost = "one.two.example.org")
            .normalized()
        assertEquals("regexp:^.*\\.example\\.org$", wildcard.xrayPattern)
        assertFailsWith<IllegalArgumentException> { wildcard.copy(testHost = "example.org").normalized() }
    }

    @Test
    fun onlyOptedInRulesHaveTestsAndPinnedMissingNodesAreBlocked() {
        val direct = DomainRule("direct", DomainMatch.EXACT, "example.org", DomainExit.DIRECT)
        val pinned = DomainRule("pinned", DomainMatch.EXACT, "iana.org", DomainExit.NODE, node, "iana.org").normalized()
        val automatic = direct.copy(id = "auto", pattern = "example.net", exit = DomainExit.AUTO, testHost = "example.net")
        val blocked = direct.copy(id = "deny", exit = DomainExit.REJECT, testHost = "example.org").normalized()
        val config = DomainRules(true, listOf(direct, pinned, automatic, blocked))
        assertEquals(listOf(pinned, automatic), config.tested)
        assertTrue(config.copy(enabled = false).tested.isEmpty())
        assertEquals(node, pinned.outbound(setOf(node)))
        assertEquals("blocked", pinned.outbound(emptySet()))
        assertNull(blocked.testHost)
    }

    @Test
    fun suppressesTestsShadowedByEarlierRulesAndValidatesRegexTestHosts() {
        val block = DomainRule("block", DomainMatch.REGEX, "^private\\.example$", DomainExit.REJECT).normalized()
        val bypass = DomainRule("bypass", DomainMatch.MATCH, "", DomainExit.DIRECT, testHost = "private.example").normalized()
        assertTrue(DomainRules(true, listOf(block, bypass)).tested.isEmpty())
        assertEquals(listOf(bypass), DomainRules(true, listOf(bypass, block)).tested)
        assertFailsWith<IllegalArgumentException> { block.copy(pattern = "(?=abc)").normalized() }
        assertFailsWith<IllegalArgumentException> {
            block.copy(exit = DomainExit.AUTO, testHost = "other.example").normalized()
        }
    }
}
