# Security checklist (status as of Milestone 4)

A phone that can send SMS on demand is a cost- and abuse-sensitive endpoint. **Any publicly reachable URL must be authenticated, even behind Cloudflare Tunnel.**

| Control | Status |
|---|---|
| Server bound to `127.0.0.1` only | Implemented; verified by code, loopback curl works from the PC only via `adb forward` |
| Bearer authentication on every endpoint | Implemented, tested (unit + route tests); verified on device for health/messages |
| 256-bit random key (`SecureRandom`), shown once, only SHA-256 stored, constant-time hash compare | Implemented |
| Key never accepted in URL/body; never logged | Implemented (no logging of requests) |
| Failed-auth throttling (429 after 20/min; valid key never blocked) | Implemented, unit-tested |
| Send limits per minute/day (default 5/100), derived from persisted history so restarts don't reset | Implemented, unit-tested; user-configurable |
| Max message length (default 320) and body size (8 KB), `Content-Length` required, 10 s body timeout, idle timeout = Ktor CIO default | Implemented. Idle timeout value not tuned |
| Bounded queue (16 pending, serialized sends) | Implemented, unit-tested through the service |
| Idempotency keys (24 h, persisted) | Implemented; concurrency unit-tested with in-memory store; Room migration **not yet run on device** |
| No automatic SMS retry | Implemented by design |
| No CORS headers; no wildcard | Implemented (none sent) |
| Message text never stored; recipients kept 30 days | Implemented |
| Minimal permissions (SEND_SMS, READ_PHONE_STATE, INTERNET) | Implemented |
| `allowBackup=false` | Implemented |
| IP allow-listing | **Not implemented.** Origin is always loopback, so the source IP would be cloudflared, not the caller; use Cloudflare Access / WAF rules instead |
| Tunnel token encrypted with an Android Keystore AES-GCM key; passed to cloudflared via environment, not argv; log lines redacted | Implemented (Milestone 5), unverified on device. Same-UID processes could still read the environment |
| API key stored hashed (not Keystore-wrapped) | Acceptable: nothing secret to protect; revisit if keys become retrievable |
| TLS | Provided by Cloudflare at the edge; loopback hop is plain HTTP by design |

Operational advice: generate a fresh key when moving from testing to real use; never paste keys into chats or commits; add a Cloudflare WAF rate-limit rule and optionally Cloudflare Access service tokens in front of `sms-api.YOURDOMAIN.com`; send only to recipients who have consented, and respect your carrier's SMS terms and local messaging law.
