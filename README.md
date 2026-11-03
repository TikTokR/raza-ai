# RAZA AI

A personal voice assistant for Android (Urdu + English) with a small secure backend.
Package `com.raza.ai`, Kotlin, minSdk 26, target/compile SDK 35, JDK 17.

## What it does
- **Assistant, Dashboard, Tasks, Settings** screens (animated orb, real mic-level waveform while listening).
- **Voice in/out**: Android speech recognition (English or Urdu, one language per listen) and text-to-speech (lowered pitch, a male-named voice if the phone has one, speech language chosen per reply by script).
- **Wake phrase** ("hey raza" by default, editable) via a microphone foreground service with start / stop / pause.
- **Device control, always confirmed**: time/date/battery, open apps and settings, volume, media keys, alarms, call and SMS. Every device-changing command needs YES/NO; call/SMS also need the phone's own screen-lock check when a lock is set. Call/SMS only open the dialer/composer; you press call/send.
- **Online / offline**: offline it handles local commands, tasks and notes. Online it asks your backend (`/api/v1/assistant`, `/api/v1/command-plan`). It never pretends the cloud answered when it did not.
- **Memory**: tasks, "remember that..." notes, chat history, and an action log; all viewable and deletable in Settings. Notes never accept passwords, PINs, OTPs or card numbers.

## Device-control commands (English / Urdu examples)
| Say | What happens | Confirmation |
|---|---|---|
| "send whatsapp message to Ali saying I'm late" / "علی کو واٹس ایپ پر میسج بھیجو کہ ..." | Finds Ali in Contacts (never guesses between two people), opens the chat with the text, presses Send if Accessibility is on | YES/NO + screen lock |
| "play despacito on youtube" / "یوٹیوب پر ... چلاؤ" | Opens YouTube search, taps the first video if Accessibility is on | YES/NO |
| "open tiktok", then "next video" / "previous video" / "scroll down" | Opens the app; swipes via Accessibility | open: YES/NO; swipes run at once |
| "go back", "go home", "recent apps" | Android global actions via Accessibility | none |
| "call 0300..." | Opens the dialer (or places the call if you enable direct calls) | YES/NO + screen lock |
| "set a timer for 10 minutes", "remind me in 2 hours to ...", "remind me at 7:30 pm to ...", "set alarm for 7:30" | Clock app timer/alarm | YES/NO |
| "volume up", "play music", "open wifi settings", "open <app>" | Android APIs | YES/NO |

## Test on your phone (nothing below has been run on a device yet)
1. Settings > Permissions: grant Microphone, Contacts. Optional: Phone (direct calls).
2. Settings > Accessibility > open accessibility settings > enable RAZA AI. If greyed out: App info > three dots > Allow restricted settings.
3. Say or type each command above with a real contact and watch the result text; RAZA only claims what Android or the screen confirmed.
4. Report anything that fails with the exact on-screen message.

## Build
```
gradle :app:testDebugUnitTest      # unit tests (+ API integration tests if RAZA_IT_URL/RAZA_IT_TOKEN are set)
gradle :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```
CI (`.github/workflows/android.yml`) runs the backend tests, starts the real backend, runs all Android unit and integration tests, builds the debug APK and uploads it as the `RAZA-AI-DEBUG-APK` artifact.
The default backend address can be baked in with `-PrazaApiBaseUrl=https://your-backend.example.com`.

## Install and first run
1. Install the APK from the CI artifact (allow "install unknown apps" for your file manager or browser).
2. Open Settings > Online / Offline: enter your backend address (https only) and access token, then "Save & test connection". Skip this to use offline features only.
3. Tap MIC once to grant the microphone (and notifications on Android 13+).
4. Optional: Settings > Wake word > switch on voice activation.

## Backend
See `backend/API.md` and `backend/.env.example`. Zero-dependency Node 22 server with bearer-token auth (user/admin roles), validation, rate limiting, no body/secret logging. The AI provider key exists only in the server's environment, never in the app.

## Honest limits
- **Owner voice verification is not available.** Android has no speaker-verification API and a recording could fool one. The screen-lock check is used for calls and texts instead.
- The wake phrase uses Android's speech recogniser, not a hotword chip: more battery, can miss in noise, may be killed by aggressive phone battery managers, stops after reboot, cannot run while the phone is rebooting or the user force-stops the app. A notification and Android's mic indicator are always shown.
- RAZA cannot unlock the phone, send an SMS or place a call by itself, and uses no Accessibility or hidden APIs.
- Speech recognition language is one at a time; Urdu TTS/STT need Urdu voice data installed on the phone.
- The Android layer (service, session, executor, UI) has **not** been run on a device or emulator. See the test report below.

## Test status
JVM unit tests cover the command parser, confirmation rules, API client errors, wake-state logic, tasks/notes/persistence, language and voice helpers, and the engine. `BackendIntegrationTest` makes real HTTP calls from the Android `ApiClient` to the real Node backend in CI. Not covered by automated tests: microphone/recognition, TTS output, the foreground service, intents launched into other apps, and the screens' appearance. These need a device.
