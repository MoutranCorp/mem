# Mem MVP Development Plan

This app should be built as a Play-native Android app, not as a proot/Debian
runtime. All executable and interpreted code must be packaged in the APK/AAB and
updated only through Play.

Companion planning docs:

- `docs/current-status-and-handoff.md`
- `docs/mvp-product-spec.md`
- `docs/technical-architecture.md`
- `docs/design-direction.md`
- `docs/design-system-customization.md`
- `docs/mvp-build-roadmap.md`

## Product Boundary

Mem is a personal memory ingestion and retrieval app. It accepts links and files
the user chooses to save, extracts metadata/transcripts/text where permitted, and
adds them to a local-first memory index. The agent can retrieve, organize,
summarize, create collections/playlists, and help the user navigate their saved
material.

The app must not be positioned as a video downloader. The UI should say users
must have the right to save/process a source, but a disclaimer alone is not the
compliance strategy. Product copy, screenshots, onboarding, and defaults should
all emphasize personal knowledge capture, citations, organization, and recall.

## Spike 0: On-device yt-dlp Metadata

Status: implemented in this repo.

- `targetSdk 35`, `minSdk 24`.
- Embedded Python via Chaquopy.
- Pinned packaged `yt-dlp==2026.6.9`.
- Share-sheet and paste URL ingestion.
- Runs `yt-dlp.extract_info(url, download=False)` on a background thread.
- Produces compact JSON suitable for a future RAG document.
- Output text is selectable and can be copied/shared for test logs.

Acceptance on a real device:

- Install debug APK.
- Share a public-domain or owned-content URL to Mem Spike.
- Confirm extraction returns title, source URL, extractor, duration, thumbnails,
  formats count, and subtitle/automatic-caption language candidates.
- Confirm no video file is downloaded.
- Confirm repeated extraction works in one app process with different URLs.
- Confirm error output can be copied and shared from the phone.

## Spike 0.5: Auth-Gated Sources

Goal: decide the compliant MVP behavior for links that require a logged-in
session, such as many Instagram Reels.

- Treat logged-out public extraction as the default.
- Do not silently read cookies from other apps or browsers.
- Evaluate explicit user-imported cookies as a testing-only option.
- If kept, store cookies encrypted, scoped by source, and explain that users
  must have the right to access and process that content.
- Prefer metadata/link capture fallback when extraction requires auth.

## Spike 1: FFmpeg Packaging

Goal: prove `yt-dlp` can use packaged `ffmpeg`/`ffprobe` on Android without
runtime downloads.

- Build or source reproducible arm64 LGPL FFmpeg binaries.
- Package them as native executable libs, e.g. `libffmpeg.so` and
  `libffprobe.so`.
- Pass their paths through `ffmpeg_location`.
- Verify `ffmpeg -version` and a small remux/transcode job on-device.
- Add third-party notices and source/build instructions.

Do not add GPL FFmpeg components unless the whole distribution strategy accepts
the GPL obligations.

## Spike 2: Ingestion Jobs

Goal: turn a one-shot extractor into durable ingestion.

- Add Room tables:
  - `sources`: canonical URL, normalized URL, source type, title, author,
    created/saved timestamps, rights confirmation state.
  - `ingestion_jobs`: state, progress, retry count, error, source ID.
  - `documents`: extracted text chunks, transcript segments, metadata JSON.
  - `assets`: thumbnails, downloaded authorized files, derived media.
- Add states: `queued`, `extracting`, `enriching`, `indexing`, `done`, `failed`,
  `canceled`.
- Keep raw extractor JSON for debugging, but index normalized fields.

## Spike 3: RAG Core

Goal: thousands of saved links stay fast.

- Use SQLite FTS5 for title/description/transcript lexical search.
- Add an embedding interface with a local-first implementation when feasible and
  a server-backed implementation only if the privacy/product model allows it.
- Chunk transcripts by timestamp so answers can cite exact video moments.
- Store source IDs and offsets in every chunk so the agent can jump to the right
  saved item.
- Benchmark 1k, 10k, and 50k source records on a midrange phone.
- Keep index corruption non-fatal. FTS/vector rows must be repaired or excluded
  when their owning source/chunk has been deleted or reprocessed.
- Keep Android crash repros in `docs/android-e2e-testing.md` and
  `tools/android-e2e.ps1` as first-class acceptance tests.

## Spike 4: Agent Actions

Goal: the agent can act across memory, not just answer.

- Define tool interfaces:
  - `search_memory(query, filters)`
  - `open_source(source_id, timestamp?)`
  - `tag_sources(source_ids, tags)`
  - `create_playlist(title, source_ids)`
  - `summarize_collection(filter)`
  - `schedule_review(source_id, date)`
- Keep actions auditable: every mutation should be visible and undoable.
- Use citations by default for answers.

## Spike 5: Background Execution

Goal: long work remains Play-compliant and user-visible.

- Use user-initiated data transfer jobs for user-started network transfers.
- Use a media-processing foreground service for remux/transcode work.
- Show notification progress, cancel controls, and clear failure states.
- Do not run indefinite hidden background services.

## Launch Track

1. Metadata-only internal build.
2. FFmpeg-enabled internal build.
3. RAG prototype with local files and public-domain URLs.
4. Closed testing with real users and Play policy-safe copy/screenshots.
5. Production launch only after download/caption behavior is proven compliant
   with the app's source rules.
