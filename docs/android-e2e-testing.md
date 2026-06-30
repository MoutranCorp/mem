# Android E2E Testing

This project now has a first Android regression harness for crash repros that need a real app runtime.

## Downloaded Video Search Crash

The debug app supports this seed intent:

```powershell
adb shell am start -n com.moutrancorp.memspike/.MainActivity --es mem_seed downloaded_youtube_search_crash
```

It creates a deterministic YouTube-like memory with:

- transcript text matching `hermetic searchcrash`
- a local playback asset
- a local thumbnail asset
- Library opened with the matching search query applied

The instrumented test is:

```text
app/src/androidTest/java/com/moutrancorp/memspike/DownloadedVideoSearchRegressionTest.kt
```

Run the full targeted harness:

```powershell
.\tools\android-e2e.ps1
```

The script builds the debug APK and test APK, runs the targeted test on a connected Android device/emulator, saves `logcat.txt`, and pulls `files/last_crash.txt` when the debug crash recorder captured a crash.

The latest production crash in this family was verified with the user's real
data:

- A saved/downloaded YouTube memory had `Gouie` in the title.
- Searching `Go` crashed the app only when the matching memory had a downloaded
  local playback asset.
- `last_crash.txt` showed Room constructing `ChunkSearchResult` with null
  `chunkType`.
- The root cause was an orphan or stale `chunk_search` FTS row combined with a
  `LEFT JOIN document_chunks`.
- The current fix uses `INNER JOIN document_chunks` in both
  `ChunkSearchDao.observeSearchResults` and `ChunkSearchDao.searchResults`.

After the fix, the exact device smoke produced `1 result for "Go"` with:

```text
Gouie: The Lost Mireling | Official Character Trailer - Rivals of Aether II
```

The app process stayed alive, `files/last_crash.txt` was absent, and logcat had
no `FATAL EXCEPTION`, `AndroidRuntime`, `ChunkSearchResult`, or
`NullPointerException` lines for this test.

If Gradle fails before installing because Windows has locked a generated KAPT file, install the debug APK and run the seed smoke test directly:

```powershell
adb install -r dist\mem-spike-debug.apk
adb logcat -c
adb shell am start -n com.moutrancorp.memspike/.MainActivity --es mem_seed downloaded_youtube_search_crash
adb shell pidof com.moutrancorp.memspike
adb exec-out run-as com.moutrancorp.memspike cat files/last_crash.txt
```

The expected result is that the app process is alive, `last_crash.txt` is absent, and Library shows `Downloaded YouTube search crash fixture` for `hermetic searchcrash`.

Artifacts are written under:

```text
artifacts/android-e2e/<timestamp>/
```

## Manual Real-Data Smoke

Use this when validating the user's installed database after a search crash fix:

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
3. Type the shortest known prefix that matched the crashing downloaded video.
   For the fixed 2026-06-30 case, this was `Go`.
4. Confirm the result renders and the app does not close.

Afterward:

```powershell
& $adb shell pidof com.moutrancorp.memspike
& $adb exec-out run-as com.moutrancorp.memspike cat files/last_crash.txt
& $adb logcat -d | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|ChunkSearchResult|NullPointerException"
```

Expected:

- `pidof` returns a process ID.
- `last_crash.txt` is missing.
- The logcat filter returns no crash lines.

## Next Harness Work

- Make the exact downloaded/local-video search crash case fully automated
  without manual tapping.
- Add coverage for imported local video search, transcript-only search, and
  auth-gated source detail.
- Add a cleanup/repair assertion for orphan `chunk_search` rows once DB
  maintenance exists.
