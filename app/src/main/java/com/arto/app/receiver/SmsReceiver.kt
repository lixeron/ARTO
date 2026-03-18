package com.arto.app.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.arto.app.domain.sanitizer.PiiSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SmsReceiver — Intercepts incoming SMS messages and triggers spam analysis
 * for messages from unknown (non-contact) senders.
 *
 * Lifecycle:
 *   1. Android delivers SMS_RECEIVED broadcast
 *   2. We extract sender + body from the PDU bundle
 *   3. Contact lookup: if the sender is in the user's contacts → ignore
 *   4. PII sanitization: strip personal data from the message body
 *   5. Hand off sanitized text to the analysis pipeline (next step to build)
 *
 * Important Android constraints:
 *   - onReceive() runs on the main thread with a ~10s deadline
 *   - goAsync() extends the deadline to ~30s for background work
 *   - For anything longer (API calls), we'll delegate to a WorkManager
 *     job or foreground service in a later step
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ArtoSmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Extract SMS messages from the raw PDU bundle.
        // Telephony.Sms.Intents.getMessagesFromIntent handles multi-part
        // messages and different PDU formats (3GPP, 3GPP2) automatically.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group message parts by sender (multi-part SMS comes as
        // separate PDUs that share the same originating address).
        val groupedBySender: Map<String, String> = messages
            .groupBy { it.displayOriginatingAddress ?: "unknown" }
            .mapValues { (_, parts) ->
                parts.joinToString("") { it.displayMessageBody ?: "" }
            }

        // Use goAsync() so we can do the contact lookup off the main
        // thread without hitting the 10-second ANR deadline.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                for ((sender, body) in groupedBySender) {
                    if (body.isBlank()) continue

                    Log.d(TAG, "Incoming SMS from: $sender")

                    // ── Privacy gate: skip known contacts ────────
                    if (isKnownContact(context, sender)) {
                        Log.d(TAG, "Sender is a known contact — ignoring.")
                        continue
                    }

                    // ── PII sanitization ────────────────────────
                    val sanitized = PiiSanitizer.sanitize(body)
                    Log.d(TAG, buildString {
                        append("Sanitized message (${sanitized.redactionCount} redactions")
                        if (sanitized.redactedTypes.isNotEmpty()) {
                            append(": ${sanitized.redactedTypes.joinToString()}")
                        }
                        append("): ${sanitized.sanitizedText}")
                    })

                    // ── Hand off to analysis pipeline ───────────
                    // TODO: This is where we'll call AnalyzeMessageUseCase
                    // in the next step. For now, log the sanitized output
                    // so you can verify the pipeline works end-to-end
                    // by sending yourself a test SMS from another number.
                    handleUnknownMessage(
                        context = context,
                        sender = sender,
                        sanitizedBody = sanitized.sanitizedText,
                        redactionCount = sanitized.redactionCount
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS", e)
            } finally {
                // CRITICAL: Always call finish() or the system will ANR.
                pendingResult.finish()
            }
        }
    }

    /**
     * Checks whether [phoneNumber] exists in the user's contacts.
     *
     * Uses ContactsContract.PhoneLookup which handles format normalization
     * internally — so "+1 (555) 123-4567" will match a contact stored
     * as "5551234567". This is essential because SMS originating addresses
     * come in wildly inconsistent formats across carriers.
     *
     * Returns false (not a contact) if the READ_CONTACTS permission
     * hasn't been granted, erring on the side of analysis rather than
     * silently ignoring a potential scam.
     */
    private fun isKnownContact(context: Context, phoneNumber: String): Boolean {
        // Guard: don't crash if the user revoked the permission at runtime
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CONTACTS permission not granted — treating as unknown.")
            return false
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.moveToFirst()  // true if at least one contact matches
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed for number", e)
            false  // Fail open: analyze the message rather than skip it
        }
    }

    /**
     * Placeholder for the analysis handoff. This will be replaced with
     * a call to AnalyzeMessageUseCase → AnthropicApiClient in the next step.
     *
     * For now it logs the sanitized output so you can validate the
     * receiver + sanitizer pipeline with `adb logcat -s ArtoSmsReceiver`.
     */
    private fun handleUnknownMessage(
        context: Context,
        sender: String,
        sanitizedBody: String,
        redactionCount: Int
    ) {
        Log.i(TAG, buildString {
            appendLine("═══ Unknown sender detected ═══")
            appendLine("  Sender:     $sender")
            appendLine("  Redactions: $redactionCount")
            appendLine("  Sanitized:  $sanitizedBody")
            appendLine("  Next step:  Send to Anthropic API for analysis")
            append("════════════════════════════════")
        })

        // TODO (next step): Wire up to AnalyzeMessageUseCase
        // val result = analyzeMessageUseCase(sanitizedBody)
        // notificationManager.showScamAlert(sender, result)
    }
}