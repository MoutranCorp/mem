# Mem MVP Product Spec

## Product Thesis

Mem is a private memory hub for everything the user chooses to save: videos,
articles, documents, screenshots, notes, links, voice notes, agenda items, and
personal reflections. It captures sources, extracts useful context, indexes the
result, and gives the user an agent that can retrieve, answer, organize, and act
across the library.

The MVP should be ambitious. It should prove that the product can become the
center of the user's memory, not just a bookmark manager with a chat box.

## Positioning

Primary positioning:

- Personal memory and knowledge capture.
- Local-first library of saved sources.
- Fast search and AI retrieval across saved context.
- Journal, agenda, notebook, and collection hub.

Not primary positioning:

- Video downloader.
- Social media scraper.
- Browser-cookie extractor.
- Cloud notes clone.

The app can support authorized offline saves later, but store listing, onboarding,
and screenshots should emphasize memory, retrieval, and organization.

## Target User

The first user is a heavy information collector:

- Saves videos, links, articles, PDFs, screenshots, and notes all day.
- Forgets where things came from.
- Wants to ask vague questions and recover the right source quickly.
- Wants an assistant that can organize a messy personal library.
- Cares about privacy and control.

## MVP Pillars

### 1. Capture Anything

Required capture paths:

- Android share sheet for links and text.
- Paste URL.
- Manual note.
- Upload/import file through Android document picker.
- Screenshot/image import.
- Voice note.
- Camera capture.

Capture result:

- Every item enters Inbox as a durable ingestion job.
- The user can see status, progress, errors, and retry/cancel controls.
- Failed extraction still saves the source URL and a useful failure reason.

### 2. Extract Useful Memory

For each source, Mem should extract as much as is allowed and technically
available:

- URL normalization and canonical URL.
- Title, author/channel, site/app/source.
- Description and visible metadata.
- Thumbnail.
- Duration for media.
- Captions/transcripts when available.
- Article readable text where available.
- PDF/document text where available.
- Image OCR for screenshots and photos.
- Audio transcription for voice notes.
- Raw metadata JSON for diagnostics.

Sources that require auth should become `needs_auth` or `metadata_only`, not
dead failures.

### 3. Build a Fast Memory Index

The MVP should support thousands of saved memories without feeling slow.

Required:

- Local database of sources, extracted documents, assets, tags, collections,
  jobs, and agent actions.
- Full-text search over title, summary, transcript, article text, note text, and
  tags.
- Chunked transcript/document storage with source IDs and offsets.
- Ranking that combines lexical matches, recency, source quality, tags, and
  semantic similarity when embeddings are available.

Performance target:

- Search results should appear in under 300 ms for 10,000 saved sources on a
  modern flagship phone.
- Search results should appear in under 700 ms for 10,000 saved sources on a
  midrange phone.
- Opening a source detail page should feel instant after metadata is indexed.

### 4. Agent That Can Act

The agent should not only answer questions. It should operate the library.

MVP tools:

- Search memory.
- Open source.
- Answer with citations.
- Summarize source.
- Summarize collection.
- Create playlist.
- Add/remove tags.
- Move sources between collections.
- Mark source for review.
- Archive source.
- Create agenda/reminder from a memory.
- Suggest related memories.

Mutation rules:

- Any agent action that changes user data must be previewed or undoable.
- The user should be able to inspect why a memory was used in an answer.
- Citations should link to source detail and transcript/document offsets where
  possible.

### 5. Personal Memory UI

Required screens:

- Mem: main screen, command field, processing state, recent context, timeline.
- Capture sheet: paste, note, file, image, voice, camera.
- Inbox: queued, processing, failed, needs review, needs auth.
- Library: feed, grid, timeline, search/filter.
- Source detail: rich page for videos, articles, notes, PDFs, images, audio.
- Collections/playlists: manual and smart collections.
- Agent conversation: opened from the command field and contextual source
  actions, not from a primary dock tab.
- Settings: privacy, source rules, model settings, storage, appearance,
  export/delete.

The Library Feed is a secondary screen, not the main home. It should make the
entire memory library browsable with a familiar chronological rhythm.

Library should remember the user's last selected mode across launches.

Appearance customization is part of the MVP architecture. Users should be able
to change theme profile, accent color, light/dark/system mode, density, corner
style, and dock style without screens needing to be rewritten.

## Core User Flows

### Save a Video Link

1. User shares a video URL to Mem.
2. Mem creates a source and ingestion job.
3. yt-dlp extracts metadata and available captions without downloading media by
   default.
4. Mem stores title, thumbnail, duration, channel, captions/transcript, and raw
   extractor JSON.
5. The item appears in Recent, Library, and search.
6. The user can ask questions about it or add it to a playlist.

### Save an Article

1. User shares or pastes an article URL.
2. Mem fetches metadata and readable text when possible.
3. Mem summarizes and chunks the article.
4. The article appears as a source detail page with summary, key passages, tags,
   and related memories.

### Ask a Vague Question

1. User types "that video about calmer work routines".
2. Mem retrieves candidate memories across title, transcript, tags, summaries,
   and semantic matches.
3. The agent answers with the top sources and citations.
4. User can open the exact video/source or create a playlist from results.

### Reorganize Memory

1. User asks "organize my AI product research into playlists".
2. Agent retrieves matching sources.
3. Agent proposes collections/playlists with source counts.
4. User approves.
5. Mem creates collections and shows an undo action.

### Auth-Gated Source

1. User shares an Instagram Reel.
2. yt-dlp returns an auth/cookies error.
3. Mem saves the URL and visible metadata if available.
4. The item is marked `needs_auth`.
5. The app explains that the user can keep the link as metadata-only, retry if
   public, or later connect/import source auth explicitly.

## Offline Save Policy

Default behavior:

- Save metadata, transcripts, summaries, and user-created notes.
- Do not download media by default.

Authorized offline save can exist behind explicit user intent:

- Use language like "Save authorized copy" instead of "Download video".
- Require the user to confirm they have the right to save/process the source.
- Store rights confirmation state per source.
- Keep this out of primary onboarding and store screenshots.

The product must not encourage copyright infringement. Google Play's intellectual
property policy says apps must not encourage or induce infringement, and calls
out streaming apps that let users download copyrighted content without
authorization as a common violation:
https://support.google.com/googleplay/android-developer/answer/9888072

## MVP Data Objects

Source:

- id
- canonicalUrl
- originalUrl
- sourceType
- originDomain
- title
- author
- thumbnailAssetId
- durationMs
- savedAt
- updatedAt
- rightsState
- authState
- processingState
- rawMetadataJson

DocumentChunk:

- id
- sourceId
- text
- chunkType
- startOffset
- endOffset
- startTimeMs
- endTimeMs
- embeddingId

IngestionJob:

- id
- sourceId
- jobType
- state
- progress
- retryCount
- lastError
- createdAt
- updatedAt

Collection:

- id
- title
- type
- filterJson
- createdBy

AgentAction:

- id
- conversationId
- actionType
- targetIds
- proposedPayloadJson
- committedAt
- undoPayloadJson

## MVP Success Criteria

The MVP is successful when:

- The user can save links from the Android share sheet reliably.
- YouTube/public video metadata and captions work on-device.
- Instagram/auth-gated failures are graceful and shareable.
- Articles, notes, PDFs, screenshots, and voice notes become searchable memory.
- 10,000 saved sources remain fast enough for daily use.
- The agent can answer from saved memory with citations.
- The agent can create playlists/collections and tag sources.
- The app feels like a premium native product, not a technical demo.
- The app supports curated appearance customization without breaking usability.
- A closed testing Play build can be submitted without relying on sideload-only
  behavior.

## Explicit Non-Goals For The First Public Build

- Advertising broad video downloads.
- Runtime `yt-dlp -U`, `pip install`, `apt`, or proot/Debian.
- Silent browser cookie extraction.
- Bypassing app/web access controls.
- Background work without visible user state.
- Cloud sync as a required dependency.
- Social graph features.
- Public sharing/discovery network.

## Open Product Questions

- Should user accounts exist in MVP, or should MVP be fully local with optional
  export/import?
- Should cloud LLM/embedding calls be allowed by default, or only after a
  privacy setup step?
- Should downloaded authorized media be inside app-private storage only, or can
  users export through SAF/MediaStore?
- Should Mem include a daily journal prompt, or should journaling stay inside
  Capture?
- What exact source categories should be first-class in filters?
