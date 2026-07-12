package main

import (
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
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
	// FileServer supports Range requests, so video seeking works;
	// DELETE removes the addressed file (tailnet-only exposure)
	mux.Handle("/snapshots/", http.StripPrefix("/snapshots/",
		filesWithDelete(snapshotDir)))
	mux.HandleFunc("/snapshots", listSnapshots(snapshotDir))
	mux.Handle("/recordings/", http.StripPrefix("/recordings/",
		filesWithDelete(recordDir)))
	mux.HandleFunc("/recordings", listRecordings(recordDir))

	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("ctrl-server listening on %s", addr)
	log.Fatal(srv.ListenAndServe())
}

// filesWithDelete serves files from dir and honors DELETE on individual
// files. Paths are jailed to dir; only regular files can be removed.
// Dotfiles (RECORD_DIR holds the persisted .security.json) and directory
// indexes are internal — clients list via /snapshots and /recordings, so
// anything that isn't a plain media file is a 404 for every method.
func filesWithDelete(dir string) http.Handler {
	root := filepath.Clean(dir)
	fs := http.FileServer(http.Dir(root))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "" || strings.HasSuffix(r.URL.Path, "/") ||
			hasDotSegment(r.URL.Path) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if r.Method != http.MethodDelete {
			fs.ServeHTTP(w, r)
			return
		}
		target := filepath.Join(root, filepath.FromSlash(path.Clean("/"+r.URL.Path)))
		rel, err := filepath.Rel(root, target)
		if err != nil || rel == "." || strings.HasPrefix(rel, "..") {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		info, err := os.Stat(target)
		if err != nil || info.IsDir() {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if err := os.Remove(target); err != nil {
			http.Error(w, "delete failed", http.StatusInternalServerError)
			return
		}
		os.Remove(target + ".jpg") // drop the gallery thumbnail with it
		log.Printf("deleted %s", rel)
		w.WriteHeader(http.StatusNoContent)
	})
}

// hasDotSegment reports whether any segment of the URL path starts with ".".
func hasDotSegment(p string) bool {
	for _, seg := range strings.Split(p, "/") {
		if strings.HasPrefix(seg, ".") {
			return true
		}
	}
	return false
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
