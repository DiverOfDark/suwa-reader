# Suwa

**[Website and docs](https://diverofdark.github.io/suwa-reader/)** · **[Download](https://github.com/DiverOfDark/suwa-reader/releases/latest)**

A native Android reader for a [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server) server, where the server is the
single source of truth. Library, categories, chapters, read progress and per-series reader settings all come from the
server; the phone keeps a cache plus downloaded chapters so it works offline.

- **Library / Updates / History / Browse** read straight from the server's GraphQL API and are cached for offline use.
- **Offline progress** is queued (`pending_progress.json`) and sent when the server is reachable again. If a chapter
  disappeared meanwhile (e.g. the series was migrated to another source), progress moves to the chapter with the same
  number in the series with the same title.
- **Downloads** live in `Android/data/<app>/files/downloads/<mangaId>/<chapterId>/`. Anything the server drops from
  the library (or a chapter it no longer lists) is deleted on the next refresh.
- **Reader** is Mihon's (Apache-2.0) pager and long-strip viewers, vendored under `eu.kanade.*` with small shims. See
  `NOTICE`.

## Build

```
export JAVA_HOME=<JDK 17>
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

`tools/gen_mr.py` regenerates the English strings the vendored reader uses from a Mihon checkout.
