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

	recordDir := os.Getenv("RECORD_DIR")
	if recordDir == "" {
		recordDir = "./recordings"
	}
	if err := os.MkdirAll(recordDir, 0755); err != nil {
		log.Fatalf("mkdir recordings: %v", err)
	}
	rtspURL := os.Getenv("RTSP_URL")
	if rtspURL == "" {
		rtspURL = "rtsp://127.0.0.1:8554/cam"
	}
	recordCrf := os.Getenv("RECORD_CRF")
	if recordCrf == "" {
		recordCrf = "28"
	}
	recordRetentionDays := 14
	if v := os.Getenv("RECORD_RETENTION_DAYS"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			recordRetentionDays = n
		}
	}

	hub := newHub(snapshotDir)
	hub.recorder = newRecorder(rtspURL, recordDir, recordCrf)
	hub.stateFile = filepath.Join(recordDir, ".security.json")
	hub.loadSecurityState()
	go hub.run()
	go pruneLoop(snapshotDir, retentionDays)
	go pruneRecordingsLoop(recordDir, recordRetentionDays)
	go remuxLoop(recordDir)

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", wsHandler(hub))
	mux.Handle("/snapshots/", http.StripPrefix("/snapshots/",
		http.FileServer(http.Dir(filepath.Clean(snapshotDir)))))
	mux.HandleFunc("/snapshots", listSnapshots(snapshotDir))
	// FileServer supports Range requests, so video seeking works
	mux.Handle("/recordings/", http.StripPrefix("/recordings/",
		http.FileServer(http.Dir(filepath.Clean(recordDir)))))
	mux.HandleFunc("/recordings", listRecordings(recordDir))

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

func remuxLoop(dir string) {
	for {
		remuxFinishedSegments(dir, time.Now())
		time.Sleep(time.Minute)
	}
}

func pruneRecordingsLoop(dir string, retentionDays int) {
	for {
		if err := pruneRecordings(dir, retentionDays, time.Now()); err != nil {
			log.Printf("prune recordings: %v", err)
		}
		time.Sleep(6 * time.Hour)
	}
}
