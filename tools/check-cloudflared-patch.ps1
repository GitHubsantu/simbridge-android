<#
.SYNOPSIS
  Tells whether the cloudflared binaries are patched for Android DNS - without running the app.

.DESCRIPTION
  An UNPATCHED binary has no DNS on Android and the tunnel fails with
  "Couldn't resolve SRV record ... lookup ... on 127.0.0.1:53".
  Checks the files in app\src\main\jniLibs and, optionally, the copy that is really inside an APK
  (that is the one the phone runs).

  Run from the project root:
    powershell -ExecutionPolicy Bypass -File tools\check-cloudflared-patch.ps1
    powershell -ExecutionPolicy Bypass -File tools\check-cloudflared-patch.ps1 -Apk dist\SimBridge-1.1-v8a-64bit.apk
#>
param([string]$Apk)
$ErrorActionPreference = "Stop"
$enc = [System.Text.Encoding]::GetEncoding(28591)   # ISO-8859-1: bytes <-> chars 1:1

function Get-PatchState([byte[]]$bytes) {
    $t = $enc.GetString($bytes)
    $old = $t.Contains("/etc/resolv.conf")
    $new = $t.Contains("//proc/self/fd/0")
    if ($new -and -not $old) { return "PATCHED (ok)" }
    if ($old -and -not $new) { return "UNPATCHED  <-- DNS will fail on Android; re-run tools\fetch-cloudflared.ps1 -PatchResolv" }
    return "UNKNOWN"
}

$root = Split-Path -Parent $PSScriptRoot
foreach ($abi in "armeabi-v7a", "arm64-v8a") {
    $f = Join-Path $root "app\src\main\jniLibs\$abi\libcloudflared.so"
    if (Test-Path $f) { "{0,-13} jniLibs : {1}" -f $abi, (Get-PatchState ([IO.File]::ReadAllBytes($f))) }
    else { "{0,-13} jniLibs : (file missing)" -f $abi }
}

if ($Apk) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Apk).Path)
    try {
        $entries = @($zip.Entries | Where-Object { $_.FullName -like "lib/*/libcloudflared.so" })
        if ($entries.Count -eq 0) { "No libcloudflared.so inside this APK." }
        foreach ($e in $entries) {
            $ms = New-Object IO.MemoryStream
            $s = $e.Open(); $s.CopyTo($ms); $s.Dispose()
            "{0,-28} in APK : {1}" -f $e.FullName, (Get-PatchState $ms.ToArray())
        }
    } finally { $zip.Dispose() }
}
