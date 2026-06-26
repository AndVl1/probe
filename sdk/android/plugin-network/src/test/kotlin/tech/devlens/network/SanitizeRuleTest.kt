package tech.devlens.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeRuleTest {

    // ── PHONE preset ─────────────────────────────────────────────────────────

    @Test
    fun `PHONE matches international format`() {
        assertTrue(SanitizeRule.PHONE.regex.containsMatchIn("+7 (999) 123-45-67"))
    }

    @Test
    fun `PHONE matches US format`() {
        assertTrue(SanitizeRule.PHONE.regex.containsMatchIn("1-800-555-1234"))
    }

    @Test
    fun `PHONE matches compact digits`() {
        assertTrue(SanitizeRule.PHONE.regex.containsMatchIn("+79991234567"))
    }

    @Test
    fun `PHONE does not match short number`() {
        assertFalse(SanitizeRule.PHONE.regex.containsMatchIn("123"))
    }

    @Test
    fun `PHONE label is PHONE`() {
        assertEquals("PHONE", SanitizeRule.PHONE.label)
    }

    // ── EMAIL preset ─────────────────────────────────────────────────────────

    @Test
    fun `EMAIL matches simple address`() {
        assertTrue(SanitizeRule.EMAIL.regex.containsMatchIn("user@example.com"))
    }

    @Test
    fun `EMAIL matches address with subdomain`() {
        assertTrue(SanitizeRule.EMAIL.regex.containsMatchIn("admin@mail.example.co.uk"))
    }

    @Test
    fun `EMAIL matches address with plus`() {
        assertTrue(SanitizeRule.EMAIL.regex.containsMatchIn("user+tag@example.com"))
    }

    @Test
    fun `EMAIL does not match string without at-sign`() {
        assertFalse(SanitizeRule.EMAIL.regex.containsMatchIn("notanemail.com"))
    }

    @Test
    fun `EMAIL label is EMAIL`() {
        assertEquals("EMAIL", SanitizeRule.EMAIL.label)
    }

    // ── BEARER_TOKEN preset ──────────────────────────────────────────────────

    @Test
    fun `BEARER_TOKEN matches Authorization header value`() {
        assertTrue(SanitizeRule.BEARER_TOKEN.regex.containsMatchIn("Bearer eyJhbGciOiJSUzI1NiJ9.abc.def"))
    }

    @Test
    fun `BEARER_TOKEN matches simple token`() {
        assertTrue(SanitizeRule.BEARER_TOKEN.regex.containsMatchIn("Bearer secret-token-123"))
    }

    @Test
    fun `BEARER_TOKEN does not match Basic auth`() {
        assertFalse(SanitizeRule.BEARER_TOKEN.regex.containsMatchIn("Basic dXNlcjpwYXNz"))
    }

    @Test
    fun `BEARER_TOKEN label is BEARER_TOKEN`() {
        assertEquals("BEARER_TOKEN", SanitizeRule.BEARER_TOKEN.label)
    }

    // ── Custom rule ──────────────────────────────────────────────────────────

    @Test
    fun `custom rule matches its pattern`() {
        val rule = SanitizeRule(Regex("""\b\d{4}-\d{4}-\d{4}-\d{4}\b"""), "CARD")
        assertTrue(rule.regex.containsMatchIn("1234-5678-9012-3456"))
        assertFalse(rule.regex.containsMatchIn("not-a-card"))
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    fun `equal rules have same equals result`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        val b = SanitizeRule(Regex("foo"), "FOO")
        assertEquals(a, b)
    }

    @Test
    fun `rules with different pattern are not equal`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        val b = SanitizeRule(Regex("bar"), "FOO")
        assertNotEquals(a, b)
    }

    @Test
    fun `rules with different label are not equal`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        val b = SanitizeRule(Regex("foo"), "BAR")
        assertNotEquals(a, b)
    }

    @Test
    fun `rules with different options are not equal`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        val b = SanitizeRule(Regex("foo", RegexOption.IGNORE_CASE), "FOO")
        assertNotEquals(a, b)
    }

    @Test
    fun `equal rules have same hashCode`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        val b = SanitizeRule(Regex("foo"), "FOO")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `rule equals itself`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        assertEquals(a, a)
    }

    @Test
    fun `rule is not equal to null`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        assertFalse(a.equals(null))
    }

    @Test
    fun `rule is not equal to different type`() {
        val a = SanitizeRule(Regex("foo"), "FOO")
        assertFalse(a.equals("foo"))
    }

    // ── toString ─────────────────────────────────────────────────────────────

    @Test
    fun `toString contains label`() {
        val rule = SanitizeRule(Regex("foo"), "MY_LABEL")
        assertTrue(rule.toString().contains("MY_LABEL"))
    }

    @Test
    fun `toString contains pattern`() {
        val rule = SanitizeRule(Regex("foo"), "MY_LABEL")
        assertTrue(rule.toString().contains("foo"))
    }
}
