# App updates

Suwa updates itself from [GitHub releases](https://github.com/DiverOfDark/suwa-reader/releases).

- It checks when the app starts and twice a day in the background.
- With **Install updates automatically** on (the default) it downloads and installs a new version by itself; otherwise
  it notifies you and **More** shows an install button.
- **More → Settings → App updates** shows the running version and lets you check and install by hand.

## What's verified

Each release publishes `update.json` with the version, and for every APK its download URL, size and SHA-256. Before
anything is installed, Suwa checks that:

1. the release is strictly newer than the running build;
2. the APK comes from this repository's releases, over HTTPS;
3. the download matches the advertised size and SHA-256;
4. it's the same package, signed with the same key as the running app.

Anything that fails a check is deleted and reported instead of installed.

## Prompts you may see

- **Install unknown apps**: Android asks once to allow Suwa to install updates.
- **Play Protect**: a new APK may be scanned and labelled "may be harmful", because Play Protect flags unknown apps that
  update themselves. Tap **Install anyway**. On some phones Play Protect cancels the first attempt even when you
  approve; Suwa retries it once on its own.
- **Update this app?**: Android asks for confirmation until Suwa has installed itself once. From Android 12 on, later
  updates usually install without asking.

The app closes while it's being updated; a notification tells you when the new version is in.
