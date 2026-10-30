<#
.SYNOPSIS
  Fetches the upstream cloudflared 32-bit ARM build, verifies it, and installs it where the app packages it.

.DESCRIPTION
  The Poco C51 runs a 32-bit userspace (armeabi-v7a), so the file needed is "cloudflared-linux-arm".
  The SHA-256 below is the value Cloudflare published in the GitHub release notes for this exact version.
  The script refuses to install a file that does not match it.

  -PatchResolv  Optional, only if the unpatched binary fails with DNS errors on the phone.
                Replaces the single 16-byte string "/etc/resolv.conf" with "//proc/self/fd/0" so the
                app can supply DNS servers on stdin. This produces a MODIFIED binary (different hash).
                The script prints the patched file's SHA-256 so you can record it.

  Run from the project root:   powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1
#>
param([switch]$PatchResolv)
$ErrorActionPreference = "Stop"

$Version        = "2026.10.4"
$ExpectedSha256 = "f29b44e97ec18dd2971dfdd9bad0c6d460aaa78223b91b186219f02135c08d8c"
$Url            = "https://github.com/cloudflare/cloudflared/releases/download/$Version/cloudflared-linux-arm"

$Dest = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\armeabi-v7a"
New-Item -ItemType Directory -Force $Dest | Out-Null
$Out = Join-Path $Dest "libcloudflared.so"
$Tmp = Join-Path $env:TEMP "cloudflared-linux-arm-$Version"

Write-Host "Downloading $Url"
Invoke-WebRequest -Uri $Url -OutFile $Tmp

$actual = (Get-FileHash $Tmp -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $ExpectedSha256) {
    Remove-Item $Tmp -Force
    throw "SHA-256 mismatch. Expected $ExpectedSha256 but got $actual. Nothing was installed."
}
Write-Host "Checksum OK ($actual) - matches Cloudflare's published value for $Version."

if ($PatchResolv) {
    $enc  = [System.Text.Encoding]::GetEncoding(28591)   # ISO-8859-1: bytes <-> chars 1:1
    $text = $enc.GetString([System.IO.File]::ReadAllBytes($Tmp))
    $old  = "/etc/resolv.conf"
    $new  = "//proc/self/fd/0"
    $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
    if ($count -ne 1) { throw "Expected exactly 1 occurrence of '$old' but found $count. Not patching." }
    [System.IO.File]::WriteAllBytes($Out, $enc.GetBytes($text.Replace($old, $new)))
    Write-Host "Installed PATCHED binary: $Out"
} else {
    Copy-Item $Tmp $Out -Force
    Write-Host "Installed UNMODIFIED binary: $Out"
}
Remove-Item $Tmp -Force
Write-Host ("Installed file SHA-256: " + (Get-FileHash $Out -Algorithm SHA256).Hash.ToLower())
Write-Host "Next: rebuild and reinstall the app (gradlew.bat installDebug)."
