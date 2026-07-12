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

// motionSpinupGrace pads the first idle timer when a motion event has to
// cold-start the stream: the cam's RTSP publish plus the recorder's connect
// take a few seconds, and without the pad that spin-up is silently deducted
// from the recording window (clips came out ~5s instead of ~10s).
var motionSpinupGrace = 5 * time.Second

type securityState struct {
	On        bool   `json:"on"`
	Fps       string `json:"fps"`
	Res       string `json:"res"`
	MotionRec bool   `json:"motion_rec"`
}

// recorderControl is what the hub needs from the security recorder; an
// interface so tests can observe recorder transitions with a fake.
type recorderControl interface {
	Start(fps, res string)
	Stop()
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
	recorder    recorderControl // nil when recording is unavailable (tests)
	stateFile   string          // "" = don't persist security state
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
	// motionGen invalidates idle timers that lost the race to a fresh
	// motion event: time.Timer.Stop doesn't wait for a running callback,
	// so an expiry can execute after the window was re-armed.
	motionGen int
	// recorder need-state, mirroring `streaming` for the stream: ffmpeg is
	// only (re)started on a real off->on transition or a param change.
	recRunning bool
	recRunFps  string
	recRunRes  string
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
				// a new connection may be a restarted cam that isn't
				// publishing, even when the stale connection it replaces
				// was: clear the flag so ensureStreamLocked re-sends
				// START_STREAM if the stream is wanted. A cam that kept
				// publishing across the reconnect ignores the redundant
				// START (tryStartStream guards on isStreaming), so this
				// cannot restart a live publish.
				h.streaming = false
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
// the cam: security-camera mode and motion-triggered recording.
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
		h.motionActive = false
		h.motionGen++ // invalidate any armed idle timer
		if h.motionTimer != nil {
			h.motionTimer.Stop()
			h.motionTimer = nil
		}
		h.applyMotionToCamLocked()
		h.ensureRecorderLocked()
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
		// applies the new params to an active motion window immediately
		h.ensureRecorderLocked()
	case cmd == "CMD:SECURITY_OFF":
		if !h.security.On {
			return
		}
		h.security.On = false
		h.security.Fps = ""
		h.security.Res = ""
		// an active motion window keeps the recorder (and stream) alive
		h.ensureRecorderLocked()
		h.ensureStreamLocked()
	case strings.HasPrefix(cmd, "CMD:SECURITY_ON:"):
		parts := strings.Split(strings.TrimPrefix(cmd, "CMD:SECURITY_ON:"), ":")
		if len(parts) != 2 || !allowedFps[parts[0]] || allowedRes[parts[1]] == "" {
			log.Printf("invalid security cmd: %q", cmd)
			return
		}
		h.security.On = true
		h.security.Fps = parts[0]
		h.security.Res = parts[1]
		h.ensureRecorderLocked()
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
	log.Printf("motion event (rec=%v active=%v streaming=%v)",
		h.motionRec, h.motionActive, h.streaming)
	if !h.motionRec || h.cam == nil {
		return
	}
	idle := motionIdleDuration
	if !h.motionActive {
		if !h.streaming {
			// cold start: the publish + recorder connect take a few seconds
			// that must not count against the recording window
			idle += motionSpinupGrace
		}
		h.motionActive = true
		h.ensureRecorderLocked()
	}
	h.ensureStreamLocked()
	// each event re-arms the window under a new generation, so an expiry
	// that already fired (and is waiting on the mutex) becomes stale
	h.motionGen++
	gen := h.motionGen
	if h.motionTimer != nil {
		h.motionTimer.Stop()
	}
	h.motionTimer = time.AfterFunc(idle, func() { h.motionExpired(gen) })
}

// motionExpired runs after motionIdleDuration of no motion; the motion
// window closes, and the recorder/stream stay up only if something else
// (security mode, viewers) still wants them.
func (h *Hub) motionExpired(gen int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if gen != h.motionGen {
		return // a fresh motion event re-armed the window; this timer lost
	}
	log.Printf("motion window expired (gen=%d)", gen)
	h.motionActive = false
	h.motionTimer = nil
	h.ensureRecorderLocked()
	h.ensureStreamLocked()
}

// caller holds h.mu — run the recorder exactly when security mode or an
// active motion window needs it, mirroring ensureStreamLocked for the
// stream: ffmpeg is only (re)started on a real off->on transition or a
// param change, and never restarted for a state change that keeps the
// same params (a restart SIGINTs the live segment). Security params win
// over motion params when both apply.
func (h *Hub) ensureRecorderLocked() {
	if h.recorder == nil {
		return
	}
	want := h.security.On || h.motionActive
	if !want {
		if h.recRunning {
			h.recRunning = false
			h.recorder.Stop()
		}
		return
	}
	fps, res := h.recFps, h.recRes
	if h.security.On {
		fps, res = h.security.Fps, h.security.Res
	}
	if fps == "" {
		fps = "1"
	}
	if res == "" {
		res = "480p"
	}
	if h.recRunning && fps == h.recRunFps && res == h.recRunRes {
		return
	}
	h.recRunning = true
	h.recRunFps, h.recRunRes = fps, res
	h.recorder.Start(fps, res)
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
	// temp + rename: a crash mid-write must not corrupt the state file
	// (loadSecurityState silently falls back to defaults on bad JSON)
	tmp := h.stateFile + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		log.Printf("save security state: %v", err)
		return
	}
	if err := os.Rename(tmp, h.stateFile); err != nil {
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
	if s.On && (!allowedFps[s.Fps] || allowedRes[s.Res] == "") {
		return // corrupted/hand-edited state; don't run ffmpeg on it
	}
	h.security = s
	h.motionRec = s.MotionRec
	h.ensureRecorderLocked()
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
	// O_EXCL + a counter suffix: two snapshots in the same millisecond get
	// distinct files instead of the second silently overwriting the first
	ms := time.Now().UnixMilli()
	for i := 0; ; i++ {
		name := fmt.Sprintf("%d.jpg", ms)
		if i > 0 {
			name = fmt.Sprintf("%d-%d.jpg", ms, i)
		}
		path := filepath.Join(dir, name)
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
		if os.IsExist(err) {
			continue
		}
		if err != nil {
			return "", err
		}
		_, werr := f.Write(data)
		if cerr := f.Close(); werr == nil {
			werr = cerr
		}
		return path, werr
	}
}
