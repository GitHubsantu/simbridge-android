<#
.SYNOPSIS
  Builds the release APKs (32-bit, 64-bit and universal), proves each one contains the cloudflared binary,
  and copies them to .\dist with clear names.

.DESCRIPTION
  One Gradle run produces all three APKs. This script then opens every APK as a zip, lists the
  libcloudflared.so inside it and compares its SHA-256 with the verified file in app\src\main\jniLibs,
  so a "library not included" problem is caught here instead of on the phone.

  Prerequisites (once):
    1. powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1        (both ABIs)
    2. A release keystore + keystore.properties (see docs\RELEASE.md). Without them the APKs are UNSIGNED
       and Android will refuse to install them.

  Run from the project root:   powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- 1. inputs present?
$libs = @{
    "armeabi-v7a" = Join-Path $root "app\src\main\jniLibs\armeabi-v7a\libcloudflared.so"
    "arm64-v8a"   = Join-Path $root "app\src\main\jniLibs\arm64-v8a\libcloudflared.so"
}
foreach ($abi in $libs.Keys) {
    if (-not (Test-Path $libs[$abi])) { throw "Missing $($libs[$abi]). Run tools\fetch-cloudflared.ps1 first." }
}
$signed = Test-Path (Join-Path $root "keystore.properties")
if (-not $signed) { Write-Warning "keystore.properties not found: the APKs will be UNSIGNED and cannot be installed. See docs\RELEASE.md." }

# --- 2. build
& (Join-Path $root "gradlew.bat") assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

# --- 3. find and verify the three APKs
$apkDir = Join-Path $root "app\build\outputs\apk\release"
$plan = @(
    @{ Pattern = "*armeabi-v7a*.apk"; Name = "v7a-32bit";     Expect = @("armeabi-v7a") },
    @{ Pattern = "*arm64-v8a*.apk";   Name = "v8a-64bit";     Expect = @("arm64-v8a") },
    @{ Pattern = "*universal*.apk";   Name = "universal"; Expect = @("armeabi-v7a", "arm64-v8a") }
)
$versionName = (Select-String -Path (Join-Path $root "app\build.gradle.kts") -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$failed = $false

foreach ($p in $plan) {
    $apk = Get-ChildItem $apkDir -Filter $p.Pattern -File | Select-Object -First 1
    if (-not $apk) { Write-Host "[$($p.Name)] NOT FOUND in $apkDir" -ForegroundColor Red; $failed = $true; continue }

    $zip = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
    try {
        $found = @{}
        foreach ($e in ($zip.Entries | Where-Object { $_.FullName -like "lib/*/libcloudflared.so" })) {
            $abi = $e.FullName.Split("/")[1]
            $stream = $e.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            $found[$abi] = (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString("x2") }) -join "")
            $stream.Dispose()
        }
        $allLibAbis = @($zip.Entries | Where-Object { $_.FullName -like "lib/*/*" } | ForEach-Object { $_.FullName.Split("/")[1] } | Select-Object -Unique)
    } finally { $zip.Dispose() }

    $ok = $true
    foreach ($abi in $p.Expect) {
        if (-not $found.ContainsKey($abi)) { Write-Host "[$($p.Name)] MISSING lib/$abi/libcloudflared.so" -ForegroundColor Red; $ok = $false; continue }
        $src = (Get-FileHash $libs[$abi] -Algorithm SHA256).Hash.ToLower()
        if ($found[$abi] -eq $src) { Write-Host "[$($p.Name)] lib/$abi/libcloudflared.so present and byte-identical to the verified file" -ForegroundColor Green }
        else { Write-Host "[$($p.Name)] lib/$abi/libcloudflared.so present but DIFFERENT from the verified file (was it modified or stripped?)" -ForegroundColor Red; $ok = $false }
    }
    foreach ($abi in $allLibAbis) {
        if ($p.Expect -notcontains $abi) { Write-Host "[$($p.Name)] unexpected ABI folder lib/$abi" -ForegroundColor Yellow }
    }
    if (-not $ok) { $failed = $true; continue }

    $out = Join-Path $dist ("SimBridge-$versionName-$($p.Name).apk")
    Copy-Item $apk.FullName $out -Force
    $hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
    $mb = [math]::Round((Get-Item $out).Length / 1MB, 1)
    $state = if ($apk.Name -like "*unsigned*") { "UNSIGNED (cannot be installed)" } else { "signed" }
    Write-Host "[$($p.Name)] -> $out  ($mb MB, $state)`n           SHA-256 $hash"
}
if ($failed) { throw "One or more APKs failed verification. Do not distribute them." }
Write-Host "`nAll APKs verified. Files are in $dist"
