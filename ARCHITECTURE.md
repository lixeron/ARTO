# Arto — Architecture & Design Decisions

This document explains the technical decisions behind Arto's architecture for anyone contributing to or reviewing the codebase.

## Core Pipeline

Every incoming message follows the same flow:

```
Entry Point → Contact Gate → PII Sanitizer → AI Classification → User Notification
```

### 1. Entry Points

**SMS:** `SmsReceiver` (BroadcastReceiver) listens for `SMS_RECEIVED_ACTION`. Uses `goAsync()` to extend the broadcast timeout from 10s to ~30s, which is enough for the contact lookup + API call. Multi-part SMS messages are reassembled by grouping PDUs by originating address.

**Calls:** `ArtoCallScreeningService` (CallScreeningService) receives incoming call metadata from the OS. Requires the user to set Arto as their default Caller ID & Spam app. Available on API 29+.

### 2. Contact Gate (Privacy Boundary)

Before any processing happens, the sender's number is checked against the user's contacts using `ContactsContract.PhoneLookup`. This API handles phone number format normalization internally, so `+1 (555) 123-4567` matches a contact stored as `5551234567`.

**Design choice:** If `READ_CONTACTS` permission is revoked, we fail open (treat as unknown). Rationale: better to over-analyze than silently skip a scam.

### 3. PII Sanitizer

`PiiSanitizer` is a pure Kotlin object with zero Android dependencies. It runs two passes:

**Pass 1 — Regex patterns (ordered by specificity):**
1. SSN (consistent separators required)
2. Credit cards (13-19 digits)
3. Email addresses
4. IPv4 addresses
5. Street addresses (number + name + suffix)
6. ZIP codes (5-digit and ZIP+4)
7. Phone numbers (greediest — runs last)
8. URLs (domain preserved, path stripped)

**Pass 2 — Context-based name detection:**
Strips names only when preceded by salutations ("Dear", "Hello"), identity phrases ("my name is"), or attribution words ("from", "signed"). Does NOT strip all capitalized words — that would destroy scam signals like "IRS" or "Federal Agent".

**Key design decisions:**
- Pattern ordering prevents the greedy phone regex from eating IPs and ZIPs
- SSN regex requires consistent separators (all dashes, all spaces, or none) to avoid matching ZIP+4 codes
- Name patterns do NOT use IGNORE_CASE — `[A-Z]` must match actual uppercase to avoid false positives on common words
- URL domains are preserved because domains like `irs-gov-verify.xyz` are strong scam signals
- False positives (over-stripping) are acceptable; false negatives (leaking PII) are not

### 4. AI Classification

`AnthropicApiClient` sends the sanitized text to Claude with a constrained system prompt that enforces JSON output:

```json
{
    "is_scam": true,
    "explanation": "This is likely a scam based off urgency language and a suspicious URL.",
    "confidence": 0.85
}
```

The response parser handles common LLM quirks (markdown fences, preamble text) by extracting the JSON object from anywhere in the response.

**Fallback behavior:** On any failure (network error, parse error, timeout), returns `isScam = false` with an explanation noting the failure. We never false-positive on infrastructure issues.

### 5. Orchestration

`AnalyzeMessageUseCase` is the single entry point that both `SmsReceiver` and `ArtoCallScreeningService` call. It runs: sanitize → API call → structured result. This keeps the receivers thin and the business logic testable.

## Dependency Injection

Koin was chosen over Hilt/Dagger for simplicity — no annotation processing, no code generation, and good enough for this project's scope.

- `AnthropicApiClient` — singleton (one HTTP client for the app lifetime)
- `AnalyzeMessageUseCase` — factory (new lightweight instance per use)

`SmsReceiver` implements `KoinComponent` to inject dependencies in a BroadcastReceiver context (where constructor injection isn't possible).

## Security

- API keys are loaded from `local.properties` → `BuildConfig` fields. Never committed to git.
- The Anthropic API key lives on-device. For production, this should be proxied through a backend (Supabase Edge Function) so the key never ships in the APK.
- `allowBackup="false"` in the manifest prevents Android backup from leaking local data.

## Testing Strategy

- **PII Sanitizer:** 25 JVM unit tests covering every PII type, edge cases, and scam signal preservation. Runs on `./gradlew test` with no device needed.
- **API Client:** Integration tests against the live API (planned).
- **Receivers:** Instrumented tests on device/emulator (planned).
