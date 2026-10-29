# Implementation Plan

Completion rule: a milestone is done only with evidence (build output, test output, or a device log) recorded here.

| # | Milestone | Exit evidence | Status |
|---|---|---|---|
| 1 | Reference analysis, project skeleton, device diagnostics screen | `docs/REFERENCE_ANALYSIS.md`; `gradlew assembleDebug` success on your PC; screenshot/text of device ABI screen | **Docs done. Build not run — needs you.** |
| 2 | Loopback API (health/info), start/stop, local curl test | `curl` via `adb forward` returns JSON | Not started |
| 3 | SMS engine, SIM selection, callbacks, Room | Fake-sender tests green; one controlled real SMS to your own number | Not started |
| 4 | Auth, rate limit, validation, idempotency, tests | Unit + API tests green | Not started |
| 5 | Tunnel PoC → `TunnelManager` | Remote HTTPS call reaches `/api/v1/health`; failure criteria below | Not started |
| 6 | Full UI, foreground service, screen-off test | Device log with screen locked | Not started |
| 7 | Hardening, docs, debug APK | APK file exists; docs complete | Not started |

## Milestone 5 PoC — success / failure criteria
Success (all required):
1. Binary file exists at `applicationInfo.nativeLibraryDir` and `exec` returns a process (no `error=2/13`).
2. `cloudflared --version` output is captured in-app and matches the pinned upstream version.
3. Tunnel reports a registered connection **and** `https://sms-api.YOURDOMAIN.com/api/v1/health` returns HTTP 200 from a different network (mobile data on another phone).
4. Unauthenticated `POST /api/v1/messages` returns 401 through the tunnel.
5. Survives screen-off for 30 minutes.

Failure modes to record rather than hide: `ENOENT` (ABI/extraction), `EACCES` (exec denied), DNS lookup failure, token rejected, process killed by OEM.

Binary sourcing rules: obtain from the upstream Cloudflare release (or build from upstream source), record version + SHA-256 in `docs/CLOUDFLARE_SETUP.md`, verify against upstream checksums, never commit unverified binaries.

## Milestone 1 — what you do on Windows
See `docs/ANDROID_SETUP.md`.
