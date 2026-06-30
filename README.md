# Mem

This is a Play-targeted Android app prototype for Mem, a private memory hub
which runs packaged `yt-dlp` directly on the phone without the Debian/proot
model used by `mobile-agent`.

## What This Proves

- `targetSdk 35` app shell.
- Share-sheet and manual URL ingestion.
- Copy/share output for phone-side test logs.
- Embedded Python through Chaquopy.
- Pinned, packaged `yt-dlp==2026.6.9`.
- On-device metadata extraction with `download=False`.
- A compact JSON result shaped for a future RAG ingestion pipeline.
- Kotlin/Compose app shell.
- Tokenized Mem theme layer with user-selectable profiles, mode, density,
  corner style, and dock style.
- Mem home, floating dock, capture sheet, inbox/logs, and library feed/grid/
  timeline shell.
- Library mode is persisted and restored.
- Room-backed `sources`, `ingestion_jobs`, and `document_chunks` tables.
- Captured links and extraction outcomes persist across app restarts.
- Auth-required and failed extractions stay visible in Inbox with logs.
- Library search is backed by a Room FTS index.
- Inbox supports retry and cancel actions for durable jobs.
- Durable tag, collection, and collection membership tables.
- Library feed can add a source to `Saved playlist` and tag it for review.
- Durable asset table for thumbnails/files, with extracted thumbnails rendered
  in recent sources, feed, and grid where available.
- Collections/playlists live in the bottom dock instead of the Library mode
  switcher, keeping Feed/Grid/Timeline compact.
- Clicking non-downloaded source thumbnails opens the original source URL in the
  matching external app/browser when available.
- Source detail sheet for saved memories, with copyable source URL, summary,
  asset/playback status, and playlist/tag actions.
- Source detail now surfaces author, duration, processing/auth state, and
  selectable raw metadata JSON from the packaged extractor.
- Article/webpage links now fall back to a packaged metadata extractor when
  yt-dlp cannot handle the URL, capturing title, description, canonical URL,
  author, and Open Graph image where available.
- Article fallback also extracts bounded readable page text and stores it as a
  durable RAG text chunk for better search/retrieval.
- Capture now saves non-URL text as a manual note, while shared text containing
  a URL extracts the first URL and processes it through the link adapters.
- Text-like files can be imported from the Capture file picker or Android share
  sheet and are stored as durable document sources with `rag_text` chunks.
- PDFs can be imported from the file picker or share sheet; embedded PDF text is
  extracted with packaged `pypdf` and stored as `rag_text` when available.
- Images can be imported from the file picker or share sheet, copied into
  app-private storage, saved as durable image sources, and rendered locally in
  the library.
- Videos can be imported from the file picker or share sheet, copied into
  app-private storage, saved as durable local playback assets, played inline in
  the feed, and opened in a fullscreen in-app player.
- Video imports generate a local thumbnail, feed autoplay is limited to one
  visible video at a time, controls stay hidden until the video is tapped, and
  playback seek-back/seek-forward seconds are configurable in settings.
- Saved memories can be deleted from feed, grid, or source detail, and playable
  video thumbnails/players expose an expand button for fullscreen playback.
- Source detail can start a rights-confirmed `Save authorized copy` flow that
  runs packaged `yt-dlp`, stores the downloaded media in app-private storage,
  and attaches it as the memory's local playback asset.
- Auth-gated links are saved as first-class `needs_auth` sources with a clear
  detail callout and retry-public-extraction action.
- `needs_auth` source detail can import a domain-scoped `cookies.txt` file,
  store it as an auth session, and retry `yt-dlp` with that cookie file.
- Instagram `needs_auth` source detail can open a dedicated login WebView,
  save the resulting session into the auth session store, and retry extraction.
- Auth-required source detail can clear the saved domain auth session so cookie
  import and WebView login paths can be retested.
- The always-reachable appearance/settings sheet can clear Instagram auth even
  when the current session is already working.
- Instagram login WebView includes loading/error diagnostics, reload, browser
  reachability check, explicit hardware acceleration, and a mobile Chrome user
  agent for better compatibility.
- Instagram connection opens directly to the desktop login mode, keeps
  diagnostics compact, and shows when a session cookie has been detected.

The app deliberately does not advertise or perform video downloads. The first
product path should ingest metadata, captions/transcripts where permitted, and
user-owned files into a memory index.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The app builds against the Android SDK at `C:\src\androidsdk`.

## Run

Install the debug APK on a connected device, then paste a URL or share text to
`Mem`.

```powershell
C:\src\androidsdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Current Extractor Limitation

Some sources, especially Instagram Reels, often require a logged-in browser
session. In that case yt-dlp will return an auth/cookies error even if YouTube
works. The Play-safe version does not scrape browser cookies silently. It can
open a dedicated Instagram login view, save the user-approved session cookies
into app-private storage, and pass that cookie file to packaged `yt-dlp`.
Importing an explicit user-selected `cookies.txt` remains available as a
fallback.

## Next Work

The production direction is documented in:

- `docs/mvp-product-spec.md`
- `docs/technical-architecture.md`
- `docs/design-direction.md`
- `docs/design-system-customization.md`
- `docs/mvp-build-roadmap.md`
- `docs/rag-agent-spike-2026-06-30.md`
- `docs/search-rag-benchmark-fixtures.md`

Immediate next steps:

1. Add transcript/caption extraction through yt-dlp without downloading media.
   Started with subtitle URL selection, WebVTT/SRT/JSON3 parsing, and
   transcript chunks returned by the packaged extractor.
2. Parse subtitles into timestamped transcript segments and durable chunks.
   Started with Room version 6 caption track and chunk search tables.
3. Expand source/detail search to query transcript/article chunks, not just
   source summaries.
   Started with chunk-level FTS and cited search result cards in Library.
   Source detail now loads indexed chunks so transcript/article context is
   inspectable from the memory itself. Timestamped citations now open source
   detail at the cited time and seek local playback when media is playable.
4. Add deterministic search filters and match explanations from the command
   field.
   Started with simple `type:`, `site:`, `domain:`, `status:`, and `has:`
   filter tokens routed into FTS tags, quoted phrase handling, and match
   reason cards. Search now applies structured hard filters for type, domain,
   status, transcript/visual/timestamp capabilities, negative terms, and shows
   rank signals on cited result cards.
5. Add an embedding/index interface so every extracted record can become a RAG
   document with stable IDs, source URL, timestamp, title, tags, and transcript
   segments.
   Started with provider interfaces for embeddings, vector search, and agent
   tool execution. Room now persists chunk embeddings and runs a local
   deterministic exact-scan semantic fallback, with hybrid keyword/semantic
   fusion in cited search results.
6. Add the first agent search tool with cited results and action previews.
   Started with a local agent search panel that summarizes grounded citations
   and can draft a collection from the cited source set. Collection drafts now
   create auditable agent action records with preview, apply, and undo.
7. Build visual indexing for local/authorized videos so Mem can answer queries
   about what happens visually, not only what is spoken.
   Started with local frame-sample visual observations for playable videos,
   indexed as `visual` chunks for `has:visual` search and future model-backed
   scene/action/OCR analysis.
8. Harden Instagram auth with session validation, expiry messaging, and
   encrypted storage.
9. Add OCR for scanned/image-only PDFs and imported images.
10. Package real per-ABI `ffmpeg` and `ffprobe` binaries as native executable
   libs and pass their paths to `yt-dlp` through `ffmpeg_location`.
11. Move long downloads to user-initiated data transfer jobs and media processing
   work to a foreground service with the `mediaProcessing` type.

## Play Store Guardrails

- Do not use proot, apt, pip install at runtime, or `yt-dlp -U`.
- All executable/interpreted code must ship through the app update.
- Use app-private temp storage and MediaStore/SAF for exports.
- Avoid `MANAGE_EXTERNAL_STORAGE`.
- Frame the product as authorized personal memory ingestion, not a downloader.
- A disclaimer alone is not enough for Play review if the app encourages
  copyright infringement.
