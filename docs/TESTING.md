# Testing

## What has actually been run
- On the Poco C51 by the project owner: local and public HTTPS requests (401 without a key, 200 with), `POST /messages` → 202 and real SMS reaching `SENT`, 429 with `Retry-After`, Room persistence, tunnel end to end, foreground service and UI.
- In the build sandbox (Milestone 7): the **pure-logic subset** of the real sources was compiled with `kotlinc 2.2.10` and the real test files were run with a minimal JUnit stand-in: **65 tests passed, 0 failed** (api key, throttle, JSON, phone validation, SIM resolution, state machine, message service incl. concurrency/idempotency/rate limits/destinations, rate policy, ELF/arch check, log parser, inputs, versions, restart guard, notification text). Three deliberate bugs (premium block removed, auth always true, rate limit off by one) were each caught by the tests.
- **Not run anywhere yet:** `ApiRoutesTest` (needs Ktor jars), `SendRequestParserTest` (needs org.json), `RoomMessageStoreTest` (needs a device), and the Android-dependent classes. Run `.\gradlew.bat test` and `.\gradlew.bat connectedDebugAndroidTest` and report the output.

## Automated (PowerShell, project root)
```powershell
.\gradlew.bat test                      # JVM unit + in-process API tests (fake SMS backend, no SMS sent)
.\gradlew.bat connectedDebugAndroidTest # on-device Room persistence test (phone connected)
```
Unit tests cover: key generation/hash/bearer parsing, phone validation, SIM resolution, state transitions, rate policy, failed-auth throttle, request parsing, MessageService (limits, idempotency incl. concurrent duplicates, failure handling), and the real Ktor routes (401/400/411-style/413/415/422/429/502/202, concurrency).
Not covered by automation: real carrier behaviour, foreground-service lifecycle, screen-off operation, tunnel.

## Manual end-to-end — four separate questions
1. **HTTP accepted:** `POST /messages` returns 202 with a `message_id`.
2. **Android accepted the submission:** status becomes `SUBMITTING` (no immediate 502).
3. **Radio/carrier accepted:** `GET /messages/{id}` shows `SENT`.
4. **Delivery receipt:** `DELIVERED` — optional, carrier-dependent. A 202 or `SENT` is not proof of delivery.

Real-SMS tests are manual only: use your own number and respect limits.

## Device checklist for Milestone 4
- Install over the existing build (exercises the Room 1→2 migration; history from before must still be listed).
- 401 without key; 202 with key; same `Idempotency-Key` twice → second is 200 replay and only one SMS arrives.
- Send > per-minute limit quickly → 429 with `Retry-After`.
- Oversized body → 413. Wrong `Content-Type` → 415.

## Device checklist for Milestone 6 (foreground service)
1. Home → **Start gateway** (accept the notification prompt). A notification "SimBridge gateway — API on 127.0.0.1:8080 · tunnel connected (n)" appears.
2. Lock the screen, wait 5 minutes, then from another network: `curl.exe -i https://sms-api.YOURDOMAIN.com/api/v1/health` → 401, and with the key → 200.
3. Swipe the app out of recent apps. Check the notification is still there and the URL still answers. Diagnostics → Service events should list "App swiped away…".
4. Send a real SMS through the public URL; Messages tab shows it; status reaches SENT.
5. Home → **Stop gateway** (or notification **Stop**): notification disappears, public URL stops answering, events list "Stopped by user".
6. Force-stop the app in Android settings, reopen it: Diagnostics must show "New app process started while the gateway was marked running…" only if it had been running (an unclean end), and **not** after a clean stop.
7. Turn on "Start after reboot", reboot the phone, wait 2 minutes, check the notification and URL. MIUI may block this unless Autostart is enabled for the app — the event log will say "refused" if Android blocked it.
8. Leave it running for 30 minutes and then several hours with the screen off; note when (if) it dies. Record the time from Service events.

Automated: `RestartGuardTest` covers the restart-loop guard. The service itself is not unit-tested.
