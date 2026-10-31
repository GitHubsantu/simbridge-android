# Changelog

All notable changes to SimBridge Gateway. Versions follow `versionName` in `app/build.gradle.kts`; `versionCode` must increase with every release that is meant to update an installed copy.

## 1.0 (versionCode 1)

First release.

### Added
- Loopback REST API (`/api/v1`) on `127.0.0.1:8080` with Bearer authentication (256-bit key, only its SHA-256 is stored).
- SMS sending through a chosen SIM; `QUEUED → SUBMITTING → SENT | FAILED`, then `DELIVERED | DELIVERY_FAILED` (delivery receipts depend on the carrier).
- Per-minute and per-day send limits, `Idempotency-Key` duplicate protection (24 h, persisted), body and message size limits, bounded queue, failed-authentication throttling.
- Destination control: built-in block of premium/satellite prefixes plus an owner allow-list of country prefixes.
- Room-backed message history (message text is never stored; recipients kept 30 days).
- Cloudflare Tunnel: upstream `cloudflared` 2026.10.4 (32-bit ARM and 64-bit ARM), checksum-verified, run from the app's native-library directory; token encrypted with an Android Keystore key.
- Foreground service, notification, five-tab Compose UI, diagnostics and event log, optional start after reboot.
- Release tooling: `tools\fetch-cloudflared.ps1` (checksum-verified binaries) and `tools\build-release.ps1` (builds the 32-bit, 64-bit and universal APKs and proves each contains the verified `libcloudflared.so`).
- Release APKs are signed with APK Signature Schemes v1, v2 and v3.

### Known limitations
- SMS only: no RCS, MMS, receiving SMS, bulk send or multiple API keys.
- R8 minification is off for release builds; not tested with Ktor/Room.
- No dependency vulnerability scan has been run.
- The bundled cloudflared cannot be updated from inside the app; update means a new pinned version and checksum, rebuild and reinstall.
- x86 / x86_64 devices and emulators are not supported by the tunnel.
