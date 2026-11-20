# FAQ

**Does Suwa download from manga sites directly?**
No. Everything goes through your Suwayomi server; the phone only talks to the server and to GitHub (for updates).

**Can I add sources or extensions from the app?**
Not yet. Install extensions in the Suwayomi web UI; they show up under Browse.

**Can I migrate a title to another source from the app?**
Migrate it on the server (the Suwayomi web UI has a migration screen). Suwa picks up the new entry on the next
refresh, and progress queued offline follows the chapter numbers.

**A series opens in the wrong reading mode.**
It has its own mode, set from the reader's reading-mode button on any device. Open the reader settings and tap
**Use default**, or pick another mode.

**The app says it's offline but the server is up.**
Check the address in **More → Settings → Server**. The phone must reach that exact URL, so a LAN address won't work
away from home unless you use a VPN.

**Where are downloads stored, and do they survive a reinstall?**
In the app's own storage (`Android/data/app.suwareader/files/downloads`). Uninstalling removes them; reinstalling
doesn't bring them back, but your library and progress are on the server.

**Play Protect says the app may be harmful.**
See [App updates](updates.md#prompts-you-may-see). The source code and the CI that builds every release are public.
