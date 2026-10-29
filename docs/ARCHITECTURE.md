# Architecture (decision record, Milestone 1)

Status legend: **Decided** = design choice; **To verify** = needs a build/device test.

```
Client ─HTTPS─▶ Cloudflare edge ─▶ cloudflared (child process of the app)
                                        │  http://127.0.0.1:<port>
                                        ▼
                     SimBridge API (loopback only) ─▶ MessageService
                                                       ├─ Room (history)
                                                       └─ SmsSender ─▶ SmsManager ─▶ SIM
```

## Layers and boundaries
- `ui/` Compose screens, ViewModels. Depends on domain interfaces only.
- `api/` HTTP server, routes, auth, rate limiting. Depends on `MessageService` and `TunnelStatusProvider` interfaces — never on cloudflared.
- `sms/` `SmsSender` interface + `AndroidSmsSender`. Tests use `FakeSmsSender`.
- `tunnel/` `TunnelManager` interface (+ `CloudflaredTunnelManager`, `NoopTunnelManager`). Only this package knows about processes, binaries, ABIs.
- `data/` Room, repositories, settings.
- `security/` key generation, Keystore wrapping, redaction.
- `service/` foreground service owning server (and tunnel) lifecycle.

## Decisions
| Topic | Decision | Notes |
|---|---|---|
| Package | `in.imdevops.simbridge` (as requested) | `in` is a Kotlin keyword → `package \`in\`.imdevops.simbridge` and every import needs backticks. Works, but is awkward; changing before the first commit is cheap (e.g. `app.imdevops.simbridge`). Your call. |
| minSdk | **26** (proposal) | Notification channels, `startForegroundService`; `SmsManager` per-subscription APIs exist from 22, `getSystemService(SmsManager)` from 31 (use compat path below). Poco C51 expected to be API 33 (**verify** via adb). |
| SMS API | `SmsManager.createForSubscriptionId` (API 31+) / `getSmsManagerForSubscriptionId` (22–30, deprecated, documented) | Explicit SIM selection via `SubscriptionManager` (needs READ_PHONE_STATE) |
| HTTP server | **Ktor server (CIO engine)** — proposal | Maintained by JetBrains; supports bind host, timeouts. NanoHTTPD 2.3.1 is effectively unmaintained. Final choice and exact version are made in Milestone 2 after checking Maven Central and a loopback test on-device — **To verify** |
| Bind address | `127.0.0.1` only | Tunnel origin = `http://127.0.0.1:<port>`; nothing on the LAN |
| DB | Room, body stored only until retention expires | Bodies redacted/trimmed in logs |
| Secrets | API key shown once, stored as hash; tunnel token encrypted with an Android Keystore AES-GCM key | **To verify** on device |
| FGS type | Candidate: `specialUse` or `dataSync` | Android 15+ limits `dataSync` runtime (~6 h/24 h) when targeting SDK 35+. Re-check current docs in Milestone 6 before choosing — **To verify** |
| Tunnel | Child process of an upstream cloudflared binary executed from `nativeLibraryDir`, token via environment variable if supported | **To verify** (Milestone 5 PoC) |
| Message states | `QUEUED → SUBMITTING → SENT \| FAILED`, then `DELIVERED \| DELIVERY_FAILED` | `SENT` = radio accepted the submission; `DELIVERED` = network receipt. They are separate events. |
| Retries | None automatic in v1 | Prevents duplicate SMS after ambiguous failures |

## Public API contract (target)
`/api/v1/health`, `/info`, `POST /messages`, `GET /messages/{id}`, `GET /messages`, `GET /tunnel/status`. Bearer auth on everything except a minimal health response. Details in `docs/API.md` (Milestone 2/4).
