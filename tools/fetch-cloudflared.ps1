<#
.SYNOPSIS
  Fetches the LATEST upstream cloudflared (or a version you name), verifies each file against the SHA-256
  that Cloudflare publishes, and installs it where the app packages it (one per CPU architecture).

.DESCRIPTION
  -Abi arm      32-bit ARM  (armeabi-v7a)  e.g. Poco C51 / many Android Go phones   -> cloudflared-linux-arm
  -Abi arm64    64-bit ARM  (arm64-v8a)    most phones                              -> cloudflared-linux-arm64
  -Abi both     (default)   installs both so one APK runs on either kind of phone

  -Version      Default "latest". Or a tag such as 2026.10.4 to pin an exact release.

  Checksum rules (a file is installed only if it passes):
    * The checksum is read from Cloudflare's release notes ("cloudflared-linux-arm64: <sha256>") and, when
      GitHub provides one, from the asset's own digest. If both exist they must agree.
    * If only the GitHub digest exists, the script warns that the notes could not be used.
    * If neither exists, nothing is installed.

  -PatchResolv  Same as before: replaces the single 16-byte string "/etc/resolv.conf" with "//proc/self/fd/0"
                so the app can supply DNS servers on stdin. Produces MODIFIED binaries (different hashes);
                the script prints each installed file's SHA-256. Use the same choice you used for the
                previous version.

  Run from the project root:   powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1 [-Abi both] [-Version latest] [-PatchResolv]
#>
param(
    [ValidateSet("arm", "arm64", "both")] [string]$Abi = "both",
    [string]$Version = "latest",
    [switch]$PatchResolv
)
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$headers = @{ "User-Agent" = "SimBridge-fetch-cloudflared"; "Accept" = "application/vnd.github+json" }

$api = if ($Version -eq "latest") {
    "https://api.github.com/repos/cloudflare/cloudflared/releases/latest"
} else {
    "https://api.github.com/repos/cloudflare/cloudflared/releases/tags/$Version"
}
$release = Invoke-RestMethod -Uri $api -Headers $headers
$Version = [string]$release.tag_name
Write-Host "Using cloudflared $Version (published $($release.published_at))"

$Targets = @(
    @{ Abi = "arm";   Asset = "cloudflared-linux-arm";   Dir = "armeabi-v7a" },
    @{ Abi = "arm64"; Asset = "cloudflared-linux-arm64"; Dir = "arm64-v8a" }
)

foreach ($t in $Targets) {
    if ($Abi -ne "both" -and $Abi -ne $t.Abi) { continue }

    $asset = $release.assets | Where-Object { $_.name -eq $t.Asset } | Select-Object -First 1
    if (-not $asset) { throw "[$($t.Abi)] Release $Version has no asset named $($t.Asset)." }
    $url = [string]$asset.browser_download_url
    if (-not $url.StartsWith("https://github.com/cloudflare/cloudflared/releases/download/")) { throw "[$($t.Abi)] Unexpected download address: $url" }

    # --- published checksums
    $fromApi = $null
    if ($asset.digest -and ([string]$asset.digest) -match '^sha256:([0-9a-fA-F]{64})$') { $fromApi = $Matches[1].ToLower() }
    $fromNotes = $null
    $m = [regex]::Match([string]$release.body, '(?<![\w-])' + [regex]::Escape($t.Asset) + ':\s*([0-9a-fA-F]{64})')
    if ($m.Success) { $fromNotes = $m.Groups[1].Value.ToLower() }

    if (-not $fromApi -and -not $fromNotes) { throw "[$($t.Abi)] No published checksum found for $($t.Asset) in $Version. Nothing installed." }
    if ($fromApi -and $fromNotes -and $fromApi -ne $fromNotes) { throw "[$($t.Abi)] GitHub's digest and Cloudflare's release notes disagree. Nothing installed." }
    if (-not $fromNotes) { Write-Warning "[$($t.Abi)] Cloudflare's release notes had no checksum for this file; verifying against GitHub's digest only." }
    $expected = if ($fromNotes) { $fromNotes } else { $fromApi }

    $dest = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$($t.Dir)"
    New-Item -ItemType Directory -Force $dest | Out-Null
    $out = Join-Path $dest "libcloudflared.so"
    $tmp = Join-Path $env:TEMP "$($t.Asset)-$Version"

    Write-Host "[$($t.Abi)] Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $tmp -Headers @{ "User-Agent" = "SimBridge-fetch-cloudflared" }

    $actual = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) {
        Remove-Item $tmp -Force
        throw "[$($t.Abi)] SHA-256 mismatch. Expected $expected but got $actual. Nothing was installed for this ABI."
    }
    Write-Host "[$($t.Abi)] Checksum OK - matches the published value for $Version."

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
Write-Host "Done: cloudflared $Version. Next: rebuild and reinstall the app (gradlew.bat installDebug)."