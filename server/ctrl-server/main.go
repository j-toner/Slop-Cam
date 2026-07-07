package main

import (
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/coder/websocket"
)

func wsHandler(hub *Hub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Default Accept rejects cross-origin browser requests (CSWSH);
		// native apps send no Origin header and pass.
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		role := r.URL.Query().Get("role") // "cam" or "viewer"
		res := r.URL.Query().Get("res")   // viewer's preferred resolution
		serveWs(hub, conn, role, res)
	}
}

func main() {
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = "127.0.0.1:8080"
	}
	snapshotDir := os.Getenv("SNAPSHOT_DIR")
	if snapshotDir == "" {
		snapshotDir = "./snapshots"
	}
	retentionDays := 14
	if v := os.Getenv("SNAPSHOT_RETENTION_DAYS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			retentionDays = n
		}
	}
	if err := os.MkdirAll(snapshotDir, 0755); err != nil {
		log.Fatalf("mkdir snapshots: %v", err)
	}

	hub := newHub(snapshotDir)
	go hub.run()
	go pruneLoop(snapshotDir, retentionDays)

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", wsHandler(hub))
	mux.Handle("/snapshots/", http.StripPrefix("/snapshots/",
		http.FileServer(http.Dir(filepath.Clean(snapshotDir)))))
	mux.HandleFunc("/snapshots", listSnapshots(snapshotDir))

	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("ctrl-server listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}

func pruneLoop(dir string, retentionDays int) {
	for {
		if err := pruneSnapshots(dir, retentionDays, time.Now()); err != nil {
			log.Printf("prune snapshots: %v", err)
		}
		time.Sleep(6 * time.Hour)
	}
}
