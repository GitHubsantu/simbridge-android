# Troubleshooting

| Symptom | Likely cause / what to do |
|---|---|
| `curl` shows `Could not resolve host: API` and a JSON error | You used single quotes in Windows **cmd**. Use `-d "{\"to\":...}"` or `-d @body.json` |
| 401 `UNAUTHORIZED` | Missing/wrong key. Keys are shown once; generate a new one on the API tab |
| 429 `RATE_LIMITED` with `Retry-After` | Sending limit reached (API tab → Sending limits). Wait or raise it |
| 429 `TOO_MANY_FAILED_ATTEMPTS` | 20+ bad keys in a minute. A valid key still works |
| 400 `SIM_SELECTION_REQUIRED` | Two SIMs and no default: choose a SIM on the API tab or send `subscription_id` |
| 503 `PERMISSION_DENIED` / `NO_ACTIVE_SIM` | Grant SMS + phone-state permission; check the SIM is active |
| Message stays `SENT`, never `DELIVERED` | Normal: many carriers send no delivery receipt |
| Tunnel `Binary: WRONG_ARCH` | A 64-bit binary on a 32-bit phone. Re-run `tools\fetch-cloudflared.ps1` (it fetches the 32-bit build) |
| Tunnel log shows DNS errors | Re-run the script with `-PatchResolv`, rebuild (see CLOUDFLARE_SETUP.md) |
| Public URL test: HTTP 502/530 | Tunnel not connected, or the dashboard service URL is not `127.0.0.1:8080` |
| Gateway stops after a while | Diagnostics → Service events show why. Set battery to "No restrictions", enable Autostart, lock in recents (Xiaomi/Poco). Android Go closes apps when memory is low |
| Crash on first start after an update | Room migration problem: send me the logcat error |
