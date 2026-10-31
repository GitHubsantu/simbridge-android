# Troubleshooting

## Using the API
| Symptom | Likely cause / what to do |
|---|---|
| `curl` shows `Could not resolve host: API` and a JSON error | You used single quotes in Windows **cmd**. Use `-d "{\"to\":...}"` or `-d @body.json` |
| 401 `UNAUTHORIZED` | Missing/wrong key. Keys are shown once; generate a new one on the API tab |
| 429 `RATE_LIMITED` with `Retry-After` | Sending limit reached (API tab → Sending limits). Wait or raise it |
| 429 `TOO_MANY_FAILED_ATTEMPTS` | 20+ bad keys in a minute. A valid key still works |
| 400 `SIM_SELECTION_REQUIRED` | Two SIMs and no default: choose a SIM on the API tab or send `subscription_id` |
| 503 `PERMISSION_DENIED` / `NO_ACTIVE_SIM` | Grant SMS + phone-state permission; check the SIM is active |
| 403 `DESTINATION_NOT_ALLOWED` | Number is in a blocked premium/satellite range, or outside your country-prefix allow-list (API tab) |
| Message stays `SENT`, never `DELIVERED` | Normal: many carriers send no delivery receipt |

## Tunnel and service
| Symptom | Likely cause / what to do |
|---|---|
| Tunnel `Binary: WRONG_ARCH` | Wrong-architecture binary for this phone. Re-run `tools\fetch-cloudflared.ps1` (fetches both) and rebuild |
| Tunnel `Binary: MISSING` on a release APK | You installed the wrong ABI build (e.g. the 64-bit APK on a phone that runs a 32-bit userspace is normally refused at install; a mismatch otherwise shows here). Install the right APK or the universal one |
| Tunnel log shows DNS errors | Re-run the script with `-PatchResolv`, rebuild (see CLOUDFLARE_SETUP.md) |
| Public URL test: HTTP 502/530 | Tunnel not connected, or the dashboard service URL is not `127.0.0.1:8080` |
| Gateway stops after a while | Diagnostics → Service events show why. Set battery to "No restrictions", enable Autostart, lock in recents (Xiaomi/Poco). Android Go closes apps when memory is low |
| Crash on first start after an update | Room migration problem: send the logcat error |

## Installing the APK
| Symptom | Likely cause / what to do |
|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | The APK's ABI does not match the phone. Check `adb shell getprop ro.zygote` (`zygote32` → 32-bit APK) or use the universal APK |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "signatures do not match" | The installed copy was signed with a different key (e.g. the debug build). Uninstall it first; this deletes the app's data |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | The APK's `versionCode` is lower than the installed one. Raise `versionCode` or uninstall |
| "App not installed" / package appears corrupt | The APK is unsigned (`...-unsigned.apk`) or the download is damaged. Compare its SHA-256 with `SHA256SUMS.txt` |
| Phone blocks the install | Allow "install unknown apps" for the app you are opening the file with (file manager or browser) |

## Building a release
| Symptom | Likely cause / what to do |
|---|---|
| `Task :app:validateSigningRelease FAILED ... Invalid file path` | `storeFile` in `keystore.properties` uses backslashes. Use forward slashes (`C:/AndroidKeys/x.jks`) or a plain file name; no quotes. Then rebuild once with `--no-configuration-cache` |
| `Missing ...libcloudflared.so. Run tools\fetch-cloudflared.ps1` | `jniLibs` is git-ignored; run the fetch script on this machine |
| `.\gradlew.bat` not recognised in PowerShell | PowerShell needs the `.\` prefix |
| Script says "APKs will be UNSIGNED" | `keystore.properties` is missing from the project root |
| `unexpected ABI folder lib/x86` | Harmless: dependency libraries for x86 inside the universal APK. See RELEASE.md to exclude them |
| Gradle ignores a changed `keystore.properties` | Configuration cache: run `.\gradlew.bat assembleRelease --no-configuration-cache` once |
