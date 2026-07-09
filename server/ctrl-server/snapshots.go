package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// pruneSnapshots deletes per-day snapshot directories (named 2006-01-02)
// older than retentionDays relative to now.
func pruneSnapshots(dir string, retentionDays int, now time.Time) error {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	cutoff := now.AddDate(0, 0, -retentionDays)
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		day, err := time.Parse("2006-01-02", e.Name())
		if err != nil {
			continue // not a day directory, leave it alone
		}
		if day.Before(cutoff) {
			if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}

func listSnapshots(dir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		files := []string{} // marshal as [] instead of null when empty
		_ = filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
			if err != nil || info.IsDir() {
				return nil
			}
			if filepath.Ext(path) == ".jpg" {
				rel, _ := filepath.Rel(dir, path)
				files = append(files, "/snapshots/"+rel)
			}
			return nil
		})
		// day dirs and millisecond filenames sort lexicographically in
		// chronological order, so reversing yields newest first
		sort.Sort(sort.Reverse(sort.StringSlice(files)))
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(files)
	}
}
