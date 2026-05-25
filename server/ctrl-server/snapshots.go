package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
)

func listSnapshots(dir string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var files []string
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
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(files)
	}
}
