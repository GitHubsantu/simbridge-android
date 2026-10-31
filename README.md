# SimBridge Gateway

**Your SIM. Your API. Your Gateway.**

SimBridge turns an Android phone with an active SIM into a small, authenticated SMS gateway. A client sends an HTTPS request to your own domain; Cloudflare Tunnel forwards it to a REST API running inside the app; the app hands the message to Android's SMS stack and reports honest status.

```
Client ─HTTPS─▶ Cloudflare ─▶ cloudflared (child process of the app) ─▶ 127.0.0.1:8080 SimBridge API ─▶ SmsManager ─▶ SIM
```

SMS only. **No RCS, no root, no Termux, no separate server.** Built with Kotlin, Jetpack Compose, Room and Ktor.

## What works (and how it was checked)
| Feature | Status |
|---|---|
| Loopback REST API, Bearer auth, `/api/v1` | Verified on a Poco C51 |
| Send SMS via a chosen SIM, `SENT`/`DELIVERED` tracked separately | Real SMS verified; delivery receipts depend on your carrier |
| Rate limits, duplicate protection (`Idempotency-Key`), body/size limits, bounded queue | 429 and 202 verified on device; logic covered by automated tests |
| Cloudflare Tunnel on a 32-bit Android Go phone | Verified end to end (public hostname → 401 without key, 200 with key) |
| Foreground service, notification, 5-tab UI, event log | Used on device |
| Not implemented | RCS, MMS, receiving SMS, multiple API keys, bulk send |

## What you need
- Windows PC, Android Studio (stable) with its bundled JDK, `adb`.
- An Android phone with a SIM (developed on a Poco C51, Android 13 Go, 32-bit). minSdk 26.
- For remote access: a Cloudflare account with a domain, and a named tunnel token.

## Build and install (PowerShell, project root)
```powershell
# 1. fetch the checksum-verified 32-bit cloudflared (needed for the tunnel only)
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1
# 2. unit tests
.\gradlew.bat test
# 3. build + install on the connected phone (USB debugging on)
.\gradlew.bat installDebug
```
The debug APK is `app\build\outputs\apk\debug\app-debug.apk`. It is **debuggable and signed with a debug key: for your own phone only, never distribute it.**

## First run
1. **API tab → Grant permissions** (Send SMS, Phone state, Notifications). They are needed to send, to list your SIMs, and to show the gateway notice. Nothing else is requested (no READ_SMS, no contacts).
2. **API tab → Generate key.** It is shown once, hashed on the device, and cannot be recovered. Screenshots are blocked while it is visible.
3. **API tab → Sending limits:** set per-minute/per-day limits and **restrict destinations to your own country prefix** (e.g. `+91`). Strongly recommended.
4. **Home → Start gateway.**

## Test locally
```powershell
adb forward tcp:8080 tcp:8080
curl.exe -H "Authorization: Bearer YOUR_API_KEY" http://127.0.0.1:8080/api/v1/health
```
Windows **cmd** does not understand single quotes; for POST use double quotes with escaped inner quotes, or `-d @body.json`:
```
curl.exe -i -X POST http://127.0.0.1:8080/api/v1/messages -H "Authorization: Bearer YOUR_API_KEY" -H "Content-Type: application/json" -H "Idempotency-Key: test-001" -d "{\"to\":\"+91XXXXXXXXXX\",\"message\":\"SimBridge test\"}"
```
Full reference: [docs/API.md](docs/API.md).

## Remote access (Cloudflare Tunnel)
Follow [docs/CLOUDFLARE_SETUP.md](docs/CLOUDFLARE_SETUP.md): create the tunnel, point the public hostname **only** at `http://127.0.0.1:8080`, paste the token in the Tunnel tab, press Start, then **Test public URL**. "Tunnel connected" only means cloudflared registered; the URL test is the proof.
```powershell
curl.exe -i https://sms-api.YOURDOMAIN.com/api/v1/health      # expect 401
curl.exe -H "Authorization: Bearer YOUR_API_KEY" https://sms-api.YOURDOMAIN.com/api/v1/health   # expect 200
```
Put a Cloudflare WAF rate-limit rule (and ideally Cloudflare Access) in front of the hostname.

## Honest limitations
- `SENT` ≠ `DELIVERED`. A 202 from the API only means the request was accepted.
- A foreground service raises the chance of running continuously but cannot guarantee it. Xiaomi/Poco and Android Go may stop apps; see Diagnostics and [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
- The cloudflared binary cannot be updated from inside the app (Android forbids running downloaded executables). Update = new checksum in `tools\fetch-cloudflared.ps1`, rebuild, reinstall.
- Release (R8-minified) builds have not been tested. Dependency vulnerability scanning has not been run.
- Send only to people who consented, and follow your carrier's terms and local law.

## Documentation
[Architecture](docs/ARCHITECTURE.md) · [API](docs/API.md) · [Android setup](docs/ANDROID_SETUP.md) · [Cloudflare setup](docs/CLOUDFLARE_SETUP.md) · [Security](docs/SECURITY.md) · [Testing](docs/TESTING.md) · [Troubleshooting](docs/TROUBLESHOOTING.md) · [Reference analysis](docs/REFERENCE_ANALYSIS.md) · [Plan](docs/IMPLEMENTATION_PLAN.md)

## Licences
SimBridge's own licence: **not yet chosen** (no `LICENSE` file until it is). Third-party components and their licences: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No code was copied from the two reference projects.
