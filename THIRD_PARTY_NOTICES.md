# Third-party notices

| Component | Used for | Licence | Source of licence text |
|---|---|---|---|
| cloudflared (Cloudflare, Inc.) — `cloudflared-linux-arm` and `cloudflared-linux-arm64`, the version fetched by `tools\fetch-cloudflared.ps1` at build time (shown on the Tunnel tab and named in each release's notes), never committed, **bundled inside the release APKs** | Tunnel client | Apache License 2.0 (verified from the upstream `LICENSE` file; upstream has no NOTICE file) | https://github.com/cloudflare/cloudflared/blob/master/LICENSE |
| Ktor server (JetBrains) 3.6.0 | Embedded HTTP server | Apache License 2.0 (verified) | https://github.com/ktorio/ktor/blob/main/LICENSE |
| AndroidX, Jetpack Compose, Room (Google) | UI, database | Apache License 2.0 (verified for the AndroidX repository) | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Material Icons (Google) — tab icon paths | Navigation icons | Apache License 2.0 | https://github.com/google/material-design-icons |
| Kotlin and kotlinx libraries (JetBrains) | Language runtime, coroutines | Apache License 2.0 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt |
| org.json (test only, not shipped) | JVM unit tests | JSON licence / public-domain style | https://github.com/stleary/JSON-java |

## Redistribution
The release APKs contain the cloudflared executable unmodified unless the release notes say that the `-PatchResolv` option was used (it changes one string, so the file is then not byte-identical to Cloudflare's). It is a Go program that statically contains many Go modules, each with its own licence; Cloudflare's repository is the source for that list.

- A copy of the Apache-2.0 text for cloudflared is kept in this repository at `licenses/cloudflared-LICENSE.txt` (copied from the upstream URL above).
- The set of Go dependency licences compiled into cloudflared has not been audited here. If you redistribute APKs, check them against Cloudflare's repository first.
- Apache-2.0 requires the licence text to accompany redistributed copies; the same applies to the other Apache-2.0 components listed above (they are linked into the app as libraries).

## Reference projects
SMS-Gateway-Free (MIT) and cloudflared-android (no licence file found) were studied only. No code was copied.
