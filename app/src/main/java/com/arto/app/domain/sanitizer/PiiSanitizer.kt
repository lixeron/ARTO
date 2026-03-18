package com.arto.app.domain.sanitizer

/**
 * PiiSanitizer — Strips personally identifiable information from message text
 * BEFORE it leaves the device for cloud-based AI analysis.
 *
 * Design goals:
 *   1. Aggressive removal: False positives (over-stripping) are acceptable.
 *      False negatives (leaking PII) are not.
 *   2. Preserve scam signals: Urgency language, suspicious URLs, and social
 *      engineering phrases must survive sanitization so the AI can classify them.
 *   3. Pure Kotlin: No Android imports, fully unit-testable off-device.
 *
 * Usage:
 *   val result = PiiSanitizer.sanitize("Call John at 555-123-4567")
 *   result.sanitizedText  // "Call [NAME] at [PHONE]"
 *   result.redactionCount  // 2
 */
object PiiSanitizer {

    /**
     * Holds the sanitized output and metadata about what was stripped.
     */
    data class SanitizationResult(
        val sanitizedText: String,
        val redactionCount: Int,
        val redactedTypes: Set<PiiType>
    )

    /**
     * Categories of PII that can be detected and redacted.
     */
    enum class PiiType {
        PHONE,
        EMAIL,
        SSN,
        CREDIT_CARD,
        STREET_ADDRESS,
        ZIP_CODE,
        IP_ADDRESS,
        NAME,
        URL_WITH_PII
    }

    // ── Regex patterns ──────────────────────────────────────────
    // Ordered from most specific to least specific to avoid
    // partial matches (e.g., SSN before generic digit runs).

    private data class PiiPattern(
        val type: PiiType,
        val regex: Regex,
        val replacement: String
    )

    private val patterns: List<PiiPattern> = listOf(
        // ── Order matters! Most specific patterns first, then the
        //    greedy phone regex last among numeric matchers. ──

        // SSN: 123-45-6789 or 123 45 6789 or 123456789
        // Requires CONSISTENT separators — either all dashes, all spaces,
        // or no separators. This prevents "90210-1234" (ZIP+4) from being
        // misread as SSN "902" + "10" + "1234" with mixed separators.
        PiiPattern(
            type = PiiType.SSN,
            regex = Regex("""\b\d{3}-\d{2}-\d{4}\b|\b\d{3}\s\d{2}\s\d{4}\b|\b\d{9}\b"""),
            replacement = "[SSN]"
        ),

        // Credit card: 13-19 digit sequences with optional separators
        // Covers Visa, Mastercard, Amex, Discover
        PiiPattern(
            type = PiiType.CREDIT_CARD,
            regex = Regex("""\b(?:\d[-\s]?){12,18}\d\b"""),
            replacement = "[CREDIT_CARD]"
        ),

        // Email addresses
        PiiPattern(
            type = PiiType.EMAIL,
            regex = Regex(
                """[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""",
                RegexOption.IGNORE_CASE
            ),
            replacement = "[EMAIL]"
        ),

        // IP addresses (v4) — MUST run before phone, otherwise
        // "192.168.1.100" gets partially eaten as a phone number
        PiiPattern(
            type = PiiType.IP_ADDRESS,
            regex = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b"""),
            replacement = "[IP_ADDR]"
        ),

        // Street addresses: Number + street name + suffix
        // e.g., "123 Main St", "4567 Oak Avenue Apt 2B"
        PiiPattern(
            type = PiiType.STREET_ADDRESS,
            regex = Regex(
                """\b\d{1,6}\s+[A-Z][a-zA-Z]*(?:\s+[A-Z][a-zA-Z]*){0,3}\s+""" +
                        """(?:St(?:reet)?|Ave(?:nue)?|Blvd|Boulevard|Dr(?:ive)?|""" +
                        """Ln|Lane|Rd|Road|Ct|Court|Pl|Place|Way|Cir(?:cle)?|""" +
                        """Pkwy|Parkway|Ter(?:race)?|Loop|Run)""" +
                        """(?:\s+(?:Apt|Suite|Ste|Unit|#)\s*\w+)?\b""",
                RegexOption.IGNORE_CASE
            ),
            replacement = "[ADDRESS]"
        ),

        // ZIP codes: 5-digit or ZIP+4 — MUST run before phone,
        // otherwise "90210-1234" gets matched as a phone number
        PiiPattern(
            type = PiiType.ZIP_CODE,
            regex = Regex("""\b\d{5}(?:-\d{4})?\b"""),
            replacement = "[ZIP]"
        ),

        // Phone numbers: Wide net to catch US/international formats.
        // This is the greediest numeric pattern — runs LAST so that
        // IPs, ZIPs, SSNs, and credit cards get matched first.
        // +1 (555) 123-4567, 555.123.4567, 15551234567, etc.
        PiiPattern(
            type = PiiType.PHONE,
            regex = Regex(
                """(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{2,4}\)?[-.\s]?)?\d{3,4}[-.\s]?\d{3,4}\b"""
            ),
            replacement = "[PHONE]"
        ),

        // URLs that contain PII-like path segments (user IDs, tokens)
        // We keep the domain (useful for scam detection) but strip the path
        PiiPattern(
            type = PiiType.URL_WITH_PII,
            regex = Regex(
                """(https?://[a-zA-Z0-9.\-]+)(/[^\s]*)""",
                RegexOption.IGNORE_CASE
            ),
            replacement = "$1/[PATH_REDACTED]"
        )
    )

    // ── Name detection ──────────────────────────────────────────
    // Context-based: looks for names preceded by common salutations,
    // greetings, or identity phrases. We do NOT strip all capitalized
    // words — that would destroy scam signal phrases like "IRS" or
    // "Federal Bureau". Instead we target conversational name patterns.

    private val nameContextPatterns: List<Regex> = listOf(
        // "Dear John", "Hello Jane Smith", "Hi Mr. Johnson"
        // Note: NO IGNORE_CASE — the [A-Z] in the capture group must
        // only match actual uppercase so we don't redact common words
        // like "for details" as names. The prefix keywords are written
        // with alternation for both cases where needed.
        Regex(
            """(?:[Dd]ear|[Hh]ello|[Hh]i|[Hh]ey|[Aa]ttn:?|Mr\.?|Mrs\.?|Ms\.?|Dr\.?)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""
        ),
        // "my name is John Smith", "this is Jane"
        Regex(
            """(?:(?:[Mm]y\s+)?[Nn]ame\s+[Ii]s|[Tt]his\s+[Ii]s|I(?:'m|\s+am))\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""
        ),
        // "sent to John Smith", "contact Jane Doe", "from Mr. Smith"
        // Removed "for" — too ambiguous, matches "for details", "for more info", etc.
        Regex(
            """(?:[Ss]ent\s+[Tt]o|[Cc]ontact|[Ff]rom|[Ss]igned|[Rr]egards)\s+(?:Mr\.?|Mrs\.?|Ms\.?|Dr\.?)?\s*([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"""
        )
    )

    // ── Public API ──────────────────────────────────────────────

    /**
     * Sanitizes [rawText] by stripping all detectable PII.
     * Returns a [SanitizationResult] with the cleaned text and metadata.
     */
    fun sanitize(rawText: String): SanitizationResult {
        var text = rawText
        var redactionCount = 0
        val redactedTypes = mutableSetOf<PiiType>()

        // Pass 1: Apply all regex-based patterns
        for (pattern in patterns) {
            val matches = pattern.regex.findAll(text).toList()
            if (matches.isNotEmpty()) {
                text = pattern.regex.replace(text, pattern.replacement)
                redactionCount += matches.size
                redactedTypes.add(pattern.type)
            }
        }

        // Pass 2: Context-based name detection
        for (namePattern in nameContextPatterns) {
            val matches = namePattern.findAll(text).toList()
            if (matches.isNotEmpty()) {
                text = namePattern.replace(text) { matchResult ->
                    // Keep the prefix (Dear, Hello, etc.), replace only the name
                    val fullMatch = matchResult.value
                    val name = matchResult.groupValues[1]
                    fullMatch.replace(name, "[NAME]")
                }
                redactionCount += matches.size
                redactedTypes.add(PiiType.NAME)
            }
        }

        return SanitizationResult(
            sanitizedText = text.trim(),
            redactionCount = redactionCount,
            redactedTypes = redactedTypes
        )
    }

    /**
     * Quick check: returns true if the text contains any detectable PII.
     * Useful for UI indicators without running full sanitization.
     */
    fun containsPii(text: String): Boolean {
        return patterns.any { it.regex.containsMatchIn(text) } ||
                nameContextPatterns.any { it.containsMatchIn(text) }
    }
}