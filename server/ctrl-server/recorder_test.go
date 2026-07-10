package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// A motion event races the cam's stream spin-up: the first ffmpeg connect
// fails (404, nothing published yet) and the recorder must retry quickly or
// the retry sleep eats most of the 10s motion window. A flat 5s sleep was
// exactly that bug: footage started ~5-8s late and clips came out ~5s long.
func TestRecorderRetriesQuicklyAfterConnectFailure(t *testing.T) {
	dir := t.TempDir()
	attempts := filepath.Join(dir, "attempts")
	// fake ffmpeg: log the attempt, fail immediately (like a 404 connect)
	script := "#!/bin/sh\necho attempt >> " + attempts + "\nexit 1\n"
	if err := os.WriteFile(filepath.Join(dir, "ffmpeg"), []byte(script), 0755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir)

	r := newRecorder("rtsp://127.0.0.1:1/none", dir, "28")
	r.Start("1", "480p")
	defer r.Stop()

	time.Sleep(2500 * time.Millisecond)
	data, _ := os.ReadFile(attempts)
	n := strings.Count(string(data), "attempt")
	if n < 3 {
		t.Errorf("got %d ffmpeg attempts in 2.5s, want >= 3 (retry backoff too slow)", n)
	}
}
