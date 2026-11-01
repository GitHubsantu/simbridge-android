# Testing

## What has actually been run
- On the Poco C51 by the project owner: local and public HTTPS requests (401 without a key, 200 with), `POST /messages` → 202 and real SMS reaching `SENT`, 429 with `Retry-After`, Room persistence, tunnel end to end, foreground service and UI. The 64-bit tunnel build is reported working on a 64-bit phone.
- In the build sandbox: the **pure-logic subset** of the real sources was compiled with `kotlinc 2.2.10` and the real test files were run with a minimal JUnit stand-in: **66 tests passed, 0 failed** (including the real ELF headers of both the 32-bit and 64-bit cloudflared builds) (api key, throttle, JSON, phone validation, SIM resolution, state machine, message service incl. concurrency/idempotency/rate limits/destinations, rate policy, ELF/arch check, log parser, inputs, versions, restart guard, notification text). Three deliberate bugs (premium block removed, auth always true, rate limit off by one) were each caught by the tests.
- Release build: `tools\build-release.ps1` produced the 32-bit, 64-bit and universal APKs, all signed, each verified to contain `libcloudflared.so` byte-identical to the verified file.
- 1.1 adds `AppUpdaterTest` (version comparison, APK selection, checksum-file parsing, UTF-16/BOM decoding). It has **not been run by anyone yet**: run `.\gradlew.bat test` and record the result before releasing.
- **Not run anywhere yet:** `ApiRoutesTest` (needs Ktor jars), `SendRequestParserTest` (needs org.json), `RoomMessageStoreTest` (needs a device), and the Android-dependent classes. Run `.\gradlew.bat test` and `.\gradlew.bat connectedDebugAndroidTest` and record the output.

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

## Release checklist (before publishing a release)
Run this on a phone that matches each APK you intend to publish. Tick every line; record anything that fails.

1. **Build:** `.\gradlew.bat clean`, then `tools\build-release.ps1` finishes with "All APKs verified". Note the SHA-256 lines it prints.
2. **Signatures:** `apksigner verify --verbose --print-certs` on each APK shows v1, v2 and v3 `true`, and the same certificate SHA-256 for all three.
3. **Install:** uninstall any debug build, then `adb install -r` the APK that fits the phone (32-bit APK on a `zygote32` phone, 64-bit APK on a 64-bit phone, universal on either). The wrong ABI must be refused with `INSTALL_FAILED_NO_MATCHING_ABIS`, not install and misbehave.
4. **Not debuggable:** `adb shell dumpsys package in.imdevops.simbridge | findstr /i debuggable` prints nothing for the release build.
5. **First run:** grant permissions, generate a key, set the country-prefix allow-list.
6. **Tunnel binary:** Tunnel → *Check cloudflared binary* shows `Binary: OK (ARM, 32-bit/64-bit)` and `Ran OK (exit 0)` with the version named in the release notes (the same version appears under "Bundled in this app").
7. **Tunnel end to end:** enter hostname and token, Start; *Test public URL* returns the 401 check; from another network `curl.exe -i https://sms-api.YOURDOMAIN.com/api/v1/health` → 401 and with the key → 200.
8. **Send:** one real SMS to your own number through the public URL reaches `SENT`.
9. **Update path:** install the new APK over the previous release with `adb install -r` (same key, higher `versionCode`; reinstalling the same `versionCode` also works): settings, key hash, tunnel token and history survive.
10. **Upload hygiene:** `git status` shows no `keystore.properties`, `*.jks`, `jniLibs/` or `dist/`; the release folder holds only the APKs and `SHA256SUMS.txt`; checksums were generated from the exact uploaded files.

## In-app update checklist (1.1 and later)
The updater looks at the repository's **latest** release, so it can only be tested for real when a release newer than the installed version exists.

1. Install release *N* on the phone (not a debug build; a debug-signed app cannot be updated). Start the gateway and the tunnel.
2. Publish release *N+1* (higher `versionCode` and `versionName`, assets and `SHA256SUMS.txt` as described in [RELEASE.md](RELEASE.md)).
3. *Diagnostics → App update → Check for update*: it shows the new tag, the size and the first lines of the release notes.
4. *Download and verify*: progress runs to 100 % and the card says the checksum and signing key are verified.
5. *Install now*: on first use Android asks you to allow SimBridge to install apps; allow it, go back, press *Install now* again. Android shows its update screen (or, on Android 12+, may apply the update without one).
6. After the update: the version on the card is the new one; API key "Set", tunnel token still saved, Messages history still listed; if the gateway was running it is running again (Service events show "Update installed" and the restart); *Check for update* now says you have the latest version.
7. Negative checks: with no network, *Check for update* shows "Could not check…" and nothing else happens; cancelling Android's screen records "Update cancelled" under Service events and the gateway stays as it was.

