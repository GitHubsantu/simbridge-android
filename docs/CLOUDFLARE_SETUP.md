# Cloudflare Tunnel setup (Milestone 5 — proof of concept)

Status legend: **Verified** = observed on the phone or checked against a source; **Unverified** = written from documentation/knowledge and still to be proven on your Poco C51.

## Why the binary is 32-bit
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
| `Binary: OK (ARM, 32-bit)` and `Ran OK (exit 0): cloudflared version 2026.10.4 …` | PoC criteria 1 and 2 pass |
| `MISSING` | the file was not packaged: re-run the script, rebuild, reinstall |
| `WRONG_ARCH` | wrong build for this phone (this is what caused your old `error=2`) |
| `Run failed: … error=13 Permission denied` | Android refused to execute it: send me the exact text |
| `Run failed: … error=2 …` with `Binary: OK` | the loader/interpreter is missing: send me the exact text |

## Step 2 — Create the tunnel (Cloudflare dashboard; menu names may differ slightly)
1. Cloudflare dashboard → **Zero Trust → Networks → Tunnels → Create a tunnel → Cloudflared**, name it e.g. `simbridge`.
2. On the "install connector" screen **copy only the token** (the long string). Do not paste it into chat, a file in the repo, or a screenshot.
3. **Public hostname:** subdomain `sms-api`, domain `YOURDOMAIN.com`, service **HTTP**, URL **`127.0.0.1:8080`**. This is the only service the tunnel exposes.

## Step 3 — Connect
In the app: enter `sms-api.YOURDOMAIN.com`, paste the token, **Save hostname / token** (token is encrypted with an Android Keystore key and never shown again), then **Start tunnel**. Also start the HTTP server.

**Unverified:** passing the token through the `TUNNEL_TOKEN` environment variable. If the log says the token is missing/invalid although it is correct, tell me; the fallback is the `--token` argument.

### DNS on Android (expected problem)
A static Go program reads `/etc/resolv.conf`, which Android does not have, so lookups fall back to `127.0.0.1:53` and fail. The app already writes a resolv.conf and feeds it on stdin, but that only helps a binary patched to read `//proc/self/fd/0`.
1. Try the unmodified binary first.
2. If the log shows DNS errors (the app prints a DNS hint), re-run the script with `-PatchResolv`, rebuild, reinstall. This replaces one 16-byte string in the binary (the script checks it occurs exactly once) and prints the new hash. **Trade-off:** the installed file is no longer byte-identical to Cloudflare's release.

## Step 4 — Prove it end to end
"Tunnel: N connection(s)" only means cloudflared registered with Cloudflare. Press **Test public URL**: the phone requests `https://sms-api.YOURDOMAIN.com/api/v1/health` without a key and expects SimBridge's `401 UNAUTHORIZED`. That proves Internet → Cloudflare → tunnel → SimBridge. Then from another network (e.g. a friend's phone on mobile data, or your PC with the phone's Wi-Fi off):
```powershell
curl.exe -i https://sms-api.YOURDOMAIN.com/api/v1/health                      # expect 401
curl.exe -H "Authorization: Bearer YOUR_API_KEY" https://sms-api.YOURDOMAIN.com/api/v1/health   # expect 200
```
`GET /api/v1/tunnel/status` (with key) reports the same state the app shows.

## Success criteria for the PoC
1. `libcloudflared.so` executes (no `error=2/13`). 2. `--version` output captured. 3. Public `/health` answers from another network. 4. Unauthenticated `POST /messages` through the tunnel returns 401. 5. Survives 30 minutes with the screen off — **not possible yet**: the tunnel dies with the app process until the Milestone 6 foreground service.

## Hardening you should add in Cloudflare
A WAF rate-limiting rule on `sms-api.YOURDOMAIN.com`, and ideally Cloudflare Access (service token) in front of it. SimBridge's API key is the last line of defence, not the only one.

## Licensing note
cloudflared is Cloudflare's software (believed Apache-2.0; **verify the upstream LICENSE** before distributing an APK containing it, and ship its notice in the About screen). The binary is fetched at build time and is git-ignored, never committed.
