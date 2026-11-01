# Changelog

All notable changes to SimBridge Gateway. Versions follow `versionName` in `app/build.gradle.kts`; `versionCode` must increase with every release that is meant to update an installed copy.

## 1.1.1 (versionCode 3)

### Fixed
- Tunnel DNS on Android: the release now bundles cloudflared patched to read its DNS servers from stdin (both 32-bit and 64-bit builds). 1.1 shipped an unpatched binary, so on phones where DNS lookups failed the tunnel could not start (`Couldn't resolve SRV record ... 127.0.0.1:53`).

### Added
- DNS diagnostics: *Tunnel → Check cloudflared binary* shows whether the binary is patched, the log starts with the patch state and the DNS servers handed to cloudflared, and a DNS failure now says what to do.
- `tools\check-cloudflared-patch.ps1` checks the binaries in `jniLibs` and inside an APK.
- Public DNS servers (1.1.1.1, 8.8.8.8) are always added after the phone's own DNS servers, with `timeout:3 attempts:2`.

### Upgrading
- From 1.1: *Diagnostics → App update → Check for update* (the first release that can be installed through the updater).
- From 1.0: install by hand once.

## 1.1 (versionCode 2)

### Added
- **In-app update** (*Diagnostics → App update*). A manual check against this repository's latest GitHub release; the APK for the phone is downloaded, must match `SHA256SUMS.txt` and be signed with the same key as the installed app, and is then handed to Android's installer. Nothing contacts GitHub until the button is pressed. The gateway restarts after the update if it was running (`resumeAfterUpdate`).
- New permission `REQUEST_INSTALL_PACKAGES` (only used for the hand-off to Android's installer) and a non-exported `UpdateStatusReceiver`.
- Unit tests for the updater's pure logic (`AppUpdaterTest`): version comparison, APK selection, checksum-file parsing, UTF-16/BOM handling.
- `tools\make-release.ps1`: checks the three APKs of a version, verifies signature schemes v1, v2 and v3 with `apksigner`, writes `SHA256SUMS.txt` and fills in the release notes.

### Changed
- **Start tunnel** now goes through the foreground service, so the tunnel keeps running in the background with the status notification. The outdated "background service comes in Milestone 6" text is gone.
- Release APK names now include the ABI: `SimBridge-<version>-v7a-32bit.apk`, `-v8a-64bit.apk`, `-universal.apk`. The updater matches on the `-32bit.apk` / `-64bit.apk` / `-universal.apk` endings and on `SHA256SUMS.txt`.
- `tools\fetch-cloudflared.ps1` fetches the **latest** upstream cloudflared by default (pin one with `-Version`). A file is installed only if its SHA-256 matches Cloudflare's release notes and/or GitHub's asset digest; if both exist they must agree. The bundled version is shown on the Tunnel tab and in the release notes.

### Upgrading
- From 1.0: install the 1.1 APK over the old app once by hand (same signing key, data is kept). 1.0 has no updater.
- A debug build or a build signed with another key must be uninstalled first.

### Not yet verified
- The in-app update on a real phone. It needs a release newer than the installed version, so the first real run is the update after 1.1.

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
