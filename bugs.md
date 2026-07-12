# OpenCode Bug Report

**Project:** SlopIpCam — personal tailnet-only cat-monitoring IP-cam (CamApp → mediamtx → ViewApp, plus a Go `ctrl-server` hub)
**Date:** 2026-07-11
**Scope:** `server/ctrl-server` (Go), `CamApp` (Kotlin, Pixel 6 camera side), `ViewApp` (Kotlin, Pixel 9 viewer side)
**Method:** Full manual read of every `.go`/`.kt` source, manifests, build files, tests, and compose config, plus a second pass applying coroutine / security / performance checklists to the Android apps.

> **Skill-install note (transparency):** Per request, an attempt was made to install 3 review skills.
> - `noloman/Android-AI-skills` — CLI installed only 2 PromptScript skills (`compose-multiplatform-best-practices`, `kmp-architecture-best-practices`); the relevant `kotlin-coroutines-best-practices` / `android-security-best-practices` / `android-performance-best-practices` are not exposed for install by this CLI. Direct fetch of their `SKILL.md` also failed (repo tree API returned nothing).
> - `physics91/claude-vibe@kotlin-android-reviewer` — **unreachable**: clone failed on both HTTPS and SSH ("Authentication failed" — private repo).
> - `rajedev/AINews-App@android-code-review` — PromptScript type, blocked for global (`-g`) install; local install also rejected.
>
> No project files were changed by any install (writes landed in `~/.agents/skills`, user-level). The coroutine/security/performance checklists those skills would apply were instead run manually against the Android code; this report's Android findings reflect them. The Go/server findings stand independently.

---

## Executive summary

The server (`ctrl-server`) is the most robust component — it has a real regression-test suite (`hub_test.go`) guarding the load-bearing "never re-START an already-streaming cam" invariant, and that invariant holds. The Android apps carry the real risk:

- **Streaming-correctness bug (Critical)** on the cam side: the START_STREAM idempotency guard is defeated by RootEncoder's *async* `isStreaming`, which can double-publish RTSP and freeze the viewer ~5s.
- **Two High crash/teardown risks** on the viewer side (WebRTC native crash on destroy; main/IO data race on `peerConnection`).
- **A permanent "stuck streaming" state** if the RTSP auth fails (cam side).
- Several Medium leaks/races (motion detection dies after rotation, bitmap leak, WS teardown race, `PttRecorder` job race).

Severity counts: **Critical 1 · High 5 · Medium 9 · Low 13** (incl. 1 documented false positive).

---

## Server (`server/ctrl-server`, Go)

### GO-1 — Motion event restarts the security recorder unnecessarily  ·  Medium  ·  ✅ **FIXED**
- **Location:** `hub.go:255-263` (`onMotionEvent`)
- **Problem:** When `security.On` is already driving the recorder and a motion burst begins, `!h.motionActive` is true, so the hub calls `h.recorder.Start(h.recFps, h.recRes)`. That SIGINTs the live security segment and re-splits it (and may change fps/res), producing a visible gap in continuous security footage on *every* first motion event after a quiet period.
- **Fix:** Skip `recorder.Start` when the recorder is already active and the motion params equal the current recording params; only flip `motionActive` and re-arm the idle timer.

### GO-2 — `DELETE` can remove persisted security state / live segments  ·  Low (Security)
- **Location:** `main.go:104-131` (`filesWithDelete`)
- **Problem:** The DELETE jail only blocks `..` path traversal, so a viewer (any WS client) can `DELETE /recordings/.security.json` (wiping persisted security mode) or delete an in-progress fragmented segment. Tailnet-only, but trivially self-inflicting.
- **Fix:** Reject DELETE for dotfiles and for `.security.json` specifically; or require a query/auth to delete. Also verify `os.Remove(target + ".jpg")` stays jailed (it does, since `target` is jailed).

### GO-3 — Full directory walk on every gallery open  ·  Low (Perf)
- **Location:** `snapshots.go:40` (`listSnapshots`, `filepath.Walk` over all day dirs), `recorder.go:161` (`listRecordings`, `os.ReadDir` — less severe)
- **Problem:** Each snapshot-gallery open walks the entire snapshot tree (O(N)). Fine for weeks of data, will get slow over months.
- **Fix:** Cache the listing, or list only recent day dirs, or return a bounded/last-N result.

### GO-4 — Per-file `ffmpeg` remux/thumb spawn every minute  ·  Low (Perf)
- **Location:** `recorder.go:298-349` (`remuxFinishedSegments` + `makeThumb`), looped every 60s in `main.go:142`
- **Problem:** Completed segments each spawn an `ffmpeg` for remux + another for thumbnail. Bounded by completed-segment count; acceptable, but worth noting for low-power hosts.
- **Fix:** (Optional) batch or rate-limit; only remux segments not yet faststart.

> Server is otherwise solid: `ensureStreamLocked` correctly dedupes START/STOP transitions, command validation whitelists fps/res, and the cross-origin WS handshake is rejected by default (`hub_test.go: TestCrossOriginBrowserRejected`).

---

## CamApp (`com.slopIpCam.cam`, Kotlin)

### CAM-1 — START_STREAM double-start guard defeated by async `isStreaming`  ·  Critical
- **Location:** `CamService.kt:165-174` (`tryStartStream`), `RtspStreamer.kt:18,27` (`isStreaming` / `start`)
- **Problem:** `tryStartStream` bails with `if (!shouldStream || streamer.isStreaming) return`, but RootEncoder's `isStreaming` only flips `true` inside the async `onConnectionSuccess` callback (network thread). Each `CMD:START_STREAM` is posted as its own `handler` runnable (`CamService.kt:206`); two arriving back-to-back both see `isStreaming==false` and call `streamer.start()` twice → `RtspStream::startStream` re-publishes RTSP and **freezes the viewer ~5s**. This is the exact load-bearing invariant the repo warns about, and the client-side guard that's supposed to protect it is broken.
- **Fix:** Track a synchronous enum/flag `streamState` (STOPPED/STARTING/STREAMING); set STARTING *before* `streamer.start()` and clear it only in `onConnectionSuccess`/`onConnectionFailed`. Guard both `tryStartStream` and `RtspStreamer.start` on that, not on the async `isStreaming`.

### CAM-2 — `onAuthError` leaves a permanent false "Streaming" state  ·  High  ·  ✅ **FIXED**
- **Location:** `RtspStreamer.kt:179`
- **Problem:** `onAuthError` calls `onError(...)` (which reschedules `tryStartStream`) but does **not** `stop()`. RootEncoder keeps `isStreaming==true`, so the retry hits the `streamer.isStreaming` guard and no-ops forever — the cam is stuck dead with the UI showing "Streaming".
- **Fix:** `override fun onAuthError() { onError("RTSP auth error"); stop() }`.

### CAM-3 — `onDestroy` races with late/inflight WS callbacks  ·  High  ·  ✅ **FIXED**
- **Location:** `CamService.kt:430-447` (`onDestroy`), `WsClient.kt:21,67`
- **Problem:** `wsClient.disconnect()` cancels the reconnect job but does not stop in-flight `WebSocketListener` callbacks (they run on OkHttp threads). A late `onBinary` → `pttPlayer.write()` can fire *after* `pttPlayer.release()` → `IllegalStateException` on a released `AudioTrack`. A late `onText` can `handler.post` a command after the service is destroyed, touching `rtspStreamer`/`wsClient`.
- **Fix:** Set a `destroyed`/`shutdown` flag checked at the top of every WS callback and `handleCommand`; release the player *before* disconnecting the socket; guard `onBinary` against a released player. Also `scope.cancel()` in `WsClient.disconnect()`.

### CAM-4 — Motion detection silently dies after a rotation stream-restart  ·  Medium
- **Location:** `RtspStreamer.kt:28,155` (`motionListenerAttached`), `CamService.kt:152-160`
- **Problem:** `motionListenerAttached` is set `true` once and never cleared in `stop()`. After a rotation restart (`stop()`→`start()`), RootEncoder rebuilds the camera session but the `ImageListener` is never re-added, so `motionFrameCb` stops firing and in-stream motion detection dies after any rotation.
- **Fix:** Clear `motionListenerAttached` in `stop()` (or re-attach defensively on each `start()`).

### CAM-5 — Async `ProcessCameraProvider` bind can hit a destroyed lifecycle / double-bind  ·  Medium  ·  ✅ **FIXED**
- **Location:** `CamService.kt:275-296` (`startMotionWatchPolling`), `324-383` (`startMotionWatch`)
- **Problem:** The motion-watch `addListener` future can fire *after* `onDestroy` and `bindToLifecycle` a destroyed lifecycle (CameraX throws). The poll loop can double-start a bind because `cameraProvider` stays null while the future is in flight. The future guard checks only `streamer.isStreaming` (false during async startup), so it can grab the back camera the stream is opening.
- **Fix:** Guard the future with a `destroyed` flag / `lifecycle.currentState.isAtLeast(STARTED)`; add a `motionWatchStarting` boolean cleared in the listener; also bail if `shouldStream` is set (set synchronously before `tryStartStream`).

### CAM-6 — Bitmap leak in `sendSnapshot`  ·  Medium
- **Location:** `CamService.kt:394-404`
- **Problem:** The rotated copy (`Bitmap.createBitmap`) and sometimes the source `bmp` are never `recycle()`d after `compress()` → steady GC pressure / OOM risk on a long-running service.
- **Fix:** After `compress`, `recycle()` `upright`; if `rot != 0` (so `upright !== bmp`) also `bmp.recycle()`. Consider a single reused `ByteArrayOutputStream`.

### CAM-7 — FLASHLIGHT command runs off the main thread  ·  Medium
- **Location:** `CamService.kt:224-225`
- **Problem:** Unlike other commands it calls `setTorch` directly on the OkHttp WS reader thread, touching `rtspStreamer.isStreaming`/`CameraManager` that everything else touches on Main.
- **Fix:** Wrap in `handler.post`, consistent with the other branches.

### CAM-8 — Flashlight in-stream fallback targets the camera the stream owns  ·  Low
- **Location:** `CamService.kt:254`
- **Problem:** While streaming, `if (!streamer.setTorch(on)) flashlight.setTorch(on)` falls back to `CameraManager.setTorchMode` on the *same* back camera RootEncoder already holds → throws/ignored.
- **Fix:** Drop the in-stream fallback; only use `CameraManager` when not streaming (the `else` branch already does).

### CAM-9 — `snapshot()` uses plain `startService`  ·  Low
- **Location:** `CamService.kt:67-68`
- **Problem:** When the service isn't already running this launches it in the background; combined with `onCreate` calling `startForeground`, on Android 12+ this can trip the "did not call startForeground" watchdog.
- **Fix:** Use `startForegroundService` (consistent with `start()`).

### CAM-10 — Non-`volatile` motion counters shared across threads  ·  Low
- **Location:** `CamService.kt:303-322,363-371` (`lastMotionEventMs`, `lastSnapshotMs`)
- **Problem:** Written/read across the ImageReader thread and `analysisExecutor` thread; two near-simultaneous events can both pass the 5s throttle and emit duplicate `EVENT:MOTION`.
- **Fix:** Make them `@Volatile` / `AtomicLong`.

### CAM-11 — `WsClient` scope/socket leak  ·  Low
- **Location:** `WsClient.kt:21,32,67`
- **Problem:** `CoroutineScope` is never cancelled; `connect()` overwrites `ws` without closing the previous socket.
- **Fix:** `scope.cancel()` in `disconnect()`; close any prior `ws` before opening a new one.

### CAM-12 — Reconnect backoff grows after *normal* closes  ·  Low
- **Location:** `WsClient.kt` (loop doubles `delayMs` every iteration)
- **Problem:** `delayMs` doubles unconditionally each loop; a routine mediamtx restart eventually makes the cam take ~30s to reconnect.
- **Fix:** Only back off on a failed connect (`catch`/`onFailure`), reset after a successful `onOpen`.

### CAM-13 — RTSP password stored in plaintext `SharedPreferences`  ·  Info (Security)
- **Location:** `CamService.kt:85`, `preferences.xml:22-25`
- **Problem:** `rtsp_pass` is app-private (`MODE_PRIVATE`) and `allowBackup="false"` blocks `adb backup` extraction, so exposure is limited to a rooted device on the tailnet. Acceptable for this design. `onConnectionStarted(url)` is overridden empty so RootEncoder never logs the credential-bearing URL.
- **Fix:** (Optional) move to `EncryptedSharedPreferences` for defense-in-depth. Verify RootEncoder isn't logging the `user:pass@host` URL at lower log levels.

---

## ViewApp (`com.slopIpCam.view`, Kotlin)

### VIEW-1 — `onDestroy` disposes WebRTC objects without joining the in-flight job  ·  High  ·  ✅ **FIXED**
- **Location:** `MainActivity.kt:419-431`
- **Problem:** `webRtcJob?.cancel()` is **not** `cancelAndJoin()`; the cancelled `startWebRtc` coroutine can be mid-`createPeerConnection`/`setRemoteDescription` when `peerConnection?.dispose()` / `peerConnectionFactory?.dispose()` run → classic libwebrtc SIGSEGV/SIGABRT.
- **Fix:** `webRtcJob?.cancelAndJoin()` before disposing `peerConnection`; dispose `peerConnectionFactory` **last**.

### VIEW-2 — Data race on `peerConnection`/`webRtcJob` (Main vs IO)  ·  High
- **Location:** `MainActivity.kt:248-259,296,357-372`
- **Problem:** `mainScope` mutates these on Main; `startWebRtc` writes `peerConnection = pc` on `Dispatchers.IO`. No `volatile`/`Mutex`/happens-before edge → the Main thread can dispose the wrong object or orphan a `PeerConnection` (native leak). `restartStream()` can also re-enter.
- **Fix:** Mark both `@Volatile`, or funnel all start/restart through one serialized `Mutex`/actor so only one setup runs at a time and field writes happen on one thread.

### VIEW-3 — `restartStream` can double-`addSink` the renderer  ·  Medium
- **Location:** `MainActivity.kt:276-283`
- **Problem:** Both Plan-B `onAddStream` and Unified-Plan `onAddTrack` call `addSink(renderer)`; `VideoTrack.addSink` doesn't dedupe, so a re-negotiation double-sinks → doubled/garbled frames.
- **Fix:** Guard with a `sinkAdded` flag, or drop the Plan-B path (comment says Unified Plan "is what actually fires"), or `removeSink` in `onRemoveTrack`.

### VIEW-4 — `PttRecorder` stop/start race + no buffer-size validation  ·  Medium
- **Location:** `PttRecorder.kt:10-46`
- **Problem:** `stop()` nulls `job` immediately while the coroutine's `finally { rec.release() }` runs later; a quick stop→start opens a second `AudioRecord` while the first is still releasing → `IllegalStateException`. Also `getMinBufferSize` can return `<=0` and isn't validated.
- **Fix:** Don't null `job` until `finally` completes (set it inside `finally`); validate/ fallback buffer size (`sampleRate*2*2` if `<=0`); wrap construction + `startRecording()` in try/catch.

### VIEW-5 — Off-main WS callback mutates WebRTC state  ·  Medium
- **Location:** `WsClient.kt:37-38` + `MainActivity.kt:210` (`onConnected` → `restartWebRtcIfNeeded`/`syncCamConfig`)
- **Problem:** `handleServerMsg`/`onConnected` run on the OkHttp thread; compounded with VIEW-2.
- **Fix:** `withContext(Dispatchers.Main)` at the top of those handlers (or post callbacks to Main in `WsClient`).

### VIEW-6 — `ObjectAnimator` (recording dot) never cancelled  ·  Low
- **Location:** `MainActivity.kt:114-119`
- **Problem:** `INFINITE`/`REVERSE` animator is a local; keeps ticking on a detached view after `onDestroy`.
- **Fix:** Store in a field and `cancel()` in `onDestroy`.

### VIEW-7 — `DISCONNECTED` PeerConnection state never triggers reconnect  ·  Low
- **Location:** `MainActivity.kt:287-293`
- **Problem:** Only `FAILED` restarts; a `DISCONNECTED` (no ICE restart) leaves dead video until an external EVENT.
- **Fix:** Also `restartStream()` on `DISCONNECTED`.

### VIEW-8 — `SnapshotsActivity` OkHttpClient never shut down  ·  Low
- **Location:** `SnapshotsActivity.kt:25`
- **Problem:** Fresh `OkHttpClient()` per activity, threads persist until GC.
- **Fix:** `client.dispatcher.executorService.shutdown()` in `onDestroy()`, or a shared singleton client.

### VIEW-9 — `loadItems` swallows all errors as an empty list  ·  Low
- **Location:** `SnapshotsActivity.kt:186-188`
- **Problem:** Any network failure / non-JSON / 404 returns `emptyList()` — "server down" looks identical to "no files".
- **Fix:** Surface an error state (e.g. `tvCount` = "offline" or a Toast).

### VIEW-10 — Optimistic `security_mode` flip / dropped `send()`  ·  Low
- **Location:** `MainActivity.kt:87-99`, `WsClient.kt:64`
- **Problem:** The toggle writes the pref then sends `CMD:SECURITY_ON`; if the WS isn't open the send is silently dropped (and `ws?.send()`'s `false` return is ignored) → UI shows "recording" but the server never records.
- **Fix:** Disable the toggle until connected / queue commands until `onConnected`; log when `send()` returns `false`.

### VIEW-11 — `WsClient` scope never cancelled  ·  Low
- **Location:** `WsClient.kt:21`
- **Problem:** Each reconnect makes a new `WsClient` with its own `CoroutineScope`; `disconnect()` cancels only `reconnectJob`.
- **Fix:** `scope.cancel()` in `disconnect()`, or share one client/scope.

### VIEW-FP — Claimed `CMD:MOTION_ON` protocol mismatch  ·  FALSE POSITIVE (not a bug)
- **Claim reviewed:** A first-pass reviewer claimed `CMD:MOTION_ON` / `CMD:MOTION_REC_ON` don't match the hub's expected format and would be silently ignored.
- **Reality:** `hub.go` `handleServerCmd` explicitly handles `CMD:MOTION_ON`, `CMD:MOTION_OFF`, `CMD:MOTION_REC_ON:<fps>:<res>`, and `CMD:MOTION_REC_OFF` — exactly what ViewApp sends (`MainActivity.kt:151-161`). The `CMD:MOTION:snap=…:detect=…` string in `CLAUDE.md` is the **hub→cam** command (a different hop). **Do not "fix" this** — it would break working control flow.

### VIEW-SEC — Plain HTTP/WS  ·  Info (Security)
- **Location:** `AndroidManifest.xml:14`, `preferences.xml` (`ws://`/`http://`)
- **Assessment:** By design — the Tailscale tailnet is the security boundary; RTSP/WHEP/DELETE are intentionally unauthenticated (see `CLAUDE.md:70`). No change recommended; a code comment would stop a future reader "fixing" it and breaking the model. Logs contain no credentials.

---

## Remediation priority

1. **CAM-1, CAM-2, CAM-3** — cam streaming correctness + teardown safety (protects the load-bearing invariant; stops the stuck-"Streaming" dead end; prevents post-destroy crashes).
2. **VIEW-1, VIEW-2** — WebRTC native-crash stoppers (app stability).
3. **CAM-4, GO-1** — motion detection after restart; recorder restart on motion.
4. **CAM-5–CAM-7, VIEW-3–VIEW-5** — leaks, races, double-sink.
5. **Low items + GO-2/GO-3** — hardening and perf.

The Go side already has a strong `hub_test.go` suite; add a regression test for **GO-1** (motion while security-on must not restart the segment) and keep the existing START/STOP-dedup tests green when touching `hub.go`.

---

## Verification done
- Read 100% of `.go` and `.kt` sources across the three components, both manifests, both `build.gradle.kts`, all tests, `docker-compose.yml`, `mediamtx.yml.example`.
- Spot-verified the critical claims against source: CAM-1 (async `isStreaming`), CAM-2 (`onAuthError` no stop), VIEW-1 (`cancel` not `cancelAndJoin`), VIEW-2 (Main/IO write of `peerConnection`), GO-1 (`onMotionEvent` recorder start), GO-2 (`filesWithDelete` jail).
- Confirmed VIEW-FP is a false positive by reading `handleServerCmd` directly.

---

# Claude Bug Report

**Date:** 2026-07-11
**Method:** Full manual read of all `.go`/`.kt` sources, manifests, configs, and tests; `go test ./...` baseline (pass); one failing repro test written for the top server bug; library-behavior claims verified against upstream docs/source (Go stdlib `time`, OkHttp 5.x, Camera2, RootEncoder **2.7.2** `StreamBase.kt`, libwebrtc `VideoTrack.java`, coder/websocket, mediamtx).

## Part 1 — Validation of the OpenCode findings above

Each finding re-checked against source and, where the claim depends on library behavior, against the actual library code for the pinned version.

### Server

| ID | Verdict | Notes |
|----|---------|-------|
| GO-1 | **CONFIRMED** | Independently found. Additionally: `Recorder.Start` bumps `gen` and SIGINTs the live ffmpeg even for identical params, and after the motion window expires the recorder is **never restored to the security fps/res** if motion params differed (`motionExpired` keeps it running as-is when `security.On`). See CG-2..5 below for sibling state-machine bugs this report missed. |
| GO-2 | **CONFIRMED** | Missed in my first pass — good catch. `stateFile = RECORD_DIR/.security.json` (`main.go:75`) is deletable and *readable* via `/recordings/.security.json` (`http.FileServer` serves dotfiles). Fix per suggestion; also consider hiding dotfiles from GET. |
| GO-3 | **CONFIRMED** (Low) | Agree; per-request `filepath.Walk` is fine at current scale. |
| GO-4 | **ADJUSTED** (noise) | Work is one-time per segment: `remuxFinishedSegments` skips non-fragmented files and `makeThumb` no-ops when the thumb exists. Steady-state cost is one `os.ReadDir` per minute. No action needed. |

> **The executive summary's core claim is wrong.** "The server is the most robust component… the invariant holds" — the server contains the single worst bug in the codebase (CG-1 below, proven with a failing test), plus a documented timer race and three motion/security state-machine bugs. `hub_test.go` covers clean disconnect→reconnect but not the stale-connection replacement path.

### CamApp

| ID | Verdict | Notes |
|----|---------|-------|
| CAM-1 | **REFUTED — false positive (the report's only Critical)** | The premise is factually wrong for the class in use. CamApp uses `RtspStream`/`StreamBase`, and RootEncoder 2.7.2 `StreamBase.kt` (fetched from the tag) sets the flag **synchronously**: `fun startStream(endPoint: String) { if (isStreaming) throw IllegalStateException(...); isStreaming = true; startStreamImp(endPoint); … }`. The report cited `RtspClient.isStreaming` — an internal protocol object, not what `RtspStreamer.isStreaming` reads. Every `CMD:START_STREAM` is posted to the single main `Handler`, so the posts serialize and the second sees `isStreaming == true`. Defense in depth: even with no guard, `startStream` would throw, not double-publish; and the hub only emits START on an idle→active transition, so back-to-back STARTs don't occur from a healthy hub. **Do not implement the suggested STARTING/STREAMING state machine to fix a non-bug.** |
| CAM-2 | **CONFIRMED** (High) | Real, and the sync-`isStreaming` fact above is exactly *why*: `startStream` flips the flag true immediately, `onAuthError` fires without `stop()` (`RtspStreamer.kt:179`), nothing in `StreamBase` resets the flag, so every 5s retry no-ops on the `isStreaming` guard forever while the UI shows "Streaming". Asymmetry with `onConnectionFailed` (which does call `stop()`) makes the one-line fix obvious. Only reachable with a wrong RTSP password — which is precisely first-run setup. |
| CAM-3 | **CONFIRMED, downgrade High→Medium** | Race window is real (`AudioTrack.write` after `release()` throws `IllegalStateException`), but it's a few ms at service teardown and the process is usually dying anyway. Flag-guard fix is right. |
| CAM-4 | **PLAUSIBLE, unverified** | Whether `Camera2ApiManager` re-adds the ImageReader surface when RootEncoder rebuilds the session after `stopStream()`→`startStream()` was not verifiable from docs; needs a 2-minute device test (rotate phone with motion rec on, watch for `EVENT:MOTION`). The defensive fix (clear flag in `stop()`) is cheap either way. Related, confirmed: `motionListenerAttached` is set `true` even when `addImageListener` *threw* (the catch is inside `attachMotionListener`, `RtspStreamer.kt:127-153`), so one transient failure kills in-stream motion until process restart. |
| CAM-5 | **CONFIRMED** (Medium) | `bindToLifecycle` on a DESTROYED lifecycle throws; the provider-future listener runs on the main executor and can land after `onDestroy`. The double-bind window while the future is in flight is real too (poll ticks every 2s). |
| CAM-6 | **REFUTED as a leak** | On API 29+ (minSdk here) bitmap pixel memory is native-heap tracked via `NativeAllocationRegistry` and reclaimed by GC without `recycle()`. At ≤1 snapshot/30s the pressure is negligible. Harmless to add `recycle()`, but not a Medium and not a leak. |
| CAM-7 | **ADJUSTED, Medium→Low** | `CameraManager.setTorchMode` is thread-safe and `isStreaming` is a plain boolean read. Posting to the handler is a fine consistency fix, not a correctness one. |
| CAM-8 | **CONFIRMED** (Low) | Fallback throws inside `FlashlightManager`'s catch → logged no-op. Dead fallback, remove. |
| CAM-9 | **ADJUSTED — dead code** | `CamService.snapshot()` has **zero callers** (grep: only the definition). Delete it instead of fixing it. Also the stated mechanism is inverted: the "did not call startForeground" watchdog applies to `startForegroundService`, not plain `startService`; the actual risk of the current code would be a background-start `IllegalStateException`. Moot once deleted. |
| CAM-10 | **CONFIRMED** (Low) | Add to the list: `streamPrevLuma` (`CamService.kt:300`) is written on the ImageReader thread and nulled from the main thread in `tryStartStream` without `@Volatile` — that reset exists specifically to avoid cross-session frame diffs, and its visibility isn't guaranteed. |
| CAM-11 | **CONFIRMED** (Low) | Agree on `scope.cancel()`; the `ws` overwrite is near-noise (the previous socket is already closed/failed when the latch completes). |
| CAM-12 | **REFUTED** | `onOpen` resets `delayMs = 1000L`. After any successful connection the next reconnect delay is 1s; doubling only accumulates across *consecutive failed* attempts, which is intended backoff. The described scenario (slow reconnect after a routine restart) cannot occur. |
| CAM-13 | **CONFIRMED** (Info) | Agree, including the acceptability assessment; `EncryptedSharedPreferences` optional. |

### ViewApp

| ID | Verdict | Notes |
|----|---------|-------|
| VIEW-1 | **CONFIRMED** (High) | Independently found, same fix (`cancelAndJoin`, factory disposed last). The only in-app native-crash risk. |
| VIEW-2 | **ADJUSTED, High→Low** | Narrower than claimed: all mutations already run in `scope` coroutines on `Dispatchers.Main`, and both restart paths do `cancelAndJoin()` before `dispose()` — a happens-before edge covering the IO-thread `peerConnection = pc` write. Residual: the `connectionState()` pre-check in `restartWebRtcIfNeeded` can read a stale reference → worst case a redundant rebuild. `@Volatile` on both fields is the whole fix; the proposed Mutex/actor is overkill. |
| VIEW-3 | **REFUTED** | libwebrtc `VideoTrack.addSink` is a documented no-op for an already-attached sink ("We allow calling addSink() with the same sink multiple times", guarded by `if (!sinks.containsKey(sink))` — verified in upstream `VideoTrack.java`). The code comment saying "addSink dedupes" is correct. No `sinkAdded` flag needed. |
| VIEW-4 | **ADJUSTED, Medium→Low** | The stop→start overlap window is milliseconds and the consequence is a transient second `AudioRecord`, not a reliable crash. The `getMinBufferSize <= 0` validation is a fair hardening nit. |
| VIEW-5 | **REFUTED** (mostly) | `handleServerMsg` and `onConnected` immediately `scope.launch` (Main dispatcher) for anything touching WebRTC state; `sendText` is thread-safe; `setStatus` wraps `runOnUiThread`. There is no off-main WebRTC mutation today. |
| VIEW-6 | **CONFIRMED** (Low) | Infinite animator keeps Choreographer ticking post-destroy; single-activity app so the leak is bounded, still worth `cancel()`. |
| VIEW-7 | **CONFIRMED** (Low) | Note DISCONNECTED often self-heals and transitions to FAILED when it doesn't — polish, not a bug. |
| VIEW-8 | **ADJUSTED (noise)** | OkHttp idle threads exit after 60s keep-alive; nothing persists "until GC". No action. |
| VIEW-9 | **CONFIRMED** (Low) | Agree — offline and empty are indistinguishable. |
| VIEW-10 | **CONFIRMED** (Low) | Both apps ignore `send()`'s boolean (OkHttp docs: *attempts to enqueue*, returns false when the queue is full/socket canceled). The optimistic pref flip compounds it. |
| VIEW-11 | **CONFIRMED** (Low) | Duplicate of CAM-11. |
| VIEW-FP | **AGREE** | Correct false-positive analysis; `CMD:MOTION:snap=…` is the hub→cam hop. |
| VIEW-SEC | **AGREE** | Matches my verification: tailnet-boundary design implemented consistently; publish password gitignored **and never present in git history** (pre-gitignore `mediamtx.yml` revisions had no auth block); `websocket.Accept(nil)` rejects cross-origin by default; ffmpeg args whitelist-gated. |

**Scorecard:** of 30 findings — 17 confirmed, 7 adjusted (severity/mechanism), 5 refuted (including the sole Critical), 1 plausible-unverified. The report also missed the one genuine Critical-class bug in the codebase (CG-1) and everything in Part 2.

## Part 2 — Findings not in the OpenCode report

### Server — Critical

#### CG-1 — Stale-cam kick strands `streaming=true`; replacement cam never told to stream · **Critical (confirmed by failing test)** · ✅ **FIXED**
- **Location:** `hub.go:87-104` (register), `hub.go:109-117` (unregister)
- **Problem:** When a new cam connection registers while the old one is still half-open (cam app crash/restart or network blip; the server's ping loop can take up to ~30s to reap the old socket), the register path kicks the stale cam (`old.cancel()`) and sets `h.cam = c` — but leaves `h.streaming = true`. The old connection's unregister then skips the reset because `h.cam == c` is false. `ensureStreamLocked` sees `want == streaming` and **never sends `CMD:START_STREAM`**. No future transition fires while viewers stay connected, so viewers sit on "Waiting for stream (retry N)…" indefinitely and security/motion recording records nothing.
- **Evidence:** Repro test written and run (scratch copy, `stale_cam_test.go`): cam + viewer connect, START drained, a second cam connection registers before the first unregisters → new cam receives no START_STREAM within 1s. Test **fails on current code**. The existing `TestCamReconnectRestartsStream` only covers close-then-reconnect, where unregister runs first and clears the flag.
- **Fix:** In the `RoleCam` register branch, set `h.streaming = false` before `ensureStreamLocked()`. Safe w.r.t. the "never re-send START to a streaming cam" invariant: the cam's `tryStartStream` guards on `streamer.isStreaming`, which RootEncoder 2.7.2 sets synchronously in `startStream`, so a redundant START to a still-publishing cam is a no-op on the phone. Add the repro test to `hub_test.go`.

### Server — the motion/security recorder state machine (shares a root cause with GO-1)

Recorder on/off is decided ad-hoc in four places instead of derived from desired state the way `ensureStreamLocked` derives the stream. Recommended shape: one `ensureRecorderLocked()` with `wantRec = security.On || motionActive`, security params winning when both apply. That one function subsumes GO-1 and CG-2/3/4/5.

#### CG-2 — `motionExpired` races a fresh motion event; recording stops despite ongoing motion · High · ✅ **FIXED**
- **Location:** `hub.go:266-269` (`onMotionEvent` re-arm), `hub.go:274-285` (`motionExpired`)
- **Problem:** Go-stdlib-documented hazard (`time.Timer.Stop`: *"returns false if it had already expired… For func-based timers, Stop does not wait for the function to complete"*). If `EVENT:MOTION` arrives as the idle timer fires, `Stop()` loses, the already-running `motionExpired` blocks on `h.mu`, runs **after** the re-arm, stops the recorder and clears `motionActive` — despite motion moments ago. The cam's 5s `EVENT:MOTION` throttle then delays the restart: split clip with a gap.
- **Fix:** Generation counter incremented on every re-arm; `motionExpired` no-ops if its generation is stale (the Go docs' "coordinate with f explicitly"). Or compare a `lastMotionAt` timestamp under the lock.

#### CG-3 — `motionExpired` leaves `motionActive=true` when security mode is on; later `SECURITY_OFF` strands the stream · Medium · ✅ **FIXED**
- **Location:** `hub.go:277-281`, `hub.go:202-212`
- **Problem:** `motionExpired` only clears `motionActive` when `!security.On`. Sequence: motion fires while security on → timer expires → `motionActive` stays true → `CMD:SECURITY_OFF` → `ensureStreamLocked` computes `want = motionActive = true` → cam streams to nobody, recorder off, until a future motion cycle happens to clear it (never, if motion rec is disabled meanwhile).
- **Fix:** `motionExpired` always clears `motionActive`; whether the *recorder* keeps running is `security.On`'s call (falls out of `ensureRecorderLocked`).

#### CG-4 — `SECURITY_OFF` kills an in-flight motion recording window · Medium · ✅ **FIXED**
- **Location:** `hub.go:202-212`
- **Problem:** `CMD:SECURITY_OFF` calls `recorder.Stop()` unconditionally. If `motionRec` is on and `motionActive` is true (cat currently moving), turning security mode off truncates the motion clip mid-window.

#### CG-5 — `MOTION_REC_ON` param change ignored while motion is active · Low · ✅ **FIXED**
- **Location:** `hub.go:192-201`
- **Problem:** Updates `recFps`/`recRes` but never restarts the recorder, so new params silently apply only from the next motion cycle.

### Server — smaller

#### CG-6 — `ClipRecorder` is dead code · Low
- **Location:** `recorder.go:183-292`, `hub.go:46`, `main.go:74`
- **Problem:** Constructed and assigned to `hub.clip`, but no command path ever calls `Start` — ~110 lines dead, plus ViewApp's `clipLabel` still special-cases `clip_manual_`/`clip_motion_` filenames nothing produces. Remove (or re-wire manual clips).

#### CG-7 — Snapshot filename collision · Low
- **Location:** `hub.go:380` — `%d.jpg` from `UnixMilli`; two snapshots in the same millisecond silently overwrite. Add a counter or use nanos.

#### CG-8 — `saveSecurityState` is a non-atomic write · Low
- **Location:** `hub.go:339` — crash mid-`WriteFile` corrupts `.security.json`; `loadSecurityState` then silently falls back to defaults (security mode lost across the very restart it exists to survive). Write temp + `os.Rename`.

#### CG-9 — `.remuxtmp` orphans never pruned; prune aborts on first error · Low
- **Location:** `recorder.go:316` (tmp suffix), `recorder.go:364-387` (`pruneRecordings` only matches `.mp4`/`.jpg` and `return err` on the first failed remove, skipping the rest of the directory).

#### CG-10 — `http.FileServer` directory listings · Info
- **Location:** `main.go:106` — `/snapshots/` and `/recordings/` render full directory indexes (including `.security.json`, see GO-2). Tailnet-only; wrap with an index-suppressing handler if tightening.

### CamApp

#### CC-1 — Stream-retry vs motion-watch camera contention (livelock) · High · ✅ **FIXED**
- **Location:** `CamService.kt:97-99` (retry path), `CamService.kt:275-296` (poll loop), `CamService.kt:165-174` (`tryStartStream`)
- **Problem:** The START_STREAM command path calls `stopMotionWatch()` before starting; the **error-retry path does not** (`onError` → `postDelayed({ tryStartStream() }, 5000)`). Meanwhile the 2s poll loop re-binds CameraX whenever `!isStreaming && detect on`. One failed stream start with motion detection enabled → CameraX grabs the camera → the retry opens it via Camera2. Per Camera2 docs, the second open either fails `CAMERA_IN_USE` or force-disconnects the current client (`CAMERA_DISCONNECTED`, "the camera service has shut down the connection due to a higher-priority access request"), and disconnected clients are told to retry on `onCameraAccessPrioritiesChanged` — so the two stacks can evict each other in a loop and the stream may never come back without operator intervention.
- **Fix:** `tryStartStream()` calls `stopMotionWatch()` first (it already runs on the main handler), and/or the poll loop refuses to bind while `shouldStream` is true.

#### CC-2 — PTT audio playback blocks the WebSocket reader thread · Medium · ✅ **FIXED**
- **Location:** `CamService.kt:109` (`onBinary = { pcm -> pttPlayer.write(pcm) }`), `PttPlayer.kt:30`
- **Problem:** OkHttp delivers all WS callbacks on the connection's reader thread and documents that *"implementations must return before further websocket messages will be delivered"* / the reader thread "must never run application-layer code". `AudioTrack.write` in MODE_STREAM blocks when the buffer is full, so sustained push-to-talk delays every queued command (`CMD:STOP_STREAM`, motion config, …) behind real-time audio drain.
- **Fix:** Hand PCM to a dedicated playback thread/queue; the WS callback just enqueues.

#### CC-3 — Settings are read once at service creation · Low
- **Location:** `CamService.kt:82-90`
- **Problem:** WS URL, mediamtx host, and RTSP credentials are captured in `onCreate` closures; changing them in Settings does nothing until the service is manually restarted. Compounds CAM-2: after fixing a wrong password the user must also know to restart. (ViewApp's `reconnectIfConfigChanged` pattern is the model.)

#### CC-4 — `WsClient.ws` not `@Volatile` (both apps) · Low
- **Location:** `CamApp WsClient.kt:20`, `ViewApp WsClient.kt:20`
- **Problem:** `ws` is written on the reconnect coroutine (IO) and read by `sendText`/`sendBinary` from other threads with no happens-before edge → callers can briefly address a dead socket after a reconnect. Pairs with VIEW-10 (ignored `send()` return) for fully silent drops.

### ViewApp

#### CV-1 — `setRemoteDescription` failure is a dead end · Low
- **Location:** `MainActivity.kt:345-353`
- **Problem:** `onSetFailure` sets status "Stream failed" and stops; no retry, no `restartStream()`. Recovery requires a WS reconnect or app restart.

## Combined remediation priority

1. ✅ **CG-1** — server: reset `streaming` on cam register (one line + the repro test). Biggest real-world outage mode.
2. ✅ **CAM-2** — cam: `stop()` in `onAuthError` (one line). Unsticks the wrong-password dead end.
3. ✅ **CC-1** — cam: unbind motion watch in `tryStartStream`. Prevents camera livelock.
4. ✅ **VIEW-1** — viewer: `cancelAndJoin` + factory-last in `onDestroy`. Native-crash stopper.
5. ✅ **GO-1 + CG-2/3/4/5** — hub: one `ensureRecorderLocked()` desired-state function + generation counter for the idle timer. Fixes five bugs in one refactor; add regression tests alongside the existing stream-invariant ones.
6. **CAM-3, CAM-5, CC-2** — teardown guards and PTT off the reader thread.
7. Low/Info items (GO-2 dotfile guard, CG-6..10, CC-3/4, CV-1, confirmed VIEW lows) as cleanup.

**Explicitly do not act on:** CAM-1, CAM-6 (as a leak), CAM-12, VIEW-3, VIEW-5, VIEW-8, VIEW-FP — refuted above with sources; "fixing" CAM-1 or VIEW-3 would add state-machine complexity to defend against behavior the libraries already prevent.

## Verification notes
- `go test ./...` passes on current `main`; the CG-1 repro test fails (bug present), run in a scratch copy so the working tree stayed untouched.
- Library claims checked against: RootEncoder 2.7.2 `StreamBase.kt` (tag source), libwebrtc `VideoTrack.java` (upstream), OkHttp 5.x WebSocket docs, Go 1.25 `time` docs, Camera2 `CameraManager`/`CameraAccessException` docs, coder/websocket `Accept` docs, mediamtx auth docs.
- Secret hygiene: `server/mediamtx.yml` and `server/.env` gitignored **and** confirmed absent from all git history (pre-gitignore revisions of `mediamtx.yml` contained no credentials).

---

# Fix Status — 2026-07-11

| Bug | Status | Fix | Regression test |
|-----|--------|-----|-----------------|
| CG-1 (Critical) | ✅ Fixed | `hub.go`: cam register clears `streaming` so `ensureStreamLocked` re-sends START_STREAM to a replacement cam | `TestStaleCamKickStillGetsStartStream` |
| GO-1 (Medium) | ✅ Fixed | `hub.go`: recorder on/off/params now derived by one `ensureRecorderLocked()` (mirrors `ensureStreamLocked`); no restart when params unchanged | `TestMotionWhileSecurityOnDoesNotRestartRecorder` |
| CG-2 (High) | ✅ Fixed | `hub.go`: `motionGen` generation counter; a stale idle-timer expiry that lost the race to fresh motion is ignored | `TestStaleMotionExpiryIgnored` |
| CG-3 (Medium) | ✅ Fixed | `hub.go`: `motionExpired` always clears `motionActive`; stream/recorder need-state re-derived | `TestSecurityOffAfterMotionExpiryStopsStream` |
| CG-4 (Medium) | ✅ Fixed | `hub.go`: `SECURITY_OFF` defers to `ensureRecorderLocked`, which keeps recording through an active motion window | `TestSecurityOffKeepsActiveMotionRecording` |
| CG-5 (Low) | ✅ Fixed | `hub.go`: `MOTION_REC_ON` re-derives recorder params immediately | `TestMotionRecParamChangeAppliesWhileActive` |
| CAM-2 (High) | ✅ Fixed | `RtspStreamer.kt`: `onAuthError` now calls `stop()`, clearing the synchronously-set `isStreaming` so retries work | — (not unit-testable without a device; verified by build) |
| CC-1 (High) | ✅ Fixed | `CamService.kt`: `tryStartStream` unbinds motion watch before starting, covering the error-retry and rotation-restart paths | — (device-level; verified by build) |
| VIEW-1 (High) | ✅ Fixed | `MainActivity.kt`: `onDestroy` does `runBlocking { webRtcJob?.cancelAndJoin() }` and cancels the scope before disposing native WebRTC objects | — (device-level; verified by build) |
| CAM-3 (Medium) | ✅ Fixed | `CamService.kt`: volatile `destroyed` flag set first in `onDestroy` gates every WS callback; `PttPlayer.write` is release-safe (flag + catch); `WsClient.disconnect` cancels its scope | — (device-level; verified by build) |
| CAM-5 (Medium) | ✅ Fixed | `CamService.kt`: provider-future listener bails on `destroyed` and on `shouldStream` (not just `isStreaming`); `motionWatchStarting` debounces the 2s poll loop; `bindToLifecycle` wrapped in try/catch with cleanup | — (device-level; verified by build) |
| CC-2 (Medium) | ✅ Fixed | `PttPlayer.kt`: dedicated playback thread drains a bounded queue (drop-oldest at 64 chunks ≈ 2.5s); the WS reader thread only enqueues, so PTT audio can no longer stall control commands | — (device-level; verified by build) |

Also hardened while there: `loadSecurityState` now validates persisted fps/res against the whitelist before acting on a corrupted/hand-edited `.security.json`.

**Verification:** `go test ./...` green (includes the 6 new regression tests plus all pre-existing stream-invariant tests); `CamApp` and `ViewApp` `assembleDebug` + `testDebugUnitTest` green.

**Not yet fixed:** the Low/Info cleanup items only (GO-2 dotfile guard, CG-6..10, CC-3/4, CV-1, VIEW lows). CAM-3, CAM-5, and CC-2 were fixed in follow-up passes (see table) — every High/Medium finding from both reports is now fixed or refuted.

**Deploy reminder:** fixes are inert until redeployed — `cd server && docker compose up -d --build`, then reinstall both APKs.

---

# Post-deploy field finding — 2026-07-11

#### CC-5 — In-stream motion tap never attaches; every motion clip is a fixed ~12s · **Critical** · ✅ **FIXED**
- **Symptom (user report):** motion recording is spotty — delayed start, then exactly ~12s of footage, then stop, even with continuous motion.
- **Location:** `RtspStreamer.kt` `start()`/`attachMotionListener()`
- **Root cause (source-verified + reproduced on device):** the motion-frame tap was attached *before* `prepareVideo`, but RootEncoder 2.7.2's `VideoSource.width/height` are `0` until `prepareVideo` calls `init()`. `Camera2Source.addImageListener` immediately builds an `ImageReader` from those dims → `IllegalArgumentException: The image dimensions must be positive` (captured live in logcat). The catch swallowed it and `motionListenerAttached` was set `true` anyway, so the cam was blind while streaming: no `EVENT:MOTION` ever re-armed the hub's idle window, so every motion recording ran exactly `motionIdleDuration + motionSpinupGrace` = 15s — ≈12s of footage after ~3s stream/ffmpeg spin-up. Server logs showed back-to-back windows of exactly 15s with a new one opening 1s after the last closed (motion clearly continuous). This subsumes the earlier CAM-4 hypothesis (the session-rebuild mechanism was refuted — `Camera2ApiManager` keeps the ImageReader across restarts; the listener simply never attached at all).
- **Fix:** attach the tap after `prepareVideo` (dims valid) and before `startStream` (camera not yet running, reader joins the session cleanly); `attachMotionListener` now returns success and the flag is only set when it actually attached, so a failure retries on the next start.
- **Expected behavior after fix:** continued motion re-arms the 10s window via in-stream detection — clips run as long as motion continues. The ~3s missing at the start of a cold-start clip is architectural (camera handoff + RTSP + ffmpeg attach); use security mode (continuous stream) if pre-roll matters.
