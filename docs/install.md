# Install

Suwa isn't on the Play Store. Install the APK from GitHub:

1. Open the [latest release](https://github.com/DiverOfDark/suwa-reader/releases/latest) on your phone.
2. Download the APK for your phone. Almost every current phone is `arm64-v8a`; the `universal` APK works everywhere but
   is three times larger.
3. Open the downloaded file and allow your browser or file manager to install apps when Android asks.

## Play Protect

Google Play Protect hasn't seen Suwa before, so it may offer to scan it and then warn that the app "may be harmful".
That warning is generic: Play Protect flags unknown apps that can install updates for themselves. Tap
**More details → Install anyway** to continue.

The same prompt can appear once for each new version. See [App updates](updates.md).

## Requirements

- Android 8.0 or newer.
- A [Suwayomi server](https://github.com/Suwayomi/Suwayomi-Server) the phone can reach: on the same network, through a
  VPN such as Tailscale, or published on the internet.

## Coming from Mihon

Suwa and Mihon can be installed side by side. Suwa doesn't import anything from Mihon: your library, categories and
read progress are whatever your Suwayomi server has. If you read in Mihon through the Suwayomi extension, that progress
is already on the server.
