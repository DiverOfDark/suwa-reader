# The server is the source of truth

Suwa is a client for Suwayomi, not a second library. Everything you see comes from the server's GraphQL API:

| What | Where it lives | On the phone |
|---|---|---|
| Library, categories | Server | Cached for offline display |
| Series details, chapters | Server | Cached per series |
| Read state, page, bookmarks | Server | Cached, plus changes not yet sent |
| Reading mode per series | Server (series metadata) | Cached |
| Shared reader settings | Server (global metadata) | Applied locally |
| Downloads | Phone | Removed when the server drops the title or chapter |
| Device-specific settings | Phone | |

Refreshing replaces the cache wholesale. The phone never decides what's in your library.

## Progress made on the phone

The only state the phone owns is reading progress the server hasn't confirmed yet:

1. Turning a page records the change locally and shows it at once.
2. About two seconds later, or straight away when a chapter is finished, it's sent to the server.
3. If the server can't be reached, it stays queued, survives app restarts, and is sent when the network returns.
4. Before a library refresh, queued progress is sent first, so the fresh copy already includes it.

## Migrations

When you migrate a series to another source on the server, its chapters get new IDs. Progress queued offline for the
old chapters is moved to the chapter with the same number in the series with the same title. If no such chapter
exists, it's dropped rather than written somewhere wrong.

## Settings

Reading-related reader settings are stored in the server's global metadata under `suwa_reader.*`. Per-series reading
mode and rotation are stored in that series' metadata as `suwa_readingMode` and `suwa_orientation`. A change made
offline is kept on the phone and wins over the server's older value until it has been sent.
