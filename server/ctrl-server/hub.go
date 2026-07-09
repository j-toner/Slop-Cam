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
	clip        *ClipRecorder // nil in tests
	stateFile   string        // "" = don't persist security state
	security    securityState
	manualRec   bool
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
				if len(h.viewers) > 0 || h.security.On {
					c.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
					log.Println("sent START_STREAM to cam")
				}
			} else {
				h.viewers[c] = true
				if c.res != "" {
					h.lastRes = c.res
				}
				log.Println("viewer registered")
				if h.cam != nil {
					h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
				}
			}
			h.mu.Unlock()

		case c := <-h.unregister:
			h.mu.Lock()
			if c.role == RoleCam {
				if h.cam == c {
					h.cam = nil
					log.Println("cam unregistered")
				}
			} else {
				if _, ok := h.viewers[c]; ok {
					delete(h.viewers, c)
					close(c.send)
					log.Println("viewer unregistered")
					h.maybeStopStreamLocked()
				}
			}
			h.mu.Unlock()

		case msg := <-h.fromViewer:
			if msg.msgType == websocket.MessageText {
				cmd := string(msg.data)
				if strings.HasPrefix(cmd, "CMD:SECURITY_") ||
					strings.HasPrefix(cmd, "CMD:MOTION_REC_") ||
					strings.HasPrefix(cmd, "CMD:RECORD_") {
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
	case cmd == "CMD:MOTION_REC_ON":
		if h.security.MotionRec {
			return
		}
		h.security.MotionRec = true
	case cmd == "CMD:MOTION_REC_OFF":
		if !h.security.MotionRec {
			return
		}
		h.security.MotionRec = false
	case cmd == "CMD:RECORD_START":
		h.manualRec = true
		if h.cam != nil {
			h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
		}
		if h.clip != nil {
			h.clip.Start("manual", 0, func() { h.clipDone() })
		}
		return // transient, not persisted
	case cmd == "CMD:RECORD_STOP":
		h.manualRec = false
		if h.clip != nil {
			h.clip.Stop()
		}
		return
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
		h.maybeStopStreamLocked()
	case strings.HasPrefix(cmd, "CMD:SECURITY_ON:"):
		parts := strings.Split(strings.TrimPrefix(cmd, "CMD:SECURITY_ON:"), ":")
		if len(parts) != 2 || !allowedFps[parts[0]] || allowedRes[parts[1]] == "" {
			log.Printf("invalid security cmd: %q", cmd)
			return
		}
		if h.security.On && h.security.Fps == parts[0] && h.security.Res == parts[1] {
			return
		}
		h.security.On = true
		h.security.Fps = parts[0]
		h.security.Res = parts[1]
		if h.recorder != nil {
			h.recorder.Start(parts[0], parts[1])
		}
		if h.cam != nil {
			h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
		}
	default:
		log.Printf("unknown server cmd: %q", cmd)
		return
	}
	h.saveSecurityState()
}

// onMotionEvent starts a bounded clip when motion-triggered recording is on.
// The cam can't watch for motion while it streams (one camera), so a clip
// runs its fixed length, the stream stops, motion watch resumes, and fresh
// motion simply starts the next clip.
func (h *Hub) onMotionEvent() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.security.MotionRec || h.clip == nil || h.cam == nil {
		return
	}
	if h.clip.Start("motion", 10, func() { h.clipDone() }) {
		h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
	}
}

// clipDone runs when a clip finishes; stop the stream if nothing else uses it.
func (h *Hub) clipDone() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.maybeStopStreamLocked()
}

// caller holds h.mu — stop the cam stream unless a viewer, security mode,
// or an active recording still needs it
func (h *Hub) maybeStopStreamLocked() {
	clipBusy := h.clip != nil && h.clip.Active()
	if len(h.viewers) == 0 && !h.security.On && !h.manualRec && !clipBusy && h.cam != nil {
		h.cam.sendMsg(websocket.MessageText, []byte("CMD:STOP_STREAM"))
	}
}

// caller holds h.mu
func (h *Hub) saveSecurityState() {
	if h.stateFile == "" {
		return
	}
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
