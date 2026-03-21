package com.arto.app.domain.usecase

import com.arto.app.data.remote.AnthropicApiClient
import com.arto.app.domain.model.AnalysisResult
import com.arto.app.domain.model.MessageInfo
import com.arto.app.domain.sanitizer.PiiSanitizer

/**
 * AnalyzeMessageUseCase — The core pipeline orchestrator.
 *
 * Flow:
 *   1. Accept raw message text + sender from the receiver
 *   2. Sanitize the text (strip PII via regex)
 *   3. Send sanitized text to the Anthropic API for classification
 *   4. Return a structured AnalysisResult
 *
 * This is the single entry point that SmsReceiver and
 * ArtoCallScreeningService both call into.
 */
class AnalyzeMessageUseCase(
    private val apiClient: AnthropicApiClient
) {
    /**
     * Analyzes a raw message from an unknown sender.
     *
     * @param sender   The phone number that sent the message
     * @param rawBody  The original, unsanitized message text
     * @return [AnalysisResult] with scam classification and explanation
     */
    suspend fun execute(sender: String, rawBody: String): Pair<MessageInfo, AnalysisResult> {
        // Step 1: Sanitize PII
        val sanitized = PiiSanitizer.sanitize(rawBody)

        // Step 2: Build the domain model (rawBody stays on-device)
        val messageInfo = MessageInfo(
            sender = sender,
            rawBody = rawBody,
            sanitizedBody = sanitized.sanitizedText,
            source = MessageInfo.Source.SMS
        )

        // Step 3: Send sanitized text to Claude for classification
        val analysisResult = apiClient.analyzeMessage(sanitized.sanitizedText)

        return Pair(messageInfo, analysisResult)
    }
}