# Play Console declarations — Echo 2.2 (versionCode 4)

Drafts to paste into Play Console. Hi-res icon (512×512 PNG): `play/icon_512.png`. Privacy policy URL: link to `PRIVACY.md` on GitHub (e.g. `https://github.com/bumpfi/Echo/blob/main/PRIVACY.md`).

## VpnService declaration

**Core functionality:** Network traffic analysis / monitoring (a permitted VpnService use case).

> Echo is a network traffic analyzer built for a master's thesis on app telemetry. Its core function is showing the user which installed apps contact which domains. The app uses VpnService to create a local, on-device tunnel that routes only DNS traffic (to 1.1.1.1 / 1.0.0.1); each DNS query is attributed to the app that sent it and forwarded unchanged to Cloudflare's public resolver. All other traffic bypasses the tunnel. No traffic is sent to a server operated by the developer, no traffic is modified, redirected or used for ads, and Echo does not collect any data. All analysis happens on the device and results are only shown to the user or exported as CSV when the user taps Export. The VPN starts only after the user taps Start and accepts the system VPN consent dialog, and a persistent notification is shown while it runs.

Video demo (Play usually asks for one): record Start → system VPN dialog → dashboard filling with apps/domains → Stop.

## QUERY_ALL_PACKAGES declaration

**Use case:** Device search / network monitoring — the app needs broad visibility into installed apps.

> Echo's core purpose is attributing network activity to every installed app. The VPN sees UIDs; to show the user which app a DNS query or byte count belongs to, Echo must resolve every UID to an app name and icon, including apps the user did not open and that cannot be predicted in advance. A fixed `<queries>` list cannot work because the set of apps that generate traffic is unknown. The user also picks the apps to analyze from the full list of installed apps. The package list is used only on the device and is never transmitted.

## Foreground service: specialUse

Manifest subtype: `Network traffic analysis for research purposes`.

> The foreground service is the VpnService that records traffic while the user has started a recording session. It must keep running while the user uses other apps, since those apps' traffic is what is measured. It is started only by the user tapping Start, shows a persistent notification, and stops when the user taps Stop or at the auto-stop time. No other foreground service type fits: `connectedDevice`, `dataSync` and `mediaProcessing` do not describe on-device VPN traffic analysis, and VpnService has no dedicated FGS type.

## Data safety form

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | N/A (no data collected) |
| Do you provide a way for users to request that their data be deleted? | N/A — nothing leaves the device; "Clear Recorded Data" in Settings deletes local data |

Why "No collection": Google defines "collect" as data transmitted off the device. Echo processes app activity and installed-app info only on-device (on-device processing is exempt). DNS queries forwarded to Cloudflare are the user's own apps' lookups passed through unchanged, as any resolver would get; mention it in the privacy policy (done) rather than declaring it as collection. If a reviewer pushes back, the fallback answer is: **Shared → App activity → Other actions (DNS lookups) → to Cloudflare, not optional, purpose: App functionality, not processed ephemerally: no**.

## Other Console answers

- **Ads:** No ads.
- **App access:** All features usable without login. Note for reviewers: tap Start and accept the VPN dialog; optional Usage Access is granted via the in-app banner.
- **Target audience:** 18+ (research tool), not designed for children.
- **Content rating:** Utility, no user-generated content, no sharing.
- **Health / financial / government features:** None.
- **Permissions removed in 2.2:** `READ_PHONE_STATE` (was never requested at runtime).

## Store listing (en-US)

Graphics in `play/`: `icon_512.png`, `feature_graphic.png` (1024×500), `screenshot_1-4.png` (1080×1920).

**Short description (≤80):**

See which apps contact which domains. Local DNS-only VPN, all data on-device.

**Full description:**

Echo shows you which apps on your phone go online, how much data they send and receive, and which domains they look up – live, while you use your phone.

Echo was built as a research tool for a master's thesis on app telemetry. It is open source and needs no root.

HOW IT WORKS
• Tap Start and accept Android's VPN dialog.
• Echo creates a local VPN on your device that only handles DNS. Each DNS lookup is matched to the app that made it and forwarded unchanged to Cloudflare's resolver (1.1.1.1). All other traffic goes directly to the network, untouched.
• Per-app data volumes come from Android's own network statistics.

FEATURES
• Live dashboard: sent/received data and active apps
• Per-app detail: traffic summary, destinations and every DNS lookup with timestamp
• Choose which apps to analyze
• Optional auto-stop timer
• CSV export (metrics, connections, DNS, destinations) for your own analysis

PRIVACY
• No account, no ads, no analytics, no tracking.
• All recordings stay on your device. Nothing is sent to the developer.
• CSV files are only created when you tap Export.
• Open source: github.com/Bumpfi/Echo

PERMISSIONS
• VPN: to observe DNS lookups on the device
• Query all packages: to show traffic by app name and icon
• Usage access (optional): for per-app byte counts
• Notifications: shows that recording is active

Echo does not block, modify or redirect any traffic.
