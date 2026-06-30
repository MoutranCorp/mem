# Mem Technical Architecture

## Architecture Goals

Mem should be a Play-native Android app with on-device ingestion and a local-first
memory database. The architecture should isolate risky or change-heavy pieces
such as yt-dlp, FFmpeg, auth, embeddings, and agent tools behind clear
interfaces.

Core goals:

- Native Android UI quality.
- Packaged executable/interpreted code only.
- Durable ingestion jobs.
- Fast local search.
- Privacy-first storage.
- Clear Play Store compliance boundaries.
- Future iOS/shared-core path without compromising Android quality.

## Recommended Stack

UI:

- Kotlin.
- Jetpack Compose.
- Material 3 components with a custom Mem design system.
- Native Android navigation and adaptive layouts.
- Tokenized appearance system so user styling choices are consumed through
  theme primitives, not hardcoded screen values.

Persistence:

- Room over SQLite.
- SQLite FTS5 for lexical search.
- App-private file storage for thumbnails, extracted assets, temp files, and
  debug bundles.
- SAF/MediaStore only for explicit user export/import.

Current implementation note: Room code generation uses KSP, not KAPT. The local
Windows/JDK build environment repeatedly hit generated KAPT file access errors,
so reverting to KAPT is a regression unless Android E2E and clean APK builds
are proven afterward.

Background work:

- WorkManager for short/deferrable indexing and enrichment.
- Android user-initiated data transfer jobs for long user-started network
  transfers on supported versions.
- Foreground service with `mediaProcessing` type for long FFmpeg/remux/transcode
  work when it is truly user-visible and not deferrable.

Extraction:

- Chaquopy or another embedded Python runtime for packaged yt-dlp.
- Packaged yt-dlp version pinned at build time.
- No runtime self-update.
- FFmpeg/ffprobe packaged per ABI when needed.

Search/RAG:

- Hybrid retrieval: FTS first, semantic rerank when embeddings are available.
- Transcript/document chunks with stable source IDs and offsets.
- Embedding provider interface with local and remote implementations.
- Cached embeddings stored locally.

Agent:

- Tool-based agent runtime.
- Mutation actions go through app services, never direct DB writes from LLM
  output.
- Every mutating action must be previewable or undoable.

## Module Shape

Near term this repo can stay single-module while the spike becomes a real app.
As soon as the production app shell starts, split into modules:

- `:app`: Compose app, navigation, dependency injection, Android entry points.
- `:core:model`: source, chunk, collection, agent action models.
- `:core:database`: Room DAOs, migrations, FTS tables.
- `:core:ingestion`: job orchestration and state machine.
- `:core:search`: FTS queries, ranking, embedding interfaces.
- `:core:agent`: tools, action proposals, citation model.
- `:core:appearance`: persisted appearance settings and theme profile models.
- `:designsystem`: Mem theme, tokens, and reusable Compose components.
- `:extractor:ytdlp`: Python bridge and extractor result normalization.
- `:media:ffmpeg`: FFmpeg/ffprobe discovery and media operations.
- `:feature:mem`: Mem home UI.
- `:feature:capture`: capture sheet, share target, inbox.
- `:feature:library`: feed/grid/timeline/source detail.
- `:feature:ask`: agent conversation and action review.
- `:feature:settings`: privacy, source rules, storage, export/delete.

## Appearance Architecture

User-customizable styling is a core requirement. Screens should not hardcode
colors, corner radii, elevations, spacing, typography sizes, blur values, or
density choices. They should render app-owned components that consume Mem theme
tokens.

Create a `MemTheme` layer with:

- `MemColorScheme`
- `MemTypography`
- `MemShapes`
- `MemSpacing`
- `MemElevation`
- `MemMotion`
- `MemDensity`
- `MemGlass`

Theme values should be exposed through Compose CompositionLocals. Persist user
choices with DataStore or an `AppearanceSettings` table, then derive runtime
tokens from the selected profile and overrides.

Initial settings:

- Theme profile.
- Accent color.
- Light, dark, or system mode.
- Density.
- Corner style.
- Dock style.
- Library last selected mode.

Implementation rules:

- Feature screens depend on `:designsystem`, not Material components directly
  where a Mem component exists.
- Components accept semantic state, not raw visual overrides.
- Theme profiles must pass contrast, dynamic type, touch target, and
  reduce-motion checks.
- Glass/translucent surfaces must have solid fallbacks.

Detailed product rules live in `docs/design-system-customization.md`.

## Ingestion Pipeline

Every capture creates a source and a job. The UI reads job state from the
database, not from in-memory callbacks.

States:

- `queued`
- `extracting`
- `enriching`
- `indexing`
- `done`
- `needs_auth`
- `metadata_only`
- `failed`
- `canceled`

Pipeline:

1. Normalize input.
2. Create or merge source.
3. Run source adapter.
4. Store raw metadata.
5. Store extracted documents/assets.
6. Generate summary/tags.
7. Chunk text/transcripts.
8. Update FTS index.
9. Generate/cache embeddings if available.
10. Mark done or needs review.

Adapters:

- `VideoLinkAdapter` using yt-dlp.
- `ArticleAdapter` using metadata/readability extraction.
- `DocumentAdapter` for PDFs/text files.
- `ImageAdapter` for OCR.
- `AudioAdapter` for voice notes/transcription.
- `ManualNoteAdapter` for user notes and journal entries.

## yt-dlp Boundary

yt-dlp should be treated as an extractor engine, not the product identity.

Rules:

- Python module exposes stable functions such as `extract_metadata(url)` and
  later `save_authorized_media(sourceId, options)`.
- Kotlin never depends on raw yt-dlp result shape directly.
- Normalize extractor results into app-owned models.
- Keep full raw JSON only for diagnostics and future reprocessing.
- Never run `yt-dlp -U`.
- Never install Python packages at runtime.
- Do not silently read cookies from browsers or other apps.

For auth-gated sources, return a typed result:

- `authRequired = true`
- `authProvider = instagram | tiktok | other`
- `visibleMetadata`
- `nextStep`

## FFmpeg Boundary

FFmpeg/ffprobe should be packaged as native binaries per ABI only when we move
from metadata extraction into media probing/remuxing/offline-save work.

Rules:

- Prefer LGPL builds unless GPL obligations are deliberately accepted.
- Store build scripts and third-party notices.
- Pass explicit `ffmpeg_location` to yt-dlp.
- Use app-private temp storage.
- Long media processing must be visible and cancelable.

Android documents the `mediaProcessing` foreground service type for
time-consuming media conversions and says it has a limited runtime budget:
https://developer.android.com/develop/background-work/services/fgs/service-types

## Background Execution

Use the least powerful background mechanism that fits the job:

- Immediate short extraction while app is visible: coroutine plus DB state.
- Short deferrable enrichment/indexing: WorkManager.
- Long user-started network transfer: user-initiated data transfer job where
  supported.
- Long media conversion/remux: foreground service with media processing type.

Android's UIDT docs say these jobs are for longer user-started transfers, require
notifications, start immediately, and can run for an extended period subject to
system conditions:
https://developer.android.com/develop/background-work/background-tasks/uidt

Google Play's Device and Network Abuse policy also says UIDT jobs should be used
only for user-initiated network data transfers that run only as long as needed:
https://support.google.com/googleplay/android-developer/answer/9888379

## Search And RAG

Retrieval should be deterministic and inspectable before it becomes magical.

Index layers:

- FTS table for lexical search.
- Metadata filters for type, source, tags, collection, date, auth state.
- Optional vector index for semantic retrieval.
- Ranking function that mixes FTS score, semantic score, recency, source quality,
  collection/tag boosts, and exact-entity matches.

Index integrity rules:

- Search queries must not materialize chunks unless the source/chunk rows still
  exist. Use inner joins or explicit existence checks when joining FTS rows to
  app-owned tables.
- Deletion and reprocessing must clean or repair FTS rows, embeddings, visual
  observations, and assets for removed sources.
- Startup or migration maintenance should eventually remove orphan
  `chunk_search` rows. The 2026-06-30 downloaded-video search crash was caused
  by an orphan/stale FTS row reaching Room as a null non-null field.
- Search results should degrade by excluding corrupt index rows, not by
  crashing the Library UI.

Chunking:

- Videos: transcript chunks by timestamp, with overlap.
- Articles: paragraph/section chunks.
- PDFs/docs: page and paragraph chunks.
- Notes: note-level chunks plus extracted entities.
- Images: OCR text chunks with image asset reference.
- Audio: transcript chunks with time offsets.

Citations:

- Every answer should include source IDs.
- Video citations include timestamp.
- Document citations include page or offset when available.
- Agent UI can open the cited source detail directly.

## Agent Runtime

The agent should call app tools, not improvise side effects.

Tools:

- `searchMemory(query, filters)`
- `getSource(sourceId)`
- `summarizeSource(sourceId)`
- `summarizeCollection(collectionId | filter)`
- `createCollection(title, sourceIds)`
- `createPlaylist(title, sourceIds)`
- `tagSources(sourceIds, tags)`
- `archiveSources(sourceIds)`
- `scheduleReview(sourceId, date)`
- `openSource(sourceId, offset?)`

Mutation flow:

1. Agent proposes action.
2. App validates source IDs and permissions.
3. UI previews material changes for risky actions.
4. User approves or the action is auto-applied only if low risk and undoable.
5. DB writes happen through repository/service layer.
6. AgentAction row records what changed.

## Privacy And Security

Default stance:

- Local-first storage.
- Minimal Android permissions.
- No `MANAGE_EXTERNAL_STORAGE`.
- No silent scraping of other apps' cookies or databases.
- No source credentials until explicit source connection/import exists.
- Clear delete/export controls.

Cloud AI:

- Treat remote LLM/embedding calls as a product setting, not an invisible
  implementation detail.
- Show what data may be sent.
- Cache results locally.
- Keep a local-only fallback path using FTS and on-device summaries where
  possible.

## Play Store Guardrails

Google Play's Device and Network Abuse policy says apps distributed through Play
may not update themselves outside Play or download executable code such as dex,
JAR, or `.so` files from outside Play:
https://support.google.com/googleplay/android-developer/answer/9888379

Implications for Mem:

- Package Python code in the APK/AAB.
- Package yt-dlp in the APK/AAB.
- Package FFmpeg/ffprobe in the APK/AAB.
- Update extractor code through app updates.
- Avoid proot/Debian, apt, runtime pip, or native binary downloads.
- Do not frame the product as a way to download copyrighted streaming content.

## iOS Future

If we build iOS later, do not share the UI layer. Use SwiftUI/UIKit for app
quality. Shared code should live in model, indexing, sync, or crypto layers only
if it does not compromise native feel.

Possible shared core:

- Rust library for indexing/ranking/chunking.
- Shared schema definitions.
- Shared sync/encryption protocol.

Do not assume the iOS app can run the same subprocess model as Android. iOS
should use embedded libraries and app-owned background modes only.
