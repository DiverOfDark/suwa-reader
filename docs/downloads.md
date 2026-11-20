# Downloads and offline reading

Download chapters from a series page (single chapters, a selection, or the download menu) or from Updates. They're
fetched from your Suwayomi server, not from the source, so the server must be reachable while downloading.

- **More → Downloads** shows the queue and what's on the phone, with the space it takes.
- Several chapters download at once. Set how many in **Settings → Downloads → Chapters downloaded at the same time**
  (1–5). Changing it applies straight away, even mid-download.
- A failed chapter shows why, with a **Retry** button. **⋮ → Retry failed** retries all of them.
- If the server stops answering, the queue stops and is retried automatically a little later.

Other download settings:

- **Only download on Wi-Fi.**
- **Delete chapters after reading them**: removes a downloaded chapter once you finish it and leave the reader.
- **While reading a downloaded chapter, download the next N**: keeps a few chapters ahead downloaded.

## Offline

Without a connection Suwa shows the library, series pages, updates and history as last seen, and reads anything
downloaded. Reading progress is queued and sent when the server is reachable again; **More** shows how many updates
are waiting.

## Downloads follow the server

Downloads belong to the library. When a title leaves the server's library, or the server no longer lists a chapter (for
example after re-fetching from a source), its downloads on the phone are deleted on the next refresh.

Files live in `Android/data/app.suwareader/files/downloads/<series>/<chapter>/`, one image per page.
