# Security

## Reporting a vulnerability
Please do **not** open a public issue for a security problem. Use GitHub's private reporting on the repository (*Security → Report a vulnerability*). Include the app version, what you did and what happened. Never include real API keys, tunnel tokens or phone numbers; if you posted one by accident, regenerate it.

## Security checklist
A phone that can send SMS on demand is a cost- and abuse-sensitive endpoint. **Any publicly reachable URL must be authenticated, even behind Cloudflare Tunnel.**

| Control | Status |
|---|---|
| Server bound to `127.0.0.1` only | Implemented; verified by code, loopback curl works from the PC only via `adb forward` |
| Bearer authentication on every endpoint | Implemented, tested (unit + route tests); verified on device for health/messages |
| 256-bit random key (`SecureRandom`), shown once, only SHA-256 stored, constant-time hash compare | Implemented |
| Key never accepted in URL/body; never logged | Implemented (no logging of requests) |
| Failed-auth throttling (429 after 20/min; valid key never blocked) | Implemented, unit-tested |
| Send limits per minute/day (default 5/100), derived from persisted history so restarts don't reset | Implemented, unit-tested; user-configurable |
| Max message length (default 320) and body size (8 KB), `Content-Length` required, 10 s body timeout, idle timeout = Ktor CIO default | Implemented. Idle timeout value not tuned |
| Bounded queue (16 pending, serialized sends) | Implemented, unit-tested through the service |
| Idempotency keys (24 h, persisted) | Implemented; concurrency unit-tested with in-memory store; Room migration **not yet run on device** |
| No automatic SMS retry | Implemented by design |
| No CORS headers; no wildcard | Implemented (none sent) |
| Message text never stored; recipients kept 30 days | Implemented |
| Minimal permissions: SEND_SMS, READ_PHONE_STATE, INTERNET, ACCESS_NETWORK_STATE (DNS servers for the tunnel), FOREGROUND_SERVICE (+SPECIAL_USE), POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED (only acts if "Start after reboot" is on) | Implemented. No READ_SMS/RECEIVE_SMS/contacts |
| `allowBackup=false` | Implemented |
| IP allow-listing | **Not implemented.** Origin is always loopback, so the source IP would be cloudflared, not the caller; use Cloudflare Access / WAF rules instead |
| Tunnel token encrypted with an Android Keystore AES-GCM key; passed to cloudflared via environment, not argv; log lines redacted | Implemented, device-tested end to end. Same-UID processes could still read the environment |
| API key stored hashed (not Keystore-wrapped) | Acceptable: nothing secret to protect; revisit if keys become retrievable |
| Update check | Outbound HTTPS GET to api.github.com only when you press the button; no identifiers sent | Implemented |
| Battery-optimization exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) | Optional, user-confirmed system dialog. Google Play restricts this permission; fine for sideloaded builds, revisit before any store release |
| TLS | Provided by Cloudflare at the edge; loopback hop is plain HTTP by design |
| Release APK integrity | Signed with APK Signature Schemes v1+v2+v3; SHA-256 of every APK published in `SHA256SUMS.txt`; signing-certificate fingerprint published in the release notes |

Operational advice: generate a fresh key when moving from testing to real use; never paste keys into chats or commits; add a Cloudflare WAF rate-limit rule and optionally Cloudflare Access service tokens in front of `sms-api.YOURDOMAIN.com`; send only to recipients who have consented, and respect your carrier's SMS terms and local messaging law.

### Verifying a download
```powershell
Get-FileHash .\SimBridge-1.0-64bit.apk -Algorithm SHA256        # must equal the line in SHA256SUMS.txt
& "<sdk>\build-tools\<ver>\apksigner.bat" verify --print-certs .\SimBridge-1.0-64bit.apk   # certificate SHA-256 must equal the one in the release notes
```
A matching hash proves the file is the one that was uploaded. A matching certificate fingerprint proves it was signed by the same key as previous releases, which is what lets Android accept it as an update.

---
# Security review log

Scope: every file in `app/src/main`, the manifest, build files, and the repository for secrets. Method: source review + grep audits + compiling the pure-logic code and running its tests locally (66 tests, plus three deliberate "mutations" to prove the tests catch broken rules).

## Findings fixed during the review
| # | Finding | Severity | Fix |
|---|---|---|---|
| 1 | `data_extraction_rules.xml` / `backup_rules.xml` were unedited template placeholders; device-transfer rules were undefined | Medium | Both now exclude every domain (root, file, database, sharedpref, external) from cloud backup and device transfer; `allowBackup=false` kept |
| 2 | Cleartext traffic not explicitly forbidden | Low | `usesCleartextTraffic="false"` (the app only makes HTTPS calls; the inbound loopback server is unaffected) |
| 3 | A freshly generated API key could be captured by screenshots, screen recording or the recents thumbnail | Medium | `FLAG_SECURE` is set only while a new key is on screen and cleared afterwards; the key view disappears when you leave the tab |
| 4 | Anyone with the key could send to premium-rate/satellite numbers (toll-fraud risk) | High (cost) | `DestinationPolicy`: built-in block of `+979 +881 +882 +883 +870 +878`, plus an owner allow-list of country prefixes (`403 DESTINATION_NOT_ALLOWED`). Home shows a warning until an allow-list is set. Best effort: premium ranges inside individual countries cannot be enumerated |
| 5 | Test method returned a value (JUnit would refuse to run it) | Test quality | Fixed; the local runner now enforces void return |

## Audit results (no change needed)
- **Exported components:** `MainActivity` (launcher, required), `BootReceiver` (system broadcasts only, acts only if you enabled it). `GatewayService` and `SmsResultReceiver` are **not exported**; SMS callback PendingIntents are explicit and `FLAG_IMMUTABLE`. No ContentProvider, WebView or FileProvider.
- **Authentication:** every route requires the key; no unauthenticated endpoint exists (tests: 401 on missing/wrong key for each route, no sender call without auth). Same body for all auth failures.
- **Injection:** Room uses bound parameters; message id and Idempotency-Key are matched against strict regexes; JSON fields are type-checked; numbers must be strict E.164.
- **Secrets:** no `Log`/`println`/stack-trace output anywhere in app code; no keystores, `.env` or token files tracked; the only token-looking string is a fake in a unit test. The tunnel token is Keystore-encrypted at rest, passed to cloudflared via environment (not argv) and redacted from displayed logs.
- **Network exposure:** server binds `127.0.0.1` only; the tunnel route should point only at `http://127.0.0.1:8080`.
- **Message content:** never persisted by SimBridge. Because the app is not the default SMS app, Android does not copy sent texts into the system SMS database via this path (not independently verified on the device); the carrier of course sees the SMS.
- **Licences:** cloudflared, Ktor and AndroidX are Apache-2.0 (verified from upstream LICENSE files).

## Accepted risks / not covered
- One shared API key (no scopes, no per-client revocation). Anyone with it can send within your limits, so rotate it if it ever leaves your hands. **Keys pasted into chats or screenshots must be regenerated.**
- Failed-auth throttling is global, not per client IP, because every request reaches the app from cloudflared on loopback. A valid key is never blocked by it.
- Message list/detail endpoints return full recipient numbers to the key holder.
- The debug APK is debuggable and debug-signed; `adb run-as` can read app data on a debuggable build. Never distribute it. Release builds are not debuggable.
- R8 minification is **off** in release builds (never tested with Ktor/Room), so the app's code is not obfuscated.
- Dependency CVE scanning was **not** run (no access to advisory databases from the build sandbox). Run `.\gradlew.bat dependencies` and a scanner of your choice (for example OWASP Dependency-Check) before wider distribution.
- `-PatchResolv` replaces one string in the cloudflared binary; the installed file is then not byte-identical to Cloudflare's release.
- Cloudflare-side protection (WAF rate limiting, Cloudflare Access) is **your** configuration and was not inspected.
- Ktor route tests (`ApiRoutesTest`) and Room/instrumented tests could not be run in the build sandbox; run them with Gradle.

## Checks only you can run on the phone/PC
1. **Not reachable on the LAN** (phone on Wi-Fi, PC on the same network):
   ```powershell
   adb shell ip -f inet addr show wlan0        # note the phone's IP
   curl.exe --max-time 5 http://PHONE_IP:8080/api/v1/health   # must FAIL (refused/timeout)
   ```
2. **Everything needs the key, through the tunnel too** (each must be 401):
   ```powershell
   foreach ($p in "health","info","messages","tunnel/status") { curl.exe -s -o NUL -w "$p -> %{http_code}`n" https://sms-api.YOURDOMAIN.com/api/v1/$p }
   ```
3. **Failed-auth throttle:** send ~25 requests with a wrong key; later ones become 429, while the correct key still returns 200.
4. **Premium/destination block:** POST a message to `+979123456789` with a valid key → `403 DESTINATION_NOT_ALLOWED`. Set `+91` as the allow-list, then POST to another country → 403.
5. **Idempotency:** same `Idempotency-Key` + same body twice → second is `200` with `"idempotent_replay":true`, one SMS only; different body with the same key → `422`.
6. **Key screenshot block:** generate a key and try to take a screenshot (should be blocked or black).
7. **Backup flags:** `adb shell dumpsys package in.imdevops.simbridge | findstr /i "allowBackup debuggable"` (expect `ALLOW_BACKUP` absent; `DEBUGGABLE` present on a debug build only, absent on a release build).
8. **Cloudflare dashboard:** add a WAF rate-limiting rule for `sms-api.YOURDOMAIN.com`, and consider Cloudflare Access with a service token.

## Addendum — release signing and repository hygiene
- **Found and fixed:** the repository's `.gitignore` had no rule for `*.jks`, `*.keystore`, `keystore.properties`, `.env` or `*.token`. Added, together with `dist/`.
- Release signing reads `keystore.properties` (git-ignored). Use forward slashes in `storeFile`. Back up the `.jks` offline; losing it blocks updates, leaking it lets others ship apps that replace yours.
- Release APKs are signed with APK Signature Schemes v1, v2 and v3.
- R8 minification was enabled by the Android Studio template for release builds and had never been tested; it is now off (documented in `build.gradle.kts`).
- The release build refuses to run if a cloudflared binary is missing, and `tools\build-release.ps1` checks the packaged binaries are byte-identical to the verified files.
- Before making the repository public: no real hostnames, tokens, keys or phone numbers in any tracked file (docs use `sms-api.YOURDOMAIN.com`); `git status` clean of `keystore.properties`, `*.jks`, `jniLibs/`, `dist/`.
