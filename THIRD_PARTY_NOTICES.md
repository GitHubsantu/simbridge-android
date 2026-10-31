# Third-party notices

| Component | Used for | Licence | Source of licence text |
|---|---|---|---|
| cloudflared (Cloudflare, Inc.) — `cloudflared-linux-arm` 2026.10.4, fetched at build time, never committed | Tunnel client | Apache License 2.0 (verified from the upstream `LICENSE` file; upstream has no NOTICE file) | https://github.com/cloudflare/cloudflared/blob/master/LICENSE |
| Ktor server (JetBrains) 3.6.0 | Embedded HTTP server | Apache License 2.0 (verified) | https://github.com/ktorio/ktor/blob/main/LICENSE |
| AndroidX, Jetpack Compose, Room (Google) | UI, database | Apache License 2.0 (verified for the AndroidX repository) | https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt |
| Material Icons (Google) — tab icon paths | Navigation icons | Apache License 2.0 | https://github.com/google/material-design-icons |
| Kotlin and kotlinx libraries (JetBrains) | Language runtime, coroutines | Apache License 2.0 | https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt |
| org.json (test only) | JVM unit tests | JSON licence / public-domain style | https://github.com/stleary/JSON-java |

**Redistribution note.** The cloudflared executable is a Go program that statically contains many Go modules, each with its own licence; Cloudflare's repository is the source for that list. If you ever give an APK containing cloudflared to other people, include Apache-2.0's text and check cloudflared's dependency licences first. This was not done for the personal debug build.

**Reference projects.** SMS-Gateway-Free (MIT) and cloudflared-android (no licence file found) were studied only. No code was copied.
