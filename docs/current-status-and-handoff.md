# Current Status And Agent Handoff

Last updated: 2026-06-30.

This document is the first file a new agent should read before continuing Mem.
It summarizes the current implementation state, verified build/test commands,
known environment issues, and the next roadmap slices.

## Current APK

- Package: `com.moutrancorp.memspike`
- Version code: `61`
- Version name: `0.3.57-search-orphan-fts-fix`
- Current test APK for the user: `dist/mem-spike-debug.apk`
- Final APK SHA-256 from the latest verified build:
  `7E3E2909D1A7293325DEA6ED7575EAAFF5F6E5E8CB79F51BCA918C2884147379`
- Debug signing certificate SHA-256:
  `2ad1592b905cc861c1e83942fb0d8c99068675abd1dd4892acd3ed2fda98b890`

The latest APK was installed successfully over the user's existing app with:

```powershell
C:\src\androidsdk\platform-tools\adb.exe install -r dist\mem-spike-debug.apk
```

## Verified User-Facing State

The app currently supports:

- Native Kotlin/Compose shell with customizable Mem appearance tokens.
- Floating bottom dock with Mem, Library, Capture, Collections, and settings
  paths.
- Share sheet and paste ingestion for URLs/text.
- Durable Room-backed sources, jobs, chunks, assets, tags, collections,
  memberships, auth sessions, caption tracks, transcript segments, embeddings,
  search logs, and agent actions.
- On-device packaged Python through Chaquopy.
- Pinned packaged `yt-dlp==2026.6.9`.
- Public video metadata extraction.
- Web/article metadata and bounded readable text fallback.
- Manual note capture.
- Text, PDF, image, and local video imports.
- App-private local media copies for imported videos and authorized saves.
- Local video thumbnails, inline feed playback, fullscreen playback, one-at-a
  time feed autoplay, hidden controls until tap, configurable seek seconds, and
  deletion from primary surfaces.
- Instagram auth-gated sources as first-class `needs_auth` sources.
- Instagram desktop-login WebView auth path plus explicit `cookies.txt`
  fallback and auth clearing.
- Transcript/caption parsing and chunk indexing for supported video sources.
- Chunk-level FTS and cited search result cards.
- Deterministic query filters for type, site/domain, status, capability,
  duration, date/saved ranges, tag, collection, author/channel, language, and
  sorting.
- Local deterministic semantic fallback through `EmbeddingProvider` and
  `VectorIndex` interfaces.
- RAG index health/status surface in settings.
- Local deterministic agent runtime scaffold with app-owned tools:
  `search_memory`, `get_source_context`, `get_transcript`,
  `get_visual_observations`, `explain_result`, `summarize_source`,
  `draft_collection`, and `tag_sources`.
- Approval-gated collection/tag action drafts with apply and undo.
- Baseline visual observation chunks for local/authorized videos. These are
  placeholders and must not claim real action/object detection yet.

## Latest Crash Fix

The latest user-blocking crash was:

- Repro: add a YouTube video, download/save the authorized copy, then search
  for a term matching that downloaded video. The user's exact device repro was
  a downloaded video with `Gouie` in the title; typing `Go` crashed the app.
- Root cause: a `chunk_search` FTS row could match while the corresponding
  `document_chunks` row was missing or not joinable. `ChunkSearchDao` used a
  `LEFT JOIN`, producing null `chunkType`, and Room tried to construct a
  non-null `ChunkSearchResult.chunkType`.
- Fix: both chunk search queries in `ChunkSearchDao` now use `INNER JOIN
  document_chunks ON document_chunks.id = chunk_search.chunkId`, so orphan FTS
  rows cannot materialize invalid result objects.
- Main file: `app/src/main/java/com/moutrancorp/memspike/data/MemDatabase.kt`

The exact crash case was verified on the connected phone:

- Search field text: `Go`
- Result count: `1 result for "Go"`
- Visible result:
  `Gouie: The Lost Mireling | Official Character Trailer - Rivals of Aether II`
- Process stayed alive.
- `files/last_crash.txt` was absent.
- Logcat had no `FATAL EXCEPTION`, `AndroidRuntime`,
  `ChunkSearchResult`, or `NullPointerException` entries for this case.

## Build Notes

Room now uses KSP instead of KAPT:

- Root plugin: `com.google.devtools.ksp:symbol-processing-gradle-plugin:1.9.24-1.0.20`
- App plugin: `com.google.devtools.ksp`
- Room compiler dependency: `ksp "androidx.room:room-compiler:2.6.1"`

This was changed because the local Windows/JDK environment repeatedly failed on
generated KAPT `R.jar` access. Do not revert to KAPT without first proving the
Android E2E harness still works.

`buildFeatures.buildConfig` is disabled. Debug-only behavior should use
`Context.isDebuggable()` instead of `BuildConfig.DEBUG`.

The local main repo build can hit locked files under `app/build/python`. A clean
copy build succeeded with:

```powershell
$env:JAVA_HOME='C:\Program Files\ojdkbuild\java-17-openjdk-17.0.3.0.6-1'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:CHAQUOPY_BUILD_PYTHON='C:\Users\jdmou\Desktop\Startups 2026\mem\.tools\python311-nuget\pkg\tools\python.exe'
.\gradlew.bat :app:assembleDebug "-Pkotlin.compiler.execution.strategy=in-process" --no-daemon
```

When producing `dist/mem-spike-debug.apk` from a clean copy, re-sign it with the
user's debug keystore so updates install over the existing app:

```powershell
C:\src\androidsdk\build-tools\35.0.0\apksigner.bat sign --ks C:\Users\jdmou\.android\debug.keystore --ks-pass pass:android --key-pass pass:android --out dist\mem-spike-debug-resigned.apk dist\mem-spike-debug.apk
Move-Item -Force dist\mem-spike-debug-resigned.apk dist\mem-spike-debug.apk
```

## Verification Commands

Offline RAG/search eval:

```powershell
C:\Users\jdmou\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe tools\search_rag_eval.py
```

Android E2E targeted harness:

```powershell
.\tools\android-e2e.ps1
```

Manual exact crash smoke:

```powershell
$adb = "C:\src\androidsdk\platform-tools\adb.exe"
& $adb shell run-as com.moutrancorp.memspike rm files/last_crash.txt
& $adb shell am force-stop com.moutrancorp.memspike
& $adb logcat -c
& $adb shell am start -n com.moutrancorp.memspike/.MainActivity
```

Then on the device:

1. Open Library.
2. Tap search.
3. Type `Go`.
4. Confirm the Gouie result renders and the app does not crash.

Afterward:

```powershell
& $adb shell pidof com.moutrancorp.memspike
& $adb exec-out run-as com.moutrancorp.memspike cat files/last_crash.txt
& $adb logcat -d | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|ChunkSearchResult|NullPointerException"
```

Expected:

- `pidof` returns a process ID.
- `last_crash.txt` is absent.
- Logcat search returns no crash lines.

## Workspace Hygiene

The repo can contain generated or temporary dirty paths. Do not stage these
unless the user explicitly asks:

- `.build_tmp/`
- `_build_worktree/tmp/`
- `_build_worktree` generated outputs
- `artifacts/android-e2e/`

The important source/doc changes from this phase are in:

- `build.gradle`
- `app/build.gradle`
- `app/src/main/java/com/moutrancorp/memspike/MainActivity.kt`
- `app/src/main/java/com/moutrancorp/memspike/data/MemDatabase.kt`
- `app/src/main/java/com/moutrancorp/memspike/data/MemoryRepository.kt`
- `app/src/androidTest/java/com/moutrancorp/memspike/DownloadedVideoSearchRegressionTest.kt`
- `tools/android-e2e.ps1`
- `docs/android-e2e-testing.md`
- `docs/current-status-and-handoff.md`
- `dist/mem-spike-debug.apk` when handing a sideload build to the user

The local sandbox has previously blocked `.git` writes, so do not claim a
commit or push succeeded unless the command actually completes.

## Roadmap Priorities

The next work should be quality and reliability, not adding more surface area
blindly.

1. Stabilize Android E2E coverage.
   - Make the exact downloaded-video search crash case runnable without manual
     tapping.
   - Add one test each for imported local video search, transcript-only search,
     and auth-gated source detail.
   - Keep pulling `last_crash.txt` and `logcat.txt` into artifacts.
2. Repair and harden FTS/index consistency.
   - Add a DB maintenance path that deletes orphan `chunk_search` rows.
   - Add migration or startup repair for stale FTS/chunk references.
   - Add repository-level tests around source deletion, local media assets, and
     chunk indexing.
3. Finish transcript-first ingestion.
   - Ensure yt-dlp caption selection chooses manual captions first, then auto
     captions, with clear language fallback.
   - Persist caption tracks, transcript segments, and transcript chunks.
   - Make source detail show transcript availability and timestamp jumps.
4. Make search results feel like actual mems, not only technical match cards.
   - Search should keep the normal library/feed affordances while showing
     citations and match reasons.
   - The local agent panel must not block page scrolling.
5. Move embeddings/vector search toward production.
   - Replace exact-scan fallback with a packaged approximate vector index
     behind the existing `VectorIndex` boundary.
   - Keep provider/model/index diagnostics visible.
   - Add re-embedding behavior for provider/model changes.
6. Upgrade visual memory from placeholders to real analysis.
   - Add OCR for imported images and sampled video frames.
   - Add explicit user-visible deep analysis for local/authorized videos.
   - Keep visual claims grounded in observation rows with provider/model names.
7. Harden media/download operations.
   - Package real per-ABI `ffmpeg` and `ffprobe`.
   - Move long transfers and media processing into user-visible Android job or
     foreground-service flows.
   - Add storage management and third-party notices.

## Product Direction To Preserve

Mem is a personal memory OS, not a downloader. yt-dlp is an extraction engine
behind a source adapter. The product value is memory capture, transcript/text
indexing, visual memory, cited retrieval, and agentic organization.

Default retrieval should stay local-first. Cloud LLM or embedding providers
should be optional, user-visible, and mediated by app-owned tools. The app
should never send the full library, cookies, auth files, or raw downloaded media
to a cloud model without an explicit user-controlled flow.
