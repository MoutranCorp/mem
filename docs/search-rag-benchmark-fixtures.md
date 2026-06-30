# Search And RAG Benchmark Fixtures

These fixtures define the minimum scenarios Mem's Search + RAG Core should
continue to satisfy as retrieval evolves.

## Automated Eval

Run the offline regression harness from the repo root:

```powershell
python tools\search_rag_eval.py
```

The harness uses `tools/search_rag_eval_fixture.json` and mirrors the current
retrieval stack closely enough to catch regressions in query parsing, hard
filters, timestamp requirements, visual placeholder honesty, rank signals, and
agentic collection seed coverage. It also checks first-pass agent tool
contracts for `get_transcript`, `get_visual_observations`, and
`explain_result`. It is intentionally synthetic, so it does not need network
access, cookies, an Android device, or a live app database.

For machine-readable output:

```powershell
python tools\search_rag_eval.py --json
```

## Fixture Set

### Transcript-Only Video Match

Source:

- A public YouTube video with captions.
- The target phrase appears in the transcript but not the title.

Queries:

- `"distribution matters"`
- `distribution product founder`
- `type:video has:transcript distribution`

Expected:

- A transcript chunk appears in cited results.
- The result includes a timestamp.
- Opening the citation shows the indexed transcript context in source detail.
- If the source has local playback, the detail player seeks to the cited
  timestamp.

### Semantic Travel Planning

Sources:

- Saved videos/articles/notes about Japan, Tokyo, Kyoto, food, hotels, trains,
  neighborhoods, and day trips.

Queries:

- `I am going on vacation to Japan`
- `help me build a Japan itinerary`
- `type:video japan food transit`

Expected:

- Hybrid or semantic citations include travel-related memories even when the
  exact word "itinerary" is absent.
- The local agent search panel reports cited source count and context types.
- Drafting a collection creates an action preview before any DB mutation.

### Deterministic Filters

Sources:

- Mixed video, article, PDF, note, image memories from multiple domains.

Queries:

- `site:youtube.com has:transcript japan`
- `type:pdf ai agents`
- `status:needs_auth site:instagram.com`
- `type:video duration:<5m saved:last30d`
- `date:2026-06 has:timestamp`
- `tag:travel japan`
- `collection:japan trip planning`
- `author:travel japan transit`
- `language:en has:transcript`
- `has:local_video`
- `has:thumbnail type:video`
- `"exact phrase" -ignored`

Expected:

- Filters contribute to retrieval tokens and result explanations.
- Nonmatching source types/domains/status/capabilities should be removed from
  the cited result set when a hard filter is present.
- Tag and collection filters should be enforced from membership tables, not
  treated as ordinary text hints.
- Author/channel and language filters should remove nonmatching authors and
  chunks without the requested language.
- Asset-backed capability filters should remove sources that do not have the
  required durable asset role.
- Quoted phrases remain searchable as meaningful terms.
- Cited result cards expose rank signals for debugging keyword, semantic,
  recency, filter, provider/model, vector index, and hybrid behavior.

### Visual Memory Placeholder

Sources:

- Local or authorized videos with future visual observations.

Queries:

- `videos where someone falls`
- `has:visual action:falling`
- `show me videos where the app UI is visible`

Expected:

- Baseline local frame-sample observations should appear for playable
  local/authorized videos under `has:visual`.
- Text/transcript results may still appear, but baseline visual observations
  must not claim detected actions or objects.
- Once visual observations exist, cited results should say `visual` or
  `semantic visual` and include timestamps.

### Agentic Collection Action

Sources:

- Any search with at least three cited sources.

Query:

- `Japan trip planning`

Expected:

- `Draft collection from citations` creates an `agent_actions` draft.
- Applying the action writes collection memberships.
- Undo removes the memberships added by that action.
- The action remains auditable in `agent_actions`.
- `tag_sources` creates the same kind of approval-gated draft for source tags.

### Agent Context Tools

Sources:

- A captioned video with transcript chunks.
- A playable/local video with visual placeholder chunks and observation rows.
- A search result with rank signals.

Expected:

- `get_transcript` returns caption tracks, timestamped transcript segments, and
  a coverage note that tells a model when a source is metadata-only.
- `get_visual_observations` returns observation rows plus visual chunks, while
  preserving the current caution that placeholders are not detected events.
- `explain_result` reruns retrieval for a query/source pair and returns the
  citation, match reason, retrieval mode, and rank signals an agent can cite or
  use for debugging.
- Tool payloads and citations include content-depth signals so model prompts can
  distinguish transcript-ready, visual, metadata-only, auth-required, and
  unindexed memories.
- The deterministic agent runtime returns cited answer payloads with used-tool
  metadata, so the UI and future model runtime share the same contract.

## Manual Smoke Test

1. Install the latest `dist/mem-spike-debug.apk`.
2. Save a public YouTube URL with captions.
3. Search for a phrase that appears only in the transcript.
4. Confirm a cited result appears and opens source detail.
5. Search a broader semantic query, such as `vacation to Japan`.
6. Confirm local agent search summarizes cited chunks.
7. Draft a collection from citations.
8. Confirm the preview appears before applying.
9. Apply, then undo.
