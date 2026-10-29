# Reference Analysis (Milestone 1)

Inspection method: both repositories were cloned (`git clone --depth 1`) and their source read directly. Anything not read is marked **UNVERIFIED**. No Android build or device test was run in this analysis environment (no Android SDK, no phone).

Snapshots inspected:
- A = `mdakashhossain1/SMS-Gateway-Free` (5 commits at inspection time)
- B = `roufsweb/cloudflared-android` (HEAD `4d4774e`, 10 commits)

## 1. Repository A — SMS gateway

| Item | Verified finding |
|---|---|
| Language / UI | Kotlin, XML views (RecyclerView adapter), Material; not Compose |
| SDK | minSdk 24, targetSdk/compileSdk 35 |
| HTTP server | NanoHTTPD 2.3.1, constructed as `NanoHTTPD(port)` → listens on **all interfaces**, plain HTTP |
| JSON / DB | Gson 2.10.1, Room 2.6.1 |
| Endpoints | `/api/info`, `/api/send`, `/api/send-bulk`, `/api/status`, `/api/history`, `/api/config` (unversioned) |
| Auth | `X-API-Key` or `Authorization: Bearer`; compared with `==` (not constant-time) |
| Auth order flaw | Key validity is checked **before** rate limiting, and the limiter is keyed by the key → wrong-key guessing is never rate limited |
| `/api/info` | Explicitly unauthenticated |
| Key generation | `"sms_" + UUID.randomUUID()`; stored in plain `SharedPreferences` (MODE_PRIVATE, no Keystore) |
| Logging | Logs first 10 chars of the API key on every successful auth |
| CORS | `Access-Control-Allow-Origin: *` on all responses |
| Rate limit | Per-minute and per-hour; no per-day cap; in-memory only |
| SMS sending | `SmsManager.getDefault()` (deprecated since API 31), **no subscription/SIM selection**; `divideMessage` + `sendMultipartTextMessage`; sent + delivered PendingIntents (FLAG_IMMUTABLE) |
| Validation | Strips non `+`/digit chars, then `^\+?[1-9]\d{1,14}$` and length ≥ 10 |
| Permissions | SEND_SMS, **READ_SMS, RECEIVE_SMS, READ_CONTACTS** (not needed for sending), INTERNET, network/wifi state, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC |
| Tunnel | **None built in.** Cloudflare option in README is a separate `cloudflared` on a server/VPS pointing at `http://ANDROID_IP:8080` — contradicts our "no separate server" requirement |
| Tests | Only the template `ExampleUnitTest` / `ExampleInstrumentedTest` |
| License | MIT, © 2026 Akash Hossain (verified `LICENSE`). Attribution required if any code is copied |
| Idempotency | `requestId` field exists and is stored; whether duplicates are rejected was **not verified** |

## 2. Repository B — Cloudflare Tunnel client

| Item | Verified finding |
|---|---|
| Native artifact | `app/src/main/jniLibs/arm64-v8a/libcloudflared.so` only. `file`: *ELF 64-bit LSB executable, ARM aarch64, statically linked* (37 MB). **No armeabi-v7a, x86 or x86_64 build** |
| It is an executable, not a library | Despite the `.so` name it is a static executable. It works only because Android extracts it to the native-lib dir and the app `exec`s it via `ProcessBuilder` |
| Packaging | `jniLibs.useLegacyPackaging = true` (forces extraction to disk; default for minSdk ≥ 23 would be uncompressed-in-APK and not exec-able) |
| Launch | `ProcessBuilder(binary, "tunnel", "--no-autoupdate", "--edge-ip-version", "4", "--protocol", "http2", "run", "--token", token)`; env `GODEBUG=netdns=go`; stdin redirected from a `resolv.conf` file |
| DNS workaround | Android has no `/etc/resolv.conf`. B **byte-patches** the string `/etc/resolv.conf` → `//proc/self/fd/0` inside the binary (confirmed: that string is present in the committed binary) and feeds nameservers via stdin |
| Version | String `2026.2.0` appears in the committed binary (likely the cloudflared version; unconfirmed). SHA-256 of the committed lib differs from `scratch/official_cloudflared`, as expected for a patched file |
| Self-update | `UpdateManager` downloads `cloudflared-linux-arm64` from GitHub releases into `filesDir`, patches it, `chmod +x`, executes it. **No checksum/signature verification** |
| targetSdk | **28**, minSdk 21. Executing binaries from writable app storage is blocked for targetSdk ≥ 29, so B's download-and-run design depends on the old target. (Executing from the native-lib dir is still allowed at newer targets.) |
| Token handling | Token passed on the command line (`--token`). Storage in `AppPreferences` was **not inspected** |
| Status detection | Parses stdout for `"Registered tunnel connection"` → "Connected" |
| Lifecycle | Foreground service type `dataSync`, partial WakeLock, `START_STICKY`, boot receiver (also uses a `LOCKED_BOOT_COMPLETED` *permission*, which does not appear to be a real platform permission — **UNVERIFIED**) |
| Risky permissions | SYSTEM_ALERT_WINDOW, REQUEST_INSTALL_PACKAGES, REQUEST_COMPANION_RUN_IN_BACKGROUND — unnecessary for us |
| License | README badge says MIT and links `LICENSE`, but **no LICENSE file exists in the repo** (`find` empty; `blob/main/LICENSE` returns 404). B's own code therefore has no verifiable grant. The cloudflared binary is Cloudflare's own work (believed Apache-2.0 — **verify upstream LICENSE/NOTICE before redistributing**) |
| Build | AGP 8.2.2, Kotlin 1.9.22, Compose, NDK mentioned in README but no native sources in the tree |

## 3. Why `.../lib/arm/libcloudflared.so: error=2 No such file or directory` happened

`error=2` is `ENOENT`. Evidence-based reading:

1. The path component **`lib/arm`** is the native-library directory for the **32-bit ARM** ABI. An app only gets `lib/arm64` on a 64-bit process. So the app that failed was running (or installed) as **32-bit ARM**. Android Go devices frequently ship a 32-bit userspace — this must be confirmed on the Poco C51 (see §5).
2. Repository B ships **only arm64-v8a**. An APK with no `armeabi-v7a` lib gets nothing extracted into `lib/arm`, so the file does not exist → `ENOENT`.
3. Even if the file existed, an aarch64 executable cannot run in a 32-bit process.

Status: **strongly consistent hypothesis, not proven.** Other causes that produce the same errno and are still possible: file not extracted (`extractNativeLibs=false`), wrong path string, or a missing ELF interpreter (not applicable to this static binary). Confirm with the diagnostics in §5.

## 4. Reuse decisions

| Component | Decision | Reason |
|---|---|---|
| A: Room entity idea, state names | **Rewrite** | Different state model (QUEUED→SUBMITTING→SENT/DELIVERED/FAILED), no plaintext key column |
| A: NanoHTTPD server | **Avoid** | Last release 2.3.1; we need loopback binding, size limits, timeouts. Choose a maintained server (see ARCHITECTURE) |
| A: AuthenticationManager | **Avoid** | Non-constant-time compare, unauth info endpoint, limiter ordering, key prefix logged |
| A: SmsManager wrapper | **Rewrite** | Deprecated `getDefault()`, no SIM selection |
| A: Permission set | **Avoid** | READ_SMS/RECEIVE_SMS/READ_CONTACTS not needed |
| A: API shape | **Rewrite** | Versioned `/api/v1`, our spec |
| B: process-launch pattern | **Isolate behind `TunnelManager`**; rewrite | Idea is sound; code is tied to arm64 and targetSdk 28 |
| B: resolv.conf byte-patch | **Avoid unless the PoC proves it is required**; if required, isolate | Patching an unsigned binary breaks any integrity guarantee |
| B: self-update downloader | **Avoid** | No integrity check; conflicts with modern exec restrictions |
| B: `libcloudflared.so` binary | **Do not redistribute** | Unverified provenance, patched, no license file; source our own from upstream with a checksum |
| B: boot receiver / service | **Rewrite** | Fit with current FGS rules |

No source from A or B is copied into SimBridge at this stage. If that changes, A's MIT notice must be kept; B's code cannot be copied without a license from its author.

## 5. Diagnostics to run on the Poco C51 (not yet done)

PowerShell, phone connected with USB debugging:

```powershell
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.product.cpu.abilist32
adb shell getprop ro.product.cpu.abilist64
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.zygote
```

`ro.zygote=zygote32` means a pure 32-bit userspace (arm64 binaries will never run). `zygote64_32` means 64-bit primary with 32-bit support. The starter app's screen shows the same facts from inside the app (`Process.is64Bit()`, supported ABIs, native-lib dir).

## 6. Feasibility decision (tunnel)

**Plausible implementation requiring a device test.** Conditions: (a) the device exposes an ABI for which a genuine upstream cloudflared build exists (arm64 or 32-bit arm — upstream release asset names/availability to be checked, not assumed); (b) the binary is executed from the native-lib dir (not `filesDir`); (c) DNS resolution works without patching or with an isolated, documented workaround. If the device is `zygote32`, B's binary is unusable and a 32-bit build must be sourced/built and verified. See `docs/IMPLEMENTATION_PLAN.md` Milestone 5.
