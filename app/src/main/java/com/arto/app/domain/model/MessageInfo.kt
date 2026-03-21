package com.arto.app.domain.model

/**
 * Represents an incoming message (SMS or call) that needs analysis.
 *
 * This is the input to the analysis pipeline. The [rawBody] is only
 * used on-device for display — it is NEVER sent to the cloud.
 * Only [sanitizedBody] leaves the device.
 */
data class MessageInfo(
    /** Phone number of the sender (as delivered by the carrier). */
    val sender: String,

    /** Original message body — stays on-device only. */
    val rawBody: String,

    /** PII-stripped message body — this is what gets sent to the AI. */
    val sanitizedBody: String,

    /** Unix timestamp (millis) when the message was received. */
    val timestamp: Long = System.currentTimeMillis(),

    /** Whether this came from SMS or a phone call. */
    val source: Source = Source.SMS
) {
    enum class Source { SMS, CALL }
}