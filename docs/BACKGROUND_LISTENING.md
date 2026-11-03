# Background listening (wake phrase) — how it works and how to turn it on

## Install (fixes "App not installed")
The old APKs were signed with a different random debug key on every build. Android refuses to update an app
whose signature changed, which shows "App not installed". From version 1.2.0 the APK is always signed with the
same committed key (SHA-256 `31:4A:D8:FB:…:E8:A5:B4`; CI checks this on every build).
**One-time step:** uninstall any older RAZA AI first (Settings > Apps > RAZA AI > Uninstall), then install the new APK.
Later updates install over it. If Play Protect blocks it: Play Store > profile > Play Protect > Settings > turn off
"Scan apps with Play Protect" temporarily, install, turn it back on.

## Turn on background listening (Android 13+)
1. Open RAZA AI > Settings > Wake word card. Tap **Allow microphone** (choose "While using the app" or "Allow").
2. Android 13+: allow **Notifications** for RAZA AI (needed to show the "listening" notification).
3. Tap **START LISTENING** while RAZA AI is on screen. Android only lets a microphone service start while the app is visible.
4. Check the status line says **LISTENING** and "Microphone ON". The notification "RAZA AI is listening" must be visible.
5. Tap **Allow background use (battery)** and choose Unrestricted / Don't optimise. On some brands (Xiaomi, Oppo, Vivo,
   Samsung) also enable Autostart / "Never sleep" for RAZA AI in the phone's battery settings.
6. Press Home or swipe RAZA AI out of Recent Apps: the notification stays and listening continues.
7. Say your wake phrase. If the app is closed, a notification appears; tap it to continue. Controls: START / STOP / RESTART.

## Honest limits
- **Force stop** (Settings > Apps > Force stop, or some cleaner apps) ends the service. Android does not allow apps to survive it.
- After a **reboot** or after Android kills the service, Android 14+ does not allow a background app to open the microphone.
  RAZA shows a "Not listening — tap to turn on" notification instead of pretending. It never starts the mic silently.
- Wake detection uses Android's speech recogniser (not a dedicated hotword chip): it uses noticeable battery.
  On Android 13+ with the language pack installed it runs on the phone; otherwise Android's speech service may use the network.
  The status line shows which one is active. RAZA does not record, save or upload audio; it only looks for the wake phrase.
- The status shown in the app comes from the real service state; if the service stops, the UI says STOPPED, not LISTENING.

## What was tested
CI runs 165 unit tests and 6 instrumented tests on an Android 13 (API 33) emulator: microphone foreground-service type,
permissions, notification, service survives removal from Recents, Stop/Restart, boot receiver posts a prompt and does not
start the mic. An emulator is not a phone: real-device testing of battery behaviour, OEM task killers and real microphone
wake-phrase detection is still needed and has not been done by the developer.
