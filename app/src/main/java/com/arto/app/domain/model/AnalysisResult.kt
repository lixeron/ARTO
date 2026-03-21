package com.arto.app.domain.model

/**
 * The result of an AI spam analysis on a single message.
 *
 * Returned by [com.arto.app.domain.usecase.AnalyzeMessageUseCase] after
 * the sanitized message text has been evaluated by the Anthropic API.
 */
data class AnalysisResult(
    /** True if the AI classified this message as a scam. */
    val isScam: Boolean,

    /**
     * Human-readable explanation, e.g.:
     *   "This is likely a scam based off urgency language and a suspicious URL."
     *   "This is likely not a scam based off a standard delivery notification."
     */
    val explanation: String,

    /**
     * AI confidence score from 0.0 to 1.0.
     * Useful for UI indicators (red/yellow/green) and for deciding
     * whether to auto-block vs. just warn the user.
     */
    val confidence: Double,

    /** The sanitized text that was actually sent to the AI. */
    val sanitizedBody: String
) {
    companion object {
        /**
         * Fallback result when the API call fails (network error, timeout, etc.).
         * Defaults to NOT flagging as scam to avoid false positives when
         * the service is unreachable.
         */
        fun fallback(sanitizedBody: String, error: String) = AnalysisResult(
            isScam = false,
            explanation = "Analysis unavailable: $error",
            confidence = 0.0,
            sanitizedBody = sanitizedBody
        )
    }
}