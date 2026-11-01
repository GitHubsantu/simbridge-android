# Tunnel fails with "DNS lookups failed" / "Couldn't resolve SRV record"

Android has no `/etc/resolv.conf`, so a stock cloudflared cannot find a DNS server and exits at start-up with
`Couldn't resolve SRV record ... lookup ... on 127.0.0.1:53`. SimBridge works around this in two parts:
the app writes a resolv.conf and feeds it to cloudflared on stdin, **and** the cloudflared file is patched
(`/etc/resolv.conf` -> `//proc/self/fd/0`, same length) so it reads that stdin. Without the patch the first part does nothing.

## 1. Find out which case you are in
On the PC, in the project folder:
```powershell
powershell -ExecutionPolicy Bypass -File tools\check-cloudflared-patch.ps1 -Apk dist\SimBridge-1.1-v8a-64bit.apk
```
It reports `PATCHED (ok)` or `UNPATCHED` for each binary in `jniLibs` and inside the APK (the APK copy is what the phone runs).
In the app: **Tunnel tab -> Check cloudflared binary** prints `DNS: patched ...` or `DNS: NOT patched ...`, and the
cloudflared log now starts with the same line plus the DNS servers that were handed to cloudflared.

## 2. Fix an UNPATCHED binary
```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch-cloudflared.ps1 -PatchResolv        # use the same -Version as before, both ABIs
powershell -ExecutionPolicy Bypass -File tools\check-cloudflared-patch.ps1               # must now say PATCHED for both
```
then rebuild (`tools\make-release.ps1 ... -PatchedResolv` for a release) and reinstall. **Patch both ABIs**: a 32-bit phone
can work while the 64-bit file is still unpatched, because the two files are fetched and patched independently.

## 3. Patched but still failing
The log line `DNS servers given to cloudflared:` lists what cloudflared uses: the phone's own IPv4 DNS servers, then
1.1.1.1 and 8.8.8.8 as a safety net (so a carrier DNS that drops SRV lookups cannot block the tunnel). If it still fails:
Private DNS set to a hostname that is unreachable, a VPN, a captive or filtered network (for example one that blocks
UDP port 53), or no data connection. Try another network (Wi-Fi vs mobile data) and send the last log lines.
