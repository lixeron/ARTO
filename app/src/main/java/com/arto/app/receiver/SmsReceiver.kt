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
import com.arto.app.domain.usecase.AnalyzeMessageUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * SmsReceiver — Intercepts incoming SMS messages and triggers spam analysis
 * for messages from unknown (non-contact) senders.
 *
 * Lifecycle:
 *   1. Android delivers SMS_RECEIVED broadcast
 *   2. We extract sender + body from the PDU bundle
 *   3. Contact lookup: if the sender is in the user's contacts → ignore
 *   4. Hand off to AnalyzeMessageUseCase (sanitize → API → result)
 *   5. Log the result (notification UI comes in a later step)
 *
 * Important Android constraints:
 *   - onReceive() runs on the main thread with a ~10s deadline
 *   - goAsync() extends the deadline to ~30s for background work
 *   - Typical API call completes in 2-5s, well within the deadline
 *   - For production resilience, migrate to WorkManager (future step)
 */
class SmsReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "ArtoSmsReceiver"
    }

    // Injected by Koin — resolves from AppModule
    private val analyzeMessageUseCase: AnalyzeMessageUseCase by inject()

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

        // Use goAsync() so we can do the contact lookup + API call off
        // the main thread without hitting the 10-second ANR deadline.
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

                    // ── Run the full analysis pipeline ──────────
                    // AnalyzeMessageUseCase handles:
                    //   1. PII sanitization (regex stripping)
                    //   2. Anthropic API call (scam classification)
                    //   3. Structured result with explanation
                    val (messageInfo, analysisResult) = analyzeMessageUseCase.execute(
                        sender = sender,
                        rawBody = body
                    )

                    // ── Log the result ───────────────────────────
                    Log.i(TAG, buildString {
                        appendLine("═══ Arto Analysis Complete ═══")
                        appendLine("  Sender:      $sender")
                        appendLine("  Is Scam:     ${analysisResult.isScam}")
                        appendLine("  Confidence:  ${(analysisResult.confidence * 100).toInt()}%")
                        appendLine("  Explanation: ${analysisResult.explanation}")
                        appendLine("  Sanitized:   ${messageInfo.sanitizedBody}")
                        append("══════════════════════════════")
                    })

                    // TODO (next step): Show notification to user
                    // notificationHelper.showScamAlert(sender, analysisResult)

                    // TODO (future): Save to Supabase for history
                    // analysisRepository.save(messageInfo, analysisResult)
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
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed for number", e)
            false
        }
    }
}