# Stemit release signing

Stemit can build a signed release APK and Android App Bundle when the publisher's upload key is supplied. The repository does not contain a signing key, and no private key should ever be committed to Git.

## Create an upload key

On a trusted computer with Java installed:

```bash
keytool -genkeypair -v -keystore stemit-upload-key.jks -keyalg RSA -keysize 4096 -validity 10000 -alias stemit-upload
base64 -w 0 stemit-upload-key.jks > stemit-upload-key.base64
```

Keep the original `stemit-upload-key.jks` and its passwords in a secure offline backup. Do not send them in chat or commit them to this repository.

## Configure GitHub Actions

In the repository's **Settings → Secrets and variables → Actions**, add:

- `STEMIT_UPLOAD_KEYSTORE_BASE64`: the one-line Base64 content of the keystore.
- `STEMIT_UPLOAD_STORE_PASSWORD`: the keystore password.
- `STEMIT_UPLOAD_KEY_ALIAS`: `stemit-upload` (or the alias you chose).
- `STEMIT_UPLOAD_KEY_PASSWORD`: the key password.

After the secrets are configured, the release build signs the release APK and AAB. Without them, CI still builds unsigned release artifacts and a debug APK for testing.

## Google Play

Use the **signed AAB** for a new Play Console release and enrol in Play App Signing. Keep the upload key backed up: losing it can complicate future updates. Never upload the debug APK as a production release.

## Local signing

Set these environment variables before running `gradle -p Stemit :app:bundleRelease`:

- `STEMIT_UPLOAD_KEYSTORE_PATH`
- `STEMIT_UPLOAD_STORE_PASSWORD`
- `STEMIT_UPLOAD_KEY_ALIAS`
- `STEMIT_UPLOAD_KEY_PASSWORD`

## Advertising and payments

No ad SDK, ad slot, or payment button is enabled in this preview. Before enabling ads, configure real AdMob app/ad-unit IDs and an appropriate consent/privacy flow. Before adding paid features, integrate and test Google Play Billing. Do not ship test ad IDs or placeholder purchase controls.
