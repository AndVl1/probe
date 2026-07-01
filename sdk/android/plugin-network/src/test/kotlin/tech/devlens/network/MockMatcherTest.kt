package tech.devlens.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure [matchRules] matcher and the [MockRuleStore].
 *
 * Covers DoD-11 (precedence: longest-url wins; tie → last-added) and the
 * documented matching semantics (method wildcard, case-insensitive method,
 * case-sensitive URL substring).
 */
class MockMatcherTest {

    private fun aRule(
        url: String,
        method: String? = null,
        status: Int = 200,
        id: String = "rule-$url-$method-$status",
        body: String? = id
    ) = MockRule(id = id, method = method, url = url, status = status, body = body)

    // ── matchRules: empty / no match ──────────────────────────────────────────

    @Test
    fun `empty rules returns null`() {
        assertNull(matchRules(emptyList(), "GET", "https://example.com/api"))
    }

    @Test
    fun `non-matching url returns null`() {
        val rules = listOf(aRule(url = "/users"))
        assertNull(matchRules(rules, "GET", "https://example.com/orders/1"))
    }

    // ── matchRules: precedence (DoD-11) ───────────────────────────────────────

    @Test
    fun `longest url wins when multiple rules match`() {
        val short = aRule(url = "/api", id = "short", status = 200)
        val long = aRule(url = "/api/users", id = "long", status = 201)
        // Both match a URL containing /api/users. Longest must win regardless of order.
        val hit = matchRules(listOf(short, long), "GET", "https://example.com/api/users/1")
        assertEquals("long", hit?.id)
        assertEquals(201, hit?.status)

        // Order-independent: long first then short still resolves to long.
        val hitReversed = matchRules(listOf(long, short), "GET", "https://example.com/api/users/1")
        assertEquals("long", hitReversed?.id)
    }

    @Test
    fun `tie on url length resolves to last-added rule`() {
        val first = aRule(url = "/api/a", id = "first", status = 200)
        val second = aRule(url = "/api/b", id = "second", status = 201)
        // A URL containing both /api/a and /api/b. Equal lengths → last-added wins.
        val url = "https://example.com/api/a/and/api/b"
        assertEquals("second", matchRules(listOf(first, second), "GET", url)?.id)
        // Reversed order → the (new) last-added wins.
        assertEquals("first", matchRules(listOf(second, first), "GET", url)?.id)
    }

    // ── matchRules: method semantics ──────────────────────────────────────────

    @Test
    fun `null method acts as wildcard matching any method`() {
        val r = aRule(url = "/items", method = null)
        val rules = listOf(r)
        assertEquals(r.id, matchRules(rules, "GET", "https://x/items")?.id)
        assertEquals(r.id, matchRules(rules, "POST", "https://x/items")?.id)
        assertEquals(r.id, matchRules(rules, "DELETE", "https://x/items")?.id)
    }

    @Test
    fun `method mismatch does not match`() {
        val r = aRule(url = "/items", method = "POST")
        val rules = listOf(r)
        assertNull(matchRules(rules, "GET", "https://x/items"))
        assertEquals(r.id, matchRules(rules, "POST", "https://x/items")?.id)
    }

    @Test
    fun `method match is case-insensitive`() {
        val r = aRule(url = "/items", method = "post")
        val rules = listOf(r)
        assertEquals(r.id, matchRules(rules, "POST", "https://x/items")?.id)
        assertEquals(r.id, matchRules(rules, "Post", "https://x/items")?.id)
        assertEquals(r.id, matchRules(rules, "post", "https://x/items")?.id)
    }

    @Test
    fun `url match is case-sensitive`() {
        val r = aRule(url = "/Users")
        val rules = listOf(r)
        // Different case in URL → no match (documented: URL contains is case-sensitive).
        assertNull(matchRules(rules, "GET", "https://x/users/1"))
        assertEquals(r.id, matchRules(rules, "GET", "https://x/Users/1")?.id)
    }

    @Test
    fun `matching returns the rule body so caller can distinguish rules`() {
        val a = aRule(url = "/a", id = "A", status = 200, body = "body-A")
        val b = aRule(url = "/a/b", id = "B", status = 200, body = "body-B")
        val hit = matchRules(listOf(a, b), "GET", "https://x/a/b")
        assertEquals("body-B", hit?.body)
    }

    // ── MockRuleStore: CRUD ──────────────────────────────────────────────────

    @Test
    fun `store starts empty and snapshot is empty`() {
        val store = MockRuleStore()
        assertTrue(store.snapshot().isEmpty())
        assertTrue(store.list().isEmpty())
        assertNull(store.match("GET", "https://x/y"))
    }

    @Test
    fun `set appends a new rule and it matches`() {
        val store = MockRuleStore()
        val r = MockRule(id = "r1", method = null, url = "/api", status = 200, body = "ok")
        store.set(r)
        assertEquals(1, store.list().size)
        val hit = store.match("GET", "https://x/api/users")
        assertEquals("r1", hit?.id)
    }

    @Test
    fun `set replaces an existing rule with the same id`() {
        val store = MockRuleStore()
        store.set(MockRule(id = "dup", method = null, url = "/a", status = 200, body = "old"))
        store.set(MockRule(id = "dup", method = null, url = "/a", status = 204, body = "new"))

        val rules = store.list()
        assertEquals("replace-by-id must keep size at 1", 1, rules.size)
        val hit = store.match("GET", "https://x/a")
        assertEquals(204, hit?.status)
        assertEquals("new", hit?.body)
    }

    @Test
    fun `remove returns true and drops the rule when id exists`() {
        val store = MockRuleStore()
        store.set(MockRule(id = "r1", method = null, url = "/a", status = 200))
        store.set(MockRule(id = "r2", method = null, url = "/b", status = 200))

        assertTrue(store.remove("r1"))
        assertEquals(1, store.list().size)
        assertEquals("r2", store.list().first().id)
        assertNull(store.match("GET", "https://x/a"))
    }

    @Test
    fun `remove returns false for unknown id (idempotent)`() {
        val store = MockRuleStore()
        store.set(MockRule(id = "r1", method = null, url = "/a", status = 200))
        assertFalse(store.remove("does-not-exist"))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `clear returns the number of rules removed and empties the store`() {
        val store = MockRuleStore()
        store.set(MockRule(id = "r1", method = null, url = "/a", status = 200))
        store.set(MockRule(id = "r2", method = null, url = "/b", status = 200))
        assertEquals(2, store.clear())
        assertTrue(store.list().isEmpty())
        assertEquals(0, store.clear()) // already empty
    }

    @Test
    fun `store match reflects live updates across snapshots (no stale reads)`() {
        val store = MockRuleStore()
        assertNull(store.match("GET", "https://x/a"))

        store.set(MockRule(id = "r1", method = null, url = "/a", status = 201))
        assertEquals(201, store.match("GET", "https://x/a")?.status)

        store.remove("r1")
        assertNull(store.match("GET", "https://x/a"))
    }
}
