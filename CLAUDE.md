# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SlopIpCam is a personal (cat-monitoring) IP-camera system with three components that only ever talk over a Tailscale tailnet (`100.x.x.x`):

- **CamApp/** — Android/Kotlin app on a Pixel 6. Captures camera, publishes RTSP to the server, obeys control commands.
- **ViewApp/** — Android/Kotlin app on a Pixel 9. Plays the live stream (WebRTC/WHEP), sends control commands, browses snapshots/recordings.
- **server/** — Docker Compose stack on the host machine (`virtuajrd`, `100.112.94.72`, Arch + **rootless** Docker): `mediamtx` (RTSP→WebRTC relay) + `ctrl-server` (custom Go WebSocket/HTTP hub).

There is no README; `projectHistory.md` at the repo root is the historical design log and is worth reading for hard-won gotchas.

## The two planes

Nothing streams peer-to-peer; everything goes through the server.

- **Video plane:** CamApp publishes `rtsp://<server>:8554/cam` → mediamtx → ViewApp pulls WebRTC via WHEP at `http://<server>:8889/cam/whep`. mediamtx does the RTSP↔WebRTC transcoding; neither app speaks the other's protocol.
- **Control plane:** both apps hold a WebSocket to `ctrl-server` at `ws://<server>:8080/ws?role=cam|viewer[&res=<res>]`. `ctrl-server` is a hub that routes text/binary messages between the single cam and N viewers. It never touches the video.
- **Files:** `ctrl-server` also serves snapshots and recordings over HTTP on `:8080` (`/snapshots`, `/recordings`, `/snapshots/...`, `/recordings/...`). Range requests work (seekable playback); DELETE is path-jailed.

## ctrl-server hub model (server/ctrl-server/)

`hub.go` is the heart. Read it before changing any control behavior.

- **Roles** come from the `?role=` query param (`client.go`); invalid role → connection closed.
- **Message routing:** viewer→cam messages are forwarded verbatim **except** prefixes `CMD:SECURITY_` and `CMD:MOTION_`, which the hub handles itself (`handleServerCmd`). cam→viewer text is broadcast to all viewers; cam→server binary is a snapshot JPEG the hub saves and announces as `SNAPSHOT:/snapshots/...`.
- **Stream need-state (`ensureStreamLocked`):** the cam should stream exactly when `viewers>0 || security.On || motionActive`. The hub tracks a `streaming` bool and only sends `CMD:START_STREAM`/`CMD:STOP_STREAM` on a real idle↔active transition. **Never re-send START_STREAM to an already-streaming cam** — it restarts the RTSP publish and freezes the viewer for ~5s. This invariant is load-bearing; there is a regression test for it.
- **Motion recording** (`onMotionEvent`/`motionExpired`): `EVENT:MOTION` from the cam gates the continuous low-fps recorder on, re-arming a `motionIdleDuration` (10s) timer each event; it stops after 10s of no motion. Motion *snapshots* and motion *recording* are independent — the hub sends `CMD:MOTION:snap=<0|1>:detect=<0|1>` so the cam detects whenever either is wanted but only saves snaps when asked.
- **Security state** persists to `RECORD_DIR/.security.json` and is restored on boot (`loadSecurityState`), so recording survives a server restart.

`recorder.go` supervises `ffmpeg` processes: the security **Recorder** pulls RTSP into low-fps H.265 hourly segments (fragmented mp4 while writing = crash-safe + playable live; a background sweep remuxes finished segments to faststart mp4 so they seek, and generates `<name>.jpg` thumbnails); `pruneRecordings` enforces retention. Only whitelisted fps/resolution values (`allowedFps`/`allowedRes`) ever reach ffmpeg.

## Commands

### Server
```bash
cd server
docker compose up -d --build            # build + (re)start; --build is required after Go changes
docker compose logs -f ctrl-server      # tail logs
docker compose restart ctrl-server
```
`ctrl-server` env (set in `docker-compose.yml`): `LISTEN_ADDR`, `SNAPSHOT_DIR`, `RECORD_DIR`, `RTSP_URL`, `RECORD_CRF`, `SNAPSHOT_RETENTION_DAYS`, `RECORD_RETENTION_DAYS`, `TZ`.

Go tests:
```bash
cd server/ctrl-server
go test ./...
go test -count=1 -run TestMotionRecStartsAndStopsStream -v   # single test (bypass cache)
```
`docker`, `go`, `gradle`, and `adb` all need `dangerouslyDisableSandbox`.

### Android apps
```bash
cd CamApp      # or ViewApp
./gradlew assembleDebug
./gradlew testDebugUnitTest                                   # JVM unit tests
./gradlew testDebugUnitTest --tests '*MotionDetectorTest'     # single test
adb -s <ip:port> install -r app/build/outputs/apk/debug/app-debug.apk
```
Both apps: `compileSdk`/`targetSdk` 34, `minSdk` 29, packages `com.slopIpCam.cam` / `com.slopIpCam.view`.

### Deploying / device access
- **Redeploy is a separate step from committing.** After changing server or app code you must rebuild+restart the container and reinstall the APKs; a stale running build looks like "the fix didn't work."
- Phones are reached by **wireless adb over Tailscale**. Ports rotate on every toggle of Wireless Debugging and mDNS doesn't cross Tailscale, so the user supplies the current `IP:port` — use it directly, don't port-scan. `adb connect <ip:port>` then `adb -s <ip:port> ...`.

## Config & security invariants

- **RTSP publish password** lives only in gitignored `server/mediamtx.yml` (`authInternalUsers`; template is `mediamtx.yml.example` with a placeholder) and must be typed into CamApp Settings. CamApp has no baked-in default and fails closed. Never commit the real password. RTSP read / WHEP / DELETE are unauthenticated **by design** — the tailnet is the security boundary.
- **mediamtx (v1.18.2) does not expand `${VAR}` in `mediamtx.yml` and ignores `MEDIAMTX_*` env overrides** — IPs are hardcoded there and must be kept in sync with `server/.env` (`TAILSCALE_IP`, `TZ`). `docker-compose.yml`'s `${TAILSCALE_IP}` interpolates from `.env` (gitignored), *not* from the `environment:` block.
- **Rootless Docker:** container root = host uid 1000 (`jrd`). Bind-mount targets (`/data/snapshots`) must be owned by `jrd` or writes fail silently.
- **Android 14+:** a camera/mic foreground service cannot start from `BOOT_COMPLETED`. `BootReceiver` posts a tap-to-start notification instead. CamApp otherwise auto-starts its service when opened.
- `git add -A` is unsafe here: the user's personal shell dotfiles (`.bashrc`, `.zshrc`, `.gitconfig`, …) sit untracked in the repo root. Always `git add` explicit paths.

## Rotation & orientation

The camera phone rotates mid-stream. This is a recurring source of bugs across all three components: the cam re-seeds encoder rotation from the sensor, motion-snapshot orientation is derived from the sensor angle (not display rotation, which is frozen for a Service), the server's ffmpeg scales the short side so portrait/landscape both keep detail, and ViewApp releases+re-inits its `SurfaceViewRenderer` on every stream restart to avoid a stale-aspect crop. When touching video sizing or rotation, check all three sides.
