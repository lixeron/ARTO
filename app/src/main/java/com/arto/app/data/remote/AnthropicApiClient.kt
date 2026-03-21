package com.arto.app.data.remote

import com.arto.app.domain.model.AnalysisResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AnthropicApiClient — Sends PII-sanitized message text to Claude for
 * scam classification and returns a structured [AnalysisResult].
 *
 * Design notes:
 *   - Uses Ktor OkHttp engine (matches your Gradle config)
 *   - Sends a tightly constrained system prompt so Claude returns
 *     consistent JSON we can parse reliably
 *   - Timeout and error handling produce fallback results rather than
 *     crashing the receiver pipeline
 *   - The API key is injected via constructor (sourced from BuildConfig
 *     through the Koin DI module we'll wire up next)
 */
class AnthropicApiClient(private val apiKey: String) {

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val MODEL = "claude-sonnet-4-20250514"
        private const val MAX_TOKENS = 256
    }

    // ── Ktor HTTP client ────────────────────────────────────────

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.NONE  // Set to BODY for debugging
        }
    }

    // ── Anthropic API request/response models ───────────────────
    // These mirror the Anthropic Messages API schema exactly.

    @Serializable
    private data class ApiRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<ApiMessage>
    )

    @Serializable
    private data class ApiMessage(
        val role: String,
        val content: String
    )

    @Serializable
    private data class ApiResponse(
        val content: List<ContentBlock>
    )

    @Serializable
    private data class ContentBlock(
        val type: String,
        val text: String? = null
    )

    // ── The classification response we ask Claude to return ─────

    @Serializable
    private data class ClassificationResponse(
        @SerialName("is_scam") val isScam: Boolean,
        val explanation: String,
        val confidence: Double
    )

    // ── System prompt ───────────────────────────────────────────
    // Tightly constrained to produce reliable, parseable output.

    private val systemPrompt = """
        You are a spam/scam message classifier for a mobile security app.
        Your job is to analyze SMS message text and determine if it is a scam.

        The message text has been pre-sanitized: personal information like
        phone numbers, emails, and names have been replaced with placeholders
        like [PHONE], [EMAIL], [NAME], etc. This is intentional for privacy.

        Analyze the message for scam indicators such as:
        - Urgency or fear tactics ("act now", "account suspended", "warrant")
        - Requests for personal information or money
        - Suspicious URLs or domains
        - Impersonation of government agencies (IRS, SSA) or companies
        - "Too good to be true" offers (lottery wins, free prizes)
        - Grammar/spelling errors typical of scam messages

        You MUST respond with ONLY a valid JSON object in this exact format,
        with no additional text, markdown, or explanation outside the JSON:
        {
            "is_scam": true or false,
            "explanation": "This is likely a scam based off [reason]." or "This is likely not a scam based off [reason].",
            "confidence": 0.0 to 1.0
        }
    """.trimIndent()

    // ── Public API ──────────────────────────────────────────────

    /**
     * Analyzes [sanitizedText] for scam indicators.
     *
     * Returns an [AnalysisResult] on success, or a fallback result
     * on any failure (network error, parse error, etc.).
     */
    suspend fun analyzeMessage(sanitizedText: String): AnalysisResult {
        return try {
            val request = ApiRequest(
                model = MODEL,
                maxTokens = MAX_TOKENS,
                system = systemPrompt,
                messages = listOf(
                    ApiMessage(
                        role = "user",
                        content = "Analyze this message for scam indicators:\n\n$sanitizedText"
                    )
                )
            )

            val response: ApiResponse = client.post(API_URL) {
                contentType(ContentType.Application.Json)
                headers {
                    append("x-api-key", apiKey)
                    append("anthropic-version", API_VERSION)
                }
                setBody(request)
            }.body()

            // Extract the text content from Claude's response
            val responseText = response.content
                .firstOrNull { it.type == "text" }
                ?.text
                ?: return AnalysisResult.fallback(sanitizedText, "Empty API response")

            // Parse the JSON classification
            parseClassification(responseText, sanitizedText)

        } catch (e: Exception) {
            AnalysisResult.fallback(sanitizedText, e.message ?: "Unknown error")
        }
    }

    /**
     * Parses Claude's JSON response into an [AnalysisResult].
     *
     * Handles minor formatting issues (markdown fences, leading text)
     * that LLMs sometimes produce despite instructions.
     */
    private fun parseClassification(
        responseText: String,
        sanitizedText: String
    ): AnalysisResult {
        return try {
            // Strip markdown code fences if present
            val cleaned = responseText
                .replace("```json", "")
                .replace("```", "")
                .trim()

            // Find the JSON object in case there's any preamble text
            val jsonStart = cleaned.indexOf('{')
            val jsonEnd = cleaned.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1) {
                return AnalysisResult.fallback(sanitizedText, "No JSON found in response")
            }

            val jsonStr = cleaned.substring(jsonStart, jsonEnd + 1)

            val parsed = Json { ignoreUnknownKeys = true }
                .decodeFromString<ClassificationResponse>(jsonStr)

            AnalysisResult(
                isScam = parsed.isScam,
                explanation = parsed.explanation,
                confidence = parsed.confidence.coerceIn(0.0, 1.0),
                sanitizedBody = sanitizedText
            )
        } catch (e: Exception) {
            AnalysisResult.fallback(sanitizedText, "Failed to parse AI response: ${e.message}")
        }
    }

    /**
     * Closes the HTTP client. Call when the app is being destroyed
     * or the client is no longer needed.
     */
    fun close() {
        client.close()
    }
}