# AGENTS.md — SlopIpCam

Personal cat-monitoring IP-cam system. **Read `CLAUDE.md` fully before changing any component** — it is the authoritative design log. This file is the fast-reference companion.

## Shape of the repo (3 components, only talk over a Tailscale tailnet)
- `CamApp/` — Android/Kotlin app on a Pixel 6. Camera side. Entrypoint: `com.slopIpCam.cam` `MainActivity` → camera foreground service.
- `ViewApp/` — Android/Kotlin app on a Pixel 9. Viewer side. Entrypoint: `com.slopIpCam.view`.
- `server/` — Docker Compose stack: `mediamtx` (RTSP→WebRTC relay) + `ctrl-server` (custom Go WebSocket/HTTP hub in `server/ctrl-server/`). Hub logic lives in `hub.go` — read it before any control change.
- Nothing streams peer-to-peer; everything routes through the server.

## Commands
Server (Go ctrl-server):
```
cd server/ctrl-server && go test ./...
go test -count=1 -run TestMotionRecStartsAndStopsStream -v   # single test, bypass cache
cd server && docker compose up -d --build   # --build REQUIRED after Go changes
docker compose restart ctrl-server / docker compose logs -f ctrl-server
```
Android apps (run from `CamApp/` or `ViewApp/`):
```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests '*MotionDetectorTest'   # single test
adb -s <ip:port> install -r app/build/outputs/apk/debug/app-debug.apk
```
Sandbox: `docker`, `go`, `gradle`, `adb` need `dangerouslyDisableSandbox`.

## Load-bearing invariants (break these and things silently rot)
- Never re-send `CMD:START_STREAM` to an already-streaming cam — it restarts RTSP publish and freezes the viewer ~5s. There is a regression test for this.
- `mediamtx.yml` does NOT expand `${VAR}` and ignores `MEDIAMTX_*` env (v1.18.2). IPs are hardcoded there; keep them in sync with `server/.env` (`TAILSCALE_IP`, `TZ`).
- RTSP publish password lives ONLY in gitignored `server/mediamtx.yml` (`authInternalUsers`); template is `mediamtx.yml.example`. Never commit the real password. RTSP read / WHEP / DELETE are unauthenticated by design (the tailnet is the boundary).
- Rootless Docker: container root = host uid 1000 (`jrd`). Bind-mount targets (e.g. `/data/snapshots`) must be owned by `jrd` or writes fail silently.
- Server host = `virtuajrd` `100.112.94.72` (Arch + rootless). `100.91.132.76` is vm-slave, NOT the server — don't point CamApp there.
- Git: do NOT `git add -A`. Untracked personal dotfiles (`.bashrc`, `.gitconfig`, …) sit in repo root. Add explicit paths only.

## Testing / debugging helpers
- `wsctl` drives the ctrl-server over WS for manual control tests, e.g. `wsctl ws://100.112.94.72:8080/ws?role=viewer CMD:SECURITY_ON:1:480p 30`.
- Phones are reached by wireless adb over Tailscale; the connect port rotates every Wireless-Debugging toggle and mDNS doesn't cross Tailscale, so the user supplies the current `IP:port` — use it directly, don't port-scan.
- ViewApp must keep `ACCESS_NETWORK_STATE` in the manifest or libwebrtc aborts the process (native RTC_CHECK SIGABRT).
