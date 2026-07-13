---
name: project-slopipcam
description: "SlopIpCam project — native Android IP camera system using Pixel 6 as camera, Pixel 9 as viewer, home server as relay"
metadata: 
  node_type: memory
  type: project
  originSessionId: 57c7581c-8d3d-49d2-b78d-c257ff9bfd91
---

Remote IP camera to monitor cat. Pixel 6 (home WiFi) as camera, Pixel 9 (5G/remote) as viewer. Home server (OpenSUSE Leap + Docker) on same LAN as Pixel 6. Both phones have Tailscale. Cloudflare tunnel also available but not used for this (Tailscale handles all traffic).

**Why:** Personal cat monitoring project, not for commercial use.

**How to apply:** Architecture uses mediamtx (Docker) for RTSP→WebRTC relay, custom Go ctrl-server for WebSocket message routing. All traffic Tailscale-only (Tailscale IP `100.x.x.x`).

Repo: /home/jrd/codes/slopIpCam

Architecture:
- `server/` — Docker Compose: mediamtx + ctrl-server (Go)
- `CamApp/` — Android Kotlin app, Pixel 6 (camera side)
- `ViewApp/` — Android Kotlin app, Pixel 9 (viewer side)

Key ports (Tailscale only):
- 8554 RTSP (CamApp → mediamtx)
- 8889 WebRTC HTTP (mediamtx → ViewApp, WHEP protocol)
- 8080 WebSocket + HTTP snapshots (ctrl-server)

Tech stack confirmed (post-research):
- RootEncoder 2.7.2 — `RtspStream(context, connectChecker)` no SurfaceView
- io.github.webrtc-sdk:android 144.7559.05 — WHEP client
- github.com/coder/websocket v1.8.12 — Go WebSocket (gorilla archived)
- mediamtx WHEP endpoint: `http://<host>:8889/<stream>/whep`

Gotchas (learned 2026-07-07):
- mediamtx cannot expand `${VAR}` in mediamtx.yml AND ignores `MEDIAMTX_*` env overrides (v1.18.2) — addresses hardcoded in mediamtx.yml, keep in sync with server/.env
- compose `${TAILSCALE_IP}` interpolates from server/.env (gitignored), not from `environment:` entries
- RTSP publish auth: user slopcam; password lives ONLY in gitignored server/mediamtx.yml (`authInternalUsers`, template: mediamtx.yml.example) and must be typed into CamApp Settings → rtsp_pass (no default, fails closed with "Set RTSP password in Settings" notification)
- Protocol: viewer passes `?role=viewer&res=<res>` on WS connect; hub sends CMD:START_STREAM:<res> to cam on viewer register and on cam (re)register with viewers waiting
- Android 14+: camera/mic FGS cannot start from BOOT_COMPLETED — BootReceiver posts tap-to-start notification instead
- Server = virtuajrd `100.112.94.72` (this machine, Arch + ROOTLESS docker). `100.91.132.76` is vm-slave, NOT the server — user once pointed CamApp there by mistake
- Rootless docker: container root = host uid 1000 (jrd); bind mounts owned by real root are unwritable → /data/snapshots must be owned by jrd
- ViewApp needs ACCESS_NETWORK_STATE in manifest — libwebrtc NetworkMonitor aborts the process (native RTC_CHECK SIGABRT on network_thread) without it
- Debugging phones: wireless adb over Tailscale works — user gives pairing code+port from Wireless debugging screen, `adb pair`, then port-scan 30000-49999 for the connect port (mDNS doesn't cross Tailscale). Debug builds: read/write app prefs via `run-as com.slopIpCam.cam cat shared_prefs/...`
- CamApp `mediamtx_host` pref must be bare IP (code appends :8554)

Features:
- On-demand streaming (CamApp idle when ViewApp not connected)
- Flashlight toggle via WebSocket CMD
- PTT audio: Pixel 9 mic → PCM → WebSocket → CamApp AudioTrack
- Motion detection (game camera style): snapshot JPEG on motion, rate-limited, saved to server /data/snapshots/
- No push notifications (no Firebase — can add ntfy.sh later)
