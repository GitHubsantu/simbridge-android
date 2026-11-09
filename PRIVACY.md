# SimBridge Gateway Privacy Policy

**Last updated:** November 10, 2026

**App:** SimBridge Gateway (`in.imdevops.simbridge`) for Android

## The short version

SimBridge Gateway has no accounts, no ads, no analytics and no servers of mine. I never receive your messages, phone numbers, API keys or anything else from your phone. The app runs on your phone, keeps its data on your phone, and only uses the network in the ways listed in section 4.

## 1. What the app does

SimBridge Gateway turns an Android phone with a SIM card into an SMS gateway. Software you control sends a request to a REST API inside the app, and the app asks Android to send the text message through your SIM. In this policy, "I" means the developer of the app, and "you" means the person who installs and runs it.

## 2. Data stored on your phone

Everything below stays on your device. None of it is sent to me.

| Data | What is kept |
|---|---|
| API key | Shown to you once when you generate it. Only its SHA-256 hash is stored, so the key cannot be recovered, only replaced. |
| Cloudflare Tunnel token | Encrypted with a key held in the Android Keystore. |
| Message history | For each message: the recipient's phone number, the time, the delivery status, the SIM used, and the length (characters and number of SMS parts). **The message text is not stored.** |
| Settings | Your sending limits, country-prefix allow-list, chosen SIM and tunnel hostname. |
| Event log | A short local log of app events (for example "gateway started") to help you troubleshoot. |

Android backups are turned off for this app, so this data is not copied to Google Drive or other backups.

## 3. Information that passes through the app

- **Your messages.** When your software asks the app to send an SMS, the recipient number and message text go to Android's SMS system and then to your mobile carrier. Your carrier's terms and privacy policy apply to that. The app does not keep a copy of the text.
- **Your own API clients.** The app only answers requests that carry your API key. I do not operate or see any of those clients.

## 4. Network connections the app makes

- **Local API.** The app's API listens only on the phone's own loopback address (127.0.0.1). It is not reachable from the network unless you set up the tunnel below.
- **Cloudflare Tunnel (optional).** If you enter a Cloudflare Tunnel token and start the tunnel, the app runs Cloudflare's open-source `cloudflared` connector. It opens an outbound connection from your phone to Cloudflare, so requests to your own hostname can reach the app. That traffic, including the phone numbers and message text your software sends, passes through Cloudflare under your own Cloudflare account. I have no access to your Cloudflare account or its traffic. Cloudflare's privacy policy applies.
- **Update checks (if your version includes them).** When you use the in-app update check, the app contacts GitHub (`api.github.com` and `github.com`) to look up the latest SimBridge release and the latest `cloudflared` release. GitHub can see your IP address and standard request details, as with any website visit. Your messages, phone numbers and keys are never part of these requests.

The app makes no other network connections, and it does not use advertising or analytics services.

## 5. Permissions and why they are needed

| Permission | Why |
|---|---|
| Send SMS | To send the text messages you ask it to send. |
| Phone state | To list the active SIMs so you can pick which one sends. |
| Notifications | To show the status notification while the gateway runs. |
| Foreground service | To keep the gateway running in the background with a visible notification. |
| Internet and network state | To run the optional tunnel and the update check, and to read the phone's DNS settings for the tunnel. |
| Start after reboot (optional) | Only used if you switch on "Start after reboot". |
| Battery optimization exemption (optional) | Lets you ask Android to stop pausing the gateway in the background. |

The app does not ask for permission to read or receive SMS, access your contacts, location, camera, microphone, photos or files.

## 6. Sharing

I do not sell, rent or share your personal data, because I never have it. The only third parties involved are the ones you choose to use: your mobile carrier, and Cloudflare if you enable the tunnel (plus GitHub if you use update checks), each as described above.

## 7. Security

The API listens only on loopback, every request needs your key, keys are compared in constant time and never logged, repeated wrong keys are throttled, and sending limits, a destination allow-list and premium-number blocking help limit damage if something goes wrong. The tunnel token is encrypted with the Android Keystore. No system is perfectly secure, so keep your API key private and generate a new one if it ever leaks.

## 8. Keeping and deleting your data

All data lives on your phone. To delete it, clear the app's storage in Android settings or uninstall the app. To retire an API key, generate a new one in the app. Because I hold none of your data, there is nothing for me to delete on my side.

## 9. Your responsibilities

You decide who receives messages. Please only message people who agreed to hear from you, and follow your carrier's terms and the laws that apply to you.

## 10. Children

The app is a developer tool and is not directed at children.

## 11. Changes to this policy

If the app's behavior changes in a way that affects your privacy, I will update this page and the date at the top.

## 12. Contact

Questions or concerns: **founder@imdevops.in**

Source code: [github.com/GitHubsantu/simbridge-android](https://github.com/GitHubsantu/simbridge-android)
