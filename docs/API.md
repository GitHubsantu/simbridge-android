# SimBridge REST API

Base URL (local): `http://127.0.0.1:8080/api/v1` — the server listens on loopback only.
Every endpoint requires `Authorization: Bearer <API_KEY>`. The key is never accepted in the URL or body.

| Method | Path | Notes |
|---|---|---|
| GET | `/health` | `{"success":true,"status":"ok","server":"running","version":...}` |
| GET | `/info` | capabilities, SMS-permission state, active limits |
| POST | `/messages` | Queue one SMS. See below |
| GET | `/messages/{id}` | Status of one message (`id` = 32 lowercase hex) |
| GET | `/messages?limit=20&offset=0` | Newest first, limit 1–100. Message text is never returned |
| GET | `/tunnel/status` | Milestone 5 (not implemented) |

## POST /messages

Headers: `Authorization`, `Content-Type: application/json`, **`Content-Length`** (chunked bodies are refused), optional `Idempotency-Key`.

```json
{ "to": "+91XXXXXXXXXX", "message": "SimBridge test message", "subscription_id": 4, "request_delivery": true }
```
`subscription_id` and `request_delivery` are optional. Numbers must be international format (`+` and country code). Body limit 8192 bytes; message length limit comes from the app setting (default 320 characters).

**202 Accepted**
```json
{ "success": true, "message_id": "…32 hex…", "status": "SUBMITTING", "message": "The message has been accepted for processing." }
```
`status` is the real current state, never an optimistic `SENT`/`DELIVERED`. Poll `GET /messages/{id}`.

### Idempotency
Send `Idempotency-Key: <1–64 chars of A-Z a-z 0-9 _ . : ->` and retry with the **same key and identical body** after a timeout: you get `200` with `"idempotent_replay": true` and the original `message_id`; nothing is sent twice. Same key with a different body → `422`. Keys are remembered for 24 h, across app restarts. Without a key there is no duplicate protection.

### Errors
`{"success":false,"error":{"code":"…","message":"…"}}`

| HTTP | code | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST`, `INVALID_NUMBER`, `EMPTY_MESSAGE`, `MESSAGE_TOO_LONG`, `INVALID_SUBSCRIPTION`, `SIM_SELECTION_REQUIRED`, `INVALID_IDEMPOTENCY_KEY` | Fix the request. With two active SIMs and no default SMS SIM, `subscription_id` is required |
| 401 | `UNAUTHORIZED` | Missing/wrong key |
| 408 | `REQUEST_TIMEOUT` | Body not received within 10 s |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Original request still running; retry shortly |
| 411 / 413 / 415 | `LENGTH_REQUIRED` / `PAYLOAD_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` | |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key, different body |
| 429 | `RATE_LIMITED`, `TOO_MANY_FAILED_ATTEMPTS` | `Retry-After` header set |
| 502 | `SUBMIT_FAILED` etc. | Android refused the submission; body includes `message_id` and `status:"FAILED"`. **Not retried automatically** |
| 503 | `BUSY`, `PERMISSION_DENIED`, `NO_ACTIVE_SIM` | Server-side condition |

Statuses: `QUEUED → SUBMITTING → SENT | FAILED`, then `DELIVERED | DELIVERY_FAILED`. `SENT` = the phone's radio accepted every part. `DELIVERED` = a carrier receipt arrived; many carriers never send one.

### Examples (PowerShell; use `curl.exe`)
```powershell
adb forward tcp:8080 tcp:8080
$k = "YOUR_API_KEY"
curl.exe -H "Authorization: Bearer $k" http://127.0.0.1:8080/api/v1/info
curl.exe -X POST http://127.0.0.1:8080/api/v1/messages `
  -H "Authorization: Bearer $k" -H "Content-Type: application/json" -H "Idempotency-Key: test-001" `
  -d '{\"to\":\"+91XXXXXXXXXX\",\"message\":\"SimBridge test\"}'
```
(PowerShell needs the `\"` escaping shown; from Linux/macOS use normal single-quoted JSON.)
