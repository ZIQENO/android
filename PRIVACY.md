# Data in ZIQENO

English · [Русский](PRIVACY.ru.md)

## On your device

- ZIQENO stores subscriptions, server addresses, and credentials in an encrypted file. The encryption key is stored in Android Keystore.
- ZIQENO stores route selections, domain rules, measurements, and the server switch log. Hostnames and IP addresses observed during per-app route selection are kept in memory for the current session. The measurement history uses a hash of the target.
- ZIQENO uses the camera to scan QR codes. You can also select an image through Android's system picker. QR code contents are processed on your device.

## Network requests

- When you add or refresh a subscription, ZIQENO contacts its provider. If HWID sharing is enabled, the request sends an identifier scoped to the provider's origin, your device model, and your Android version. You can turn this off per subscription.
- Automatic and manual measurements use Cloudflare and Google HTTPS endpoints. Site-specific routes also test GitHub and YouTube. If you enable a probe for a domain rule, ZIQENO contacts the domain you specify. In app-connection mode, probes use the observed target.
- Traffic goes through the selected servers or directly to its destination, according to the routing rules. The built-in DNS resolver uses Cloudflare.

You can clear local data from the app info screen in Android settings.
