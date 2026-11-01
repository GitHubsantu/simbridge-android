# Implementation Plan

Completion rule: a milestone is done only with evidence (build output, test output, or a device log) recorded here.

| # | Milestone | Exit evidence | Status |
|---|---|---|---|
| 1 | Reference analysis, project skeleton, device diagnostics screen | `docs/REFERENCE_ANALYSIS.md`; app built and run on the Poco C51; ABI screen | **Done** — the diagnostics screen showed 32-bit ARM, API 33 |
| 2 | Loopback API (health/info), start/stop, local curl test | `curl.exe` via `adb forward` returns JSON with a valid key and 401 without | **Done** — 200 with key, 401 without, on the Poco C51. (`gradlew test` result not reported.) |
| 3a | SMS engine, SIM selection, send/delivery callbacks, read-only message endpoints | One real SMS reaches SENT and is visible via the API | **Done** — a real SMS on the Poco C51; API showed `SENT` (no delivery receipt observed) |
| 3b | Room persistence (survives process death), 30-day retention | record persists after app restart | **Done** — reported working on the phone (Room 2.8.5, KSP 2.3.10) |
| 4 | POST /messages, rate limits, validation, idempotency, bounded queue, tests | device checklist in docs/TESTING.md | **Mostly verified on device** — 202 on POST, 429 + Retry-After when over the limit. Idempotency replay/422 and `gradlew test` results not yet reported |
| 5 | Tunnel PoC → `TunnelManager` | Remote HTTPS call reaches `/api/v1/health`; criteria below | **Verified end to end:** `https://sms-api.YOURDOMAIN.com/api/v1/health` → 401 `UNAUTHORIZED` without a key and 200 with it, served by SimBridge through Cloudflare, on a 32-bit phone; the 64-bit build is also reported working. Not yet verified: from a different network/phone, DNS patch variant used, unauthenticated POST through the tunnel, 30-minute screen-off run |
| 6 | Full UI, foreground service, screen-off test | docs/TESTING.md Milestone 6 checklist | **Built and in use on the phone.** Long screen-off run and reboot test not yet reported (5-tab UI, GatewayService with `specialUse` type, opt-in boot start, restart-loop guard, event log, battery guidance) |
| 7 | Hardening, docs, debug APK | README, security review, tests, APK steps | **Done except two items:** README, ARCHITECTURE, SECURITY review, THIRD_PARTY_NOTICES done; 66 pure-logic tests run; LICENSE added (MIT). Still awaited: `gradlew test` output and the device security checks in SECURITY.md |
| 8 | Release 1.0: signed 32-bit / 64-bit / universal APKs, GitHub release | `tools\build-release.ps1` output; release checklist in docs/TESTING.md | **Built and verified by script:** all three APKs signed (v1+v2+v3 enabled), each contains `libcloudflared.so` byte-identical to the verified file. **To do before publishing:** install each APK type on a matching phone and run the release checklist; copy the cloudflared licence to `licenses/`; confirm the LICENSE holder name |
| 9 | Release 1.1: in-app update, tunnel through the foreground service, `fetch-cloudflared` with published-checksum rules, `make-release` helper | `gradlew test` incl. `AppUpdaterTest`; release checklist and in-app update checklist in docs/TESTING.md | **Code written and applied by the owner. To record:** `gradlew test` result; 1.1 release build and checklist; the first real in-app update (needs a release newer than the installed one) |

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

## Milestone 5 update — 32-bit and 64-bit targets
Device is `zygote32` / armeabi-v7a. Plan: package upstream `cloudflared-linux-arm` as `app/src/main/jniLibs/armeabi-v7a/libcloudflared.so` and upstream `cloudflared-linux-arm64` as `app/src/main/jniLibs/arm64-v8a/libcloudflared.so`, set `packaging.jniLibs.useLegacyPackaging = true`, execute from `applicationInfo.nativeLibraryDir`. Record the verified version and SHA-256 in `docs/CLOUDFLARE_SETUP.md`; do not commit the binaries. DNS behaviour on Android to be tested first without any patching.
