# ZIQENO

English · [Русский](README.ru.md)

<img src="branding/ziqeno.svg" width="108" height="108" alt="ZIQENO logo">

ZIQENO is an Android VPN client with automatic server selection, per-app routing, and domain-based split tunneling. It requires Android 8.0 or later.

The interface follows Android's language settings. Russian and English are available, with English as the fallback.

[Changelog](CHANGELOG.md) · [Privacy](PRIVACY.md) · [Product](PRODUCT.md) · [Design](DESIGN.md) · [Third-party notices](androidApp/src/main/assets/notices/SOURCES.txt)

## Servers and subscriptions

1. Open **Settings** (`Настройки`) → **Servers and subscriptions** (`Серверы и подписки`). Add a provider's HTTPS subscription URL, a single server, or a QR code from the camera or an image. After scanning, review the populated form and save the source.
2. Add a server from a link or enter its address, port, and credentials manually. ZIQENO supports VLESS, VMess, Trojan, Shadowsocks, and Hysteria 2. The form groups protocol, transport, and security settings and shows fields for the selected mode. VLESS, VMess, and Trojan support TCP (RAW), WebSocket, gRPC, XHTTP, HTTPUpgrade, and mKCP. TLS supports SNI, ALPN, client fingerprints, SHA-256 certificate fingerprints, and ECH. REALITY supports SNI, client fingerprints, public keys, Short ID, and SpiderX. Hysteria 2 supports Salamander, UDP timeouts, and congestion settings.
3. Tap a source to edit its name, URL, or settings, refresh its subscription, or remove it. If a provider returns an error, ZIQENO keeps the last downloaded version. **Subscription servers** (`Серверы подписки`) shows the server list, latest measurements, and a manual test button.
4. Tap **Connect** (`Подключить`) and approve Android's VPN permission. ZIQENO considers all sources and deduplicates matching nodes. You can save up to 32 sources, and up to 250 distinct nodes can participate in a single session. Source changes take effect on the next connection.

ZIQENO supports Remnawave HWID. By default, subscription requests send a stable identifier scoped to the provider's HTTPS origin, along with the device model and Android version. You can turn this off per subscription. Redirects to a different host or port omit device details. ZIQENO displays provider responses about device limits and HWID requirements. [Remnawave HWID documentation](https://docs.rw/features/hwid-device-limit/)

Compatible server links open with prefilled fields. Links with parameters outside the scope of the form open in URL mode. [Xray transports](https://xtls.github.io/en/config/transports/index.html)

## Android shortcuts and notifications

In settings, tap **Add VPN tile** (`Добавить плитку VPN`). Android 13 and later show a system confirmation. On earlier Android versions, open the Quick Settings editor and drag ZIQENO into the active tiles. The tile connects and disconnects the VPN. Android asks for VPN permission the first time you connect.

**Add VPN widget** (`Добавить виджет VPN`) places ZIQENO on the home screen after confirmation. You can also add it from the launcher's widget picker. The widget shows the VPN status and controls the connection.

On first launch, ZIQENO offers to open Android settings for background operation. Links to battery optimization and the app info screen are under **Settings** → **Background operation** (`Фоновая работа`). [Android battery optimization](https://developer.android.com/training/monitoring-device-state/doze-standby)

**Detailed notification** (`Подробное уведомление`) shows the active server and download and upload rates. With details off, Android's service notification shows the VPN status. Visibility in the notification shade depends on notification permission. Android 14 and later allow users to dismiss the ongoing notification while the device is unlocked. [Android VPN documentation](https://developer.android.com/develop/connectivity/vpn)

## Per-app routing

On Android 10 and later, you can select up to 24 apps from the launcher list. Android identifies an app from the owner of its connection. Apps that share a system UID cannot be selected. Per-app rules take precedence over site rules. Connections whose owner cannot be identified use regular VPN routing.

In **Based on app connections** mode (`По соединениям приложения`), ZIQENO waits for a TCP connection with a visible TLS hostname on port 443. It then tests TLS to the observed IP address through several VPN nodes and selects a dedicated route. Measurements are stored separately by app, test target, and network type. The app uses the default VPN route until the first successful test. After ten minutes without a new observation, ZIQENO keeps the last selected node and marks the check as stale. Observed hostnames and IP addresses are kept in memory for the current session.

For Telegram, ZIQENO uses actual TCP connections to addresses in published data center ranges. The test performs the initial MTProto exchange over obfuscated transport without authentication. When you add a browser, ZIQENO warns that an automatic test may pick a background connection instead of the open page.

You can also choose **Responsiveness** (`Отзывчивость`) to favor response time or **Download** (`Загрузка`) to favor throughput. Apps on the same preset share a route and measurements. The app list shows the current server and the most recent switch. Automatic route changes apply to new connections without restarting the VPN.

**Open switch log** (`Открыть журнал`) on the VPN screen shows the last 100 switches. Each entry includes the time, network, route, outgoing and incoming nodes, and the reason for the switch. Entries persist across sessions.

## VPN traffic

The VPN screen shows live download and upload rates and the last 10 minutes of history. It reads Xray's VPN inbound counters. Server probes use a separate inbound, so the chart tracks VPN traffic only. The VPN service continues collecting data while the app is closed. Recorded points remain visible after disconnecting, and gaps in the data stay blank.

## Split tunneling

Settings support up to 32 domain rules. ZIQENO applies the first matching rule from top to bottom, ahead of site and app rules. Match types include exact domains, suffixes with subdomains, wildcards using `*` and `?`, keywords, RE2 regular expressions, and a catch-all. Each rule can use a direct connection, automatic VPN routing, a specific VPN node, or block traffic. If a fixed node is unavailable, traffic matching the rule is blocked. Saving, reordering, or toggling rules applies them to new connections in the current tunnel. Open connections keep their route until they close.

You can enable an HTTPS probe for each rule and specify its target domain. An automatic VPN rule uses measurements to select a dedicated node and backup. Without measurements, it uses the default VPN node. For direct and fixed-node rules, the probe checks reachability. Domain matching uses hostnames visible to Xray in HTTP, TLS, or QUIC. Connections whose hostname is hidden can only match catch-all rules. Direct traffic is excluded from the VPN chart.

## Server selection

- The Kotlin Multiplatform module `routing` implements ranking, measurement history, and switch policy. `androidApp` handles the tunnel, Xray, measurements, and storage.
- On a cold connection, ZIQENO tests nodes in batches and starts routing through the first one to respond. On a warm connection, it first checks candidates from the current network's history. ZIQENO then tests up to 30 nodes, assigns suitable servers, and applies its standard switching safeguards. Later rounds rotate through active nodes, backups, and catalog candidates. Up to 12 candidates get separate HTTPS probes for GitHub and YouTube. Unmatched traffic uses the default VPN route.
- ZIQENO estimates download speed with a size-limited HTTPS GET to Cloudflare through a specific node. Each test uses up to 256 KiB on an unmetered connection and up to 64 KiB on a mobile or metered network. Over 15 minutes, ZIQENO tests up to three and two candidates, respectively. Power saving pauses test downloads. The YouTube route uses this speed as an estimate of throughput to Cloudflare's CDN.
- ZIQENO keeps separate Wi-Fi and mobile samples for the past 24 hours. When the network type changes, it stops probes after the current batch and starts another round. It recalculates ranking and confidence from the history for the current network type. Source URLs and configurations are encrypted with a key stored in Android Keystore.
- ZIQENO switches to a candidate after it performs at least 15% better in three consecutive checks and the active node has been in use for at least two minutes. Active-node health checks run separately from ranking, roughly every two seconds on Wi-Fi and every four seconds on mobile or metered networks or in power-saving mode. If checks to two distinct HTTPS endpoints confirm a failure, ZIQENO immediately switches to a tested backup when one is available and starts an extra round to find the next backup. A node is excluded for two minutes after a failure or a route update error. Changes apply to new connections through Xray's local API.

## Build

Install JDK 17 or later, Android SDK Platform 36, and Build Tools 35.0.0. Set the SDK path through `ANDROID_HOME` or `local.properties`.

Run these commands in PowerShell.

```powershell
./scripts/fetch-libxray.ps1
./gradlew.bat :routing:jvmTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug
```

The script downloads the official libXray v26.10.31 archive and verifies its SHA-256 checksum. To reuse a downloaded archive, pass `-ArchivePath path/to/libxray-android.zip`. On Linux and macOS, run `pwsh -File scripts/fetch-libxray.ps1`, then run the same Gradle tasks with `sh gradlew`.

The build writes separate debug APKs for arm64-v8a, armeabi-v7a, x86_64, and x86 to `androidApp/build/outputs/apk/debug`. Use arm64-v8a for most modern phones and x86_64 for an x86_64 emulator.

## Publish an Android release

1. Update `versionCode` and `versionName` in `androidApp/build.gradle.kts` and add the version to `CHANGELOG.md`.
2. Open **Actions → Android release → Run workflow** and select the default branch.
3. The workflow runs the tests, builds the APKs, signs each architecture, and publishes the release with SHA-256 checksums.

The workflow uses the repository secrets `ZIQENO_SIGNING_KEY_B64` (the Base64-encoded PKCS#12 keystore) and `ZIQENO_SIGNING_PASSWORD`. The signing alias is `ziqeno`. Keep a separate backup of the key and password for future updates. The signing script verifies each APK against the [release certificate fingerprint](scripts/release-certificate.sha256).

## Third-party components

The [notices directory](androidApp/src/main/assets/notices) contains dependency versions and license texts. The build includes it in the APK.

[libXray v26.10.31](https://github.com/XTLS/libXray/releases/tag/v26.10.31) · [Android VpnService](https://developer.android.com/develop/connectivity/vpn) · [Xray routing API](https://xtls.github.io/en/config/api.html) · [Cloudflare Speed Test](https://github.com/cloudflare/speedtest)
