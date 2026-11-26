# Embedded Playback Slider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `PlaybackActivity` show one playback video above a draggable 24-hour slider, where releasing the slider moves that same video to the chosen time via one cached clip and one VLC decoder.

**Architecture:** `PlaybackTimelineView` stays the 24-hour slider. A new pure `PlaybackCacheSession` decides reuse-vs-download and seek offset. A new `EmbeddedPlaybackController` owns the single `MediaPlayer` attached to the embedded `VLCVideoLayout`; `PlaybackActivity` wires slider picks to download/cache/seek and never launches `RecordingPlayerActivity` for playback.

**Tech Stack:** Kotlin, Android Views, LibVLC 3.7.7, coroutines, JUnit4, existing Material/AppCompat dependencies only.

**Spec:** `docs/superpowers/specs/2026-11-26-embedded-playback-slider-design.md`

## Global Constraints

- Satu `MediaPlayer` VLC aktif lewat `SharedVlc`.
- Tanpa decoder kedua, tanpa grid, tanpa PiP.
- RTSP tidak di-seek. Clip lokal boleh di-seek.
- Cloud tetap metadata-only; binary tetap HTTP langsung ke NVR.
- Tanpa dependency baru, native SDK, TFLite/ML, atau polling jaringan.
- Tidak ada activity baru; semua activity tetap `android:exported="false"`.
- Jangan bump version, release, tag, atau push tanpa perintah eksplisit.
- Versi saat ini: `versionCode = 13`, `versionName = "1.1.11"`.
- Tanda tangan rilis harus tetap `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.
- Build check: `./gradlew.bat assembleDebug assembleRelease testDebugUnitTest --console=plain`.

## Review Focus

- Tepat di `clip.endMillis` → harus mulai dari `0`, bukan seek ke durasi penuh. Task 1: `plan_atClipEndStartsFromZero` + existing `PlaybackTimelineTest.safePicked_atEndFallsBackToStart`.
- Kanal beda dengan `trackId`/`startMillis` sama → harus download ulang, bukan reuse file lama. Task 1: `plan_downloadsWhenCameraChanges`.
- Scrub kedua pada klip yang sama → harus reuse cache, tidak download ulang. Task 1: `plan_reusesSameClip`.
- Ganti tanggal/filter/kanal saat cache aktif → harus reset sebelum search baru. Task 1: `reset_forcesDownload`; Task 4: calls `resetEmbeddedPlayback()`.
- Ganti klip → file lama harus dihapus setelah file baru dipakai. Task 1: `adopt_returnsPreviousFileForCleanup`.

---

### Task 1: Cache session policy

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackCacheSession.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackCacheSessionTest.kt`

**Interfaces:**
- Consumes: `RecordingClip`, `PlaybackTimeline.safePicked`, `PlaybackTimeline.seekOffset`.
- Produces:
  - `data class PlaybackClipKey(val cameraId: Int, val trackId: Int, val startMillis: Long)`
  - `enum class PlaybackCacheAction { REUSE, DOWNLOAD }`
  - `data class PlaybackCachePlan(val action: PlaybackCacheAction, val offsetMillis: Long)`
  - `class PlaybackCacheSession` with `plan(key: PlaybackClipKey, clip: RecordingClip, requestedMillis: Long): PlaybackCachePlan`, `adopt(key: PlaybackClipKey, file: File): File?`, `reset()`, `loadedFile(): File?`, `loadedKey(): PlaybackClipKey?`.

- [ ] **Step 1: Write failing tests**

```kotlin
package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PlaybackCacheSessionTest {
    private val clip = RecordingClip(101, 1_000L, 61_000L)
    private val key = PlaybackClipKey(7, clip.trackId, clip.startMillis)

    @Test fun plan_downloadsFirstClipAndComputesOffset() {
        val session = PlaybackCacheSession()
        assertEquals(PlaybackCacheAction.DOWNLOAD, session.plan(key, clip, 31_000L).action)
        assertEquals(30_000L, session.plan(key, clip, 31_000L).offsetMillis)
    }

    @Test fun plan_reusesSameClip() {
        val session = PlaybackCacheSession()
        val file = File("clip.mp4")
        session.adopt(key, file)

        val plan = session.plan(key, clip, 41_000L)

        assertEquals(PlaybackCacheAction.REUSE, plan.action)
        assertEquals(40_000L, plan.offsetMillis)
        assertEquals(file, session.loadedFile())
    }

    @Test fun plan_downloadsWhenCameraChanges() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("old.mp4"))

        val plan = session.plan(key.copy(cameraId = 8), clip, 10_000L)

        assertEquals(PlaybackCacheAction.DOWNLOAD, plan.action)
        assertEquals(9_000L, plan.offsetMillis)
    }

    @Test fun plan_atClipEndStartsFromZero() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("clip.mp4"))

        val plan = session.plan(key, clip, clip.endMillis)

        assertEquals(PlaybackCacheAction.REUSE, plan.action)
        assertEquals(0L, plan.offsetMillis)
    }

    @Test fun reset_forcesDownload() {
        val session = PlaybackCacheSession()
        session.adopt(key, File("clip.mp4"))
        session.reset()

        assertNull(session.loadedFile())
        assertNull(session.loadedKey())
        assertEquals(PlaybackCacheAction.DOWNLOAD, session.plan(key, clip, 1_000L).action)
    }

    @Test fun adopt_returnsPreviousFileForCleanup() {
        val session = PlaybackCacheSession()
        val old = File("old.mp4")
        val next = File("next.mp4")
        session.adopt(key, old)

        assertEquals(old, session.adopt(key, next))
        assertEquals(next, session.loadedFile())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackCacheSessionTest" --console=plain`
Expected: FAIL because `PlaybackCacheSession` and its types do not exist.

- [ ] **Step 3: Implement `PlaybackCacheSession`**

Implement `plan()` with `picked = PlaybackTimeline.safePicked(clip, requestedMillis)` and `offsetMillis = PlaybackTimeline.seekOffset(clip, picked)`. Return `REUSE` only when `loadedKey == key && loadedFile != null`; otherwise `DOWNLOAD`. Do not call `File.isFile()` here; this class is a pure decision maker and the caller owns file validity. `adopt()` stores the new key/file and returns the previous file only when it is a different file. `reset()` clears both fields. Keep the file JVM-only; do not delete files inside this class.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackCacheSessionTest" --console=plain`
Expected: PASS, all 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackCacheSession.kt app/src/test/java/id/cctv/monitor/playback/PlaybackCacheSessionTest.kt
git commit -m "test: pin embedded playback cache decisions"
```

---

### Task 2: Embedded playback controller

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/player/EmbeddedPlaybackController.kt`
- Test: none. Android/LibVLC surface lifecycle is verified by compile + device check in Task 5.

**Interfaces:**
- Consumes: `SharedVlc.get(context)`, `VLCVideoLayout`, `MediaPlayer`, `Media`, `File`.
- Produces:
  - `data class EmbeddedPlaybackUiState(val overlay: String?, val playing: Boolean, val elapsedMillis: Long, val durationMillis: Long)`
  - `class EmbeddedPlaybackController(libVlc: LibVLC, videoLayout: VLCVideoLayout, onUi: (EmbeddedPlaybackUiState) -> Unit)` with `load(file: File, offsetMillis: Long)`, `seek(offsetMillis: Long)`, `playPause()`, `pause()`, `resume()`, `stop()`, `release()`.

- [ ] **Step 1: Create controller skeleton**

Declare the constructor and all method signatures above. Attach exactly one `MediaPlayer` to `videoLayout` in `load()`; never create a second player. Reuse `MediaPlayer.ScaleType.SURFACE_BEST_FIT`.

- [ ] **Step 2: Implement load and seek**

`load()` stops current media, clears the previous applied-offset flag, creates `Media(libVlc, Uri.fromFile(file))` with `:no-video-title-show` and `:file-caching=300`, sets it on the player, releases the `Media`, then `play()`. Store `offsetMillis` as pending and apply it exactly once on the first `Playing` or `Vout` event. `seek()` sets `player.time = offsetMillis` when a player is attached, otherwise stores it as pending.

- [ ] **Step 3: Implement UI state and lifecycle**

Emit `EmbeddedPlaybackUiState` on `Opening`/`Buffering` (`"Memuat…"`), `Playing`/`Vout` (hide overlay), `Paused`, `EndReached` (`"Selesai"`), and `EncounteredError` (`"Gagal memutar rekaman"`). Use a 500 ms `Handler` tick for elapsed/duration text, mirroring `RecordingPlayerActivity.tickTime()`. `pause()`/`resume()` only pause/resume the existing player. `stop()` stops media without detaching views. `release()` removes callbacks, stops, detaches views, and releases the player.

- [ ] **Step 4: Verify compile**

Run: `./gradlew.bat assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/player/EmbeddedPlaybackController.kt
git commit -m "feat: add embedded single-decoder playback controller"
```

---

### Task 3: Embedded video frame in playback layout

**Files:**
- Modify: `app/src/main/res/layout/activity_playback.xml`

**Interfaces:**
- Consumes: existing `activity_playback.xml` IDs.
- Produces: `R.id.videoPlayback` (`VLCVideoLayout`), `R.id.tvPlaybackVideoOverlay` (`TextView`), `R.id.tvPlaybackVideoTitle` (`TextView`), `R.id.btnPlaybackVideoPlayPause` (`ImageButton`).

- [ ] **Step 1: Add video frame**

Insert a black `FrameLayout` directly below the app bar, `match_parent` width and `220dp` height. Inside it add `org.videolan.libvlc.util.VLCVideoLayout` (`@+id/videoPlayback`, `match_parent`), a centered overlay `TextView` (`@+id/tvPlaybackVideoOverlay`, initially `gone`), a top-start title `TextView` (`@+id/tvPlaybackVideoTitle`), and a top-end play/pause `ImageButton` (`@+id/btnPlaybackVideoPlayPause`, reuse `@drawable/ic_pause` and `@drawable/bg_chip_circle`).

- [ ] **Step 2: Keep controls reachable**

Wrap the existing channel/date/filter/timeline/step section in a `ScrollView` below the video frame with `fillViewport="true"`. Do not change existing control IDs or `PlaybackTimelineView`.

- [ ] **Step 3: Verify compile**

Run: `./gradlew.bat assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/activity_playback.xml
git commit -m "feat: place playback video above timeline"
```

---

### Task 4: Wire slider to cache, seek, and one decoder

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/PlaybackActivity.kt`

**Interfaces:**
- Consumes: `PlaybackCacheSession` from Task 1, `EmbeddedPlaybackController` from Task 2, layout IDs from Task 3, existing `PlaybackTimelineView.onScrub`/`onPick`, existing `PlaybackDownloader`, `PlaybackTransportResolver`, `PlaybackUi.rowLabel`.
- Produces: no new public API. `openAt(clip, requested)` now loads/seeks embedded video instead of launching `RecordingPlayerActivity`.

- [ ] **Step 1: Add activity fields and controller setup**

Add `lateinit var` views for the four new IDs, plus `lateinit var cacheSession: PlaybackCacheSession` and `var embeddedPlayer: EmbeddedPlaybackController?`. In `onCreate()` after `downloader = PlaybackDownloader(this)`, initialize `cacheSession = PlaybackCacheSession()`. Wire `btnPlaybackVideoPlayPause` to `embeddedPlayer?.playPause()`. Set `tvPlaybackVideoTitle` from `PlaybackUi.rowLabel(clip, camera, zone)` when a clip loads.

- [ ] **Step 2: Keep scrub visual-only and commit on pick**

Leave `timeline.onScrub` as label/marker update only. Change `timeline.onPick` to call `openAt(clip, millis)`. Change `stepClip(delta)` to call the same `openAt(...)` instead of `startPlayer(...)`.

- [ ] **Step 3: Rewrite `openAt`**

Build `key = PlaybackClipKey(context.camera.id, clip.trackId, clip.startMillis)` and `plan = cacheSession.plan(key, clip, requested)`. For `REUSE`, call `embeddedPlayer?.seek(plan.offsetMillis)` and return. For `DOWNLOAD`, first check `PlaybackTransportResolver.resolve(...)`; on `ERROR_NO_ROUTE`, toast and return. Otherwise call `embeddedPlayer?.stop()`, `setBusy(true)`, `downloader.download(clip, endpoint, user, pass)`, `setBusy(false)`, then on success `embeddedPlayer?.load(file, plan.offsetMillis)`, `val stale = cacheSession.adopt(key, file)`, and `stale?.takeIf { it != file }?.delete()`. On failure show toast and leave the video stopped with overlay `"Gagal mengunduh rekaman"`.

- [ ] **Step 4: Remove second-activity launch**

Delete `startPlayer(...)` and the now-unused `Intent` import. Do not modify `RecordingPlayerActivity`; `HistoryActivity` still uses it.

- [ ] **Step 5: Add lifecycle reset**

Add `resetEmbeddedPlayback()`: `embeddedPlayer?.stop()`, `cacheSession.reset()`, `downloader.clear()`, hide overlay, reset title to `"Playback"`. Call it from channel `onItemSelected`, filter checked listener, `shiftDay`, `pickDate`, and `onDestroy`. In `onStart()` call `embeddedPlayer?.resume()`; in `onStop()` call `embeddedPlayer?.pause()`. In `onDestroy()` call `embeddedPlayer?.release()` before `super.onDestroy()`.

- [ ] **Step 6: Update hint copy and build**

Change hint text to `"${visible.size} klip • geser slider untuk memindahkan video"`. Then run `./gradlew.bat assembleDebug assembleRelease testDebugUnitTest --console=plain`; expected BUILD SUCCESSFUL with all tests green.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/PlaybackActivity.kt
git commit -m "feat: seek cached playback from embedded slider"
```

---

### Task 5: Device verification and final gate

**Files:**
- None expected. Modify only if a defect is found, then amend the owning task's commit.

**Interfaces:**
- Consumes: all previous tasks.

- [ ] **Step 1: Install debug build**

Run: `./gradlew.bat installDebug --console=plain`
Expected: BUILD SUCCESSFUL, app installed on `R9RYB03HG7E`.

- [ ] **Step 2: Verify live-free embedded playback**

Open `Playback` → choose NVR/CAM-09 → today. Tap clip `01:29–02:50`. Expected: video appears in the top frame on the same screen; no `RecordingPlayerActivity` transition; slider stays visible below.

- [ ] **Step 3: Verify slider seek**

Drag slider to `02:50` and release. Expected: marker/time label moves, video seeks to that time from the cached clip; no redownload for a second scrub in the same clip. Use `»` once: expected next clip start `05:46`, video reloads and seeks there.

- [ ] **Step 4: Verify reset and cache cleanup**

Switch filter or day, then return. Expected: old frame cleared, old cache deleted, next tap downloads again. Confirm only one VLC decoder is active via logcat/visual behavior.

- [ ] **Step 5: Final build gate**

Run: `./gradlew.bat assembleDebug assembleRelease testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL, all tests green, signer cert unchanged when checked with `apksigner.bat verify --print-certs`. Do not release/tag/push.

## Self-Review

- Spec coverage: layout (Task 3), cache+seek (Tasks 1/4), one decoder (Tasks 2/4), lifecycle/cache cleanup (Task 4), no RTSP seek (Task 4), non-goals/global constraints (all tasks), acceptance device check (Task 5).
- Step scan: exact signatures, IDs, commands, and expected outputs are pinned; no TBD lines.
- Type consistency: `PlaybackClipKey`, `PlaybackCachePlan`, `EmbeddedPlaybackController` names are reused verbatim across tasks.
- Review Focus: each listed failure mode has a test in Task 1 or an explicit Task 4/5 step.
- Proportion: plan is longer than the short spec because exact interfaces and test assertions are required for a fresh implementer; no full implementation bodies are included.
