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
	On  bool   `json:"on"`
	Fps string `json:"fps"`
	Res string `json:"res"`
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
	recorder    *Recorder // nil when recording is unavailable (tests)
	stateFile   string    // "" = don't persist security state
	security    securityState
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
					// security mode keeps the cam publishing for the recorder
					if len(h.viewers) == 0 && h.cam != nil && !h.security.On {
						h.cam.sendMsg(websocket.MessageText, []byte("CMD:STOP_STREAM"))
					}
				}
			}
			h.mu.Unlock()

		case msg := <-h.fromViewer:
			if msg.msgType == websocket.MessageText &&
				strings.HasPrefix(string(msg.data), "CMD:SECURITY_") {
				h.handleSecurityCmd(string(msg.data))
				continue
			}
			h.mu.RLock()
			cam := h.cam
			h.mu.RUnlock()
			if cam == nil {
				continue
			}
			cam.sendMsg(msg.msgType, msg.data)

		case msg := <-h.fromCam:
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

// handleSecurityCmd owns security-camera mode: "CMD:SECURITY_ON:<fps>:<res>"
// starts continuous low-fps recording (and keeps the cam streaming without
// viewers), "CMD:SECURITY_OFF" stops it.
func (h *Hub) handleSecurityCmd(cmd string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	switch {
	case cmd == "CMD:SECURITY_OFF":
		if !h.security.On {
			return
		}
		h.security = securityState{}
		if h.recorder != nil {
			h.recorder.Stop()
		}
		if len(h.viewers) == 0 && h.cam != nil {
			h.cam.sendMsg(websocket.MessageText, []byte("CMD:STOP_STREAM"))
		}
	case strings.HasPrefix(cmd, "CMD:SECURITY_ON:"):
		parts := strings.Split(strings.TrimPrefix(cmd, "CMD:SECURITY_ON:"), ":")
		if len(parts) != 2 || !allowedFps[parts[0]] || allowedRes[parts[1]] == "" {
			log.Printf("invalid security cmd: %q", cmd)
			return
		}
		next := securityState{On: true, Fps: parts[0], Res: parts[1]}
		if h.security == next {
			return
		}
		h.security = next
		if h.recorder != nil {
			h.recorder.Start(next.Fps, next.Res)
		}
		if h.cam != nil {
			h.cam.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
		}
	default:
		log.Printf("unknown security cmd: %q", cmd)
		return
	}
	h.saveSecurityState()
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
	if json.Unmarshal(data, &s) != nil || !s.On {
		return
	}
	h.security = s
	if h.recorder != nil {
		h.recorder.Start(s.Fps, s.Res)
	}
	log.Printf("security mode restored (fps=%s res=%s)", s.Fps, s.Res)
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
