package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPruneSnapshots(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 7, 7, 12, 0, 0, 0, time.UTC)
	for _, day := range []string{"2026-06-01", "2026-07-01", "2026-07-07"} {
		os.MkdirAll(filepath.Join(dir, day), 0755)
		os.WriteFile(filepath.Join(dir, day, "1.jpg"), []byte("x"), 0644)
	}
	os.WriteFile(filepath.Join(dir, "notes.txt"), []byte("keep"), 0644)

	if err := pruneSnapshots(dir, 14, now); err != nil {
		t.Fatalf("prune: %v", err)
	}

	if _, err := os.Stat(filepath.Join(dir, "2026-06-01")); !os.IsNotExist(err) {
		t.Error("2026-06-01 should have been pruned")
	}
	for _, keep := range []string{"2026-07-01", "2026-07-07", "notes.txt"} {
		if _, err := os.Stat(filepath.Join(dir, keep)); err != nil {
			t.Errorf("%s should have been kept: %v", keep, err)
		}
	}
}

func TestListSnapshots(t *testing.T) {
	dir := t.TempDir()
	dayDir := filepath.Join(dir, "2026-05-24")
	os.MkdirAll(dayDir, 0755)
	os.WriteFile(filepath.Join(dayDir, "1234.jpg"), []byte("fake"), 0644)

	req := httptest.NewRequest("GET", "/snapshots", nil)
	w := httptest.NewRecorder()
	listSnapshots(dir)(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status %d", w.Code)
	}
	var result []string
	json.NewDecoder(w.Body).Decode(&result)
	if len(result) != 1 {
		t.Fatalf("expected 1 snapshot, got %d", len(result))
	}
	if result[0] != "/snapshots/2026-05-24/1234.jpg" {
		t.Errorf("got %q, want /snapshots/2026-05-24/1234.jpg", result[0])
	}
}

func TestListSnapshotsEmpty(t *testing.T) {
	dir := t.TempDir()
	req := httptest.NewRequest("GET", "/snapshots", nil)
	w := httptest.NewRecorder()
	listSnapshots(dir)(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status %d", w.Code)
	}
	// Should return JSON null or empty array, not error
	body := w.Body.String()
	if body == "" {
		t.Error("expected non-empty response body")
	}
}
