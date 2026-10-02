# ClipVault for Android

TikTok (no watermark), YouTube & Facebook video downloader — as a native Android app.

## ⬇ Download the APK

### **[Download ClipVault.apk](https://github.com/zmovie388-lgtm/clipvault-android/releases/latest/download/ClipVault.apk)**

Download page: **https://clipvault-apk.vercel.app** · or browse all versions on the [Releases page](https://github.com/zmovie388-lgtm/clipvault-android/releases).

**Install:** open the downloaded `ClipVault.apk` → if Android asks, allow *Install unknown apps* for your browser / file manager → Install. Requires Android 7.0+.

![ClipVault](docs/promo.png)

## Features
- **Share to ClipVault** — in TikTok / YouTube / Facebook tap *Share → ClipVault* and the video is fetched instantly
- One-tap **Paste** from clipboard (auto-fetches)
- TikTok without watermark, HD up to 4K, **MP4** or **MP3** (128 / 192 / 320 kbps)
- **Trim clip** to a custom time range
- Saves straight to your gallery: `Movies/ClipVault` (video) or `Music/ClipVault` (audio), with live progress
- Open or share the file right after download
- Dark / light theme

## How it works
The app bundles the ClipVault UI (`app/src/main/assets/www`) in a WebView with a native bridge for clipboard, share-intents and streaming downloads into MediaStore. Processing (yt-dlp + FFmpeg) runs on the ClipVault API at https://clipvault-beta.vercel.app — source: [zmovie388-lgtm/clipvault](https://github.com/zmovie388-lgtm/clipvault).

To point the app at your own server, change `API` in `MainActivity.java`.

## Build
Every push to `main` is built by GitHub Actions ([workflow](.github/workflows/build-apk.yml)), signed, and published as a new Release with `ClipVault.apk` attached.

Local build (Android SDK + JDK 17 + Gradle 8.9):
```bash
gradle :app:assembleRelease
```
Signing uses the `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets; without them the debug key is used.

## Note
YouTube may show a bot-check error until the server owner adds YouTube cookies (see the main repo). Only download public videos you have the right to save.
