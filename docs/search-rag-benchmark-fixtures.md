# Search And RAG Benchmark Fixtures

These fixtures define the minimum scenarios Mem's Search + RAG Core should
continue to satisfy as retrieval evolves.

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
- `"exact phrase" -ignored`

Expected:

- Filters contribute to retrieval tokens and result explanations.
- Nonmatching source types/domains should fall down the ranking.
- Quoted phrases remain searchable as meaningful terms.

### Visual Memory Placeholder

Sources:

- Local or authorized videos with future visual observations.

Queries:

- `videos where someone falls`
- `has:visual action:falling`
- `show me videos where the app UI is visible`

Expected:

- Until visual indexing is implemented, text/transcript results may appear but
  the app must not claim strong visual evidence.
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

