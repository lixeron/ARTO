# Arto — AI-Powered Spam Shield for Android
# hu
Arto intercepts incoming SMS messages and phone calls from unknown senders, analyzes them with AI, and tells you whether they're scams — all while keeping your personal data private.

## How It Works

```
Incoming SMS/Call
       │
       ▼
┌─────────────┐     Known contact?     ┌──────────┐
│  Receiver    │ ──── Yes ────────────▶ │  Ignore  │
│  (SMS/Call)  │                        └──────────┘
└──────┬──────┘
       │ No
       ▼
┌─────────────┐
│ PII Sanitizer│  Strips names, phones, emails,
│  (on-device) │  SSNs, addresses, credit cards
└──────┬──────┘
       │ Sanitized text only
       ▼
┌─────────────┐
│ Anthropic AI │  Returns: Scam (true/false)
│  (Claude)    │  + 1-sentence explanation
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  User gets   │  "Likely a scam based off
│ notification │   urgency language and
└─────────────┘   suspicious URL."
```

## Privacy First

Arto is designed so **your personal data never leaves your device**.

- Messages from known contacts are **ignored entirely** — zero processing
- Before any text reaches the cloud, a regex-based PII sanitizer strips:
  - Phone numbers, email addresses
  - Names (context-aware detection)
  - SSNs, credit card numbers
  - Street addresses, ZIP codes, IP addresses
- URLs are preserved (domains are scam signals) but paths are redacted
- The AI only ever sees sanitized text like: `"Dear [NAME], your account at [PHONE] has been suspended. Click https://suspicious-site.com/[PATH_REDACTED] to verify."`

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| AI | Anthropic Claude API |
| Backend | Supabase (auth, database, history) |
| Networking | Ktor (OkHttp engine) |
| DI | Koin |
| Min SDK | API 29 (Android 10) |

## Project Structure

```
com.arto.app/
├── data/
│   └── remote/
│       └── AnthropicApiClient.kt    # Ktor HTTP client for Claude
├── di/
│   └── AppModule.kt                 # Koin dependency injection
├── domain/
│   ├── model/
│   │   ├── AnalysisResult.kt        # Scam classification result
│   │   └── MessageInfo.kt           # Message metadata
│   ├── sanitizer/
│   │   └── PiiSanitizer.kt          # Regex-based PII stripping
│   └── usecase/
│       └── AnalyzeMessageUseCase.kt  # Orchestrator: sanitize → API → result
├── receiver/
│   └── SmsReceiver.kt               # SMS broadcast interception
├── service/
│   └── ArtoCallScreeningService.kt   # Call screening via Android API
├── ui/
│   ├── screens/                      # Compose screens
│   ├── components/                   # Reusable UI components
│   ├── navigation/                   # Nav graph
│   └── MainActivity.kt
└── ArtoApplication.kt                # App initialization + Koin startup
```

## Getting Started

### Prerequisites

- Android Studio (Ladybug or newer)
- JDK 17
- An [Anthropic API key](https://console.anthropic.com/)

### Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/lixeron/ARTO.git
   cd ARTO
   ```

2. Add your API keys to `local.properties` (this file is gitignored):
   ```properties
   ANTHROPIC_API_KEY=sk-ant-your-key-here
   SUPABASE_URL=https://yourproject.supabase.co
   SUPABASE_ANON_KEY=eyJ...
   ```

3. Open in Android Studio → Sync Gradle → Build → Run

### Permissions

Arto requests the following permissions at runtime:

| Permission | Why |
|-----------|-----|
| `RECEIVE_SMS` / `READ_SMS` | Intercept incoming text messages |
| `READ_CONTACTS` | Filter out known contacts (privacy gate) |
| `READ_PHONE_STATE` / `READ_CALL_LOG` | Call screening service |
| `INTERNET` | Send sanitized text to AI for analysis |
| `POST_NOTIFICATIONS` | Alert user about detected scams |

## Testing

Run the PII sanitizer unit tests (no device needed):

```bash
./gradlew test
```

25 test cases covering all PII types, edge cases, and scam signal preservation.

## Roadmap

- [x] PII sanitizer with regex-based stripping
- [x] SMS broadcast receiver with contact filtering
- [x] Anthropic API integration for scam classification
- [x] Koin dependency injection
- [ ] Push notifications for scam alerts
- [ ] Jetpack Compose UI (dashboard, history, settings)
- [ ] Supabase integration (user auth, analysis history)
- [ ] Call screening service implementation
- [ ] On-device ML fallback (TFLite) for offline classification
- [ ] CI/CD pipeline with GitHub Actions
- [ ] Fastlane for automated Play Store deployment

## License

This project is currently private/personal. License TBD.

---

*Built by [Ethan Tran](https://github.com/lixeron)*
