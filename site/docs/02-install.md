# Install & verify

## Which file do I need?

ONYX ships one APK per processor type to keep downloads small:

| File | For |
|---|---|
| `arm64-v8a` | Almost every phone from 2017 onward. **Pick this if unsure.** |
| `armeabi-v7a` | Older 32-bit phones |
| `x86_64` | Emulators and a few tablets |
| `universal` | Works everywhere, but larger |

ONYX requires **Android 11 or newer**.

## Verify the download

Before installing anything that claims to protect your privacy, check that it is the file that was published.

### 1. Check the SHA-256 hash

Compare the output with the hash shown on the download page or in [/SHA256SUMS](/SHA256SUMS).

```powershell
# Windows
Get-FileHash .\app-arm64-v8a-release.apk -Algorithm SHA256
```

```bash
# Linux / macOS
sha256sum app-arm64-v8a-release.apk
```

If a single character differs, **do not install it**.

### 2. Check the signing certificate

The hash proves the file wasn't altered on the way to you. The signature proves who built it. With the Android SDK build-tools installed:

```bash
apksigner verify --print-certs app-arm64-v8a-release.apk
```

Compare the `SHA-256 digest` of the signer certificate with the one published on the download page. Android refuses to install an update signed by a different key, so after this first check every later update is verified automatically.

> **Warning** Preview builds are signed with a development key. The F-Droid release will be signed by F-Droid and will **not** upgrade over a preview install. Uninstall the preview first (this deletes its data).

## Install

From a computer with `adb`:

```bash
adb install -r app-arm64-v8a-release.apk
```

Or copy the APK to your phone and open it. Android will ask you to allow your file manager or browser to install unknown apps.

## First launch

1. Open ONYX. It starts Tor in the background; the status line reads **BUILDING TOR CIRCUITS…** and then **ONLINE**. The first start usually takes 10–60 seconds.
2. Allow notifications. Message notifications never show the sender or text, only "New message".
3. If messages arrive late, exempt ONYX from battery optimisation (Settings → Apps → ONYX → Battery → Unrestricted). ONYX must keep its onion service running so contacts can reach you.

## Add your first contact

You need to be **in the same place** as the other person.

1. One of you taps **Show my code**.
2. The other taps **Scan code** and points the camera at it.
3. Wait up to a minute while the phones connect through Tor and exchange keys. The chat opens automatically.

The code works **once** and expires after **ten minutes**. Never send a screenshot of it: anyone who has it can pair with you until it expires.
