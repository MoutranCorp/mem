# Mem MVP Build Roadmap

This roadmap assumes quality matters more than development speed. The goal is to
move from the current Java spike to a production-grade native Android app while
preserving the proven on-device yt-dlp extraction path.

## Phase 0: Preserve The Spike

Status: mostly done.

Deliverables:

- Keep the current APK as a proof that packaged yt-dlp can run on-device.
- Keep copy/share logs.
- Keep repeated extraction fix.
- Keep graceful auth-required output.
- Keep all mockups under `docs/mockups/main-page/`.

Exit criteria:

- Debug APK remains buildable.
- README explains spike boundaries.
- Spike is not mistaken for production architecture.

## Phase 1: Product Skeleton

Goal: create the real app shell.

Deliverables:

- Convert app to Kotlin.
- Add Jetpack Compose.
- Add app navigation.
- Add Mem design tokens and component primitives.
- Add appearance settings architecture for theme profiles, density, accent,
  corner style, dock style, and library last mode.
- Build Mem shell with floating dock.
- Build Capture sheet shell.
- Build Library shell with Feed/Grid/Timeline tabs.
- Build Source Detail placeholder.
- Build agent conversation as an expansion from the command field and source
  actions, not as a dock tab.

Exit criteria:

- The app visually resembles the chosen design direction.
- The first screens use design-system tokens instead of hardcoded styling.
- Navigation is smooth and native.
- The share sheet still lands in Capture/Inbox.
- No ingestion behavior regresses.

## Phase 2: Durable Local Memory

Goal: replace one-shot extraction output with real data.

Status: started. The app now has Room-backed `sources`, `ingestion_jobs`, and
`document_chunks` tables. Captured links create durable jobs, extraction results
persist as sources, and Inbox/Library/Home observe database state.

Deliverables:

- Add Room. Done.
- Add Source, IngestionJob, DocumentChunk, Asset, Tag, Collection tables.
  Source, IngestionJob, and DocumentChunk are done; Asset, Tag, and Collection
  remain.
- Add migrations from version 1 onward.
- Add repository layer.
- Add Inbox screen backed by DB state. Done.
- Store raw metadata JSON. Done.
- Store normalized extracted fields. Started.

Exit criteria:

- Closing and reopening the app preserves sources and job state.
- Repeated saves of the same URL merge cleanly.
- Failed jobs are visible and retryable.

## Phase 3: Extraction Adapters

Goal: turn capture into source-specific extraction.

Deliverables:

- yt-dlp adapter for public video metadata and captions.
- Article metadata/readability adapter.
- Manual note adapter.
- PDF/text import adapter.
- Image import plus OCR spike.
- Voice note plus transcription spike.
- Auth-gated source result type.

Exit criteria:

- YouTube/public video links become searchable sources.
- Instagram/auth-gated links save as `needs_auth` instead of dead failures.
- Articles and notes become searchable.
- Users can share logs for any failed extraction.

## Phase 4: Library And Source Detail

Goal: make saved memories useful before the agent is smart.

Deliverables:

- Library Feed.
- Library Grid.
- Library Timeline.
- Source detail for video.
- Source detail for article/document.
- Tags.
- Manual collections.
- Smart collection placeholders.
- Archive and favorite.

Exit criteria:

- A user can browse hundreds of sources comfortably.
- Source pages show extracted context clearly.
- Library filters work by type, tag, source, date, and processing state.

## Phase 5: Search And RAG Core

Goal: make "ask/find anything" fast and reliable.

Deliverables:

- SQLite FTS5 index.
- Query parser for filters and natural phrases.
- Transcript/document chunking.
- Ranking function.
- Embedding provider interface.
- First embedding implementation.
- Search result citations.
- Benchmark dataset generator.

Exit criteria:

- Search works under the performance targets in `docs/mvp-product-spec.md`.
- A vague query finds the right saved sources.
- Results can jump to exact source detail and timestamp/page/offset.

## Phase 6: Agent Actions

Goal: make the agent useful beyond Q&A.

Deliverables:

- Ask screen.
- Tool interfaces.
- Search-memory tool.
- Summarize-source tool.
- Tag/create-collection/create-playlist tools.
- Action previews and undo.
- Agent action history.
- Citation UI.

Exit criteria:

- Agent answers with citations.
- Agent creates playlists/collections from retrieved memories.
- Agent can tag and archive sources through auditable actions.
- Mutations are undoable or require approval.

## Phase 7: Media And Offline Save

Goal: support authorized offline media operations without turning the app into a
downloader product.

Deliverables:

- Package FFmpeg/ffprobe.
- Add media probe.
- Add explicit "Save authorized copy" flow.
- Add rights confirmation state.
- Add user-visible progress notifications.
- Add cancel/retry.
- Add storage management.
- Add third-party notices.

Exit criteria:

- Media operations use packaged binaries only.
- Long transfers/processing are visible and cancelable.
- Store listing and in-app copy still position Mem as memory capture.

## Phase 8: Play Closed Testing

Goal: submit a policy-safe closed testing build.

Deliverables:

- App bundle build.
- Privacy policy draft.
- Data safety draft.
- Store listing draft with original screenshots/content.
- Internal/closed testing release notes.
- Crash/performance telemetry decision.
- Pre-launch report review.
- Test account/source instructions if needed.

Exit criteria:

- Closed testing build installs from Play.
- No sideload-only dependency remains.
- Policy-sensitive flows are documented and defensible.

## Immediate Next Build Sequence

1. Commit the mockups and planning docs.
2. Create a production branch or keep `main` as the product line and preserve
   spike history through tags.
3. Add Kotlin and Compose without removing the working extractor.
4. Rebuild the current spike UI as a Compose Capture/Inbox prototype.
5. Add Room and persist source/job state.
6. Move extraction output into normalized source records.
