# Mem Spike

This is a Play-targeted Android spike for running `yt-dlp` directly on the
phone without the Debian/proot model used by `mobile-agent`.

## What This Proves

- `targetSdk 35` app shell.
- Share-sheet and manual URL ingestion.
- Copy/share output for phone-side test logs.
- Embedded Python through Chaquopy.
- Pinned, packaged `yt-dlp==2026.6.9`.
- On-device metadata extraction with `download=False`.
- A compact JSON result shaped for a future RAG ingestion pipeline.

The spike deliberately does not advertise or perform video downloads. The first
product path should ingest metadata, captions/transcripts where permitted, and
user-owned files into a memory index.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The app builds against the Android SDK at `C:\src\androidsdk`.

## Run

Install the debug APK on a connected device, then paste a URL or share text to
`Mem Spike`.

```powershell
C:\src\androidsdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Current Extractor Limitation

Some sources, especially Instagram Reels, often require a logged-in browser
session. In that case yt-dlp will return an auth/cookies error even if YouTube
works. The Play-safe version should not scrape browser cookies silently; the
next auth spike should evaluate explicit user-imported cookies or a source-level
login flow.

## Next Spikes

1. Package real per-ABI `ffmpeg` and `ffprobe` binaries as native executable
   libs and pass their paths to `yt-dlp` through `ffmpeg_location`.
2. Add a persistent Room job queue with cancel/retry/resume.
3. Move long downloads to user-initiated data transfer jobs and media processing
   work to a foreground service with the `mediaProcessing` type.
4. Add transcript/caption fetching behind an explicit rights confirmation.
5. Add an embedding/index interface so every extracted record becomes a RAG
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
