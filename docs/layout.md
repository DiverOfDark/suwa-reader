# Project layout and credits

```
app/src/main/java/
├── app/suwareader/            the app
│   ├── data/api/              GraphQL client and typed Suwayomi API
│   ├── data/repo/             library and series cache (the server's view)
│   ├── data/sync/             queued reading progress, shared reader settings
│   ├── data/download/         download queue and worker
│   ├── update/                in-app updates from GitHub releases
│   └── ui/                    Compose screens
├── eu/kanade/tachiyomi/ui/reader/   Mihon's reader, plus Suwa's reader activity and page loaders
└── tachiyomi/, mihon/, dev/…        small shims the vendored reader expects
```

## The reader

The paged and long-strip viewers are taken from [Mihon](https://github.com/mihonapp/mihon) and kept in their original
packages, so they can be compared with upstream. Suwa replaces the parts that tied them to Mihon: dependency
injection, preferences, sources and downloads. Strings come from Mihon's English translations through
`tools/gen_mr.py`.

## Credits and license

- Reader: Mihon and Tachiyomi, Apache License 2.0. See `NOTICE`.
- In-app update flow ported from [Phos](https://github.com/DiverOfDark/phos).
- Server: [Suwayomi](https://github.com/Suwayomi/Suwayomi-Server).

Suwa is licensed under the Apache License 2.0.
