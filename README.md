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

Immediate next steps:

1. Add in-app local media playback for downloaded/authorized video assets.
2. Add local thumbnail/file caching for durable assets.
3. Expand source detail into full video/article/document layouts.
4. Build collection detail screens and richer tag editing.
5. Expand FTS indexing to transcript/caption chunks and richer ranking.
6. Harden Instagram auth with session validation, expiry messaging, and
   encrypted storage.
7. Add OCR for scanned/image-only PDFs and imported images.
8. Package real per-ABI `ffmpeg` and `ffprobe` binaries as native executable
   libs and pass their paths to `yt-dlp` through `ffmpeg_location`.
9. Move long downloads to user-initiated data transfer jobs and media processing
   work to a foreground service with the `mediaProcessing` type.
10. Add transcript/caption fetching behind an explicit rights confirmation.
11. Add an embedding/index interface so every extracted record becomes a RAG
   document with stable IDs, source URL, timestamp, title, tags, and transcript
   segments.

## Play Store Guardrails

- Do not use proot, apt, pip install at runtime, or `yt-dlp -U`.
- All executable/interpreted code must ship through the app update.
- Use app-private temp storage and MediaStore/SAF for exports.
- Avoid `MANAGE_EXTERNAL_STORAGE`.
- Frame the product as authorized personal memory ingestion, not a downloader.
- A disclaimer alone is not enough for Play review if the app encourages
  copyright infringement.
