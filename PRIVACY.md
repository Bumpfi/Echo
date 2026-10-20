# Privacy Policy — Echo

_Last updated: 2026-10-20_

[English](#english) · [Deutsch](#deutsch)

## English

Echo is a network traffic analyzer developed as part of a master's thesis. It shows which apps on your device communicate over the network and which domains they contact.

**All data stays on your device.** Echo has no server, no account, no analytics, no ads and no crash reporting. Recorded data (per-app byte counts, DNS queries, contacted domains, connection metadata) is held in memory on the device and is discarded when you clear it or the app process ends.

**DNS goes to Cloudflare.** While recording, Echo routes only DNS queries through a local VPN tunnel on the device and forwards them unchanged to Cloudflare's public resolver (1.1.1.1 / 1.0.0.1). Cloudflare therefore receives the domain names your apps look up, as it would for any DNS resolver. Cloudflare's resolver privacy policy: https://developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/. All other traffic does not pass through Echo and goes directly to the network.

**CSV export only on your request.** Echo writes CSV files only when you tap "Export Session Data". The files are saved in the app's own storage folder on your device. Echo never uploads them; sharing them is up to you.

### Permissions

| Permission | Why |
|---|---|
| `BIND_VPN_SERVICE` (VpnService) | Creates the local VPN that observes DNS queries to attribute domains to apps. Requires your explicit consent in the system dialog. |
| `QUERY_ALL_PACKAGES` | Lists installed apps so traffic can be shown by app name and icon, and so you can choose which apps to analyze. The list never leaves the device. |
| `PACKAGE_USAGE_STATS` | Reads per-app network byte counts from Android's NetworkStatsManager. Granted manually by you in system settings; optional. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Forward DNS queries to Cloudflare; detect connectivity. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` | Keep recording running with a visible notification. |

Echo does not request `READ_PHONE_STATE`, location, contacts or any other personal data.

**Children.** Echo is not directed at children.

**Changes.** Changes to this policy are published in this file in the source repository.

**Contact.** <CONTACT_EMAIL>

## Deutsch

Echo ist ein Netzwerk-Traffic-Analysator, der im Rahmen einer Masterarbeit entwickelt wurde. Die App zeigt, welche Apps auf deinem Gerät über das Netzwerk kommunizieren und welche Domains sie kontaktieren.

**Alle Daten bleiben auf dem Gerät.** Echo hat keinen Server, kein Konto, keine Analyse-Dienste, keine Werbung und kein Crash-Reporting. Aufgezeichnete Daten (Bytes pro App, DNS-Anfragen, kontaktierte Domains, Verbindungs-Metadaten) liegen nur im Arbeitsspeicher des Geräts und werden verworfen, wenn du sie löschst oder der App-Prozess endet.

**DNS geht an Cloudflare.** Während der Aufzeichnung leitet Echo ausschließlich DNS-Anfragen über einen lokalen VPN-Tunnel auf dem Gerät und gibt sie unverändert an Cloudflares öffentlichen Resolver (1.1.1.1 / 1.0.0.1) weiter. Cloudflare erhält dadurch die Domainnamen, die deine Apps abfragen, wie bei jedem DNS-Resolver. Datenschutzerklärung von Cloudflare: https://developers.cloudflare.com/1.1.1.1/privacy/public-dns-resolver/. Sonstiger Datenverkehr läuft nicht durch Echo, sondern direkt ins Netz.

**CSV-Export nur auf deinen Wunsch.** Echo schreibt CSV-Dateien nur, wenn du auf „Export Session Data" tippst. Die Dateien werden im app-eigenen Speicherordner auf deinem Gerät abgelegt. Echo lädt sie nie hoch; ob du sie teilst, entscheidest du.

### Berechtigungen

| Berechtigung | Zweck |
|---|---|
| `BIND_VPN_SERVICE` (VpnService) | Erstellt das lokale VPN, das DNS-Anfragen beobachtet, um Domains den Apps zuzuordnen. Erfordert deine ausdrückliche Zustimmung im Systemdialog. |
| `QUERY_ALL_PACKAGES` | Listet installierte Apps, damit Traffic mit App-Name und Icon angezeigt wird und du Apps zur Analyse auswählen kannst. Die Liste verlässt das Gerät nie. |
| `PACKAGE_USAGE_STATS` | Liest Netzwerk-Bytes pro App aus Androids NetworkStatsManager. Wird von dir manuell in den Systemeinstellungen erteilt; optional. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | DNS-Anfragen an Cloudflare weiterleiten; Verbindungsstatus erkennen. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` | Aufzeichnung mit sichtbarer Benachrichtigung am Laufen halten. |

Echo fordert weder `READ_PHONE_STATE` noch Standort, Kontakte oder andere personenbezogene Daten an.

**Kinder.** Echo richtet sich nicht an Kinder.

**Änderungen.** Änderungen dieser Erklärung werden in dieser Datei im Quellcode-Repository veröffentlicht.

**Kontakt.** <CONTACT_EMAIL>
