<#
.SYNOPSIS
  Run AFTER tools\build-release.ps1. Checks the three APKs of the current version, verifies their signatures,
  writes dist\SHA256SUMS.txt and fills in the release notes.

.DESCRIPTION
  What it does, in order:
    1. Reads versionName from app\build.gradle.kts and expects exactly these files in dist\:
         SimBridge-<ver>-v7a-32bit.apk   SimBridge-<ver>-v8a-64bit.apk   SimBridge-<ver>-universal.apk
       Other files in dist\ (older versions) are listed and ignored, so they never end up in the checksums.
    2. Runs apksigner on each APK: signature schemes v1, v2 and v3 must all verify, and all three APKs must be
       signed with the same certificate. The certificate SHA-256 is printed.
    3. Writes dist\SHA256SUMS.txt (UTF-8 without BOM, LF line endings, "<sha256>  <file name>").
       The in-app updater downloads this exact file name and reads these lines, so do not rename it.
    4. Fills publish\RELEASE_NOTES_v<ver>.md into dist\RELEASE_NOTES_v<ver>.md (versions, sizes, hashes, certificate).
    5. Prints the commands to tag and publish.

  -Cloudflared   The cloudflared version bundled in this build (the line "Using cloudflared X" printed by
                 tools\fetch-cloudflared.ps1, or "Bundled in this app" on the app's Tunnel tab). Required.
  -PatchedResolv Pass this if you built with fetch-cloudflared.ps1 -PatchResolv. The release notes then say the
                 binary is modified.
  -ApkSigner     Full path to apksigner(.bat). Found automatically in the Android SDK if omitted.
  -SkipSignatureCheck  For testing the script only. Never use it for a real release.

  Run from the project root:
    powershell -ExecutionPolicy Bypass -File tools\make-release.ps1 -Cloudflared 2026.10.6
#>
param(
    [Parameter(Mandatory = $true)] [string]$Cloudflared,
    [switch]$PatchedResolv,
    [string]$ApkSigner = "",
    [switch]$SkipSignatureCheck
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($Cloudflared -notmatch '^\d{4}\.\d+\.\d+$') { throw "-Cloudflared must look like 2026.10.6 (got '$Cloudflared')." }

# --- 1. version and files
$gradle = Join-Path (Join-Path $root "app") "build.gradle.kts"
$gradleText = Get-Content $gradle -Raw
$vn = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$vc = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
if (-not $vn.Success -or -not $vc.Success) { throw "Could not read versionName/versionCode from $gradle." }
$version = $vn.Groups[1].Value
Write-Host "Version $version (versionCode $($vc.Groups[1].Value)), cloudflared $Cloudflared"

$dist = Join-Path $root "dist"
$names = [ordered]@{
    "32"  = "SimBridge-$version-v7a-32bit.apk"
    "64"  = "SimBridge-$version-v8a-64bit.apk"
    "uni" = "SimBridge-$version-universal.apk"
}
foreach ($k in $names.Keys) {
    if (-not (Test-Path (Join-Path $dist $names[$k]))) { throw "Missing dist\$($names[$k]). Run tools\build-release.ps1 first (and check versionName in app\build.gradle.kts)." }
}
$wanted = @($names.Values) + @("SHA256SUMS.txt", "RELEASE_NOTES_v$version.md")
$others = @(Get-ChildItem $dist -File | Where-Object { $wanted -notcontains $_.Name })
foreach ($o in $others) { Write-Host "Ignoring (not part of $version): dist\$($o.Name)" -ForegroundColor Yellow }

# --- 2. signatures
$certs = @{}
if ($SkipSignatureCheck) {
    Write-Warning "Signature check skipped. Do NOT publish this build."
    foreach ($k in $names.Keys) { $certs[$k] = "(not checked)" }
} else {
    if (-not $ApkSigner) {
        $sdkRoots = @()
        if ($env:ANDROID_HOME) { $sdkRoots += $env:ANDROID_HOME }
        if ($env:ANDROID_SDK_ROOT) { $sdkRoots += $env:ANDROID_SDK_ROOT }
        if ($env:LOCALAPPDATA) { $sdkRoots += (Join-Path (Join-Path $env:LOCALAPPDATA "Android") "Sdk") }
        foreach ($r in $sdkRoots) {
            $bt = Join-Path $r "build-tools"
            if (-not (Test-Path $bt)) { continue }
            $dirs = Get-ChildItem $bt -Directory | Sort-Object { try { [version]($_.Name -replace '-.*$', '') } catch { [version]"0.0" } } -Descending
            foreach ($d in $dirs) {
                $cand = Join-Path $d.FullName "apksigner.bat"
                if (Test-Path $cand) { $ApkSigner = $cand; break }
            }
            if ($ApkSigner) { break }
        }
    }
    if (-not $ApkSigner -or -not (Test-Path $ApkSigner)) { throw "apksigner not found. Install Android SDK Build-Tools or pass -ApkSigner <path to apksigner.bat>." }
    Write-Host "Using $ApkSigner"
    foreach ($k in $names.Keys) {
        $file = Join-Path $dist $names[$k]
        $out = (& $ApkSigner verify --verbose --print-certs $file | Out-String)
        if ($LASTEXITCODE -ne 0) { throw "apksigner rejected $($names[$k])." }
        foreach ($n in 2, 3) {
            if ($out -notmatch "Verified using v$n scheme[^\r\n]*:\s*true") { throw "$($names[$k]): signature scheme v$n is not verified. Check enableV1Signing/V2/V3 in app\build.gradle.kts." }
        }
        $m = [regex]::Match($out, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})')
        if (-not $m.Success) { throw "$($names[$k]): could not read the certificate digest from apksigner output." }
        $certs[$k] = $m.Groups[1].Value.ToLower()
        Write-Host "[$($names[$k])] v1 v2 v3 verified, certificate $($certs[$k])" -ForegroundColor Green
    }
    $distinct = @($certs.Values | Select-Object -Unique)
    if ($distinct.Count -ne 1) { throw "The three APKs are not signed with the same certificate." }
}
$cert = $certs["64"]

# --- 3. SHA256SUMS.txt
$hashes = @{}
$lines = New-Object System.Collections.Generic.List[string]
foreach ($k in $names.Keys) {
    $h = (Get-FileHash (Join-Path $dist $names[$k]) -Algorithm SHA256).Hash.ToLower()
    $hashes[$k] = $h
    $lines.Add("$h  $($names[$k])")
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
$sumsPath = Join-Path $dist "SHA256SUMS.txt"
[System.IO.File]::WriteAllText($sumsPath, (($lines -join "`n") + "`n"), $utf8)
Write-Host "Wrote $sumsPath" -ForegroundColor Green
$lines | ForEach-Object { Write-Host "  $_" }

# --- 4. release notes
$tplPath = Join-Path (Join-Path $root "publish") "RELEASE_NOTES_v$version.md"
if (-not (Test-Path $tplPath)) { throw "Release-notes template not found: $tplPath" }
$mb = {
    param($name)
    ([math]::Round((Get-Item (Join-Path $dist $name)).Length / 1MB, 1)).ToString([System.Globalization.CultureInfo]::InvariantCulture)
}
$resolvNote = if ($PatchedResolv) {
    'One string is patched (`/etc/resolv.conf` -> `//proc/self/fd/0`) so DNS works on Android, so it is **not** byte-identical to Cloudflare''s release. The upstream checksum was verified before patching.'
} else {
    'Unmodified upstream file, checked against Cloudflare''s published SHA-256 before it was packaged.'
}
$map = [ordered]@{
    "{{VERSION}}"      = $version
    "{{CLOUDFLARED}}"  = $Cloudflared
    "{{RESOLV_NOTE}}"  = $resolvNote
    "{{CERT_SHA256}}"  = $cert
    "{{SHA_32}}"       = $hashes["32"]
    "{{SHA_64}}"       = $hashes["64"]
    "{{SHA_UNI}}"      = $hashes["uni"]
    "{{MB_32}}"        = (& $mb $names["32"])
    "{{MB_64}}"        = (& $mb $names["64"])
    "{{MB_UNI}}"       = (& $mb $names["uni"])
}
$notes = [System.IO.File]::ReadAllText($tplPath)
foreach ($key in $map.Keys) { $notes = $notes.Replace($key, [string]$map[$key]) }
if ($notes -match '\{\{[A-Z0-9_]+\}\}') { throw "Unfilled placeholder left in the release notes: $($Matches[0])" }
$notesPath = Join-Path $dist "RELEASE_NOTES_v$version.md"
[System.IO.File]::WriteAllText($notesPath, $notes, $utf8)
Write-Host "Wrote $notesPath" -ForegroundColor Green

# --- 5. next steps
Write-Host @"

Next (after the release checklist in docs\TESTING.md is ticked):
  git add .
  git commit -m "Release $version"
  git tag -a v$version -m "SimBridge $version"
  git push origin main --tags
  gh release create v$version "dist\$($names['32'])" "dist\$($names['64'])" "dist\$($names['uni'])" dist\SHA256SUMS.txt ``
    --title "SimBridge $version - in-app updates" --notes-file "dist\RELEASE_NOTES_v$version.md"
"@
