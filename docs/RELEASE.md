# Release builds (32-bit, 64-bit, universal)

One command builds **three** signed APKs:

| File in `dist\` | Contains | Use on | Size (1.0 build) |
|---|---|---|---|
| `SimBridge-<ver>-32bit.apk` | cloudflared for 32-bit ARM only | Poco C51 and other 32-bit phones | about 27 MB |
| `SimBridge-<ver>-64bit.apk` | cloudflared for 64-bit ARM only | 64-bit phones | about 27 MB |
| `SimBridge-<ver>-universal.apk` | both | anything ARM; when unsure | about 45 MB |

Pick the right one: on a 32-bit phone the 64-bit APK will not install (`INSTALL_FAILED_NO_MATCHING_ABIS`), and vice versa. A phone can have a 64-bit CPU but a 32-bit userspace; check with `adb shell getprop ro.zygote` (`zygote32` → 32-bit APK). x86 devices/emulators are not supported.

The universal APK may also contain `lib/x86` and `lib/x86_64` folders from third-party dependencies. `tools\build-release.ps1` prints these as "unexpected ABI folder". They are harmless on ARM phones (there is simply no tunnel binary for x86). To drop them and shrink the universal APK, add this to `packaging { jniLibs { ... } }` in `app\build.gradle.kts`:
```kotlin
excludes += setOf("lib/x86/**", "lib/x86_64/**")
```

## One-time setup

### 1. Put the tunnel binaries in place
```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1      # both ABIs, checksum-verified
```
The folder `app\src\main\jniLibs\` is **git-ignored**. A fresh clone, a CI machine or a different PC will not have it, and a build made there would ship without the tunnel. That is the most likely reason for a "library not included" release. The release build **refuses to run** if either binary is missing.

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

Both `keystore.properties` and `*.jks` are in `.gitignore`. **Never commit them, never paste them anywhere.** Back the `.jks` up somewhere safe (offline): if you lose it you cannot update an installed app, and anyone who gets it can publish apps that replace yours.

### 3. Signature schemes
`app\build.gradle.kts` enables APK Signature Schemes **v1, v2 and v3** in the `release` signing config (`enableV1Signing`, `enableV2Signing`, `enableV3Signing`). With `minSdk 26` v2 is enough for every supported device; v1 is kept for tools that still check it.

## Build and verify
```powershell
.\gradlew.bat clean
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```
It runs `gradlew assembleRelease`, opens each APK, checks that `lib/<abi>/libcloudflared.so` is inside and **byte-identical** to the verified file, prints each APK's SHA-256, and copies them to `dist\`. If anything is off it stops and tells you. If you changed `keystore.properties` and Gradle seems to ignore it, run once with `.\gradlew.bat assembleRelease --no-configuration-cache`.

Check the signatures (use your own `build-tools` version folder):
```powershell
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
& "$($bt.FullName)\apksigner.bat" verify --verbose --print-certs dist\SimBridge-1.0-64bit.apk
```
Expect `Verified using v1 scheme ... true`, `v2 ... true`, `v3 ... true`. The printed **certificate SHA-256 digest** is your signing-certificate fingerprint: publish it in the release notes so people can confirm that updates come from the same key.

Manual check of the packaged binaries:
```powershell
tar -tf app\build\outputs\apk\release\app-universal-release.apk | findstr /i "lib/"
```
You should see `lib/armeabi-v7a/libcloudflared.so` and `lib/arm64-v8a/libcloudflared.so` (universal), or just one of them (the split APKs).

## Install
```powershell
adb install -r dist\SimBridge-1.0-32bit.apk
```
Installing over an existing build signed with the **same** key keeps your settings, API key hash, token and history. A build signed with a **different** key (for example your debug build) must be uninstalled first (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), which deletes that data.

## Publish a release (GitHub)
1. **Test first.** Uninstall the debug build, install the release APK that matches your phone, then check: Tunnel → *Check cloudflared binary*, *Test public URL*, and send one real SMS. See the release checklist in [TESTING.md](TESTING.md).
2. **Bump the version** for anything after 1.0: raise `versionCode` (must increase) and `versionName` in `app\build.gradle.kts`; add an entry to `CHANGELOG.md`.
3. **Licences:** copy the upstream cloudflared `LICENSE` text to `licenses\cloudflared-LICENSE.txt` (see [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)).
4. **Build** as above, then create checksums:
   ```powershell
   Get-FileHash dist\*.apk -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $_.Path -Leaf)" } | Set-Content dist\SHA256SUMS.txt -Encoding ascii
   ```
   Rebuilding changes the APK hashes; always regenerate the checksums from the files you actually upload.
5. **Check git** is clean of secrets: `git status` must not list `keystore.properties`, any `.jks`, `jniLibs\` or `dist\`.
6. **Tag and release:**
   ```powershell
   git add .
   git commit -m "Release 1.0"
   git tag -a v1.0 -m "SimBridge 1.0"
   git push origin main --tags
   gh release create v1.0 dist\SimBridge-1.0-32bit.apk dist\SimBridge-1.0-64bit.apk dist\SimBridge-1.0-universal.apk dist\SHA256SUMS.txt `
     --title "SimBridge 1.0 — SMS gateway over Cloudflare Tunnel" --notes-file publish\RELEASE_NOTES_v1.0.md
   ```
   (Or upload the same files on the GitHub *Releases → Draft a new release* page.) Upload only the three APKs and `SHA256SUMS.txt`. Never upload the `.jks`, `keystore.properties` or the debug APK.

## If the library is "not included"
1. `dir app\src\main\jniLibs\*\libcloudflared.so` must list both ABIs. If not, run the fetch script on **this** machine.
2. Build with `tools\build-release.ps1` (or `.\gradlew.bat assembleRelease`) and read its output; it names the missing ABI.
3. Check the APK with the `tar -tf` command above. If the file is in the APK but the app says `MISSING`, you installed the wrong ABI build for the phone (see the table).
4. If the verification says "DIFFERENT from the verified file", something modified it; `keepDebugSymbols` in `app\build.gradle.kts` is meant to prevent Android's stripping step from touching it.
5. Still stuck: send the output of the script and the `tar -tf` line.

## What was NOT tested
- Minification (R8) is switched **off** for release on purpose; it has not been tested with Ktor/Room here.
- Google Play uploads need an app bundle and extra policy work (battery-optimization and SMS permissions are restricted); this setup is for sideloading.
