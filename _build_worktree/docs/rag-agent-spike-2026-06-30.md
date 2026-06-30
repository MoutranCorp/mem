# RAG And Agentic Memory Spike

Date: 2026-06-30

This spike answers the question: what is the best path for semantic search,
non-semantic search, multimodal memory, and agentic control in Mem?

The conclusion is that Mem should not build a single "RAG feature." Mem should
build a local-first memory substrate with provider-swappable AI:

1. A normalized source and chunk model.
2. Transcript-first video indexing.
3. Visual observations for things transcripts never say.
4. Powerful deterministic search.
5. Hybrid retrieval that fuses filters, full-text search, embeddings, and
   reranking.
6. An agent runtime that calls app tools and previews mutations.

The model landscape is moving too quickly to bind the product to one provider or
one current model family. The defensible architecture is stable app-owned
memory and search primitives with replaceable model providers.

## Current Landscape

Relevant 2026 facts:

- yt-dlp exposes a Python embedding path through `YoutubeDL.extract_info`, and
  its docs recommend `sanitize_info` before treating info as JSON. It also
  exposes subtitle options for manual and automatic subtitles, including
  subtitle language and format selection.
  - https://github.com/yt-dlp/yt-dlp#embedding-yt-dlp
  - https://github.com/yt-dlp/yt-dlp#subtitle-options
- yt-dlp metadata fields are extractor-dependent, so Mem should store a stable
  normalized subset plus sanitized raw metadata for future migrations.
  - https://github.com/yt-dlp/yt-dlp#output-template
- SQLite FTS5 supports efficient full-text search, ranking, snippets, column
  filters, boolean queries, prefix queries, and NEAR queries. Current app code
  uses Room FTS4, which is a workable compatibility layer, but the target search
  design should account for FTS5-level capabilities or a bundled SQLite layer if
  needed.
  - https://www.sqlite.org/fts5.html
- Google AI Edge documents the standard RAG pipeline as import, split/index,
  embed, retrieve, then generate. The current AI Edge RAG SDK page is marked
  deprecated, which reinforces that Mem should own the abstraction rather than
  couple itself tightly to that SDK.
  - https://developers.google.com/edge/mediapipe/solutions/genai/rag
- Android now has meaningful on-device AI surfaces through Gemini Nano/AICore
  and ML Kit GenAI, including summarization, image description, speech
  recognition, and custom prompt APIs. Device support is still limited and
  model/API availability varies, so this should be a provider plugin, not a hard
  dependency.
  - https://developer.android.com/ai/gemini-nano
  - https://developers.google.com/ml-kit/genai
- Gemini Developer API through Firebase AI Logic supports cloud Gemini models
  from Android and can handle text, image, audio, and video inputs. It also
  points developers toward Firebase App Check for abuse protection, which
  matters if calling a model directly from the app.
  - https://developer.android.com/ai/gemini/developer-api
- OpenAI's current API docs position function calling as the way to connect a
  model to app data and app actions. Its model docs recommend flagship models
  for complex reasoning and smaller variants for latency/cost. This maps well to
  Mem's needed split between local retrieval and cloud-quality reasoning.
  - https://developers.openai.com/api/docs/guides/function-calling
  - https://developers.openai.com/api/docs/models
- OpenAI embeddings docs continue to frame embeddings as useful for search,
  clustering, recommendations, classification, and relatedness. Embeddings are
  not a replacement for filters, transcripts, or visual indexing.
  - https://developers.openai.com/api/docs/guides/embeddings
- sqlite-vec is a promising SQLite vector extension that stores float, int8,
  and binary vectors and runs on mobile-class environments, but it is still
  pre-v1. Use behind a local vector index interface with an exact-scan fallback.
  - https://github.com/asg017/sqlite-vec
  - https://alexgarcia.xyz/sqlite-vec/

## Product Principle

The user should experience Mem as a memory operating system, not as search
with an AI wrapper.

The app should answer and act across:

- Saved videos and their transcripts.
- Saved videos and what visually happens in them.
- Articles and readable text.
- PDFs, screenshots, notes, images, and voice notes.
- Tags, collections, projects, dates, places, people, and user intent.
- Past agent actions and user interactions.

The LLM should not be the memory store. The app owns the memory store. The LLM
is the reasoning, planning, explanation, and action proposal layer.

## Key Use Cases

Groundbreaking use cases that depend on LLMs plus structured memory:

- "Create a collection containing only videos where someone falls."
- "I am going on vacation to Japan. Show me mems that relate to this and help
  me create an itinerary."
- "Find the video where the founder says distribution matters more than
  product, then jump to the moment."
- "Turn my saved cooking videos into a grocery list and three dinner plans."
- "Make a beginner-to-advanced playlist from everything I saved about AI
  agents."
- "Find examples where an app UI is shown, not just discussed."
- "Organize my saved startup advice into fundraising, product, hiring,
  distribution, and personal operating system."
- "Find mems that contradict each other about sleep, diet, or productivity."
- "Prepare me for tomorrow's meeting using anything I saved about this person,
  company, or topic."
- "Create a travel agenda from my saved TikToks, YouTube videos, Reddit posts,
  articles, maps notes, and personal notes."
- "Show me all mems that felt important when I saved them but I have not acted
  on yet."
- "Turn this cluster of research into a checklist, then create follow-up tasks."

The important distinction:

- Transcript RAG can answer "where did someone say X?"
- Visual memory can answer "where did X happen?"
- Agent tools can turn either result into collections, playlists, plans, tags,
  tasks, and workflows.

## Data We Need From yt-dlp

For every video/link, Mem should store both normalized fields and sanitized raw
metadata.

Normalized identity:

- `extractor`
- `extractorKey`
- `id`
- `webpageUrl`
- `originalUrl`
- `canonicalUrl`
- `originDomain`

Text:

- `title`
- `fulltitle`
- `altTitle`
- `description`
- `chapters`
- `series`, `season`, `episode` when present

Creator:

- `uploader`
- `uploaderId`
- `uploaderUrl`
- `channel`
- `channelId`
- `creator`
- `creators`

Dates and duration:

- `durationSeconds`
- `timestamp`
- `uploadDate`
- `releaseDate`
- `modifiedDate`

Classification:

- `tags`
- `categories`
- `language`
- `availability`
- `liveStatus`
- `ageLimit`

Signals:

- `viewCount`
- `likeCount`
- `commentCount` when available
- `thumbnail`
- `thumbnails`
- `formatsSummary`

Subtitle/caption inventory:

- `manualSubtitleLanguages`
- `automaticCaptionLanguages`
- available subtitle formats by language
- chosen transcript language
- chosen transcript source: `manual`, `automatic`, `audio_transcribed`,
  `none`

Raw:

- sanitized full info JSON
- extractor version
- extraction options version
- extraction timestamp
- auth/cookie state

## Transcript Strategy

Transcript indexing is the first high-value implementation slice.

Preferred order:

1. Manual subtitles in the user's preferred language.
2. Manual subtitles in English.
3. Auto captions in the user's preferred language.
4. Auto captions in English.
5. Audio transcription only when the user owns or has authorized the media.
6. Metadata-only fallback.

Implementation details:

- Use yt-dlp with `skip_download=True`.
- Enable manual subtitle writing.
- Enable automatic subtitle writing.
- Prefer `vtt` or `srv3` where timestamps survive cleanly.
- Request language preferences first, not all languages by default.
- Exclude live chat/danmaku unless the user explicitly wants it.
- Store the caption file as an asset only if it is useful for diagnostics or
  reprocessing; the query path should use parsed DB segments.
- Parse WebVTT/SRT into timestamped segments.
- Merge tiny caption fragments into semantic chunks around 30-90 seconds, with
  overlap.
- Store exact segment rows and chunk rows. Segments are for playback jumps.
  Chunks are for search and agent context.

Proposed content depth states:

- `metadata_only`
- `metadata_plus_description`
- `transcript_available`
- `transcript_indexed`
- `article_text_indexed`
- `ocr_indexed`
- `audio_transcribed`
- `visual_indexed`
- `fully_indexed`

This lets the agent explain confidence. For example: "I found this from the
title and description only; transcript is not available."

## Visual Memory Strategy

The "someone falls" use case proves that transcript RAG is insufficient.

Visual indexing should be a separate pipeline:

1. Generate or collect frames.
2. Detect scenes or sample at intervals.
3. Run cheap local vision signals.
4. Optionally run higher-quality multimodal descriptions.
5. Store visual observations tied to timestamps.

Frame sources:

- Local imported videos: sample frames directly.
- Authorized saved copies: sample frames directly.
- Remote videos without local copy: use thumbnails and platform-provided
  thumbnails only; do not silently download video for visual analysis.

Local first signals:

- Thumbnail/frame perceptual hash for deduping.
- Image embeddings for visual similarity.
- Object labels.
- Pose/gesture or action-specific classifiers where available.
- OCR on frames.
- Short image descriptions via on-device GenAI when supported.

Deep visual indexing:

- User-initiated, explicit, and visible.
- Runs on local/user-authorized media.
- Samples scenes or short clips.
- Uses a high-quality multimodal model to create timestamped observations:
  `person falls`, `app UI visible`, `recipe ingredients on counter`,
  `train station`, `temple`, `whiteboard diagram`, etc.

Visual observation rows should include:

- `sourceId`
- `assetId`
- `startTimeMs`
- `endTimeMs`
- `observationType`: `frame_description`, `object`, `action`, `ocr`,
  `scene`, `place`, `product`, `ui`, `pose`
- `text`
- `confidence`
- `provider`
- `model`
- `createdAt`

The agent should know when a query requires visual evidence. For example:

- "where someone falls" -> visual/action query.
- "where they explain falling revenue" -> transcript/text query.
- "Japan itinerary" -> text, metadata, place/entity, collection, and user-note
  query.

## Search Architecture

Mem needs three search modes that share the same index:

1. Deterministic search: exact filters, dates, domains, types, tags, statuses,
   durations, collections, and boolean text.
2. Semantic search: embeddings over text chunks and visual observations.
3. Agent search: the LLM decomposes intent, calls search tools, reranks, and
   proposes actions.

Search stages:

1. Parse query into filters and free text.
2. Apply hard filters first.
3. Run full-text search against chunk/source FTS.
4. Run vector search against relevant embedding spaces.
5. Fuse candidates with reciprocal-rank-style fusion.
6. Deduplicate by source and nearby timestamps.
7. Rerank the top candidates using metadata, exact entities, recency, user
   interaction, collection/tag boosts, and optional LLM reranking.
8. Return cited, explainable results.

Result shape:

- `sourceId`
- `chunkId`
- `assetId`
- `title`
- `sourceType`
- `originDomain`
- `snippet`
- `matchReason`
- `rankSignals`
- `startTimeMs`
- `endTimeMs`
- `page`
- `offset`
- `score`
- `confidence`

## Non-LLM Search

Non-semantic search must be first-class.

Recommended query language:

```text
japan type:video has:transcript site:youtube.com duration:<15m saved:last90d -tokyo
"distribution matters" channel:"Y Combinator"
type:article OR type:pdf tag:travel collection:japan
has:local_video has:visual action:falling
status:needs_auth site:instagram.com
```

Filters:

- `type:video/article/pdf/note/image/audio`
- `site:youtube.com`
- `domain:reddit.com`
- `channel:...`
- `author:...`
- `tag:...`
- `collection:...`
- `has:transcript`
- `has:visual`
- `has:local_video`
- `has:thumbnail`
- `has:auth`
- `status:done/needs_auth/metadata_only/failed/indexing`
- `duration:<5m`, `duration:5m..30m`
- `saved:today`, `saved:last30d`, `saved:2026-06`
- `date:2025..2026`
- `language:en`

Sorts:

- relevance
- newest saved
- oldest saved
- shortest
- longest
- most complete index
- most interacted
- source quality

This should work without an LLM. The LLM can generate these filters from
natural language, but the query system itself must be deterministic.

## Embedding Architecture

Do not hardcode one embedding model.

Add:

- `embedding_models`
- `chunk_embeddings`
- `visual_embeddings`
- `embedding_jobs`
- `embedding_provider_state`

Every embedding row should store:

- provider
- model
- model version or alias
- dimensions
- quantization
- embedding type: `text`, `query`, `image`, `visual_observation`
- source chunk ID
- content hash
- createdAt

Provider options:

- Local text embeddings through MediaPipe/Google AI Edge/LiteRT where feasible.
- Local visual embeddings through MediaPipe image embedding.
- Cloud embeddings for highest-quality semantic search when the user opts in.
- Future model providers through the same interface.

Vector index strategy:

- Start with exact scan over filtered candidates for reliability.
- Add sqlite-vec or another packaged vector index behind `VectorIndex`.
- Store compact int8 vectors when quality is acceptable.
- Keep float vectors optional for quality-sensitive local testing.
- Design re-embedding as a background job, since providers and models will keep
  changing.

Scale estimates:

- 10k mems with 20 chunks each: 200k chunks.
- 200k chunks with 256-dim int8 embeddings: about 51 MB raw vectors.
- 200k chunks with 768-dim float32 embeddings: about 614 MB raw vectors.
- 100k mems with 20 chunks each: 2M chunks.
- 2M chunks requires quantized vectors, tiering, and likely ANN or heavy
  prefiltering.

The MVP should target 10k-50k mems locally and design for 100k. Beyond that,
use hot/cold indexing, optional cloud sync, and archive tiers.

## Agent Architecture

The agent should be app-tool-first.

The model should not receive the full library. It should receive:

- user request
- available tool schemas
- relevant user settings
- compact search results
- selected snippets/citations
- proposed action preview

Core tools:

- `searchMemory(query, filters, modes)`
- `getSource(sourceId)`
- `getChunks(sourceId, range)`
- `getTranscript(sourceId, timestampRange)`
- `getVisualObservations(sourceId, timestampRange)`
- `createCollectionDraft(title, sourceIds, rationale)`
- `createPlaylistDraft(title, sourceIds, rationale)`
- `tagSourcesDraft(sourceIds, tags, rationale)`
- `openSource(sourceId, timestamp/page/offset)`
- `explainResult(query, sourceId, chunkIds)`
- `buildItinerary(memoryIds, constraints)`

Mutation flow:

1. LLM proposes a draft action.
2. App validates every source ID and permission.
3. UI previews the action.
4. User approves.
5. Repository writes the mutation.
6. `agent_actions` records the exact change.
7. Undo is available.

LLM provider path:

- Quality mode: strong cloud reasoning model through a Mem AI Gateway or a
  secured provider integration. This is needed for planning, nuanced
  organization, itinerary generation, and multi-step action proposals.
- Private/local mode: on-device model when available, useful for summaries,
  short classifications, and lower-stakes local commands.
- No-AI mode: deterministic search and filters remain fully useful.

The app should mediate tool calls. If a cloud model asks to search memory, the
phone executes local retrieval and returns only selected snippets/results to the
model.

## Privacy Model

Default:

- Local DB owns memory.
- Local retrieval first.
- No full-library upload.
- Cloud AI opt-in.
- Clear setting for what can leave the device.
- Per-query disclosure for sensitive actions.
- Delete/export controls.

Cloud calls should send:

- the current request
- top retrieved snippets/chunks
- source titles/domains/dates
- IDs needed for citations/action previews

Cloud calls should not send:

- the entire database
- all embeddings
- all raw metadata
- cookies/auth files
- downloaded media unless user explicitly asks for a multimodal/deep visual
  analysis flow

## Proposed Schema Additions

Near-term:

- `caption_tracks`
- `transcript_segments`
- `content_chunks` or expanded `document_chunks`
- `chunk_search`
- `source_index_state`
- `search_queries` for eval/debug

Medium-term:

- `embedding_models`
- `chunk_embeddings`
- `visual_observations`
- `visual_embeddings`
- `agent_actions`
- `action_previews`
- `entity_mentions`
- `places`
- `tasks`

Suggested `content_chunks` fields:

- `id`
- `sourceId`
- `assetId`
- `chunkType`
- `text`
- `language`
- `startOffset`
- `endOffset`
- `startTimeMs`
- `endTimeMs`
- `page`
- `sectionTitle`
- `provider`
- `contentHash`
- `createdAt`

## Next Implementation Path

### Slice 1: Transcript-First Retrieval

Goal: YouTube/public video memories become actually searchable by what is said.

Build:

- Update yt-dlp extraction to request manual and auto subtitles without media
  download.
- Parse VTT/SRT into timestamped segments.
- Store `caption_tracks` and `transcript_segments`.
- Generate chunk rows from transcript segments.
- Expand source detail to show transcript availability and sample snippets.
- Add `contentDepth`.
- Index transcript chunks into FTS.

Exit criteria:

- Save a YouTube video with captions.
- Search words that appear only in the transcript.
- Result opens source detail at the transcript timestamp.
- If captions are unavailable, UI clearly says metadata-only.

### Slice 2: Serious Non-LLM Search

Goal: Mem becomes useful before embeddings.

Build:

- Query parser for filters and free text.
- Chunk FTS search.
- Result screen from the command field.
- Search result snippets and match reasons.
- Saved search/debug logs for ranking improvements.

Exit criteria:

- Query examples like `site:youtube.com has:transcript japan duration:<20m`
  work.
- Exact phrase and negative filters work.
- Results are explainable.

### Slice 3: Embedding Provider Interface

Goal: Semantic retrieval without coupling to one AI provider.

Build:

- `EmbeddingProvider` interface.
- `EmbeddingModelRegistry`.
- DB tables for models and embeddings.
- Exact vector scan implementation.
- Optional sqlite-vec experiment behind `VectorIndex`.
- Background embedding queue.

Exit criteria:

- Natural query finds semantically related transcript/article chunks without
  exact keyword overlap.
- Re-embedding can be queued when provider/model changes.

### Slice 4: Agent Search Tool

Goal: The command field becomes a cited memory assistant.

Build:

- `searchMemory` tool.
- `getSource` and `getChunks` tools.
- Agent answer UI with citations.
- Cloud/provider abstraction.
- User-visible privacy setting for cloud AI.

Exit criteria:

- "What have I saved about Japan?" returns cited mems.
- "Help me plan Japan" retrieves relevant mems, groups them, and produces a
  source-backed draft.

### Slice 5: Collection/Playlist Action Drafts

Goal: Agentic control starts with safe, reversible organization.

Build:

- `createCollectionDraft`.
- Preview UI.
- Approval/undo.
- Agent action history.

Exit criteria:

- "Create a collection from my Japan travel mems" proposes a collection with
  source list and rationale before writing.

### Slice 6: Visual Memory

Goal: Support use cases transcripts cannot solve.

Build:

- Frame sampling for local/authorized videos.
- Visual observation table.
- Local image embeddings/labels/OCR.
- Optional deep visual analysis for selected videos.
- Visual query intent detection.

Exit criteria:

- Queries like "videos where someone falls" work for videos that have been
  visually indexed.
- Results cite timestamps and observation reasons.

## Decision

The next product implementation should be:

1. Transcript-first ingest and chunk indexing.
2. Deterministic search parser and chunk FTS.
3. Embedding provider interface.
4. Agent search tool with cited answers.
5. Collection/action preview.
6. Visual indexing.

This path is optimal because it makes every layer useful independently:

- The app gets better immediately with transcripts and FTS.
- Semantic search can improve retrieval without replacing deterministic search.
- Cloud LLMs can reason over local results without owning the library.
- On-device models can plug in where available.
- Visual indexing unlocks use cases text cannot handle.
- Provider churn becomes an implementation detail, not a product rewrite.

