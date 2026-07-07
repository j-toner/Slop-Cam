package main

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const defaultResolution = "720p"

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
				if len(h.viewers) > 0 {
					c.sendMsg(websocket.MessageText, startStreamCmd(h.lastRes))
					log.Println("viewers waiting, sent START_STREAM to cam")
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
					if len(h.viewers) == 0 && h.cam != nil {
						h.cam.sendMsg(websocket.MessageText, []byte("CMD:STOP_STREAM"))
					}
				}
			}
			h.mu.Unlock()

		case msg := <-h.fromViewer:
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
