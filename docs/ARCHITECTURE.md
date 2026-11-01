# Architecture (current state)

```
Client ─HTTPS─▶ Cloudflare edge ─▶ cloudflared (child process, run from the app's native-lib dir)
                                        │ http://127.0.0.1:8080
                                        ▼
        Ktor CIO server (loopback only) ── auth ─▶ MessageService ─┬─ Room (history, idempotency)
                                                                    └─ SmsSender ─▶ SmsManager ─▶ SIM
GatewayService (foreground, type specialUse) owns: API server lifecycle, tunnel lifecycle, notification, public-URL probe
```

## Packages (`in.imdevops.simbridge`)
| Package | Responsibility | Android-free? |
|---|---|---|
| `api` | Routes, auth, `FailedAuthThrottle`, request parser, JSON helpers, `ServerState` | Mostly (only `SendRequestParser` needs `org.json`) |
| `sms` | `MessageService`, state machine (`SmsReducer`), `RatePolicy`, `DestinationPolicy`, SIM resolution, stores, `AndroidSmsSender`, `SmsResultReceiver` | Core logic yes; `Android*` classes no |
| `data` | Room database (v2), DAOs, `RoomMessageStore`, `RoomIdempotencyStore` | No |
| `security` | `ApiKeys` (generation, hashing, bearer parsing), `ApiKeyStore` | `ApiKeys` yes |
| `tunnel` | `TunnelManager` interface, `CloudflaredTunnelManager`, ELF/arch check, log parser, token vault (Keystore), update checker | Parsing/validation yes |
| `service` | `GatewayService`, `BootReceiver`, `GatewayPrefs`, `RestartGuard`, `NotificationText` | `RestartGuard`, `NotificationText` yes |
| `update` | `AppUpdater` (GitHub release lookup, download + SHA-256 check, signing-key comparison, `PackageInstaller` hand-off), `UpdateStatusReceiver` (installer result) | Pure helpers (version compare, APK pick, checksum parse) yes; the rest no |
| `ui` | Compose theme, 5 tabs, cards (incl. `AppUpdateCard` on Diagnostics) | No |

The API layer depends on the `MessageService` and tunnel *status* only; nothing outside `tunnel` knows about processes, binaries or ABIs.

## Decisions (V = verified on the target phone, S = verified from sources/docs, U = still unverified)
| Topic | Decision | Status |
|---|---|---|
| Package name | `in.imdevops.simbridge` (needs backticks in Kotlin because `in` is a keyword) | V (builds) |
| minSdk / target | 26 / 37 | V |
| HTTP server | Ktor CIO 3.6.0, bound to `127.0.0.1` | V |
| Auth | 256-bit random key, SHA-256 hash stored, constant-time hash compare | V |
| Persistence | Room 2.8.5 + KSP 2.3.10; text never stored; 30-day retention | V |
| SMS states | `QUEUED→SUBMITTING→SENT\|FAILED`, then `DELIVERED\|DELIVERY_FAILED` | V (SENT); delivery depends on carrier |
| Limits | Derived from persisted history (restart-proof); serialized sends; max 16 pending | V (429); S (tests) |
| Idempotency | Persisted 24 h; reserve-then-complete so concurrent duplicates send once | S (concurrency test passes); replay path not yet reported from device |
| Destination control | Built-in premium/satellite prefix block + owner allow-list | S (unit tests); U on device |
| Tunnel | Upstream `cloudflared-linux-arm` and `cloudflared-linux-arm64` (hashes checked against Cloudflare's release notes and/or GitHub's asset digest), executed from `nativeLibraryDir`. 1.0 shipped 2026.10.4; later releases name their version in the release notes | V on a 32-bit phone (Poco C51) and on a 64-bit phone (with 2026.10.4) |
| Tunnel DNS | resolv.conf fed via stdin; binary optionally patched (`-PatchResolv`) | V that tunnel works; U which variant was used |
| Foreground service type | `specialUse` (Android 15 limits `dataSync` to ~6 h per 24 h) | S (Android docs); V runs |
| Updating cloudflared | A new cloudflared means a new SimBridge release. The app never runs a downloaded executable; the in-app update replaces the whole APK (which contains the binary) | S |
| In-app app update (1.1) | Manual only. Latest release of `GitHubsantu/simbridge-android`; APK picked by ABI (`-64bit.apk` / `-32bit.apk`, else `-universal.apk`); must match `SHA256SUMS.txt`; signing certificate must equal the installed app's; installed through `PackageInstaller` (`REQUEST_INSTALL_PACKAGES`); gateway resumes afterwards if it was running | S (unit tests for the pure logic); **U on device**. Android 12+ may skip its confirmation screen (`USER_ACTION_NOT_REQUIRED` is requested) |
| Loop guard | ≤3 automatic restarts per 10 min, no auto-restart of a failed tunnel | S (unit test) |
| Release packaging | ABI splits: `armeabi-v7a`, `arm64-v8a` and a universal APK; `useLegacyPackaging = true` so cloudflared is extracted to disk; `keepDebugSymbols` so it is not stripped | V (build output proves each APK holds the byte-identical binary) |
| Release signing | `keystore.properties` (git-ignored); APK Signature Schemes v1 + v2 + v3 enabled; `tools\make-release.ps1` checks all three with `apksigner` and that every APK has the same certificate | V (build and install); script-checked per release |
| Minification | R8 off for release | S (deliberate; untested with Ktor/Room) |
