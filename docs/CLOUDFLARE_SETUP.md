# Cloudflare Tunnel setup

Status legend: **Verified** = observed on the phone or checked against a source; **Unverified** = written from documentation/knowledge and still to be proven on your phone.

## 32-bit and 64-bit phones
`tools\fetch-cloudflared.ps1` installs **both** upstream builds by default (`-Abi arm`, `-Abi arm64` or `-Abi both`), each checked against Cloudflare's published SHA-256 for the pinned version. The universal APK runs on either kind of phone: Android extracts the matching binary into the app's native-library folder, and the app checks the file's real architecture against the phone before running it. The 32-bit and 64-bit APKs contain only their own binary (about 27 MB each; the universal APK is about 45 MB). x86/x86_64 devices and emulators are not supported (no binary is bundled for them).

**Verified:** the 2026.10.4 `cloudflared-linux-arm64` download has SHA-256 `2b7203b4fb0ba28a0c1e12f8855ce2c25f76d266a8d5edafac5540b6812acff6`, identical to Cloudflare's published value; it is an AArch64 ELF with exactly one `/etc/resolv.conf` string, so `-PatchResolv` applies the same way. **Device-verified by the project owner:** the arm64 build works on a 64-bit phone. The 32-bit build is verified on the Poco C51.

## Why the Poco C51 needs the 32-bit binary
Your phone reports `zygote32`, ABIs `armeabi-v7a,armeabi`. Upstream publishes `cloudflared-linux-arm` (32-bit ARM, static). **Verified:** release `2026.10.4` of that asset has SHA-256 `f29b44e97ec18dd2971dfdd9bad0c6d460aaa78223b91b186219f02135c08d8c`, which matches the value Cloudflare lists in that release's notes; its ELF header is 32-bit ARM. Newer tags may exist; pin only versions whose published checksum you have compared.

## Step 1 — Prove the binary runs (no Cloudflare account needed)
```powershell
cd C:\dev\simbridge-android
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1
.\gradlew.bat installDebug
```
The script refuses to install anything whose SHA-256 differs from the pinned value. In the app press **Check cloudflared binary**.

| Result | Meaning |
|---|---|
| `Binary: OK (ARM, 32-bit)` (or `64-bit`) and `Ran OK (exit 0): cloudflared version 2026.10.4 …` | The binary is the right architecture and executes |
| `MISSING` | the file was not packaged: re-run the script, rebuild, reinstall. With a release APK, you may have installed the wrong ABI build |
| `WRONG_ARCH` | wrong build for this phone (this is what caused the original `error=2`) |
| `Run failed: … error=13 Permission denied` | Android refused to execute it: report the exact text |
| `Run failed: … error=2 …` with `Binary: OK` | the loader/interpreter is missing: report the exact text |

## Step 2 — Create the tunnel (Cloudflare dashboard; menu names may differ slightly)
1. Cloudflare dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared**, name it e.g. `simbridge`.
2. On the "install connector" screen **copy only the token** (the long string). Do not paste it into chat, a file in the repo, or a screenshot.
3. **Public hostname:** subdomain `sms-api`, domain `YOURDOMAIN.com`, service **HTTP**, URL **`127.0.0.1:8080`**. This is the only service the tunnel exposes.

## Step 3 — Connect
In the app: enter `sms-api.YOURDOMAIN.com`, paste the token, **Save hostname / token** (token is encrypted with an Android Keystore key and never shown again), then **Start tunnel**. Also start the HTTP server.

**Unverified:** passing the token through the `TUNNEL_TOKEN` environment variable on every phone/OEM. If the log says the token is missing/invalid although it is correct, report it; the fallback is the `--token` argument.

### DNS on Android (expected problem)
A static Go program reads `/etc/resolv.conf`, which Android does not have, so lookups fall back to `127.0.0.1:53` and fail. The app already writes a resolv.conf and feeds it on stdin, but that only helps a binary patched to read `//proc/self/fd/0`.
1. Try the unmodified binary first.
2. If the log shows DNS errors (the app prints a DNS hint), re-run the script with `-PatchResolv`, rebuild, reinstall. This replaces one 16-byte string in the binary (the script checks it occurs exactly once) and prints the new hash. **Trade-off:** the installed file is no longer byte-identical to Cloudflare's release. A release built this way must say so in its release notes.

## Step 4 — Prove it end to end
"Tunnel: N connection(s)" only means cloudflared registered with Cloudflare. Press **Test public URL**: the phone requests `https://sms-api.YOURDOMAIN.com/api/v1/health` without a key and expects SimBridge's `401 UNAUTHORIZED`. That proves Internet → Cloudflare → tunnel → SimBridge. Then from another network (e.g. a friend's phone on mobile data, or your PC with the phone's Wi-Fi off):
```powershell
curl.exe -i https://sms-api.YOURDOMAIN.com/api/v1/health                      # expect 401
curl.exe -H "Authorization: Bearer YOUR_API_KEY" https://sms-api.YOURDOMAIN.com/api/v1/health   # expect 200
```
`GET /api/v1/tunnel/status` (with key) reports the same state the app shows.

## Success criteria
1. `libcloudflared.so` executes (no `error=2/13`). 2. `--version` output captured. 3. Public `/health` answers from another network. 4. Unauthenticated `POST /messages` through the tunnel returns 401. 5. Survives 30 minutes with the screen off: the tunnel now runs under the foreground service; use the checklist in [TESTING.md](TESTING.md) and record the result.

## Hardening you should add in Cloudflare
A WAF rate-limiting rule on `sms-api.YOURDOMAIN.com`, and ideally Cloudflare Access (service token) in front of it. SimBridge's API key is the last line of defence, not the only one.

## Licensing note
cloudflared is Cloudflare's software, licensed under Apache-2.0 (verified from the upstream `LICENSE` file; see [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)). The binary is fetched at build time and is git-ignored, never committed. Release APKs contain it, so the Apache-2.0 text ships in the repository under `licenses/`.

## Updating cloudflared
**The app cannot update the binary by itself — by design.** Android only lets an app execute files that were installed with the APK (the read-only native-library folder). Downloading a new binary into app storage and running it is blocked on current Android; the reference project only did it by setting the app's target SDK to 28, which weakens security and is not acceptable here.

What the app does: the Tunnel tab shows the bundled version (from `--version`) and a **Check for newer release** button that asks GitHub for the latest upstream tag and compares it. This is informational only and sends no data about you.

To update: open the new release page, copy the SHA-256 listed for **both** `cloudflared-linux-arm` and `cloudflared-linux-arm64`, put the version and hashes into `tools/fetch-cloudflared.ps1`, run it, rebuild and reinstall (and bump `versionCode`/`versionName` for a new release). The script refuses any file whose hash does not match. Read hashes from the official release page of that exact tag, and compare them with a second source where you can: third-party mirrors have been seen to list different or garbled values.
