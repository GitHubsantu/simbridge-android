# Release builds (32-bit, 64-bit, universal)

One build produces **three** signed APKs, and one helper script turns them into a ready-to-publish release.

| File in `dist\` | Contains | Use on | Size (1.0 build) |
|---|---|---|---|
| `SimBridge-<ver>-v7a-32bit.apk` | cloudflared for 32-bit ARM only | Poco C51 and other 32-bit phones | about 27 MB |
| `SimBridge-<ver>-v8a-64bit.apk` | cloudflared for 64-bit ARM only | 64-bit phones | about 27 MB |
| `SimBridge-<ver>-universal.apk` | both | anything ARM; when unsure | about 45 MB |

On a 32-bit phone the 64-bit APK will not install (`INSTALL_FAILED_NO_MATCHING_ABIS`), and vice versa. A phone can have a 64-bit CPU but a 32-bit userspace; check with `adb shell getprop ro.zygote` (`zygote32` → 32-bit APK). x86 devices/emulators are not supported.

The universal APK may also contain `lib/x86` and `lib/x86_64` folders from third-party dependencies. `tools\build-release.ps1` prints these as "unexpected ABI folder". They are harmless on ARM phones. To drop them and shrink the universal APK, add this to `packaging { jniLibs { ... } }` in `app\build.gradle.kts`:
```kotlin
excludes += setOf("lib/x86/**", "lib/x86_64/**")
```

## What the in-app updater expects (do not break these)
Since 1.1 the app updates itself from this repository's releases (`AppUpdater.REPO` = `GitHubsantu/simbridge-android`). A release only works for the updater if:

| Requirement | Why |
|---|---|
| The repository is **public** and the release is a normal, published release (not a draft, not a pre-release) | The app asks GitHub for `releases/latest`, unauthenticated |
| The tag looks like `v<versionName>`, e.g. `v1.1`, and equals `versionName` in `app\build.gradle.kts` with an optional `v` | Versions are compared as numbers; "latest" or "final" cannot be compared |
| `versionCode` is higher than the previous release | Android refuses an update that is not newer |
| An asset ending in `-64bit.apk` and one ending in `-32bit.apk` (and/or `-universal.apk`) is attached | The app picks the file for the phone's userspace and falls back to `-universal.apk` |
| An asset named exactly `SHA256SUMS.txt` is attached, with a line `<sha256>  <exact APK file name>` for every APK | The download is rejected without it |
| The APKs are signed with the **same key** as the installed app | Both SimBridge and Android refuse anything else |

`tools\make-release.ps1` produces files that satisfy all of this. Phones running 1.0 have no updater: they install the first updater release by hand once.

## One-time setup

### 1. Put the tunnel binaries in place
```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1      # both ABIs, latest upstream, checksum-verified
```
Add `-Version 2026.10.4` to pin an exact upstream release. Add `-PatchResolv` only if the unpatched binary fails with DNS errors, and use the **same choice as for the previous release**. Note the line `Using cloudflared <version>` it prints: you need it for the release notes.

The folder `app\src\main\jniLibs\` is **git-ignored**. A fresh clone, a CI machine or a different PC will not have it, and a build made there would ship without the tunnel. The release build **refuses to run** if either binary is missing.

### 2. Create a signing key (once, keep it forever)
Unsigned release APKs cannot be installed. Use the keytool that ships with Android Studio. Keeping the key outside the project folder is safer:
```powershell
& "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore C:\AndroidKeys\simbridge-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias simbridge
```
Create `keystore.properties` in the project root (same folder as `gradlew.bat`):
```properties
storeFile=C:/AndroidKeys/simbridge-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=simbridge
keyPassword=YOUR_KEY_PASSWORD
```
**Use forward slashes (or just a file name for a keystore in the project root).** In a `.properties` file `\` is an escape character; `C:\AndroidKeys\...` is mangled and the build fails with `validateSigningRelease ... Invalid file path`. No quotes around values. A relative `storeFile` is resolved from the project root.

Both `keystore.properties` and `*.jks` are in `.gitignore`. **Never commit them, never paste them anywhere.** Back the `.jks` up somewhere safe (offline): if you lose it you cannot update an installed app, and anyone who gets it can publish apps that replace yours, including through the in-app updater.

### 3. Signature schemes
`app\build.gradle.kts` enables APK Signature Schemes **v1, v2 and v3** in the `release` signing config (`enableV1Signing`, `enableV2Signing`, `enableV3Signing`). `tools\make-release.ps1` checks all three on every APK.

## Make a release (example: 1.1)

### Step 1. Set the version
In `app\build.gradle.kts`, `defaultConfig`:
```kotlin
versionCode = 2        // must be higher than the last release
versionName = "1.1"
```
The scripts and the in-app updater read these. Forgetting this step is the most common mistake: the APKs would be named with the old version and the updater would see "no newer version".

### Step 2. Prepare the text
- Add the release's section to `CHANGELOG.md` (already drafted for 1.1).
- Check `publish\RELEASE_NOTES_v<version>.md` (the template). It contains placeholders like `{{CLOUDFLARED}}` and `{{SHA_64}}` that the script fills in; do not fill them by hand.
- Update `README.md` and the docs if behaviour changed (screenshots too: the Diagnostics screen gained an *App update* card in 1.1).

### Step 3. Test and build
```powershell
.\gradlew.bat test
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1
.\gradlew.bat clean
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```
`build-release.ps1` runs `assembleRelease`, opens each APK, checks that `lib/<abi>/libcloudflared.so` is inside and **byte-identical** to the verified file, prints each APK's SHA-256, and copies the APKs to `dist\`. If you changed `keystore.properties` and Gradle seems to ignore it, run once with `.\gradlew.bat assembleRelease --no-configuration-cache`.

### Step 4. Checksums, signature check, release notes
```powershell
powershell -ExecutionPolicy Bypass -File tools\make-release.ps1 -Cloudflared 2026.10.6
```
Replace `2026.10.6` with the cloudflared version from step 1 (the Tunnel tab shows the same number under "Bundled in this app"). Add `-PatchedResolv` if you built with `-PatchResolv`. The script:
- expects exactly `SimBridge-<ver>-v7a-32bit.apk`, `-v8a-64bit.apk` and `-universal.apk` in `dist\` and **ignores everything else** there (old versions never end up in the checksums);
- runs `apksigner verify` on each: v1, v2 and v3 must be `true` and all three APKs must share one certificate;
- writes `dist\SHA256SUMS.txt` (plain `<sha256>  <file name>` lines, LF, no BOM);
- writes `dist\RELEASE_NOTES_v<ver>.md` with versions, sizes, hashes and the certificate fingerprint filled in.

It stops with a clear message if anything is wrong. If apksigner is not found automatically, pass `-ApkSigner "C:\...\build-tools\<version>\apksigner.bat"`.

### Step 5. Test the APKs on phones
Run the release checklist in [TESTING.md](TESTING.md) with the APK that matches each phone. Uninstall a debug build first (different key). Upgrading an installed release with `adb install -r dist\SimBridge-1.1-v7a-32bit.apk` keeps your settings, API key hash, token and history.

### Step 6. Publish
```powershell
git add .
git commit -m "Release 1.1"
git tag -a v1.1 -m "SimBridge 1.1"
git push origin main --tags
gh release create v1.1 `
  dist\SimBridge-1.1-v7a-32bit.apk dist\SimBridge-1.1-v8a-64bit.apk dist\SimBridge-1.1-universal.apk dist\SHA256SUMS.txt `
  --title "SimBridge 1.1 - in-app updates" --notes-file dist\RELEASE_NOTES_v1.1.md
```
(Or upload the same four files on the GitHub *Releases → Draft a new release* page and paste the notes.) Upload only the three APKs and `SHA256SUMS.txt`. Never upload the `.jks`, `keystore.properties` or a debug APK. Do not tick "pre-release": the in-app updater ignores pre-releases.

Before pushing, `git status` must not list `keystore.properties`, any `.jks`, `jniLibs\` or `dist\`.

### Step 7. After publishing
- Open the release page and check the four files and the notes.
- Download one APK and compare `Get-FileHash` with `SHA256SUMS.txt`.
- Phones on 1.0: install the 1.1 APK by hand once. Phones on 1.1 or later: *Diagnostics → App update → Check for update*.

## Manual checks
Check the signatures yourself (the script does the same):
```powershell
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs dist\SimBridge-1.1-v8a-64bit.apk
```
Expect `Verified using v1 scheme ... true`, `v2 ... true`, `v3 ... true`. The printed **certificate SHA-256 digest** must be identical for every APK and every release.

Check the packaged binaries:
```powershell
tar -tf app\build\outputs\apk\release\app-universal-release.apk | findstr /i "lib/"
```
You should see `lib/armeabi-v7a/libcloudflared.so` and `lib/arm64-v8a/libcloudflared.so` (universal), or just one of them (the split APKs).

## Install
```powershell
adb install -r dist\SimBridge-1.1-v7a-32bit.apk
```
Installing over an existing build signed with the **same** key keeps your data. A build signed with a **different** key (for example your debug build) must be uninstalled first (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which deletes that data.

## If the library is "not included"
1. `dir app\src\main\jniLibs\*\libcloudflared.so` must list both ABIs. If not, run the fetch script on **this** machine.
2. Build with `tools\build-release.ps1` and read its output; it names the missing ABI.
3. Check the APK with the `tar -tf` command above. If the file is in the APK but the app says `MISSING`, you installed the wrong ABI build for the phone (see the table).
4. If the verification says "DIFFERENT from the verified file", something modified it; `keepDebugSymbols` in `app\build.gradle.kts` is meant to prevent Android's stripping step from touching it.
5. Still stuck: send the output of the script and the `tar -tf` line.

## What was NOT tested
- Minification (R8) is switched **off** for release on purpose; it has not been tested with Ktor/Room here.
- The in-app update has not been run end to end on a phone (it needs a release newer than the installed one).
- Google Play uploads need an app bundle and extra policy work (battery-optimization, SMS and install-packages permissions are restricted); this setup is for sideloading.
