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
| `ui` | Compose theme, 5 tabs, cards | No |

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
| Tunnel | Upstream `cloudflared-linux-arm` 2026.10.4 (hash verified), executed from `nativeLibraryDir` | V |
| Tunnel DNS | resolv.conf fed via stdin; binary optionally patched (`-PatchResolv`) | V that tunnel works; U which variant was used |
| Foreground service type | `specialUse` (Android 15 limits `dataSync` to ~6 h per 24 h) | S (Android docs); V runs |
| Updating cloudflared | Rebuild only. App cannot run downloaded executables | S |
| Loop guard | ≤3 automatic restarts per 10 min, no auto-restart of a failed tunnel | S (unit test) |
