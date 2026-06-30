# Mem Design Direction

Mem should feel like a private memory operating system: fast, calm, dense, and
personal. The product should not look like a downloader, a notes clone, or a
social feed. The main screen should make it obvious that the app can remember,
retrieve, organize, and act across the user's saved life context.

## Current Visual Inputs

Initial concept mockups:

- `docs/mockups/main-page/initial-memory-command-center.png`
- `docs/mockups/main-page/initial-calm-journal.png`
- `docs/mockups/main-page/initial-visual-library.png`
- `docs/mockups/main-page/initial-agent-first-memory-console.png`

Floating navigation explorations:

- `docs/mockups/main-page/agent-first-floating-nav.png`
- `docs/mockups/main-page/calm-journal-floating-nav.png`
- `docs/mockups/main-page/command-center-floating-nav.png`
- `docs/mockups/main-page/library-feed-floating-nav.png`

## Chosen Direction

Use a hybrid of:

- Agent-first memory console for the home screen.
- Command-center density for source state, processing, and quick actions.
- Calm-journal warmth for Today, agenda, reflection, and personal timeline.
- Visual-library richness for browsing, but only as a secondary library mode.

The home screen should answer four questions immediately:

1. What can I ask Mem right now?
2. What is Mem currently processing?
3. What has Mem learned or organized recently?
4. What happened today?

## Navigation

Use a floating lower dock inspired by modern translucent iOS navigation, but
implemented in a way that still feels native on Android.

Primary dock items:

- Mem
- Inbox
- Capture
- Library

Capture should be visually central. Ask should not be a tab; the command field
is the main agent entry point, with contextual "ask about this" actions
throughout the app. Profile/settings should live in the top right or a sheet,
not consume a primary tab.

Dock rules:

- Floating, translucent, softly blurred when platform support is good.
- Large enough for thumb use, never tall enough to dominate the screen.
- Always leaves content readable behind it with a bottom safe-area gradient.
- Central Capture opens a modal sheet with URL paste, note, upload, scan,
  voice note, and camera capture.
- The agent can expand into a full-screen conversation from the command field,
  search results, or contextual source actions.

## Home Screen Structure

Recommended order:

1. App header with Mem, search/filter controls, and profile.
2. Command field: "Ask or find anything" / "Ask your memory".
3. Quick intent chips: summarize, find, organize, make playlist.
4. Processing and needs-review status.
5. Memory context: people, projects, topics, collections, source health.
6. Recent sources.
7. Today timeline: notes, saved items, agenda, reflections.

The command field should search and ask from the same place. If the user's input
is short and entity-like, show retrieval results. If it is a question or command,
route it to the agent.

## Library

Library is the place to browse everything. It should support multiple modes:

- Feed: chronological, Twitter/X-like scanning rhythm, private and calm.
- Grid: visual browsing for videos, screenshots, articles, PDFs, notes.
- Timeline: diary/history view across days and months.
- Map: location-based memories where location exists and the user opted in.

The feed is valuable, but it should not become the main screen. It is for
review, rediscovery, and bulk organization. Library should remember the user's
last selected mode instead of always defaulting back to Feed.

Feed item anatomy:

- Thumbnail or source icon.
- Source type and origin.
- Title.
- Extracted summary.
- AI tags.
- Saved time.
- Transcript/article/note snippet.
- Quick actions: ask, playlist, tag, archive.

## Source Detail

Every saved item needs a rich detail page.

Video detail:

- Title, channel/author/source, saved date.
- Thumbnail/player placeholder.
- Summary.
- Transcript/captions with timestamp chunks.
- Related memories.
- Collections/playlists.
- Actions: ask about this, tag, save authorized copy, export notes.

Article/document detail:

- Reader view.
- Summary.
- Key passages.
- Tags and related memories.
- Actions: ask, cite, add to collection, schedule review.

## Visual System

Tone:

- Premium productivity app.
- Quiet and tactile.
- Warm enough to feel personal.
- Dense enough to feel serious.

Palette:

- Base: warm white, ink, soft gray.
- Accents: muted teal/jade, amber, steel blue, olive, coral.
- Avoid a one-note purple/blue gradient identity.
- Avoid beige-only journaling aesthetics.
- Avoid dark slate dominance for the default theme.

Typography:

- Clear, compact, native-feeling.
- Large type only for Today/date moments.
- Dense panels should use smaller headings and stable row heights.

Components:

- Floating dock.
- Command field.
- Segmented controls.
- Source rows.
- Feed rows.
- Timeline rails.
- Processing rows.
- Context chips.
- Quick action sheets.
- Undoable mutation toasts.

## Customization

Mem's styling should be user-customizable. This means the visual system must be
tokenized from the start, not hardcoded into individual screens.

Customizable dimensions:

- Theme profile.
- Accent color.
- Light, dark, or system mode.
- Density.
- Corner style.
- Dock style.
- Feed/card image emphasis.
- Motion level.

The customization model should be curated rather than arbitrary. Users should be
able to make Mem feel personal while the app preserves readability,
accessibility, and navigation consistency.

Implementation details live in `docs/design-system-customization.md`.

## Product Personality

Mem should speak like a trusted private assistant, not a chatbot mascot.

Good UI copy:

- "Ask your memory"
- "Processing 7 items"
- "Needs review"
- "Saved from YouTube"
- "AI tagged"
- "Add to playlist"
- "Find related"

Avoid:

- "Download any video"
- "Bypass restrictions"
- "Unlock private content"
- "AI magic"
- Long instructional text inside the app

## Resolved Design Decisions

- The default home label is Mem, not Today.
- Ask is not a dock tab.
- Library remembers the user's last selected mode.
- Styling customization is a first-class product and architecture requirement.

## Design Open Questions

- Should Inbox include both processing and manual review, or should processing
  be a status panel on Mem?
- How much warmth should the app have before it stops feeling like a power tool?
