<#
.SYNOPSIS
  Fetches upstream cloudflared builds, verifies each against Cloudflare's published SHA-256, and installs
  them where the app packages them (one per CPU architecture).

.DESCRIPTION
  -Abi arm      32-bit ARM  (armeabi-v7a)  e.g. Poco C51 / many Android Go phones   -> cloudflared-linux-arm
  -Abi arm64    64-bit ARM  (arm64-v8a)    most phones                              -> cloudflared-linux-arm64
  -Abi both     (default)   installs both so one APK runs on either kind of phone (APK is ~25 MB larger)

  The SHA-256 values below are the ones Cloudflare lists in the GitHub release notes for $Version.
  The script refuses to install a file that does not match.

  -PatchResolv  Optional, only if the unpatched binary fails with DNS errors on the device.
                Replaces the single 16-byte string "/etc/resolv.conf" with "//proc/self/fd/0" so the app
                can supply DNS servers on stdin. This produces MODIFIED binaries (different hashes); the
                script prints each installed file's SHA-256 so you can record it.

  To update cloudflared: change $Version and the two hashes (copy them from the new release notes), run again.

  Run from the project root:   powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1 [-Abi both] [-PatchResolv]
#>
param(
    [ValidateSet("arm", "arm64", "both")] [string]$Abi = "both",
    [switch]$PatchResolv
)
$ErrorActionPreference = "Stop"

$Version = "2026.10.4"
$Targets = @(
    @{ Abi = "arm";   Asset = "cloudflared-linux-arm";   Dir = "armeabi-v7a"; Sha256 = "f29b44e97ec18dd2971dfdd9bad0c6d460aaa78223b91b186219f02135c08d8c" },
    @{ Abi = "arm64"; Asset = "cloudflared-linux-arm64"; Dir = "arm64-v8a";   Sha256 = "2b7203b4fb0ba28a0c1e12f8855ce2c25f76d266a8d5edafac5540b6812acff6" }
)

foreach ($t in $Targets) {
    if ($Abi -ne "both" -and $Abi -ne $t.Abi) { continue }
    if ($t.Sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw "Bad checksum for $($t.Asset)." }
    $expected = $t.Sha256.ToLower()
    $url  = "https://github.com/cloudflare/cloudflared/releases/download/$Version/$($t.Asset)"
    $dest = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$($t.Dir)"
    New-Item -ItemType Directory -Force $dest | Out-Null
    $out  = Join-Path $dest "libcloudflared.so"
    $tmp  = Join-Path $env:TEMP "$($t.Asset)-$Version"

    Write-Host "[$($t.Abi)] Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $tmp

    $actual = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) {
        Remove-Item $tmp -Force
        throw "[$($t.Abi)] SHA-256 mismatch. Expected $expected but got $actual. Nothing was installed for this ABI."
    }
    Write-Host "[$($t.Abi)] Checksum OK - matches Cloudflare's published value for $Version."

    if ($PatchResolv) {
        $enc   = [System.Text.Encoding]::GetEncoding(28591)   # ISO-8859-1: bytes <-> chars 1:1
        $text  = $enc.GetString([System.IO.File]::ReadAllBytes($tmp))
        $old   = "/etc/resolv.conf"
        $new   = "//proc/self/fd/0"
        $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
        if ($count -ne 1) { throw "[$($t.Abi)] Expected exactly 1 occurrence of '$old' but found $count. Not patching." }
        [System.IO.File]::WriteAllBytes($out, $enc.GetBytes($text.Replace($old, $new)))
        Write-Host "[$($t.Abi)] Installed PATCHED binary: $out"
    } else {
        Copy-Item $tmp $out -Force
        Write-Host "[$($t.Abi)] Installed UNMODIFIED binary: $out"
    }
    Remove-Item $tmp -Force
    Write-Host ("[$($t.Abi)] Installed file SHA-256: " + (Get-FileHash $out -Algorithm SHA256).Hash.ToLower())
}
Write-Host "Next: rebuild and reinstall the app (gradlew.bat installDebug)."
