# SlopIpCam — Consolidated Bug Report

**Date:** 2026-07-11
**Scope:** `server/ctrl-server` (Go), `CamApp` (Kotlin, Pixel 6 camera side), `ViewApp` (Kotlin, Pixel 9 viewer side)
**Method:** Two review passes merged here —
1. *OpenCode* initial full read of every `.go`/`.kt` source, manifests, build files, tests, compose config, plus coroutine/security/performance checklists on the Android apps.
2. *Claude* cross-validation of every finding against upstream library source for the pinned versions (Go stdlib `time`, OkHttp, Camera2, **RootEncoder 2.7.2** `StreamBase.kt`, libwebrtc `VideoTrack.java`, coder/websocket, mediamtx), plus a failing repro test for the top server bug.

**Status legend:** ✅ FIXED · OPEN · 🚫 Not a bug (refuted) · ❓ Plausible, device-unverified.

> Companion file `bugs.md` holds the original two-report audit trail (OpenCode report + Claude cross-validation tables + FIXED markers). This file is the merged, de-duplicated view.

---

## Scorecard

| | Count | IDs |
|---|---|---|
| ✅ Fixed | 9 | GO-1, CAM-2, VIEW-1, CG-1, CG-2, CG-3, CG-4, CG-5, CC-1 |
| OPEN | 28 | GO-2, GO-3, GO-4 (no-action), CG-6..10, CAM-3/4/5/7/8/9/10/11/13, CC-2/3/4, VIEW-2/4/6/7/9/10/11, CV-1 |
| 🚫 Not a bug | 7 | CAM-1, CAM-6 (as leak), CAM-12, VIEW-3, VIEW-5, VIEW-8, VIEW-FP |
| ❓ Unverified | 1 | CAM-4 |

The one genuine **Critical** in the codebase (CG-1) and the worst cam-side dead-end (CAM-2) are already fixed. Remaining work is Medium/Low hardening.

---

## ✅ Fixed

Fix commit: `3aa2cf1` — "fix: recorder state machine, auth-error dead end, camera livelock, WebRTC teardown".

| ID | Sev | Component | Fix | Verification |
|----|-----|-----------|-----|--------------|
| CG-1 | Critical | server | `hub.go`: cam register now clears `streaming` so `ensureStreamLocked` re-sends `CMD:START_STREAM` to a replacement cam | `TestStaleCamKickStillGetsStartStream` |
| GO-1 | Medium | server | `hub.go`: recorder on/off/params now derived by one `ensureRecorderLocked()` (mirrors `ensureStreamLocked`); no restart when params unchanged | `TestMotionWhileSecurityOnDoesNotRestartRecorder` |
| CG-2 | High | server | `hub.go`: `motionGen` generation counter; a stale idle-timer expiry that lost the race to fresh motion is ignored | `TestStaleMotionExpiryIgnored` |
| CG-3 | Medium | server | `hub.go`: `motionExpired` always clears `motionActive`; stream/recorder need-state re-derived | `TestSecurityOffAfterMotionExpiryStopsStream` |
| CG-4 | Medium | server | `hub.go`: `SECURITY_OFF` defers to `ensureRecorderLocked`, which keeps recording through an active motion window | `TestSecurityOffKeepsActiveMotionRecording` |
| CG-5 | Low | server | `hub.go`: `MOTION_REC_ON` re-derives recorder params immediately | `TestMotionRecParamChangeAppliesWhileActive` |
| CAM-2 | High | cam | `RtspStreamer.kt`: `onAuthError` now calls `stop()`, clearing the synchronously-set `isStreaming` so retries work | build (device-level; not unit-testable without a device) |
| CC-1 | High | cam | `CamService.kt`: `tryStartStream` unbinds motion watch before starting, covering the error-retry and rotation-restart paths | build (device-level) |
| VIEW-1 | High | viewer | `MainActivity.kt`: `onDestroy` does `runBlocking { webRtcJob?.cancelAndJoin() }` and cancels the scope before disposing native WebRTC objects | build (device-level) |

---

## OPEN — Server (`server/ctrl-server`, Go)

### GO-2 — `DELETE` can remove persisted security state / live segments · Low (Security)
- **Location:** `main.go:104-131` (`filesWithDelete`)
- **Problem:** The DELETE jail only blocks `..` traversal, so a WS client can `DELETE /recordings/.security.json` (wiping persisted security mode) or an in-progress segment. `.security.json` is *also* readable via `/recordings/.security.json` (`http.FileServer` serves dotfiles).
- **Fix:** Reject DELETE for dotfiles / `.security.json`; consider hiding dotfiles from GET listings too.

### GO-3 — Full directory walk on every gallery open · Low (Perf)
- **Location:** `snapshots.go:40` (`listSnapshots`, `filepath.Walk`), `recorder.go:161` (`listRecordings`)
- **Problem:** Each snapshot-gallery open walks the entire snapshot tree (O(N)). Fine now, slow over months.
- **Fix:** Cache the listing, or list only recent day dirs, or return a bounded/last-N result.

### GO-4 — Per-file `ffmpeg` remux/thumb spawn · Low (Perf) · *No action needed*
- **Location:** `recorder.go:298-349`, looped every 60s in `main.go:142`
- **Assessment (Claude):** Work is one-time per segment — `remuxFinishedSegments` skips non-fragmented files and `makeThumb` no-ops when the thumb exists. Steady-state cost is one `os.ReadDir` per minute.

### CG-6 — `ClipRecorder` is dead code · Low
- **Location:** `recorder.go:183-292`, `hub.go:46`, `main.go:74`
- **Problem:** Constructed and assigned to `hub.clip`, but no command path ever calls `Start` (~110 dead lines); ViewApp's `clipLabel` still special-cases `clip_manual_`/`clip_motion_` filenames nothing produces.
- **Fix:** Remove (or re-wire manual clips).

### CG-7 — Snapshot filename collision · Low
- **Location:** `hub.go:380` — `%d.jpg` from `UnixMilli`; two snapshots in the same millisecond silently overwrite.
- **Fix:** Add a counter or use nanos.

### CG-8 — `saveSecurityState` is a non-atomic write · Low
- **Location:** `hub.go:339` — crash mid-`WriteFile` corrupts `.security.json`; `loadSecurityState` then silently falls back to defaults (security mode lost across the very restart it exists to survive).
- **Fix:** Write temp + `os.Rename`.

### CG-9 — `.remuxtmp` orphans never pruned; prune aborts on first error · Low
- **Location:** `recorder.go:316` (tmp suffix), `recorder.go:364-387` (`pruneRecordings` only matches `.mp4`/`.jpg` and `return err` on the first failed remove, skipping the rest of the directory).

### CG-10 — `http.FileServer` directory listings · Info
- **Location:** `main.go:106` — `/snapshots/` and `/recordings/` render full directory indexes (incl. `.security.json`, see GO-2). Tailnet-only; wrap with an index-suppressing handler if tightening.

---

## OPEN — CamApp (`com.slopIpCam.cam`, Kotlin)

### CAM-3 — `onDestroy` races with late/inflight WS callbacks · Medium *(was High; downgraded — window is a few ms at teardown)*
- **Location:** `CamService.kt:430-447`, `WsClient.kt:21,67`
- **Problem:** `wsClient.disconnect()` cancels the reconnect job but not in-flight `WebSocketListener` callbacks (OkHttp threads). A late `onBinary` → `pttPlayer.write()` can fire *after* `pttPlayer.release()` → `IllegalStateException` on a released `AudioTrack`; a late `onText` can `handler.post` a command after destroy.
- **Fix:** Set a `destroyed`/`shutdown` flag checked at the top of every WS callback and `handleCommand`; release the player *before* disconnecting the socket; guard `onBinary` against a released player; `scope.cancel()` in `WsClient.disconnect()`.

### CAM-4 — Motion detection silently dies after a rotation stream-restart (+ flag-set-on-throw bug) · Medium · ❓ *device-unverified*
- **Location:** `RtspStreamer.kt:28,155`, `CamService.kt:152-160`
- **Problem:** `motionListenerAttached` is set `true` once and never cleared in `stop()`; after a rotation restart, RootEncoder rebuilds the camera session but the `ImageListener` is never re-added → in-stream motion detection dies after any rotation. **Also confirmed:** `motionListenerAttached` is set `true` even when `addImageListener` *threw* (catch is inside `attachMotionListener`, `RtspStreamer.kt:127-153`), so one transient failure kills in-stream motion until process restart.
- **Fix:** Clear `motionListenerAttached` in `stop()` (or re-attach defensively on each `start()`).
- **Verify:** 2-min device test — rotate phone with motion-rec on, watch for `EVENT:MOTION`.

### CAM-5 — Async `ProcessCameraProvider` bind can hit a destroyed lifecycle / double-bind · Medium
- **Location:** `CamService.kt:275-296`, `324-383`
- **Problem:** The motion-watch `addListener` future can fire *after* `onDestroy` and `bindToLifecycle` a destroyed lifecycle (CameraX throws). The poll loop can double-start a bind while the future is in flight (poll ticks every 2s). The future guard checks only `streamer.isStreaming` (false during async startup), so it can grab the back camera the stream is opening.
- **Fix:** Guard the future with a `destroyed` flag / `lifecycle.currentState.isAtLeast(STARTED)`; add a `motionWatchStarting` boolean; also bail if `shouldStream` is set.

### CAM-7 — FLASHLIGHT command runs off the main thread · Low *(was Medium; `CameraManager.setTorchMode` is thread-safe)*
- **Location:** `CamService.kt:224-225`
- **Fix:** Wrap in `handler.post` for consistency with the other branches.

### CAM-8 — Flashlight in-stream fallback targets the camera the stream owns · Low
- **Location:** `CamService.kt:254`
- **Problem:** While streaming, `if (!streamer.setTorch(on)) flashlight.setTorch(on)` falls back to `CameraManager.setTorchMode` on the same back camera RootEncoder already holds → throws/ignored (logged no-op).
- **Fix:** Drop the in-stream fallback; only use `CameraManager` when not streaming.

### CAM-9 — `snapshot()` is dead code · Low
- **Location:** `CamService.kt:67-68`
- **Problem:** Zero callers (grep: only the definition). (Original mechanism note was inverted — the "did not call startForeground" watchdog applies to `startForegroundService`, not plain `startService`.)
- **Fix:** Delete it.

### CAM-10 — Non-`volatile` motion/shared state across threads · Low
- **Location:** `CamService.kt:303-322,363-371` (`lastMotionEventMs`, `lastSnapshotMs`), `CamService.kt:300` (`streamPrevLuma`)
- **Problem:** Counters written/read across the ImageReader and `analysisExecutor` threads (duplicate `EVENT:MOTION` possible); `streamPrevLuma` is nulled from main but written on the ImageReader thread without visibility guarantee.
- **Fix:** `@Volatile` / `AtomicLong`.

### CAM-11 — `WsClient` scope/socket leak · Low
- **Location:** `WsClient.kt:21,32,67`
- **Fix:** `scope.cancel()` in `disconnect()`; close any prior `ws` before opening a new one.

### CAM-13 — RTSP password stored in plaintext `SharedPreferences` · Info (Security)
- **Location:** `CamService.kt:85`, `preferences.xml:22-25`
- **Assessment:** App-private (`MODE_PRIVATE`), `allowBackup="false"` blocks `adb backup` extraction; exposure limited to a rooted device on the tailnet. Acceptable. `onConnectionStarted(url)` is overridden empty so RootEncoder never logs the credential URL.
- **Fix:** (Optional) `EncryptedSharedPreferences`.

### CC-2 — PTT audio playback blocks the WebSocket reader thread · Medium
- **Location:** `CamService.kt:109` (`onBinary = { pcm -> pttPlayer.write(pcm) }`), `PttPlayer.kt:30`
- **Problem:** OkHttp delivers WS callbacks on the reader thread and requires the callback to return before further messages are delivered; `AudioTrack.write` in MODE_STREAM blocks when the buffer is full, so sustained push-to-talk delays every queued command behind real-time audio drain.
- **Fix:** Hand PCM to a dedicated playback thread/queue; the WS callback just enqueues.

### CC-3 — Settings are read once at service creation · Low
- **Location:** `CamService.kt:82-90`
- **Problem:** WS URL, mediamtx host, RTSP credentials captured in `onCreate` closures; changing them in Settings does nothing until the service is manually restarted. Compounds CAM-2 (after fixing a wrong password the user must also restart).
- **Fix:** Re-read settings on (re)connect, per ViewApp's `reconnectIfConfigChanged` pattern.

### CC-4 — `WsClient.ws` not `@Volatile` (both apps) · Low
- **Location:** `CamApp WsClient.kt:20`, `ViewApp WsClient.kt:20`
- **Problem:** `ws` written on the reconnect coroutine (IO) and read by `sendText`/`sendBinary` from other threads with no happens-before edge → callers can briefly address a dead socket after a reconnect. Pairs with VIEW-10 for fully silent drops.
- **Fix:** `@Volatile ws`.

---

## OPEN — ViewApp (`com.slopIpCam.view`, Kotlin)

### VIEW-2 — Data race on `peerConnection`/`webRtcJob` (Main vs IO) · Low *(was High; restart paths already `cancelAndJoin` before `dispose`)*
- **Location:** `MainActivity.kt:248-259,296,357-372`
- **Problem:** Narrower than originally claimed — all mutations run in `scope` coroutines on `Dispatchers.Main` and both restart paths `cancelAndJoin()` before `dispose()`. Residual: the `connectionState()` pre-check in `restartWebRtcIfNeeded` can read a stale reference → worst case a redundant rebuild.
- **Fix:** `@Volatile` on both fields (the Mutex/actor suggestion was overkill).

### VIEW-4 — `PttRecorder` no buffer-size validation · Low *(was Medium; stop→start window is ms-scale)*
- **Location:** `PttRecorder.kt:10-46`
- **Problem:** `getMinBufferSize` can return `<=0` and isn't validated; `stop()` nulls `job` while the coroutine's `finally` still releases — a quick stop→start briefly opens a second `AudioRecord`.
- **Fix:** Validate/fallback buffer size (`sampleRate*2*2` if `<=0`); wrap construction + `startRecording()` in try/catch; don't null `job` until `finally`.

### VIEW-6 — `ObjectAnimator` (recording dot) never cancelled · Low
- **Location:** `MainActivity.kt:114-119`
- **Fix:** Store in a field and `cancel()` in `onDestroy`.

### VIEW-7 — `DISCONNECTED` PeerConnection state never triggers reconnect · Low
- **Location:** `MainActivity.kt:287-293`
- **Fix:** Also `restartStream()` on `DISCONNECTED` (often self-heals to FAILED anyway).

### VIEW-9 — `loadItems` swallows all errors as an empty list · Low
- **Location:** `SnapshotsActivity.kt:186-188`
- **Fix:** Surface an error state ("offline" / Toast) so server-down ≠ no-files.

### VIEW-10 — Optimistic `security_mode` flip / dropped `send()` · Low
- **Location:** `MainActivity.kt:87-99`, `WsClient.kt:64`
- **Problem:** Toggle writes the pref then sends `CMD:SECURITY_ON`; if the WS isn't open the send is silently dropped (and `ws?.send()`'s `false` return is ignored) → UI shows "recording" but the server never records.
- **Fix:** Disable the toggle until connected / queue commands until `onConnected`; log when `send()` returns `false`.

### VIEW-11 — `WsClient` scope never cancelled · Low *(duplicate of CAM-11)*
- **Location:** `WsClient.kt:21`
- **Fix:** `scope.cancel()` in `disconnect()`, or share one client/scope.

### CV-1 — `setRemoteDescription` failure is a dead end · Low
- **Location:** `MainActivity.kt:345-353`
- **Problem:** `onSetFailure` sets status "Stream failed" and stops; no retry, no `restartStream()`. Recovery needs a WS reconnect or app restart.
- **Fix:** On `onSetFailure`, `restartStream()`.

### VIEW-SEC — Plain HTTP/WS · Info (Security)
- **Assessment:** By design — the Tailscale tailnet is the boundary; RTSP/WHEP/DELETE are intentionally unauthenticated (`CLAUDE.md:70`). `websocket.Accept(nil)` rejects cross-origin by default; ffmpeg args are whitelist-gated; the RTSP publish password is gitignored **and absent from all git history**. No change recommended.

---

## 🚫 Not a bug (refuted — do not act)

- **CAM-1** (was the sole Critical) — RootEncoder 2.7.2 `StreamBase.startStream` sets `isStreaming` **synchronously** (`if (isStreaming) throw …; isStreaming = true; …`). Every `CMD:START_STREAM` posts to the single main `Handler` (serialized), and the hub only emits START on an idle→active transition. The reported async double-publish cannot occur. *(This sync behavior is exactly why confirmed CAM-2 happens.)*
- **CAM-6** (as a leak) — On API 29+ (minSdk here) bitmap pixel memory is native-heap tracked via `NativeAllocationRegistry` and reclaimed by GC without `recycle()`. At ≤1 snapshot/30s the pressure is negligible. Harmless to `recycle()`, but not a leak.
- **CAM-12** — `onOpen` resets `delayMs = 1000L`; doubling only accumulates across *consecutive failed* attempts (intended backoff). The "slow reconnect after a routine restart" scenario cannot occur.
- **VIEW-3** — libwebrtc `VideoTrack.addSink` is a documented no-op for an already-attached sink (`if (!sinks.containsKey(sink))`). The "addSink dedupes" comment is correct; no `sinkAdded` flag needed.
- **VIEW-5** — `handleServerMsg`/`onConnected` immediately `scope.launch` (Main dispatcher) for anything touching WebRTC state; `sendText` is thread-safe; `setStatus` wraps `runOnUiThread`. No off-main WebRTC mutation today.
- **VIEW-8** — OkHttp idle threads exit after 60s keep-alive; nothing persists "until GC". No action.
- **VIEW-FP** — The claimed `CMD:MOTION_ON` protocol mismatch is false: `hub.go` `handleServerCmd` explicitly handles `CMD:MOTION_ON`/`CMD:MOTION_OFF`/`CMD:MOTION_REC_ON:<fps>:<res>`/`CMD:MOTION_REC_OFF` (what ViewApp sends). `CMD:MOTION:snap=…:detect=…` is the separate hub→cam hop. Do not "fix".

---

## Remediation priority (remaining OPEN work)

1. **CAM-3, CAM-5, CC-2** — teardown guards (destroyed flag + player release order), destroyed-lifecycle/double-bind guard, and PTT off the WS reader thread.
2. **GO-2** — dotfile/`.security.json` DELETE (and GET) guard.
3. **CG-6..10, CC-3/4, CV-1, VIEW-2/4/6/7/9/10/11** — Low/Info cleanup; the `ensureRecorderLocked()` refactor (already fixed) is the model for keeping the hub's state machine centralized.
4. **CAM-4** — confirm on-device (rotation + motion-rec) and apply the cheap flag-reset fix regardless.

The server's `hub_test.go` now carries regression tests for the fixed stream/recorder state machine (CG-1, GO-1, CG-2..5); keep them green when touching `hub.go`.

---

## Verification notes

- `go test ./...` passes on current `main`; the CG-1 repro test (`TestStaleCamKickStillGetsStartStream`) fails on the pre-fix code.
- Library claims checked against: RootEncoder 2.7.2 `StreamBase.kt` (tag source), libwebrtc `VideoTrack.java` (upstream), OkHttp 5.x WebSocket docs, Go 1.25 `time` docs, Camera2 `CameraManager`/`CameraAccessException` docs, coder/websocket `Accept` docs, mediamtx auth docs.
- Secret hygiene: `server/mediamtx.yml` and `server/.env` are gitignored **and** absent from all git history (pre-gitignore `mediamtx.yml` revisions contained no credentials).
- Skill-install note (transparency): an attempt was made to install 3 review skills. `noloman/Android-AI-skills` CLI installed only 2 PromptScript skills (compose/kmp, not relevant here); `physics91/claude-vibe` was auth-blocked (private repo); `rajedev/android-code-review` was blocked for global install. No project files were changed by any install (user-level `~/.agents/skills`). The coroutine/security/performance checklists those skills enforce were instead applied manually and reconciled in this report.
