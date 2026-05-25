package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"

	"github.com/coder/websocket"
)

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	snapshotDir := os.Getenv("SNAPSHOT_DIR")
	if snapshotDir == "" {
		snapshotDir = "./snapshots"
	}
	if err := os.MkdirAll(snapshotDir, 0755); err != nil {
		log.Fatalf("mkdir snapshots: %v", err)
	}

	hub := newHub(snapshotDir)
	go hub.run()

	http.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			InsecureSkipVerify: true, // Tailscale-only, no public exposure
		})
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		role := r.URL.Query().Get("role") // "cam" or "viewer"
		serveWs(hub, conn, role)
	})

	http.Handle("/snapshots/", http.StripPrefix("/snapshots/",
		http.FileServer(http.Dir(filepath.Clean(snapshotDir)))))

	http.HandleFunc("/snapshots", listSnapshots(snapshotDir))

	log.Printf("ctrl-server listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, nil))
}
