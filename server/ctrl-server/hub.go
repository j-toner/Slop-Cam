package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const defaultResolution = "720p"

// motionIdleDuration is how long motion recording stays on after the last
// detected motion before it auto-stops. A package var so tests can shorten it.
var motionIdleDuration = 10 * time.Second

type securityState struct {
	On        bool   `json:"on"`
	Fps       string `json:"fps"`
	Res       string `json:"res"`
	MotionRec bool   `json:"motion_rec"`
}

type Hub struct {
	mu          sync.RWMutex
	cam         *Client
	viewers     map[*Client]bool
	lastRes     string
	register    chan *Client
	unregister  chan *Client
	fromCam     chan Message
	fromViewer  chan Message
	snapshotDir string
	recorder    *Recorder     // nil when recording is unavailable (tests)
	clip        *ClipRecorder // nil in tests (manual clips, if any)
	stateFile   string        // "" = don't persist security state
	security    securityState
	// stream need-state: the cam streams while a viewer, security mode, or
	// motion recording needs it. Tracked so we only send START/STOP_STREAM
	// on a real idle<->active transition, never to "refresh" an already
	// running stream (which restarts the publish and freezes the viewer).
	streaming    bool
	motionSnaps  bool // viewer wants cam to save snapshots on motion
	motionRec    bool // viewer wants motion-triggered (security-mode) recording
	motionActive bool // motion has currently forced security recording on
	recFps       string
	recRes       string
	motionTimer  *time.Timer
}

type Message struct {
	client  *Client
	data    []byte
	msgType websocket.MessageType
}

func newHub(snapshotDir string) *Hub {
	return &Hub{
		viewers:     make(map[*Client]bool),
		lastRes:     defaultResolution,
		register:    make(chan *Client, 8),
		unregister:  make(chan *Client, 8),
		fromCam:     make(chan Message, 64),
		fromViewer:  make(chan Message, 64),
		snapshotDir: snapshotDir,
	}
}

func startStreamCmd(res string) []byte {
	return []byte(fmt.Sprintf("CMD:START_STREAM:%s", res))
}

func (h *Hub) run() {
	for {
		select {
		case c := <-h.register:
			h.mu.Lock()
			if c.role == RoleCam {
				if old := h.cam; old != nil && old != c {
					old.cancel() // kick stale cam connection
				}
				h.cam = c
				log.Println("cam registered")
				h.applyMotionToCamLocked()
				h.ensureStreamLocked()
			} else {
				h.viewers[c] = true
				if c.res != "" {
					h.lastRes = c.res
				}
				log.Println("viewer registered")
				h.ensureStreamLocked()
			}
			h.mu.Unlock()

		case c := <-h.unregister:
			h.mu.Lock()
			if c.role == RoleCam {
				if h.cam == c {
					h.cam = nil
					// the physical stream is gone; clear the flag so a
					// reconnecting cam gets a fresh START_STREAM instead of
					// being treated as already streaming
					h.streaming = false
					log.Println("cam unregistered")
				}
			} else {
				if _, ok := h.viewers[c]; ok {
					delete(h.viewers, c)
					close(c.send)
					log.Println("viewer unregistered")
					h.ensureStreamLocked()
				}
			}
			h.mu.Unlock()

		case msg := <-h.fromViewer:
			if msg.msgType == websocket.MessageText {
				cmd := string(msg.data)
				if strings.HasPrefix(cmd, "CMD:SECURITY_") ||
					strings.HasPrefix(cmd, "CMD:MOTION_") {
					h.handleServerCmd(cmd)
					continue
				}
			}
			h.mu.RLock()
			cam := h.cam
			h.mu.RUnlock()
			if cam == nil {
				continue
			}
			cam.sendMsg(msg.msgType, msg.data)

		case msg := <-h.fromCam:
			if msg.msgType == websocket.MessageText && string(msg.data) == "EVENT:MOTION" {
				h.onMotionEvent()
			}
			if msg.msgType == websocket.MessageBinary {
				path, err := h.saveSnapshot(msg.data)
				if err != nil {
					log.Printf("save snapshot: %v", err)
					continue
				}
				rel, err := filepath.Rel(h.snapshotDir, path)
				if err != nil {
					log.Printf("snapshot rel path: %v", err)
					continue
				}
				notify := fmt.Sprintf("SNAPSHOT:/snapshots/%s", filepath.ToSlash(rel))
				h.broadcastViewers(websocket.MessageText, []byte(notify))
			} else {
				h.broadcastViewers(websocket.MessageText, msg.data)
			}
		}
	}
}

// handleServerCmd owns viewer commands addressed to the server rather than
// the cam: security-camera mode, motion-triggered recording, and manual
// clip recording.
func (h *Hub) handleServerCmd(cmd string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	switch {
	case cmd == "CMD:MOTION_OFF":
		h.motionSnaps = false
		h.applyMotionToCamLocked()
	case cmd == "CMD:MOTION_ON":
		h.motionSnaps = true
		h.applyMotionToCamLocked()
	case cmd == "CMD:MOTION_REC_OFF":
		h.motionRec = false
		if !h.security.On {
			h.motionActive = false
			if h.recorder != nil {
				h.recorder.Stop()
			}
		}
		h.applyMotionToCamLocked()
		h.ensureStreamLocked()
	case strings.HasPrefix(cmd, "CMD:MOTION_REC_ON:"):
		parts := strings.Split(strings.TrimPrefix(cmd, "CMD:MOTION_REC_ON:"), ":")
		if len(parts) != 2 || !allowedFps[parts[0]] || allowedRes[parts[1]] == "" {
			log.Printf("invalid motion-rec cmd: %q", cmd)
			return
		}
		h.motionRec = true
		h.recFps = parts[0]
		h.recRes = parts[1]
		h.applyMotionToCamLocked()
	case cmd == "CMD:SECURITY_OFF":
		if !h.security.On {
			return
		}
		h.security.On = false
		h.security.Fps = ""
		h.security.Res = ""
		if h.recorder != nil {
			h.recorder.Stop()
		}
		h.ensureStreamLocked()
	case strings.HasPrefix(cmd, "CMD:SECURITY_ON:"):
		parts := strings.Split(strings.TrimPrefix(cmd, "CMD:SECURITY_ON:"), ":")
		if len(parts) != 2 || !allowedFps[parts[0]] || allowedRes[parts[1]] == "" {
			log.Printf("invalid security cmd: %q", cmd)
			return
		}
		paramsChanged := !h.security.On ||
			h.security.Fps != parts[0] || h.security.Res != parts[1]
		h.security.On = true
		h.security.Fps = parts[0]
		h.security.Res = parts[1]
		if paramsChanged && h.recorder != nil {
			h.recorder.Start(parts[0], parts[1])
		}
		h.ensureStreamLocked()
	default:
		log.Printf("unknown server cmd: %q", cmd)
		return
	}
	h.saveSecurityState()
}

// onMotionEvent gates security-mode recording on motion: when motion
// recording is enabled it switches the continuous (low-fps) recorder on and
// keeps it on, re-arming a 10s idle timer on every motion event. When the
// timer fires with no further motion the recorder stops (unless manual
// security mode is also on). This replaces the old fixed 10s full-framerate
// clip, so motion footage is just part of the normal security segments.
func (h *Hub) onMotionEvent() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.motionRec || h.cam == nil {
		return
	}
	if !h.motionActive {
		h.motionActive = true
		if h.recorder != nil {
			if h.recFps == "" {
				h.recFps = "1"
			}
			if h.recRes == "" {
				h.recRes = "480p"
			}
			h.recorder.Start(h.recFps, h.recRes)
		}
	}
	h.ensureStreamLocked()
	if h.motionTimer != nil {
		h.motionTimer.Stop()
	}
	h.motionTimer = time.AfterFunc(motionIdleDuration, h.motionExpired)
}

// motionExpired runs after motionIdleDuration of no motion; drop motion
// recording (and the stream) unless manual security mode still wants it.
func (h *Hub) motionExpired() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.security.On {
		h.motionActive = false
		if h.recorder != nil {
			h.recorder.Stop()
		}
	}
	h.ensureStreamLocked()
	h.motionTimer = nil
}

// caller holds h.mu — send START_STREAM exactly when the stream becomes
// needed (a viewer, security mode, or active motion recording) and
// STOP_STREAM exactly when it stops being needed. No-op on a no-op
// transition, so an already-running stream is never restarted.
func (h *Hub) ensureStreamLocked() {
	want := len(h.viewers) > 0 || h.security.On || h.motionActive
	if want == h.streaming {
		return
	}
	if h.cam == nil {
		// recomputed when the cam (re)registers
		return
	}
	h.streaming = want
	if want {
		h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
		log.Println("sent START_STREAM to cam")
	} else {
		h.cam.sendMsg(websocket.MessageText, []byte("CMD:STOP_STREAM"))
		log.Println("sent STOP_STREAM to cam")
	}
}

// caller holds h.mu — push the cam's desired motion config. The cam emits
// EVENT:MOTION whenever detection is on; snapshots are saved only when the
// user enabled motion snapshots, so the two are fully independent.
func (h *Hub) applyMotionToCamLocked() {
	if h.cam == nil {
		return
	}
	if !h.motionSnaps && !h.motionRec {
		return // nothing to change — don't spam the cam on every (re)connect
	}
	snap := 0
	if h.motionSnaps {
		snap = 1
	}
	detect := 0
	if h.motionSnaps || h.motionRec {
		detect = 1
	}
	h.cam.sendMsg(websocket.MessageText,
		[]byte(fmt.Sprintf("CMD:MOTION:snap=%d:detect=%d", snap, detect)))
}

// caller holds h.mu
func (h *Hub) saveSecurityState() {
	if h.stateFile == "" {
		return
	}
	h.security.MotionRec = h.motionRec
	data, _ := json.Marshal(h.security)
	if err := os.WriteFile(h.stateFile, data, 0644); err != nil {
		log.Printf("save security state: %v", err)
	}
}

// loadSecurityState restores security mode across restarts; call before run().
func (h *Hub) loadSecurityState() {
	if h.stateFile == "" {
		return
	}
	data, err := os.ReadFile(h.stateFile)
	if err != nil {
		return // first run
	}
	var s securityState
	if json.Unmarshal(data, &s) != nil {
		return
	}
	h.security = s
	h.motionRec = s.MotionRec
	if s.On && h.recorder != nil {
		h.recorder.Start(s.Fps, s.Res)
	}
	log.Printf("state restored (security=%v fps=%s res=%s motionRec=%v)",
		s.On, s.Fps, s.Res, s.MotionRec)
}

func (h *Hub) broadcastViewers(msgType websocket.MessageType, data []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for v := range h.viewers {
		v.sendMsg(msgType, data)
	}
}

func (h *Hub) saveSnapshot(data []byte) (string, error) {
	day := time.Now().Format("2006-01-02")
	dir := filepath.Join(h.snapshotDir, day)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	name := fmt.Sprintf("%d.jpg", time.Now().UnixMilli())
	path := filepath.Join(dir, name)
	return path, os.WriteFile(path, data, 0644)
}
