# Building from source

You need JDK 17 and the Android SDK with platform 37.1 (`platforms;android-37.1`, `build-tools;37.0.0`).

```sh
git clone https://github.com/DiverOfDark/suwa-reader
cd suwa-reader
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
```

The debug build installs as `app.suwareader.debug`, next to a release build. APKs are split per ABI under
`app/build/outputs/apk/`.

## Release builds

Release builds are signed with the key in `keystore.properties` when the file exists:

```properties
storeFile=keystore/suwa-release.jks
storePassword=...
```

CI reads the key from the `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` repository secrets. Without a key, release builds
fall back to the debug key; they install fine, but can't update a copy installed from GitHub.

## Versions and releases

Every push to `main` builds, signs and publishes a GitHub release (`.github/workflows/release.yml`):

- `versionCode` is the commit time, so every release is newer than the last;
- `versionName` is `0.<yymmdd>.<run number>`;
- the release carries one APK per ABI, a universal APK and `update.json` for the in-app updater.

Local builds default to `versionCode` 1 and version name `dev`; pass `-PversionCode=… -PversionName=…` to override.

## Docs

This site is built with [mdBook](https://rust-lang.github.io/mdBook/) from `docs/` and published to GitHub Pages by
`.github/workflows/docs.yml`:

```sh
mdbook serve --open
```
