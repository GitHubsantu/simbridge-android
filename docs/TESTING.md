# Testing

## Automated (PowerShell, project root)
```powershell
.\gradlew.bat test                      # JVM unit + in-process API tests (fake SMS backend, no SMS sent)
.\gradlew.bat connectedDebugAndroidTest # on-device Room persistence test (phone connected)
```
Unit tests cover: key generation/hash/bearer parsing, phone validation, SIM resolution, state transitions, rate policy, failed-auth throttle, request parsing, MessageService (limits, idempotency incl. concurrent duplicates, failure handling), and the real Ktor routes (401/400/411-style/413/415/422/429/502/202, concurrency).
Not covered by automation: real carrier behaviour, foreground-service lifecycle, screen-off operation, tunnel.

## Manual end-to-end — four separate questions
1. **HTTP accepted:** `POST /messages` returns 202 with a `message_id`.
2. **Android accepted the submission:** status becomes `SUBMITTING` (no immediate 502).
3. **Radio/carrier accepted:** `GET /messages/{id}` shows `SENT`.
4. **Delivery receipt:** `DELIVERED` — optional, carrier-dependent. A 202 or `SENT` is not proof of delivery.

Real-SMS tests are manual only: use your own number and respect limits.

## Device checklist for Milestone 4
- Install over the existing build (exercises the Room 1→2 migration; history from before must still be listed).
- 401 without key; 202 with key; same `Idempotency-Key` twice → second is 200 replay and only one SMS arrives.
- Send > per-minute limit quickly → 429 with `Retry-After`.
- Oversized body → 413. Wrong `Content-Type` → 415.
