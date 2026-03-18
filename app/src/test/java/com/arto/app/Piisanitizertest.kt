package com.arto.app.domain.sanitizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PiiSanitizer.
 *
 * Run with: ./gradlew test
 * These execute on the JVM — no emulator or device needed.
 */
class PiiSanitizerTest {

    // ── Phone numbers ───────────────────────────────────────────

    @Test
    fun `strips US phone number with dashes`() {
        val result = PiiSanitizer.sanitize("Call me at 555-123-4567")
        assertEquals("Call me at [PHONE]", result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.PHONE in result.redactedTypes)
    }

    @Test
    fun `strips international phone number`() {
        val result = PiiSanitizer.sanitize("Reach me at +1 (555) 123-4567")
        assertTrue("[PHONE]" in result.sanitizedText)
        assertFalse("555" in result.sanitizedText)
    }

    @Test
    fun `strips phone with dots`() {
        val result = PiiSanitizer.sanitize("Text 555.867.5309 now")
        assertTrue("[PHONE]" in result.sanitizedText)
    }

    // ── Email addresses ─────────────────────────────────────────

    @Test
    fun `strips email address`() {
        val result = PiiSanitizer.sanitize("Send to john.doe@example.com for details")
        assertEquals("Send to [EMAIL] for details", result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.EMAIL in result.redactedTypes)
    }

    @Test
    fun `strips email with plus addressing`() {
        val result = PiiSanitizer.sanitize("user+tag@gmail.com")
        assertEquals("[EMAIL]", result.sanitizedText)
    }

    // ── SSN ─────────────────────────────────────────────────────

    @Test
    fun `strips SSN with dashes`() {
        val result = PiiSanitizer.sanitize("SSN: 123-45-6789")
        assertEquals("SSN: [SSN]", result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.SSN in result.redactedTypes)
    }

    @Test
    fun `strips SSN with spaces`() {
        val result = PiiSanitizer.sanitize("Your number is 123 45 6789")
        assertTrue("[SSN]" in result.sanitizedText)
    }

    // ── Credit cards ────────────────────────────────────────────

    @Test
    fun `strips credit card with spaces`() {
        val result = PiiSanitizer.sanitize("Card: 4111 1111 1111 1111")
        assertTrue("[CREDIT_CARD]" in result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.CREDIT_CARD in result.redactedTypes)
    }

    @Test
    fun `strips credit card with dashes`() {
        val result = PiiSanitizer.sanitize("Use 5500-0000-0000-0004")
        assertTrue("[CREDIT_CARD]" in result.sanitizedText)
    }

    // ── Street addresses ────────────────────────────────────────

    @Test
    fun `strips street address`() {
        val result = PiiSanitizer.sanitize("Ship to 742 Evergreen Terrace Dr")
        assertTrue("[ADDRESS]" in result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.STREET_ADDRESS in result.redactedTypes)
    }

    @Test
    fun `strips address with apartment`() {
        val result = PiiSanitizer.sanitize("Deliver to 100 Main Street Apt 4B")
        assertTrue("[ADDRESS]" in result.sanitizedText)
    }

    // ── ZIP codes ───────────────────────────────────────────────

    @Test
    fun `strips 5-digit ZIP`() {
        val result = PiiSanitizer.sanitize("Area code 90210 is in LA")
        assertTrue("[ZIP]" in result.sanitizedText)
    }

    @Test
    fun `strips ZIP plus 4`() {
        val result = PiiSanitizer.sanitize("ZIP: 90210-1234")
        assertTrue("[ZIP]" in result.sanitizedText)
    }

    // ── IP addresses ────────────────────────────────────────────

    @Test
    fun `strips IPv4 address`() {
        val result = PiiSanitizer.sanitize("Server at 192.168.1.100")
        assertEquals("Server at [IP_ADDR]", result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.IP_ADDRESS in result.redactedTypes)
    }

    // ── Names (context-based) ───────────────────────────────────

    @Test
    fun `strips name after Dear`() {
        val result = PiiSanitizer.sanitize("Dear John Smith, your account is locked")
        assertTrue("[NAME]" in result.sanitizedText)
        assertFalse("John" in result.sanitizedText)
        assertTrue(PiiSanitizer.PiiType.NAME in result.redactedTypes)
    }

    @Test
    fun `strips name after greeting`() {
        val result = PiiSanitizer.sanitize("Hello Jane, click here to verify")
        assertTrue("[NAME]" in result.sanitizedText)
        assertFalse("Jane" in result.sanitizedText)
    }

    @Test
    fun `strips name in identity phrase`() {
        val result = PiiSanitizer.sanitize("My name is Robert Johnson and I need help")
        assertTrue("[NAME]" in result.sanitizedText)
    }

    // ── URLs ────────────────────────────────────────────────────

    @Test
    fun `preserves domain but strips URL path`() {
        val result = PiiSanitizer.sanitize(
            "Click https://suspicious-site.com/user/12345/verify"
        )
        // Domain is preserved (useful for scam detection)
        assertTrue("suspicious-site.com" in result.sanitizedText)
        // Path with potential PII is stripped
        assertTrue("[PATH_REDACTED]" in result.sanitizedText)
        assertFalse("12345" in result.sanitizedText)
    }

    // ── Scam signal preservation ────────────────────────────────

    @Test
    fun `preserves urgency language`() {
        val result = PiiSanitizer.sanitize(
            "URGENT: Your account will be suspended! Act NOW or lose access. " +
                    "Call 555-123-4567 immediately. Dear John Smith, verify your identity."
        )
        // Scam signals survive
        assertTrue("URGENT" in result.sanitizedText)
        assertTrue("suspended" in result.sanitizedText)
        assertTrue("Act NOW" in result.sanitizedText)
        // PII does not
        assertFalse("555-123-4567" in result.sanitizedText)
        assertFalse("John Smith" in result.sanitizedText)
    }

    @Test
    fun `preserves IRS scam pattern language`() {
        val result = PiiSanitizer.sanitize(
            "IRS Notice: You owe back taxes. Failure to pay will result " +
                    "in arrest. Contact agent at 800-555-0199."
        )
        assertTrue("IRS" in result.sanitizedText)
        assertTrue("arrest" in result.sanitizedText)
        assertTrue("owe back taxes" in result.sanitizedText)
        assertFalse("800-555-0199" in result.sanitizedText)
    }

    // ── Edge cases ──────────────────────────────────────────────

    @Test
    fun `handles empty string`() {
        val result = PiiSanitizer.sanitize("")
        assertEquals("", result.sanitizedText)
        assertEquals(0, result.redactionCount)
    }

    @Test
    fun `handles text with no PII`() {
        val input = "Your package is delayed. Track it online."
        val result = PiiSanitizer.sanitize(input)
        assertEquals(input, result.sanitizedText)
        assertEquals(0, result.redactionCount)
        assertTrue(result.redactedTypes.isEmpty())
    }

    @Test
    fun `handles multiple PII types in one message`() {
        val result = PiiSanitizer.sanitize(
            "Dear Jane Doe, confirm your card 4111-1111-1111-1111 " +
                    "or email support@bank.com"
        )
        assertTrue(result.redactionCount >= 3)
        assertTrue(result.redactedTypes.containsAll(
            setOf(PiiSanitizer.PiiType.NAME, PiiSanitizer.PiiType.CREDIT_CARD, PiiSanitizer.PiiType.EMAIL)
        ))
    }

    // ── containsPii() quick-check ───────────────────────────────

    @Test
    fun `containsPii returns true for phone number`() {
        assertTrue(PiiSanitizer.containsPii("Call 555-0123"))
    }

    @Test
    fun `containsPii returns false for clean text`() {
        assertFalse(PiiSanitizer.containsPii("Your order shipped today"))
    }
}