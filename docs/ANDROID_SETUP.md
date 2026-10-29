# Android setup (Windows) — Milestone 1

## 1. Install
1. Install the latest **stable Android Studio** from developer.android.com/studio. Use its bundled JDK (Settings → Build Tools → Gradle → Gradle JDK = *jbr*/embedded). Don't install a separate JDK for this.
2. Android Studio → *SDK Manager*: install the latest stable **Android SDK Platform**, **Platform-Tools**, and **Build-Tools** it offers. Let the wizard pick compatible Gradle/AGP versions — do not hand-edit versions.
3. Phone (Poco C51): Settings → About phone → tap *MIUI/OS version* 7× → Developer options → enable **USB debugging** (and *Install via USB* if shown). Authorize the PC on the phone.

## 2. Create the project
*File → New → New Project → **Empty Activity*** (the Compose one), then:
- Name: `SimBridge Gateway`
- Package name: `in.imdevops.simbridge` (see the keyword caveat in ARCHITECTURE.md — change it now if you prefer)
- Save location: `C:\dev\simbridge-android`
- Minimum SDK: **API 26**
- Build configuration language: **Kotlin DSL**

Wait for the first Gradle sync to finish.

## 3. Overlay the starter files
Copy from this download into your project, replacing the generated file:
- `app/src/main/java/in/imdevops/simbridge/MainActivity.kt` → replaces the generated `MainActivity.kt` (if the wizard created it under `app/src/main/java/in/imdevops/simbridge/` it is the same path)
- `.gitignore` → project root (merge with the generated one if you prefer)
- `docs/` → project root

If the wizard also generated `ui/theme/*`, leave it; the starter screen doesn't use it.

## 4. Build and run
PowerShell, in the project folder:
```powershell
cd C:\dev\simbridge-android
.\gradlew.bat assembleDebug
```
Expected: `BUILD SUCCESSFUL`; APK at `app\build\outputs\apk\debug\app-debug.apk`. **I could not run this build myself**, so if it fails, paste the first error block and I'll fix the cause.

Install on the phone:
```powershell
adb devices                      # your phone must show as "device"
.\gradlew.bat installDebug
```

## 5. What to send back
1. The app screen (or text): *This process is 64-bit*, *Supported ABIs*, *Native library dir*.
2. Output of:
```powershell
adb shell getprop ro.product.cpu.abilist
adb shell getprop ro.zygote
adb shell getprop ro.build.version.sdk
```
These decide the tunnel binary architecture (Milestone 5) and confirm minSdk.

## 6. Git
```powershell
git init
git add .
git commit -m "Milestone 1: project skeleton and docs"
```
Never commit tokens, API keys, keystores or downloaded binaries.
